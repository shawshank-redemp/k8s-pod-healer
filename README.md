# Sentinel - Detection & Diagnosis Modules (Steps 1-2)

Two pieces:

- **Step 1 - Detection** (`detection_module.py`): event-driven watcher that
  detects pod failures, filters out transient/self-recovering ones, and
  hands off a `pod_info` dict via `trigger_diagnosis()`.
- **Step 2 - Diagnosis** (`diagnosis_module.py`): takes that `pod_info`,
  fetches logs/events/spec from Kubernetes, sends them to Claude for
  root-cause analysis, and returns a structured diagnosis dict.

## Files

- `detection_module.py` / `test_detection.py` - Step 1 and its tests
- `diagnosis_module.py` / `test_diagnosis.py` - Step 2 and its tests
- `requirements.txt` - Python dependencies

## Setup

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

`anthropic` is only needed if you instantiate `RealClaudeClient` - the mocks
and all tests run without it.

## Run the unit tests

```bash
python test_detection.py
python test_diagnosis.py
```

Both run in a few seconds against fabricated/mock data - no real cluster or
Claude API key required.

---

## Step 1: Detection

### Run against a real cluster (Kind)

```bash
brew install kind
kind create cluster --name sentinel-test
python detection_module.py
```

With the watcher running, open a second terminal and try the scenarios below.

**Test 1 - Basic detection**

```bash
kubectl run crash-test --image=busybox -- sh -c "exit 1"
```

Expect, after ~30s of continuous CrashLoopBackOff with restart_count >= 1:

```
DETECTED: Pod failure - crash-test [default] reason=CrashLoopBackOff restarts=1 severity=HIGH
→ DIAGNOSIS: Processing crash-test ... severity=HIGH
```

**Test 2 - Deduplication**: leave `crash-test` running and let it keep
restarting. It should only ever be diagnosed once per 5-minute window, even
as `restart_count` keeps climbing.

**Test 3 - Transient failure**

```bash
kubectl run flaky-test --image=busybox -- sh -c "exit 1"
kubectl delete pod flaky-test   # simulate recovery before the 30s wait elapses
```

**Test 4 - Restart count threshold**: `crash-test` should be diagnosed as
soon as `restart_count` reaches 1 (not 3) and the 30s wait has elapsed.

**Test 5 - Namespace filtering**

```bash
kubectl run system-crash --image=busybox -n kube-system -- sh -c "exit 1"
```

The watcher should never log this pod, even after many restarts.

**Test 6 - Recovery after the wait starts**: delete or fix a failing pod
within the 30s window after it starts failing - it should be dropped from
`waiting_pods` instead of being diagnosed.

```bash
kind delete cluster --name sentinel-test   # cleanup
```

### Handoff to Step 2

`extract_pod_info()` returns:
`{pod_id, pod_name, namespace, status, reason, restart_count, severity, pod_object}`.
This dict is exactly what `DiagnosisModule.diagnose(pod_info)` expects.

---

## Step 2: Diagnosis

### Using it with mocks (now)

```python
from diagnosis_module import DiagnosisModule

module = DiagnosisModule()  # MockK8sClient + MockClaudeClient
diagnosis = module.diagnose(pod_info)
print(diagnosis["root_cause"], diagnosis["severity"], diagnosis["confidence"])
```

`MockK8sClient` and `MockClaudeClient` both take constructor overrides so
you can script specific scenarios:

```python
from diagnosis_module import DiagnosisModule, MockK8sClient, MockClaudeClient

mock_k8s = MockK8sClient(
    pod_status="Failed",
    logs="memory exceeded, exit code 137",
    events=[{"reason": "OOMKilling", "message": "...", "timestamp": 1234}],
    spec={"image": "myapp:latest", "memory_limit": "512Mi", ...},
)
mock_claude = MockClaudeClient(response={
    "root_cause": "Out of memory", "severity": "CRITICAL",
    "recommended_fix": "Increase memory limit", "confidence": 0.95,
    "evidence": ["exit code 137"],
})

module = DiagnosisModule(k8s_client=mock_k8s, claude_client=mock_claude)
diagnosis = module.diagnose(pod_info)
```

### Swapping in real clients (hackathon day)

```python
from diagnosis_module import DiagnosisModule, RealK8sClient, RealClaudeClient

real_k8s = RealK8sClient(config_path="~/.kube/config")   # or omit for in-cluster
real_claude = RealClaudeClient(api_key=api_key_from_trueforge)

module = DiagnosisModule(k8s_client=real_k8s, claude_client=real_claude)
diagnosis = module.diagnose(pod_info)  # identical call, real data now
```

`RealK8sClient`/`RealClaudeClient` lazily import `kubernetes`/`anthropic`
inside `__init__`, so importing `diagnosis_module` never requires either
package unless you actually construct one of these.

### Wiring Step 1 -> Step 2

In `detection_module.py`, replace the body of `trigger_diagnosis(pod_info)`:

```python
def trigger_diagnosis(pod_info):
    diagnosis = diagnosis_module.diagnose(pod_info)
    print(f"→ DIAGNOSIS: {diagnosis['root_cause']} "
          f"(confidence={diagnosis['confidence']}) - {diagnosis['recommended_fix']}")
```

The detection loop never blocks on this - `should_diagnose_now()` already
guarantees `trigger_diagnosis` is only called once per pod per 5-minute
window, so a slow Claude call doesn't back up the watch stream.

### Diagnosis output shape

```python
{
    "pod_id": "namespace/pod-name",
    "pod_name": "pod-name",
    "namespace": "namespace",
    "root_cause": "reason pod is failing",
    "severity": "CRITICAL" | "HIGH" | "MEDIUM" | "LOW",
    "recommended_fix": "how to fix it",
    "confidence": 0.85,
    "evidence": ["line 1", "line 2"],
    "analysis_timestamp": 1695406800,
    "raw_logs": "...",
    "raw_events": [{"reason": "...", "message": "...", "timestamp": "..."}],
}
```

### Loopholes handled

1. Pod deleted before diagnosis -> `root_cause="Pod deleted"`, confidence 0.0, Claude never called.
2. No logs available -> `"[No logs - check events]"` sent to Claude instead of failing.
3. Logs over ~10KB -> truncated to the last 3000 chars, but any line matching an error keyword (error/exception/panic/fatal/oom/killed/crash) anywhere in the log is extracted and kept even if it falls outside the tail.
4. Multiple containers -> only the primary (first) container's logs are fetched.
5. Pod recovered during diagnosis -> pod status is re-checked right before fetching logs; if it's back to `Running`, diagnosis is skipped entirely (no Claude call).
6. Init container failures -> if an init container is stuck waiting or terminated abnormally, its logs are prepended to the main container's logs.
7. Claude API failure -> caught and returned as `root_cause="Diagnosis pending"`, confidence 0.0, instead of raising.
