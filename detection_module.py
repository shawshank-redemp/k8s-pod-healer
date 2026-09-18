"""
Sentinel - Kubernetes Pod Failure Detection Module (Step 1).

Event-driven watcher that detects pod failures across all namespaces,
deduplicates diagnoses, filters transient/self-recovering failures, and
safely extracts pod metadata before handing off to Step 2 (diagnosis).
"""

import threading
import time
from datetime import datetime, timezone

from kubernetes import client, config, watch

# --------------------------------------------------------------------------
# Configuration / tunable constants
# --------------------------------------------------------------------------

EXCLUDED_NAMESPACES = {"kube-system", "kube-node-lease", "kube-public"}

# Reasons/phases we consider "failure states" worth tracking. Image-pull and
# container-config errors surface as container *reasons* (state.waiting.reason),
# not pod phases, so they belong in FAILURE_REASONS alongside CrashLoopBackOff.
FAILURE_REASONS = {
    "CrashLoopBackOff", "Error",
    "ImagePullBackOff", "ImageInspectError",
    "CreateContainerConfigError", "ErrImagePull",
}
FAILURE_PHASES = {"Failed", "Pending"}

DEDUP_WINDOW_SECONDS = 5 * 60      # don't re-diagnose the same pod within 5 min
TRANSIENT_WAIT_SECONDS = 30        # must be failing continuously for 30s
WAITING_STALE_TIMEOUT_SECONDS = 60 # forget "waiting" pods after 1 min of silence
PENDING_MIN_AGE_SECONDS = 30       # ignore brand-new Pending pods (still scheduling)
MIN_RESTART_COUNT_CRASHLOOP = 1    # only diagnose CrashLoopBackOff after 1+ restarts
CLEANUP_EVERY_N_EVENTS = 50        # sweep stale dict entries every N events
RECONNECT_DELAY_SECONDS = 5

# --------------------------------------------------------------------------
# In-memory state
# --------------------------------------------------------------------------

# pod_id -> timestamp of last diagnosis (dedup window)
processed_pods = {}

# pod_id -> timestamp first observed in a failing state (transient-failure wait)
waiting_pods = {}


# --------------------------------------------------------------------------
# Safe extraction helpers
# --------------------------------------------------------------------------

def get_failure_reason(pod):
    """Extract a human-readable failure reason from all possible locations.

    Checks, in order: container waiting-state reasons, container last-terminated
    reasons, pod-level condition reasons, and finally the pod phase. Regular and
    init containers are both checked. Never raises - returns "Unknown" on any
    missing/unexpected structure.
    """
    try:
        status = getattr(pod, "status", None)
        if status is None:
            return "Unknown"

        container_statuses = list(getattr(status, "container_statuses", None) or [])
        container_statuses += list(getattr(status, "init_container_statuses", None) or [])

        # 1) Currently waiting (e.g. CrashLoopBackOff, ImagePullBackOff)
        for cs in container_statuses:
            state = getattr(cs, "state", None)
            waiting = getattr(state, "waiting", None) if state else None
            reason = getattr(waiting, "reason", None) if waiting else None
            if reason:
                return reason

        # 2) Currently terminated (e.g. Error, OOMKilled - container is down now)
        for cs in container_statuses:
            state = getattr(cs, "state", None)
            terminated = getattr(state, "terminated", None) if state else None
            reason = getattr(terminated, "reason", None) if terminated else None
            if reason:
                return reason

        # 3) Last terminated (previous run's reason, before the latest restart)
        for cs in container_statuses:
            last_state = getattr(cs, "last_state", None)
            terminated = getattr(last_state, "terminated", None) if last_state else None
            reason = getattr(terminated, "reason", None) if terminated else None
            if reason:
                return reason

        # 4) Pod-level conditions (e.g. Evicted, Unschedulable)
        conditions = getattr(status, "conditions", None) or []
        for cond in conditions:
            reason = getattr(cond, "reason", None)
            if reason:
                return reason

        # 5) Fallback to the pod phase
        phase = getattr(status, "phase", None)
        if phase:
            return phase
    except Exception:
        pass

    return "Unknown"


