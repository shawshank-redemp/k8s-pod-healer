"""
Test script for diagnosis_module.py.

Covers the 5 core scenarios from the spec, plus one extra test per remaining
loophole (deleted pod, log truncation, primary-container-only fetch, init
container logs, Claude API failure) that the 5 core scenarios don't already
exercise. Everything runs against MockK8sClient/MockClaudeClient - no real
cluster or Claude API key needed.

Run with:
    python test_diagnosis.py
"""

import time

from kubernetes import client as k8s_client

from diagnosis_module import (
    DiagnosisModule,
    MockK8sClient,
    MockClaudeClient,
    truncate_logs,
    LOG_SIZE_THRESHOLD_CHARS,
)


def check(label, condition):
    status = "PASS" if condition else "FAIL"
    print(f"[{status}] {label}")
    assert condition, label


def make_pod_info(**overrides):
    base = {
        "pod_id": "default/test-pod",
        "pod_name": "test-pod",
        "namespace": "default",
        "status": "Running",
        "reason": "Unknown",
        "restart_count": 0,
        "severity": "MEDIUM",
        "pod_object": None,
    }
    base.update(overrides)
    return base


def make_pod_object(container_names=("main",), init_container_reason=None):
    """Build a real V1Pod so _get_primary_container_name / _get_failed_init_container
    exercise the actual attribute paths they'll see in production."""
    containers = [k8s_client.V1Container(name=n, image="busybox") for n in container_names]

    init_statuses = None
    if init_container_reason:
        init_statuses = [
            k8s_client.V1ContainerStatus(
                name="init-setup", image="busybox", image_id="", ready=False, restart_count=0,
                state=k8s_client.V1ContainerState(
                    terminated=k8s_client.V1ContainerStateTerminated(
                        reason=init_container_reason, exit_code=1
                    )
                ),
            )
        ]

    return k8s_client.V1Pod(
        metadata=k8s_client.V1ObjectMeta(name="test-pod", namespace="default"),
        spec=k8s_client.V1PodSpec(containers=containers),
        status=k8s_client.V1PodStatus(phase="Running", init_container_statuses=init_statuses),
    )


# --------------------------------------------------------------------------
# TEST 1: OOMKilled pod
# --------------------------------------------------------------------------

def test_1_oom_killed():
    print("\n--- Test 1: OOMKilled Pod ---")
    pod_info = make_pod_info(status="Failed", reason="OOMKilled", restart_count=3, severity="HIGH")

    mock_k8s = MockK8sClient(
        pod_status="Failed",
        logs="Starting app...\nmemory exceeded\nContainer killed with exit code 137",
        events=[{"reason": "OOMKilling", "message": "Memory cgroup out of memory", "timestamp": int(time.time())}],
        spec={"image": "myapp:latest", "memory_limit": "512Mi", "cpu_limit": "250m",
              "memory_request": "256Mi", "cpu_request": "100m", "env_vars": {},
              "liveness_probe": None, "readiness_probe": None},
    )
    mock_claude = MockClaudeClient(response={
        "root_cause": "Out of memory",
        "severity": "CRITICAL",
        "recommended_fix": "Increase memory limit above 512Mi",
        "confidence": 0.95,
        "evidence": ["exit code 137", "memory exceeded in logs"],
    })

    module = DiagnosisModule(k8s_client=mock_k8s, claude_client=mock_claude)
    diagnosis = module.diagnose(pod_info)

    check("root_cause is Out of memory", diagnosis["root_cause"] == "Out of memory")
    check("severity is CRITICAL", diagnosis["severity"] == "CRITICAL")
    check("confidence is 0.95", diagnosis["confidence"] == 0.95)
    check("pod_id preserved", diagnosis["pod_id"] == "default/test-pod")
    check("raw_logs contains exit code evidence", "137" in diagnosis["raw_logs"])


# --------------------------------------------------------------------------
# TEST 2: ImagePullBackOff
# --------------------------------------------------------------------------

