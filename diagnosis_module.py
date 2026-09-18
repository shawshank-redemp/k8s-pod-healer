"""
Sentinel - Kubernetes Pod Diagnosis Module (Step 2).

Takes pod_info from Step 1 (detection_module.py), fetches logs/events/spec
from Kubernetes, sends them to Claude for root-cause analysis, and returns a
structured diagnosis dict. Built around two pluggable interfaces
(K8sDataFetcher, ClaudeAnalyzer) so mocks can be swapped for real clients
without touching DiagnosisModule itself.
"""

import json
import time
from abc import ABC, abstractmethod

# --------------------------------------------------------------------------
# Configuration / tunable constants
# --------------------------------------------------------------------------

# NOTE: the spec text disagreed with itself on the truncation trigger size
# ("Truncate to last 3000 chars if > 10KB" in the data-collection section vs.
# "Logs > 10MB" in the loopholes section). 10KB is used here since it's the
# threshold actually reachable by real pod logs in a diagnosis window; 10MB
# would almost never trigger truncation in practice.
LOG_TRUNCATE_CHARS = 3000
LOG_SIZE_THRESHOLD_CHARS = 10_000
MAX_EVENTS = 10
ERROR_KEYWORDS = ("error", "exception", "panic", "fatal", "oom", "killed", "crash")


class PodNotFoundError(Exception):
    """Raised when a pod no longer exists (deleted before/during diagnosis)."""


# --------------------------------------------------------------------------
# Interfaces
# --------------------------------------------------------------------------

class K8sDataFetcher(ABC):
    """Interface for fetching pod data from Kubernetes."""

    @abstractmethod
    def get_pod_status(self, namespace, pod_name):
        """Return the pod's current phase string. Raise on lookup failure
        (e.g. PodNotFoundError, or a kubernetes ApiException) so the caller
        can distinguish 'deleted/unreachable' from a normal phase value."""

    @abstractmethod
    def fetch_pod_logs(self, namespace, pod_name, container=None, previous=False):
        """Return logs for `container` (primary container if None) as str."""

    @abstractmethod
    def fetch_init_container_logs(self, namespace, pod_name, container=None):
        """Return logs for a specific init container, or None if unavailable."""

    @abstractmethod
    def fetch_pod_events(self, namespace, pod_name):
        """Return a list of {reason, message, timestamp} dicts."""

    @abstractmethod
    def fetch_pod_spec(self, pod_object):
        """Return a dict: image, memory_limit, cpu_limit, memory_request,
        cpu_request, env_vars, liveness_probe, readiness_probe."""


class ClaudeAnalyzer(ABC):
    """Interface for sending diagnostic context to Claude and getting a
    structured root-cause analysis back."""

    @abstractmethod
    def analyze(self, pod_info, logs, events, spec):
        """Return a dict: root_cause, severity, recommended_fix, confidence,
        evidence (list of strings)."""


# --------------------------------------------------------------------------
# Prompt construction
# --------------------------------------------------------------------------

def _format_events(events):
    if not events:
        return "[No events]"
    lines = []
    for e in events:
        reason = e.get("reason", "Unknown")
        message = e.get("message", "")
        ts = e.get("timestamp")
        lines.append(f"- [{ts}] {reason}: {message}" if ts else f"- {reason}: {message}")
    return "\n".join(lines)


def _format_spec_fields(spec):
    spec = spec or {}
    image = spec.get("image", "unknown")
    memory_limit = spec.get("memory_limit", "not set")
    cpu_limit = spec.get("cpu_limit", "not set")
    env_vars = spec.get("env_vars") or {}
    env_str = ", ".join(f"{k}={v}" for k, v in env_vars.items()) or "none"

    probes = []
    if spec.get("liveness_probe"):
        probes.append(f"liveness={spec['liveness_probe']}")
    if spec.get("readiness_probe"):
        probes.append(f"readiness={spec['readiness_probe']}")
    probes_str = "; ".join(probes) if probes else "none configured"

    return image, memory_limit, cpu_limit, env_str, probes_str