def get_max_restart_count(pod):
    """Return the highest restart count across ALL containers (regular + init).

    Returns 0 if container_statuses is missing/empty (e.g. pod still Pending).
    """
    try:
        status = getattr(pod, "status", None)
        if status is None:
            return 0

        statuses = list(getattr(status, "container_statuses", None) or [])
        statuses += list(getattr(status, "init_container_statuses", None) or [])

        if not statuses:
            return 0

        return max(getattr(cs, "restart_count", 0) or 0 for cs in statuses)
    except Exception:
        return 0


def classify_severity(status, reason, restart_count):
    """Classify how urgent this failure is for downstream triage/alerting.

    `reason` (e.g. CrashLoopBackOff, ImagePullBackOff) drives most of the
    classification since that's where container-level failure info actually
    lives; `status` (pod phase) is only meaningful on its own for Pending.
    """
    if reason == "CrashLoopBackOff":
        return "CRITICAL" if restart_count >= 5 else "HIGH"
    elif reason in ("ImagePullBackOff", "ImageInspectError",
                     "CreateContainerConfigError", "ErrImagePull") or status == "Failed":
        return "CRITICAL"
    elif status == "Pending":
        return "MEDIUM"
    else:
        return "MEDIUM"


def pod_age_seconds(pod):
    """Return pod age in seconds based on creation_timestamp. 0 on failure."""
    try:
        created = pod.metadata.creation_timestamp
        if created is None:
            return 0
        now = datetime.now(timezone.utc)
        return (now - created).total_seconds()
    except Exception:
        return 0


def extract_pod_info(pod):
    """Safely pull out everything downstream diagnosis needs from a pod object."""
    try:
        pod_name = pod.metadata.name
        namespace = pod.metadata.namespace
    except Exception:
        pod_name = "unknown"
        namespace = "unknown"

    pod_id = f"{namespace}/{pod_name}"

    try:
        status = pod.status.phase or "Unknown"
    except Exception:
        status = "Unknown"

    reason = get_failure_reason(pod)
    restart_count = get_max_restart_count(pod)

    return {
        "pod_id": pod_id,
        "pod_name": pod_name,
        "namespace": namespace,
        "status": status,
        "reason": reason,
        "restart_count": restart_count,
        "severity": classify_severity(status, reason, restart_count),
        "pod_object": pod,
    }


# --------------------------------------------------------------------------
# Decision logic (all loophole fixes live here)
# --------------------------------------------------------------------------