def test_2_image_pull_backoff():
    print("\n--- Test 2: ImagePullBackOff ---")
    pod_info = make_pod_info(status="Pending", reason="ImagePullBackOff", severity="CRITICAL")

    mock_k8s = MockK8sClient(
        pod_status="Pending",
        logs="",
        events=[{"reason": "FailedPulling", "message": "rpc error: image myapp:typo123 not found",
                 "timestamp": int(time.time())}],
        spec={"image": "myapp:typo123", "memory_limit": "256Mi", "cpu_limit": "200m",
              "memory_request": "128Mi", "cpu_request": "100m", "env_vars": {},
              "liveness_probe": None, "readiness_probe": None},
    )
    mock_claude = MockClaudeClient(response={
        "root_cause": "Image not found",
        "severity": "CRITICAL",
        "recommended_fix": "Fix the image tag typo123 to a valid tag",
        "confidence": 0.95,
        "evidence": ["FailedPulling event", "image tag typo123"],
    })

    module = DiagnosisModule(k8s_client=mock_k8s, claude_client=mock_claude)
    diagnosis = module.diagnose(pod_info)

    check("root_cause is Image not found", diagnosis["root_cause"] == "Image not found")
    check("severity is CRITICAL", diagnosis["severity"] == "CRITICAL")
    check("confidence is 0.95", diagnosis["confidence"] == 0.95)
    check("no logs handled - raw_logs marks it explicitly",
          diagnosis["raw_logs"] == "[No logs - check events]")


# --------------------------------------------------------------------------
# TEST 3: No logs available
# --------------------------------------------------------------------------

def test_3_no_logs_available():
    print("\n--- Test 3: No Logs Available ---")
    pod_info = make_pod_info(status="CrashLoopBackOff", reason="CrashLoopBackOff", severity="HIGH")

    mock_k8s = MockK8sClient(
        pod_status="CrashLoopBackOff",
        logs="",
        events=[{"reason": "BackOff", "message": "Back-off restarting failed container",
                 "timestamp": int(time.time())}],
    )
    mock_claude = MockClaudeClient(response={
        "root_cause": "Unable to diagnose without logs",
        "severity": "LOW",
        "recommended_fix": "Check events and add application logging",
        "confidence": 0.3,
        "evidence": ["No logs available", "BackOff event present"],
    })

    module = DiagnosisModule(k8s_client=mock_k8s, claude_client=mock_claude)
    diagnosis = module.diagnose(pod_info)

    check("root_cause reflects missing logs",
          diagnosis["root_cause"] == "Unable to diagnose without logs")
    check("confidence is low", diagnosis["confidence"] == 0.3)
    check("raw_logs placeholder sent instead of failing",
          diagnosis["raw_logs"] == "[No logs - check events]")


# --------------------------------------------------------------------------
# TEST 4: Pod recovered (skip diagnosis, save the Claude call)
# --------------------------------------------------------------------------

def test_4_pod_recovered():
    print("\n--- Test 4: Pod Recovered ---")
    pod_info = make_pod_info(status="CrashLoopBackOff", reason="CrashLoopBackOff", restart_count=5)

    mock_k8s = MockK8sClient(pod_status="Running")
    mock_claude = MockClaudeClient()  # should never be called

    module = DiagnosisModule(k8s_client=mock_k8s, claude_client=mock_claude)
    diagnosis = module.diagnose(pod_info)

    check("root_cause is Pod recovered", diagnosis["root_cause"] == "Pod recovered")
    check("Claude was never called (saved the API call)", mock_claude.call_count == 0)


# --------------------------------------------------------------------------
# TEST 5: Liveness probe killing pod
# --------------------------------------------------------------------------

def test_5_liveness_probe_too_aggressive():
    print("\n--- Test 5: Liveness Probe Killing Pod ---")
    pod_info = make_pod_info(status="CrashLoopBackOff", reason="CrashLoopBackOff", restart_count=6)

    mock_k8s = MockK8sClient(
        pod_status="CrashLoopBackOff",
        logs="App started\nHealthy\nHealthy\nContainer killed",
        events=[{"reason": "Unhealthy",
                 "message": "Liveness probe failed: HTTP probe failed with statuscode: 500",
                 "timestamp": int(time.time())}],
        spec={"image": "myapp:latest", "memory_limit": "256Mi", "cpu_limit": "200m",
              "memory_request": "128Mi", "cpu_request": "100m", "env_vars": {},
              "liveness_probe": {"timeout_seconds": 5, "period_seconds": 5, "failure_threshold": 1},
              "readiness_probe": None},
    )
    mock_claude = MockClaudeClient(response={
        "root_cause": "Liveness probe too aggressive",
        "severity": "HIGH",
        "recommended_fix": "Increase liveness probe timeoutSeconds and failureThreshold",
        "confidence": 0.80,
        "evidence": ["Liveness probe failed events", "logs show Healthy right before kill"],
    })

    module = DiagnosisModule(k8s_client=mock_k8s, claude_client=mock_claude)
    diagnosis = module.diagnose(pod_info)

    check("root_cause mentions the probe", "probe" in diagnosis["root_cause"].lower())
    check("recommended_fix suggests a probe adjustment",
          "probe" in diagnosis["recommended_fix"].lower())
    check("confidence is 0.80", diagnosis["confidence"] == 0.80)


