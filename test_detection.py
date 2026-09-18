"""
Simple test script for detection_module.py.

Covers the 6 original scenarios plus 3 additional ones (ImagePullBackOff,
1-restart CrashLoopBackOff, long-Pending) using fabricated kubernetes.client
pod objects (no real cluster required). Timing constants are temporarily
shrunk so the suite runs in seconds instead of minutes - production defaults
(30s wait, 5min dedup, etc.) are untouched in detection_module itself.

Run with:
    python test_detection.py
"""

import threading
import time
from datetime import datetime, timedelta, timezone

from kubernetes import client as k8s_client

import detection_module as dm


# --------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------

def make_pod(name, namespace="default", phase="Running", reason=None,
             restart_count=0, age_seconds=120, container_state="waiting",
             extra_container_restart_counts=None):
    """Build a realistic V1Pod for a given failure scenario."""
    created = datetime.now(timezone.utc) - timedelta(seconds=age_seconds)

    state_kwargs = {}
    if reason:
        if container_state == "waiting":
            state_kwargs["waiting"] = k8s_client.V1ContainerStateWaiting(reason=reason)
        elif container_state == "terminated":
            state_kwargs["terminated"] = k8s_client.V1ContainerStateTerminated(
                reason=reason, exit_code=1
            )

    main_status = k8s_client.V1ContainerStatus(
        name="main",
        image="busybox",
        image_id="",
        ready=False,
        restart_count=restart_count,
        state=k8s_client.V1ContainerState(**state_kwargs) if state_kwargs else k8s_client.V1ContainerState(),
    )

    container_statuses = [main_status]
    for i, rc in enumerate(extra_container_restart_counts or []):
        container_statuses.append(
            k8s_client.V1ContainerStatus(
                name=f"sidecar-{i}",
                image="busybox",
                image_id="",
                ready=False,
                restart_count=rc,
                state=k8s_client.V1ContainerState(),
            )
        )

    return k8s_client.V1Pod(
        metadata=k8s_client.V1ObjectMeta(
            name=name, namespace=namespace, creation_timestamp=created
        ),
        status=k8s_client.V1PodStatus(
            phase=phase,
            container_statuses=container_statuses,
        ),
    )


def reset_state():
    dm.processed_pods.clear()
    dm.waiting_pods.clear()


def shrink_timings():
    """Speed up the wait/dedup windows for fast test runs."""
    dm.TRANSIENT_WAIT_SECONDS = 0.5
    dm.DEDUP_WINDOW_SECONDS = 2
    dm.WAITING_STALE_TIMEOUT_SECONDS = 1
    dm.PENDING_MIN_AGE_SECONDS = 1


def check(label, condition):
    status = "PASS" if condition else "FAIL"
    print(f"[{status}] {label}")
    assert condition, label


# --------------------------------------------------------------------------
# Test 1: Basic detection (CrashLoopBackOff, enough restarts, past the wait)
# --------------------------------------------------------------------------

def test_1_basic_detection():
    print("\n--- Test 1: Basic Detection ---")
    reset_state()
    pod = make_pod("crash-test", reason="CrashLoopBackOff", restart_count=3)
    pod_id = "default/crash-test"

    first = dm.should_diagnose_now(pod_id, "crash-test", "default", pod)
    check("first sighting starts the wait, does not diagnose yet", first is False)

    time.sleep(dm.TRANSIENT_WAIT_SECONDS + 0.2)
    second = dm.should_diagnose_now(pod_id, "crash-test", "default", pod)
    check("diagnoses after wait elapses", second is True)


# --------------------------------------------------------------------------
# Test 2: Deduplication
# --------------------------------------------------------------------------

def test_2_deduplication():
    print("\n--- Test 2: Deduplication ---")
    reset_state()
    pod = make_pod("dedup-test", reason="CrashLoopBackOff", restart_count=5)
    pod_id = "default/dedup-test"

    dm.should_diagnose_now(pod_id, "dedup-test", "default", pod)
    time.sleep(dm.TRANSIENT_WAIT_SECONDS + 0.2)
    diagnosed_once = dm.should_diagnose_now(pod_id, "dedup-test", "default", pod)
    check("diagnosed the first time", diagnosed_once is True)

    # Pod "restarts" again immediately - should NOT be re-diagnosed within window.
    again = dm.should_diagnose_now(pod_id, "dedup-test", "default", pod)
    check("not re-diagnosed within dedup window", again is False)


# --------------------------------------------------------------------------
# Test 3: Transient failure (recovers before the wait elapses)
# --------------------------------------------------------------------------