def should_diagnose_now(pod_id, pod_name, namespace, pod):
    """Return True only if this pod should be diagnosed right now.

    Encodes every loophole fix: namespace filtering, dedup window, eviction
    skip, pod-recovery detection, new-pod age filtering, restart-count
    thresholds, and the transient-failure wait (TRANSIENT_WAIT_SECONDS).
    Mutates the module-level `waiting_pods` / `processed_pods` dicts as a
    side effect since this is
    called once per relevant event and needs to remember state across calls.
    """
    now = time.time()

    # Loophole fix: namespace permissions - never touch system namespaces.
    if namespace in EXCLUDED_NAMESPACES:
        return False

    # Loophole fix: deduplication - skip if diagnosed within the last 5 min.
    last_diagnosed = processed_pods.get(pod_id)
    if last_diagnosed is not None and (now - last_diagnosed) < DEDUP_WINDOW_SECONDS:
        return False

    info = extract_pod_info(pod)
    reason = info["reason"]
    status = info["status"]
    restart_count = info["restart_count"]

    # Loophole fix: pod eviction - evicted pods aren't actionable failures.
    if reason == "Evicted":
        waiting_pods.pop(pod_id, None)
        return False

    is_failure_state = reason in FAILURE_REASONS or status in FAILURE_PHASES

    # Loophole fix: pod recovery detection - if it's no longer failing, drop it
    # from the waiting set instead of diagnosing.
    if not is_failure_state:
        waiting_pods.pop(pod_id, None)
        return False

    # Loophole fix: new pod initialization - a brand-new Pending pod is
    # probably still being scheduled, not actually stuck.
    if status == "Pending" and pod_age_seconds(pod) < PENDING_MIN_AGE_SECONDS:
        return False

    # Loophole fix: transient failure - require the pod to have been observed
    # failing continuously for TRANSIENT_WAIT_SECONDS before diagnosing. The
    # clock starts the moment the pod is first seen failing, independent of
    # restart count. This naturally filters out self-recovering pods, since a
    # recovered pod hits the `not is_failure_state` branch above on its next
    # event and gets dropped from waiting_pods before the wait elapses.
    first_seen = waiting_pods.get(pod_id)
    if first_seen is None:
        waiting_pods[pod_id] = now
        return False

    if (now - first_seen) < TRANSIENT_WAIT_SECONDS:
        return False

    # Loophole fix: restart count patterns (checked once the wait has
    # elapsed, so a fast-accumulating CrashLoopBackOff doesn't need to wait
    # the full TRANSIENT_WAIT_SECONDS again after crossing the threshold).
    #   - CrashLoopBackOff: require >= MIN_RESTART_COUNT_CRASHLOOP restarts
    #     across ALL containers. If not met yet, keep waiting (don't reset
    #     the clock) until it is.
    #   - Failed (incl. restartPolicy=Never, which never accrues restarts):
    #     diagnose immediately, no restart requirement.
    if reason == "CrashLoopBackOff" and restart_count < MIN_RESTART_COUNT_CRASHLOOP:
        return False

    # All checks passed - diagnose now.
    waiting_pods.pop(pod_id, None)
    processed_pods[pod_id] = now
    return True


# --------------------------------------------------------------------------
# Memory management
# --------------------------------------------------------------------------

def cleanup_old_entries():
    """Remove stale entries from processed_pods and waiting_pods.

    Prevents unbounded dict growth on long-running watchers with lots of
    pod churn. Called periodically from the main watch loop.
    """
    now = time.time()

    stale_processed = [
        pid for pid, ts in processed_pods.items()
        if (now - ts) > DEDUP_WINDOW_SECONDS
    ]
    for pid in stale_processed:
        del processed_pods[pid]

    stale_waiting = [
        pid for pid, ts in waiting_pods.items()
        if (now - ts) > WAITING_STALE_TIMEOUT_SECONDS
    ]
    for pid in stale_waiting:
        del waiting_pods[pid]

    if stale_processed or stale_waiting:
        print(
            f"[CLEANUP] removed {len(stale_processed)} processed_pods, "
            f"{len(stale_waiting)} waiting_pods entries "
            f"(processed={len(processed_pods)}, waiting={len(waiting_pods)})"
        )


# --------------------------------------------------------------------------
# Diagnosis handoff (Step 2)
# --------------------------------------------------------------------------

# Lazily-created DiagnosisModule instance used by trigger_diagnosis(). Left
# as None until either the first diagnosis is dispatched (defaults to mocks)
# or set_diagnosis_module() injects a configured one (e.g. with real K8s +
# Claude clients on hackathon day).
_diagnosis_module_instance = None


def set_diagnosis_module(module):
    """Inject a configured DiagnosisModule for trigger_diagnosis() to use.

    Call this before watch_pod_events() to swap in real clients:

        import diagnosis_module as dm2
        set_diagnosis_module(dm2.DiagnosisModule(
            k8s_client=dm2.RealK8sClient(),
            claude_client=dm2.RealClaudeClient(api_key=...),
        ))
    """
    global _diagnosis_module_instance
    _diagnosis_module_instance = module


def _get_diagnosis_module():
    global _diagnosis_module_instance
    if _diagnosis_module_instance is None:
        import diagnosis_module
        _diagnosis_module_instance = diagnosis_module.DiagnosisModule()
    return _diagnosis_module_instance