def build_diagnosis_prompt(pod_info, logs, events, spec):
    """Build the exact prompt Claude sees. Used by RealClaudeClient; kept as
    a standalone function so it's easy to unit test or reuse."""
    image, memory_limit, cpu_limit, env_str, probes_str = _format_spec_fields(spec)

    return f"""You are a Kubernetes troubleshooting expert.

POD: {pod_info.get('pod_name', 'unknown')} in {pod_info.get('namespace', 'unknown')}
Status: {pod_info.get('status', 'Unknown')}
Reason: {pod_info.get('reason', 'Unknown')}
Severity: {pod_info.get('severity', 'Unknown')}
Restarts: {pod_info.get('restart_count', 0)}

EVENTS:
{_format_events(events)}

LOGS:
{logs}

SPEC:
- Image: {image}
- Memory limit: {memory_limit}
- CPU limit: {cpu_limit}
- Environment: {env_str}
- Probes: {probes_str}

Provide diagnosis as JSON:
{{
  "root_cause": "specific reason pod is failing",
  "severity": "CRITICAL|HIGH|MEDIUM|LOW",
  "recommended_fix": "how to fix it",
  "confidence": 0.0-1.0,
  "evidence": ["key evidence line 1", "key evidence line 2"]
}}"""


# --------------------------------------------------------------------------
# Log handling helpers
# --------------------------------------------------------------------------

def truncate_logs(logs, max_chars=LOG_TRUNCATE_CHARS, threshold_chars=LOG_SIZE_THRESHOLD_CHARS):
    """Loophole fix: huge logs get truncated to the tail, but error lines
    found anywhere in the log (even outside the tail window) are preserved
    up front so Claude doesn't lose the actual failure signal."""
    if not logs:
        return "[No logs available]"
    if len(logs) <= threshold_chars:
        return logs

    tail = logs[-max_chars:]
    error_lines = [l for l in logs.splitlines() if any(k in l.lower() for k in ERROR_KEYWORDS)]

    header = f"[Log truncated - {len(logs)} chars total, showing last {max_chars}]"
    if error_lines:
        error_block = "\n".join(error_lines[-10:])
        return f"{header}\n\n--- ERROR LINES (extracted) ---\n{error_block}\n\n--- TAIL ---\n{tail}"
    return f"{header}\n\n{tail}"


def _get_primary_container_name(pod_object):
    """Loophole fix: multiple containers - only ever fetch the first
    (primary) container's logs, never all of them."""
    try:
        return pod_object.spec.containers[0].name
    except Exception:
        return None


def _get_failed_init_container(pod_object):
    """Loophole fix: init container failures. Returns the name of an init
    container that's stuck waiting or terminated abnormally, or None if all
    init containers are missing/running/completed cleanly."""
    try:
        statuses = pod_object.status.init_container_statuses or []
    except Exception:
        return None

    for cs in statuses:
        state = getattr(cs, "state", None)
        if state is None:
            continue
        if getattr(state, "waiting", None) is not None:
            return cs.name
        terminated = getattr(state, "terminated", None)
        if terminated is not None and getattr(terminated, "reason", None) not in (None, "Completed"):
            return cs.name
    return None


# --------------------------------------------------------------------------
# Mock clients (for testing NOW, before a real cluster/Claude key exist)
# --------------------------------------------------------------------------

