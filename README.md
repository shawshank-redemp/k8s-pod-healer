# Sentinel - Detection & Diagnosis Modules (Steps 1-2)

Two pieces, both Java 21 / Maven:

- **Step 1 - Detection** (`com.sentinel.detection.DetectionModule`): event-driven watcher that
  detects pod failures, filters out transient/self-recovering ones, and hands off a `PodInfo`
  via `triggerDiagnosis()`.
- **Step 2 - Diagnosis** (`com.sentinel.diagnosis.DiagnosisModule`): takes that `PodInfo`,
  fetches logs/events/spec from Kubernetes, sends them to Claude for root-cause analysis, and
  returns a structured `DiagnosisResult`.

## Stack

- **Kubernetes client**: [fabric8 `kubernetes-client`](https://github.com/fabric8io/kubernetes-client)
- **Claude**: no official Anthropic Java SDK exists, so `RealClaudeClient` calls the Messages API
  directly over `java.net.http.HttpClient` (built into the JDK) with Jackson for JSON.
- **Concurrency**: Java 21 virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) for
  dispatching diagnoses without blocking the watch loop.
- **Tests**: JUnit 5, run via `mvn test`.

## Setup

```bash
brew install openjdk@21 maven   # if you don't already have a JDK/Maven
mvn -q dependency:resolve
```

## Run the tests

```bash
mvn test
```

Both `DetectionModuleTest` (11 cases) and `DiagnosisModuleTest` (10 cases) run against
fabricated pod objects and mock K8s/Claude clients - no real cluster or API key required.

## Run against a real cluster (Kind)

```bash
brew install kind
kind create cluster --name sentinel-test
mvn exec:java -Dexec.mainClass="com.sentinel.detection.DetectionModule"
```

With the watcher running, open a second terminal and try the scenarios below.

**Test 1 - Basic detection**

```bash
kubectl run crash-test --image=busybox -- sh -c "exit 1"
```

Expect, after ~30s of continuous CrashLoopBackOff with restart_count >= 1:

```
DETECTED: Pod failure - crash-test [default] reason=CrashLoopBackOff restarts=1 severity=HIGH
→ DIAGNOSIS: crash-test root_cause=... severity=... confidence=... fix=...
```

**Test 2 - Deduplication**: leave `crash-test` running and let it keep restarting. It should
only ever be diagnosed once per 5-minute window, even as `restart_count` keeps climbing.

**Test 3 - Transient failure**

```bash
kubectl run flaky-test --image=busybox -- sh -c "exit 1"
kubectl delete pod flaky-test   # simulate recovery before the 30s wait elapses
```

**Test 4 - Restart count threshold**: `crash-test` should be diagnosed as soon as
`restart_count` reaches 1 (not 3) and the 30s wait has elapsed.

**Test 5 - Namespace filtering**

```bash
kubectl run system-crash --image=busybox -n kube-system -- sh -c "exit 1"
```

The watcher should never log this pod, even after many restarts.

**Test 6 - Recovery after the wait starts**: delete or fix a failing pod within the 30s window
after it starts failing - it should be dropped from the waiting set instead of being diagnosed.

```bash
kind delete cluster --name sentinel-test   # cleanup
```

### Handoff to Step 2

`DetectionModule.extractPodInfo()` returns a `PodInfo` record:
`(podId, podName, namespace, status, reason, restartCount, severity, podObject)`. This is
exactly what `DiagnosisModule.diagnose(PodInfo)` expects.

---

## Step 2: Diagnosis

### Using it with mocks (now)

```java
DiagnosisModule module = new DiagnosisModule(); // MockK8sClient + MockClaudeClient
DiagnosisResult diagnosis = module.diagnose(podInfo);
System.out.println(diagnosis.rootCause() + " " + diagnosis.severity() + " " + diagnosis.confidence());
```

`MockK8sClient` (via its `Builder`) and `MockClaudeClient` both let you script specific
scenarios:

```java
MockK8sClient mockK8s = MockK8sClient.builder()
    .podStatus("Failed")
    .logs("memory exceeded, exit code 137")
    .events(List.of(new EventInfo("OOMKilling", "...", "1234")))
    .spec(new PodSpecInfo("myapp:latest", "512Mi", "250m", "256Mi", "100m", Map.of(), null, null))
    .build();

MockClaudeClient mockClaude = new MockClaudeClient(new ClaudeAnalysis(
    "Out of memory", "CRITICAL", "Increase memory limit", 0.95, List.of("exit code 137")));

DiagnosisModule module = new DiagnosisModule(mockK8s, mockClaude);
```

### Swapping in real clients (hackathon day)

```java
K8sDataFetcher realK8s = new RealK8sClient();               // or new RealK8sClient(kubeconfigPath)
ClaudeAnalyzer realClaude = new RealClaudeClient(apiKeyFromTrueForge);

DiagnosisModule module = new DiagnosisModule(realK8s, realClaude);
DiagnosisResult diagnosis = module.diagnose(podInfo); // identical call, real data now
```

### Wiring Step 1 -> Step 2

Already wired: `DetectionModule.triggerDiagnosis(PodInfo)` submits `DiagnosisModule.diagnose()`
to a virtual-thread executor, so a slow (real) Claude call never blocks the watch loop -
`shouldDiagnoseNow()` already guarantees it's called at most once per pod per 5-minute window.

By default it lazily creates a mock-backed `DiagnosisModule` the first time a diagnosis fires.
To use real clients, call `setDiagnosisModule()` before starting the watcher:

```java
DetectionModule.setDiagnosisModule(new DiagnosisModule(
    new RealK8sClient(), new RealClaudeClient(apiKeyFromTrueForge)));
DetectionModule.watchPodEvents();
```

### Diagnosis output shape

```
DiagnosisResult(
    podId, podName, namespace,
    rootCause, severity, recommendedFix, confidence, evidence,
    analysisTimestamp,
    rawLogs, rawEvents)
```

### Loopholes handled

1. Pod deleted before diagnosis -> `rootCause="Pod deleted"`, confidence 0.0, Claude never called.
2. No logs available -> `"[No logs - check events]"` sent to Claude instead of failing.
3. Logs over ~10KB -> truncated to the last 3000 chars, but any line matching an error keyword (error/exception/panic/fatal/oom/killed/crash) anywhere in the log is extracted and kept even if it falls outside the tail.
4. Multiple containers -> only the primary (first) container's logs are fetched.
5. Pod recovered during diagnosis -> pod status is re-checked right before fetching logs; if it's back to `Running`, diagnosis is skipped entirely (no Claude call).
6. Init container failures -> if an init container is stuck waiting or terminated abnormally, its logs are prepended to the main container's logs.
7. Claude API failure -> caught and returned as `rootCause="Diagnosis pending"`, confidence 0.0, instead of throwing.

## Notes on the fabric8 client

`RealK8sClient` uses `Config.autoConfigure()` (via the no-arg `KubernetesClientBuilder`) rather
than a hand-rolled "try in-cluster, catch, fall back to kubeconfig" check. fabric8 validates env
vars and file existence properly before treating a run as in-cluster; an earlier TypeScript port
of this same project hit a real bug where its Kubernetes client's naive in-cluster loader
"succeeded" with a garbage URL on a plain dev machine instead of throwing - fabric8's own
autoConfigure doesn't have that problem, so there's no need to reimplement the detection here.