def trigger_diagnosis(pod_info):
    """Hand a failing pod off to Step 2 without blocking the watch loop.

    Runs DiagnosisModule.diagnose() on a background daemon thread - a real
    Claude call can take several seconds, and should_diagnose_now() already
    guarantees this fires at most once per pod per dedup window, so a
    lightweight thread-per-diagnosis is enough at hackathon scale (a handful
    of concurrent failures, not a flood).
    """
    thread = threading.Thread(target=_run_diagnosis, args=(pod_info,), daemon=True)
    thread.start()


def _run_diagnosis(pod_info):
    pod_name = pod_info.get("pod_name", "unknown")
    try:
        module = _get_diagnosis_module()
        diagnosis = module.diagnose(pod_info)
        print(f"→ DIAGNOSIS: {pod_name} root_cause={diagnosis['root_cause']} "
              f"severity={diagnosis['severity']} confidence={diagnosis['confidence']} "
              f"fix={diagnosis['recommended_fix']}")
    except Exception as exc:
        print(f"[ERROR] diagnosis thread failed for {pod_name}: {exc}")


# --------------------------------------------------------------------------
# Main event-driven watch loop
# --------------------------------------------------------------------------

def _load_kube_config():
    """Load in-cluster config if running inside k8s, else local kubeconfig."""
    try:
        config.load_incluster_config()
        print("[INFO] loaded in-cluster kube config")
    except Exception:
        config.load_kube_config()
        print("[INFO] loaded local kubeconfig")


def watch_pod_events():
    """Subscribe to pod events across all namespaces and detect failures.

    Event-driven (uses the Kubernetes Watch API, not polling). Automatically
    reconnects on disconnection after a 5s delay. Runs cleanup_old_entries()
    every CLEANUP_EVERY_N_EVENTS events to bound memory growth.
    """
    _load_kube_config()
    v1 = client.CoreV1Api()
    event_count = 0

    print("[INFO] Sentinel detection module starting - watching all namespaces "
          f"(excluding {sorted(EXCLUDED_NAMESPACES)})")

    while True:
        w = watch.Watch()
        try:
            for event in w.stream(v1.list_pod_for_all_namespaces):
                pod = event.get("object")
                if pod is None:
                    continue

                event_count += 1

                try:
                    namespace = pod.metadata.namespace
                    pod_name = pod.metadata.name

                    if namespace in EXCLUDED_NAMESPACES:
                        continue

                    pod_id = f"{namespace}/{pod_name}"

                    if should_diagnose_now(pod_id, pod_name, namespace, pod):
                        info = extract_pod_info(pod)
                        print(f"DETECTED: Pod failure - {pod_name} "
                              f"[{namespace}] reason={info['reason']} "
                              f"restarts={info['restart_count']} "
                              f"severity={info['severity']}")
                        trigger_diagnosis(info)

                except Exception as inner_exc:
                    # Never let one malformed event kill the watch loop.
                    print(f"[WARN] failed to process event: {inner_exc}")
                    continue

                if event_count % CLEANUP_EVERY_N_EVENTS == 0:
                    cleanup_old_entries()

        except KeyboardInterrupt:
            print("[INFO] shutting down (KeyboardInterrupt)")
            return
        except client.exceptions.ApiException as api_exc:
            print(f"[ERROR] Kubernetes API error: {api_exc}. "
                  f"Reconnecting in {RECONNECT_DELAY_SECONDS}s...")
            time.sleep(RECONNECT_DELAY_SECONDS)
        except Exception as exc:
            print(f"[ERROR] watch stream disconnected: {exc}. "
                  f"Reconnecting in {RECONNECT_DELAY_SECONDS}s...")
            time.sleep(RECONNECT_DELAY_SECONDS)
        finally:
            w.stop()


if __name__ == "__main__":
    watch_pod_events()