class MockK8sClient(K8sDataFetcher):
    """Canned Kubernetes responses, configurable per test scenario."""

    def __init__(self, logs=None, events=None, spec=None, pod_status="Running",
                 init_container_logs=None, raise_not_found=False):
        self._logs = logs if logs is not None else "[MockLog] Container crashed with exit code 137"
        self._events = events if events is not None else [
            {"reason": "BackOff", "message": "Pod crashing", "timestamp": int(time.time())}
        ]
        self._spec = spec if spec is not None else {
            "image": "mock-image:latest",
            "memory_limit": "512Mi",
            "cpu_limit": "100m",
            "memory_request": "256Mi",
            "cpu_request": "50m",
            "env_vars": {},
            "liveness_probe": None,
            "readiness_probe": None,
        }
        self._pod_status = pod_status
        self._init_container_logs = init_container_logs
        self._raise_not_found = raise_not_found

        # Spy attributes so tests can assert on how the client was called.
        self.last_logs_container = None
        self.call_count = 0

    def get_pod_status(self, namespace, pod_name):
        self.call_count += 1
        if self._raise_not_found:
            raise PodNotFoundError(f"pod {namespace}/{pod_name} not found")
        return self._pod_status

    def fetch_pod_logs(self, namespace, pod_name, container=None, previous=False):
        self.last_logs_container = container
        return self._logs

    def fetch_init_container_logs(self, namespace, pod_name, container=None):
        return self._init_container_logs

    def fetch_pod_events(self, namespace, pod_name):
        return list(self._events)

    def fetch_pod_spec(self, pod_object):
        return dict(self._spec)


class MockClaudeClient(ClaudeAnalyzer):
    """Canned Claude responses, configurable per test scenario."""

    def __init__(self, response=None, raise_error=False):
        self._response = response
        self._raise_error = raise_error
        self.call_count = 0

    def analyze(self, pod_info, logs, events, spec):
        self.call_count += 1
        if self._raise_error:
            raise RuntimeError("Mock Claude API failure")
        if self._response is not None:
            return dict(self._response)
        return {
            "root_cause": "OOMKilled",
            "severity": "CRITICAL",
            "recommended_fix": "[MockClaude] Investigate manually",
            "confidence": 0.95,
            "evidence": ["[MockClaude] default canned response"],
        }


# --------------------------------------------------------------------------
# Real clients (wire these in on hackathon day - see README.md)
# --------------------------------------------------------------------------

class RealK8sClient(K8sDataFetcher):
    """Talks to an actual cluster via the `kubernetes` Python client.
    Kubernetes is imported lazily so this module stays importable (and the
    mocks stay usable) even in environments without cluster access.
    """

    def __init__(self, config_path=None):
        from kubernetes import client, config as k8s_config

        try:
            if config_path:
                k8s_config.load_kube_config(config_file=config_path)
            else:
                k8s_config.load_incluster_config()
        except Exception:
            k8s_config.load_kube_config(config_file=config_path)

        self._client_module = client
        self._v1 = client.CoreV1Api()

    def get_pod_status(self, namespace, pod_name):
        try:
            pod = self._v1.read_namespaced_pod(name=pod_name, namespace=namespace)
            return pod.status.phase
        except self._client_module.exceptions.ApiException as exc:
            if exc.status == 404:
                raise PodNotFoundError(f"pod {namespace}/{pod_name} not found") from exc
            raise

    def fetch_pod_logs(self, namespace, pod_name, container=None, previous=False):
        try:
            return self._v1.read_namespaced_pod_log(
                name=pod_name, namespace=namespace, container=container,
                previous=previous, tail_lines=500,
            )
        except Exception:
            return ""

    def fetch_init_container_logs(self, namespace, pod_name, container=None):
        try:
            return self._v1.read_namespaced_pod_log(
                name=pod_name, namespace=namespace, container=container, tail_lines=200,
            )
        except Exception:
            return None

    def fetch_pod_events(self, namespace, pod_name):
        try:
            events = self._v1.list_namespaced_event(
                namespace=namespace,
                field_selector=f"involvedObject.name={pod_name}",
            )
            return [
                {
                    "reason": e.reason,
                    "message": e.message,
                    "timestamp": str(e.last_timestamp or e.event_time or ""),
                }
                for e in events.items[-MAX_EVENTS:]
            ]
        except Exception:
            return []

    def fetch_pod_spec(self, pod_object):
        try:
            container = pod_object.spec.containers[0]
            resources = container.resources
            limits = (resources.limits or {}) if resources else {}
            requests = (resources.requests or {}) if resources else {}
            env_vars = {e.name: e.value for e in (container.env or []) if e.value is not None}
            return {
                "image": container.image,
                "memory_limit": limits.get("memory", "not set"),
                "cpu_limit": limits.get("cpu", "not set"),
                "memory_request": requests.get("memory", "not set"),
                "cpu_request": requests.get("cpu", "not set"),
                "env_vars": env_vars,
                "liveness_probe": _probe_to_dict(container.liveness_probe),
                "readiness_probe": _probe_to_dict(container.readiness_probe),
            }
        except Exception:
            return {
                "image": "unknown", "memory_limit": "unknown", "cpu_limit": "unknown",
                "memory_request": "unknown", "cpu_request": "unknown",
                "env_vars": {}, "liveness_probe": None, "readiness_probe": None,
            }