def test_3_transient_failure():
    print("\n--- Test 3: Transient Failure ---")
    reset_state()
    pod_id = "default/flaky-test"

    failing_pod = make_pod("flaky-test", reason="Error", container_state="terminated")
    dm.should_diagnose_now(pod_id, "flaky-test", "default", failing_pod)
    check("pod added to waiting set", pod_id in dm.waiting_pods)

    # Recovers well before TRANSIENT_WAIT_SECONDS elapses.
    healthy_pod = make_pod("flaky-test", phase="Running", reason=None)
    recovered = dm.should_diagnose_now(pod_id, "flaky-test", "default", healthy_pod)
    check("recovered pod is not diagnosed", recovered is False)
    check("recovered pod removed from waiting set", pod_id not in dm.waiting_pods)


# --------------------------------------------------------------------------
# Test 4: Restart count threshold for CrashLoopBackOff (MIN_RESTART_COUNT_CRASHLOOP=1)
# --------------------------------------------------------------------------

def test_4_restart_count():
    print("\n--- Test 4: Restart Count Threshold ---")
    reset_state()
    pod_id = "default/restart-test"

    zero_restarts = make_pod("restart-test", reason="CrashLoopBackOff", restart_count=0)
    dm.should_diagnose_now(pod_id, "restart-test", "default", zero_restarts)
    time.sleep(dm.TRANSIENT_WAIT_SECONDS + 0.2)
    still_blocked = dm.should_diagnose_now(pod_id, "restart-test", "default", zero_restarts)
    check("blocked below restart threshold (restart_count=0)", still_blocked is False)

    # Restart count reaches 1 on a later event - only the sidecar restarted,
    # confirming ALL containers are checked, not just the first.
    one_restart = make_pod(
        "restart-test", reason="CrashLoopBackOff", restart_count=0,
        extra_container_restart_counts=[1],
    )
    check("get_max_restart_count picks the max across containers",
          dm.get_max_restart_count(one_restart) == 1)
    diagnosed = dm.should_diagnose_now(pod_id, "restart-test", "default", one_restart)
    check("diagnosed once restart_count >= 1", diagnosed is True)


# --------------------------------------------------------------------------
# Test 5: Namespace filtering
# --------------------------------------------------------------------------

def test_5_namespace_filtering():
    print("\n--- Test 5: Namespace Filtering ---")
    reset_state()
    pod = make_pod("system-pod", namespace="kube-system",
                    reason="CrashLoopBackOff", restart_count=10)
    pod_id = "kube-system/system-pod"

    result = dm.should_diagnose_now(pod_id, "system-pod", "kube-system", pod)
    check("kube-system pods are always skipped", result is False)
    check("kube-system pods never enter the waiting set", pod_id not in dm.waiting_pods)


# --------------------------------------------------------------------------
# Test 6: Pod recovery after starting the wait, cleaned from waiting_pods
# --------------------------------------------------------------------------

def test_6_recovery_after_wait_started():
    print("\n--- Test 6: Pod Recovery After Wait Started ---")
    reset_state()
    pod_id = "default/recover-test"

    failing_pod = make_pod("recover-test", reason="CrashLoopBackOff", restart_count=5)
    dm.should_diagnose_now(pod_id, "recover-test", "default", failing_pod)
    check("entered waiting_pods on first failure sighting", pod_id in dm.waiting_pods)

    time.sleep(dm.TRANSIENT_WAIT_SECONDS * 0.4)  # well before the wait elapses

    recovered_pod = make_pod("recover-test", phase="Running", reason=None, restart_count=5)
    result = dm.should_diagnose_now(pod_id, "recover-test", "default", recovered_pod)
    check("not diagnosed - recovered before wait elapsed", result is False)
    check("removed from waiting_pods on recovery", pod_id not in dm.waiting_pods)


# --------------------------------------------------------------------------
# Extra: safe extraction on missing/edge-case data
# --------------------------------------------------------------------------

def test_safe_extraction_edge_cases():
    print("\n--- Extra: Safe Extraction Edge Cases ---")

    empty_pod = k8s_client.V1Pod(
        metadata=k8s_client.V1ObjectMeta(name="empty", namespace="default"),
        status=k8s_client.V1PodStatus(phase="Pending"),
    )
    check("get_failure_reason falls back to phase when no container info",
          dm.get_failure_reason(empty_pod) == "Pending")
    check("get_max_restart_count is 0 with no container_statuses",
          dm.get_max_restart_count(empty_pod) == 0)

    info = dm.extract_pod_info(empty_pod)
    check("extract_pod_info returns all required keys",
          set(info.keys()) == {"pod_id", "pod_name", "namespace", "status",
                                "reason", "restart_count", "severity", "pod_object"})

    evicted_pod = make_pod("evicted-test", phase="Failed")
    evicted_pod.status.conditions = [k8s_client.V1PodCondition(
        type="Ready", status="False", reason="Evicted"
    )]
    result = dm.should_diagnose_now("default/evicted-test", "evicted-test",
                                     "default", evicted_pod)
    check("evicted pods are skipped", result is False)