# --------------------------------------------------------------------------
# EXTRA: Loophole 1 - pod deleted before diagnosis
# --------------------------------------------------------------------------

def test_6_pod_deleted():
    print("\n--- Extra: Pod Deleted Before Diagnosis ---")
    pod_info = make_pod_info()

    mock_k8s = MockK8sClient(raise_not_found=True)
    mock_claude = MockClaudeClient()

    module = DiagnosisModule(k8s_client=mock_k8s, claude_client=mock_claude)
    diagnosis = module.diagnose(pod_info)

    check("root_cause is Pod deleted", diagnosis["root_cause"] == "Pod deleted")
    check("confidence is 0.0", diagnosis["confidence"] == 0.0)
    check("Claude was never called", mock_claude.call_count == 0)


# --------------------------------------------------------------------------
# EXTRA: Loophole 3 - log truncation keeps error lines out of the tail window
# --------------------------------------------------------------------------

def test_7_log_truncation_keeps_error_lines():
    print("\n--- Extra: Log Truncation Keeps Error Lines ---")
    filler = "normal log line, nothing interesting here\n" * 400
    huge_log = "FATAL: disk full during startup\n" + filler
    check("fixture is actually over the threshold", len(huge_log) > LOG_SIZE_THRESHOLD_CHARS)

    truncated = truncate_logs(huge_log)
    check("truncated output is shorter than the original", len(truncated) < len(huge_log))
    check("early FATAL line survives truncation despite being outside the tail",
          "FATAL: disk full during startup" in truncated)

    short_log = "just a short log, no truncation needed"
    check("short logs pass through unchanged", truncate_logs(short_log) == short_log)
    check("empty logs get the placeholder", truncate_logs("") == "[No logs available]")


# --------------------------------------------------------------------------
# EXTRA: Loophole 4 - multiple containers, primary only
# --------------------------------------------------------------------------

def test_8_primary_container_only():
    print("\n--- Extra: Multiple Containers - Primary Only ---")
    pod_info = make_pod_info(
        pod_object=make_pod_object(container_names=("main-app", "sidecar-proxy", "sidecar-logger"))
    )
    mock_k8s = MockK8sClient(pod_status="Failed")

    module = DiagnosisModule(k8s_client=mock_k8s, claude_client=MockClaudeClient())
    module.diagnose(pod_info)

    check("logs fetched for the first container only",
          mock_k8s.last_logs_container == "main-app")


# --------------------------------------------------------------------------
# EXTRA: Loophole 6 - init container failure logs get included
# --------------------------------------------------------------------------

def test_9_init_container_failure():
    print("\n--- Extra: Init Container Failure Logs Included ---")
    pod_info = make_pod_info(
        pod_object=make_pod_object(container_names=("main",), init_container_reason="Error")
    )
    mock_k8s = MockK8sClient(
        pod_status="Failed",
        logs="main container starting normally",
        init_container_logs="init-setup: failed to mount config volume",
    )

    module = DiagnosisModule(k8s_client=mock_k8s, claude_client=MockClaudeClient())
    diagnosis = module.diagnose(pod_info)

    check("init container logs included in raw_logs",
          "failed to mount config volume" in diagnosis["raw_logs"])
    check("main container logs still present too",
          "main container starting normally" in diagnosis["raw_logs"])


# --------------------------------------------------------------------------
# EXTRA: Loophole 7 - Claude API failure degrades gracefully
# --------------------------------------------------------------------------

def test_10_claude_api_failure():
    print("\n--- Extra: Claude API Failure ---")
    pod_info = make_pod_info(status="Failed", severity="HIGH")

    mock_k8s = MockK8sClient(pod_status="Failed")
    mock_claude = MockClaudeClient(raise_error=True)

    module = DiagnosisModule(k8s_client=mock_k8s, claude_client=mock_claude)
    diagnosis = module.diagnose(pod_info)

    check("root_cause is Diagnosis pending", diagnosis["root_cause"] == "Diagnosis pending")
    check("confidence is 0.0", diagnosis["confidence"] == 0.0)
    check("severity falls back to pod_info severity", diagnosis["severity"] == "HIGH")


if __name__ == "__main__":
    test_1_oom_killed()
    test_2_image_pull_backoff()
    test_3_no_logs_available()
    test_4_pod_recovered()
    test_5_liveness_probe_too_aggressive()
    test_6_pod_deleted()
    test_7_log_truncation_keeps_error_lines()
    test_8_primary_container_only()
    test_9_init_container_failure()
    test_10_claude_api_failure()

    print("\nAll tests passed.")