def _probe_to_dict(probe):
    if probe is None:
        return None
    try:
        return {
            "initial_delay_seconds": probe.initial_delay_seconds,
            "period_seconds": probe.period_seconds,
            "timeout_seconds": probe.timeout_seconds,
            "failure_threshold": probe.failure_threshold,
        }
    except Exception:
        return None


class RealClaudeClient(ClaudeAnalyzer):
    """Sends the diagnosis prompt to the real Claude API via the `anthropic`
    SDK. Imported lazily for the same reason as RealK8sClient above.
    """

    def __init__(self, api_key, model="claude-sonnet-5"):
        import anthropic
        self._client = anthropic.Anthropic(api_key=api_key)
        self._model = model

    def analyze(self, pod_info, logs, events, spec):
        prompt = build_diagnosis_prompt(pod_info, logs, events, spec)
        response = self._client.messages.create(
            model=self._model,
            max_tokens=1024,
            messages=[{"role": "user", "content": prompt}],
        )
        text = response.content[0].text
        return _parse_claude_json(text)


def _parse_claude_json(text):
    """Claude may wrap the JSON in prose or a code fence - pull out the
    {...} block rather than assuming the whole response is bare JSON."""
    try:
        start = text.index("{")
        end = text.rindex("}") + 1
        return json.loads(text[start:end])
    except Exception:
        return {
            "root_cause": "Unable to parse Claude response",
            "severity": "MEDIUM",
            "recommended_fix": "Manual review required - diagnosis parsing failed",
            "confidence": 0.0,
            "evidence": [text[:200] if text else "empty response"],
        }


# --------------------------------------------------------------------------
# DiagnosisModule
# --------------------------------------------------------------------------