# --------------------------------------------------------------------------
# Test 7: ImagePullBackOff detection, classified CRITICAL
# --------------------------------------------------------------------------

def test_7_image_pull_backoff():
    print("\n--- Test 7: ImagePullBackOff Detection ---")
    reset_state()
    pod_id = "default/badimage-test"

    pod = make_pod("badimage-test", reason="ImagePullBackOff", restart_count=0)
    first = dm.should_diagnose_now(pod_id, "badimage-test", "default", pod)
    check("first sighting starts the wait, does not diagnose yet", first is False)

    time.sleep(dm.TRANSIENT_WAIT_SECONDS + 0.2)
    diagnosed = dm.should_diagnose_now(pod_id, "badimage-test", "default", pod)
    check("ImagePullBackOff diagnosed after wait (no restart requirement)",
          diagnosed is True)

    info = dm.extract_pod_info(pod)
    check("ImagePullBackOff classified as CRITICAL", info["severity"] == "CRITICAL")


# --------------------------------------------------------------------------
# Test 8: CrashLoopBackOff with exactly 1 restart diagnoses without waiting
# for the old 3-restart threshold
# --------------------------------------------------------------------------

def test_8_crashloop_one_restart():
    print("\n--- Test 8: CrashLoopBackOff with 1 Restart ---")
    reset_state()
    pod_id = "default/onerestart-test"

    pod = make_pod("onerestart-test", reason="CrashLoopBackOff", restart_count=1)
    first = dm.should_diagnose_now(pod_id, "onerestart-test", "default", pod)
    check("first sighting starts the wait, does not diagnose yet", first is False)

    time.sleep(dm.TRANSIENT_WAIT_SECONDS + 0.2)
    diagnosed = dm.should_diagnose_now(pod_id, "onerestart-test", "default", pod)
    check("diagnosed with only 1 restart (threshold lowered to 1)", diagnosed is True)

    info = dm.extract_pod_info(pod)
    check("1 restart classified as HIGH (not yet CRITICAL, needs >=5)",
          info["severity"] == "HIGH")


# --------------------------------------------------------------------------
# Test 9: Pod Pending for 40s is diagnosed with MEDIUM severity
# --------------------------------------------------------------------------

def test_9_pending_40_seconds():
    print("\n--- Test 9: Pending for 40 Seconds ---")
    reset_state()
    pod_id = "default/stuckpending-test"

    pod = make_pod("stuckpending-test", phase="Pending", reason=None, age_seconds=40)
    first = dm.should_diagnose_now(pod_id, "stuckpending-test", "default", pod)
    check("first sighting starts the wait, does not diagnose yet", first is False)

    time.sleep(dm.TRANSIENT_WAIT_SECONDS + 0.2)
    diagnosed = dm.should_diagnose_now(pod_id, "stuckpending-test", "default", pod)
    check("40s-old Pending pod diagnosed (past the age filter)", diagnosed is True)

    info = dm.extract_pod_info(pod)
    check("long-Pending pod classified as MEDIUM", info["severity"] == "MEDIUM")


# --------------------------------------------------------------------------
# Test 10: trigger_diagnosis dispatches to Step 2 without blocking the caller
# --------------------------------------------------------------------------

def test_10_trigger_diagnosis_non_blocking():
    print("\n--- Test 10: trigger_diagnosis Dispatches Without Blocking ---")

    calls = []
    ran = threading.Event()

    class SlowSpyDiagnosisModule:
        """Stands in for a real DiagnosisModule with a slow Claude call."""
        def diagnose(self, pod_info):
            time.sleep(0.3)
            calls.append(pod_info["pod_name"])
            ran.set()
            return {"root_cause": "test", "severity": "LOW",
                    "recommended_fix": "n/a", "confidence": 1.0}

    dm.set_diagnosis_module(SlowSpyDiagnosisModule())
    try:
        start = time.time()
        dm.trigger_diagnosis({"pod_name": "spy-test", "namespace": "default",
                               "pod_id": "default/spy-test"})
        elapsed = time.time() - start
        check("trigger_diagnosis returns immediately, doesn't wait for diagnose()",
              elapsed < 0.1)

        check("diagnosis eventually runs in the background",
              ran.wait(timeout=2) and calls == ["spy-test"])
    finally:
        dm.set_diagnosis_module(None)  # don't leak state into other tests/processes


if __name__ == "__main__":
    shrink_timings()

    test_1_basic_detection()
    test_2_deduplication()
    test_3_transient_failure()
    test_4_restart_count()
    test_5_namespace_filtering()
    test_6_recovery_after_wait_started()
    test_safe_extraction_edge_cases()
    test_7_image_pull_backoff()
    test_8_crashloop_one_restart()
    test_9_pending_40_seconds()
    test_10_trigger_diagnosis_non_blocking()

    print("\nAll tests passed.")