class DiagnosisModule:
    """Orchestrates fetching K8s context and getting a Claude diagnosis.

    NOW (testing):        DiagnosisModule()  # MockK8sClient + MockClaudeClient
    HACKATHON DAY (real): DiagnosisModule(k8s_client=RealK8sClient(...),
                                           claude_client=RealClaudeClient(...))
    `diagnose()` is identical either way.
    """

    def __init__(self, k8s_client=None, claude_client=None):
        self.k8s_client = k8s_client or MockK8sClient()
        self.claude_client = claude_client or MockClaudeClient()

    def diagnose(self, pod_info):
        pod_name = pod_info.get("pod_name", "unknown")
        namespace = pod_info.get("namespace", "unknown")
        pod_id = pod_info.get("pod_id") or f"{namespace}/{pod_name}"

        # Loophole 1: pod deleted before diagnosis even starts.
        try:
            current_status = self.k8s_client.get_pod_status(namespace, pod_name)
        except Exception as exc:
            return self._build_diagnosis(
                pod_id, pod_name, namespace,
                root_cause="Pod deleted",
                severity="LOW",
                recommended_fix="No action needed - pod no longer exists",
                confidence=0.0,
                evidence=[f"Pod lookup failed: {exc}"],
                raw_logs="", raw_events=[],
            )

        # Loophole 5: pod recovered on its own - skip the Claude call to
        # save API cost/latency.
        if current_status == "Running":
            return self._build_diagnosis(
                pod_id, pod_name, namespace,
                root_cause="Pod recovered",
                severity="LOW",
                recommended_fix="No action needed - pod is now Running",
                confidence=1.0,
                evidence=["Pod status re-checked as Running at diagnosis time"],
                raw_logs="", raw_events=[],
            )

        pod_object = pod_info.get("pod_object")

        # Loophole 4: multiple containers - fetch the primary container only.
        primary_container = _get_primary_container_name(pod_object)
        try:
            logs = self.k8s_client.fetch_pod_logs(namespace, pod_name, container=primary_container) or ""
        except Exception:
            logs = ""

        # Loophole 6: init container failures - prepend init logs if one
        # isn't Running/Completed cleanly.
        failed_init = _get_failed_init_container(pod_object)
        if failed_init:
            try:
                init_logs = self.k8s_client.fetch_init_container_logs(namespace, pod_name, container=failed_init)
            except Exception:
                init_logs = None
            if init_logs:
                logs = f"[Init container '{failed_init}' logs]\n{init_logs}\n\n[Main container logs]\n{logs}"

        # Loophole 2: no logs available - tell Claude explicitly, don't fail.
        if not logs.strip():
            logs = "[No logs - check events]"

        # Loophole 3: huge logs - truncate but keep error lines.
        logs = truncate_logs(logs)

        try:
            events = self.k8s_client.fetch_pod_events(namespace, pod_name) or []
        except Exception:
            events = []
        events = events[-MAX_EVENTS:]

        try:
            spec = self.k8s_client.fetch_pod_spec(pod_object) or {}
        except Exception:
            spec = {}

        # Loophole 7: Claude API failure - degrade gracefully.
        try:
            result = self.claude_client.analyze(pod_info, logs, events, spec)
        except Exception as exc:
            result = {
                "root_cause": "Diagnosis pending",
                "severity": pod_info.get("severity", "MEDIUM"),
                "recommended_fix": "Manual review required - diagnosis engine unavailable",
                "confidence": 0.0,
                "evidence": [f"Claude analysis failed: {exc}"],
            }

        return self._build_diagnosis(
            pod_id, pod_name, namespace,
            root_cause=result.get("root_cause", "Unknown"),
            severity=result.get("severity", pod_info.get("severity", "MEDIUM")),
            recommended_fix=result.get("recommended_fix", "No recommendation available"),
            confidence=result.get("confidence", 0.0),
            evidence=result.get("evidence", []),
            raw_logs=logs, raw_events=events,
        )

    @staticmethod
    def _build_diagnosis(pod_id, pod_name, namespace, root_cause, severity,
                          recommended_fix, confidence, evidence, raw_logs, raw_events):
        return {
            "pod_id": pod_id,
            "pod_name": pod_name,
            "namespace": namespace,
            "root_cause": root_cause,
            "severity": severity,
            "recommended_fix": recommended_fix,
            "confidence": confidence,
            "evidence": evidence,
            "analysis_timestamp": int(time.time()),
            "raw_logs": raw_logs,
            "raw_events": raw_events,
        }


if __name__ == "__main__":
    demo_pod_info = {
        "pod_id": "default/demo-pod",
        "pod_name": "demo-pod",
        "namespace": "default",
        "status": "Failed",
        "reason": "OOMKilled",
        "restart_count": 3,
        "severity": "HIGH",
        "pod_object": None,
    }
    # Explicit pod_status="Failed" so this demo shows the full pipeline
    # (logs/events/spec -> Claude) rather than the default mock's
    # pod_status="Running", which would just trigger the recovered shortcut.
    module = DiagnosisModule(k8s_client=MockK8sClient(pod_status="Failed"))
    print(json.dumps(module.diagnose(demo_pod_info), indent=2))
