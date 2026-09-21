# Sentinel - Kubernetes pod failure detection & autonomous remediation

Sentinel watches a cluster for failing pods and hands each failure to a **TrueForge agent**
(Claude via the TrueFoundry AI gateway) that investigates with real Kubernetes tools, proves its
fix in a sandbox first, and **pauses for a human before changing production**.

```
 Kind cluster ──events──▶ DetectionModule (Java) ──POST session+turn──▶ TrueForge (:8790)
                                                                         │  agent loop = Claude
                                    ┌────────────────────────────────────┼────────────────────────┐
                                    ▼                                    ▼                        ▼
                         k8s-prod MCP (:3002)              k8s-sandbox MCP (:3001)      code-execution sandbox
                         read tools: free                  RBAC-limited to the          validates the fix,
                         write tools: PAUSE for approval   sentinel-sandbox namespace   runs `sleep`, no cluster access
                                    ▲
   human answers y/N in the terminal (or in the TrueForge UI) ──resume turn──┘
```

- **Detection** (`com.sentinel.detection.DetectionModule`): event-driven watcher that filters
  transient/self-recovering failures, dedups, and classifies severity.
- **TrueForge handoff** (`com.sentinel.trueforge.TrueForgeAgentTrigger`): starts the agent run,
  follows it, and relays the human's approve/deny decision back.
- **Local diagnosis** (`com.sentinel.diagnosis.DiagnosisModule`): the original in-process
  diagnosis (logs/events/spec -> Claude). Now optional - used only if you call
  `DetectionModule.setDiagnosisModule(...)`, e.g. offline. See the sections below.

## TrueForge integration

### The three requirements, and how each is met

| Requirement | How |
|---|---|
| **Real tool reached** | The agent calls the Kubernetes MCP server ([Flux159/mcp-server-kubernetes](https://github.com/Flux159/mcp-server-kubernetes), Streamable HTTP) through TrueForge: `kubectl_get`, `kubectl_logs`, `kubectl_describe`, ... You can see each call in the run's tool log. |
| **Code run in a sandbox** | The agent uses TrueForge's `exec` sandbox tool to write and validate its fix (parse the patch, `sleep 30`), then trials it in the `sentinel-sandbox` Kubernetes namespace through a connector that is RBAC-limited to that namespace. |
| **Pause before irreversible** | Every write tool on the `k8s-prod` connector has `require_approval_for_tools`. The turn ends with `tool.approval_required`; Sentinel shows the exact change and waits for a human. |

### Design decisions worth knowing

- **Two Kubernetes connectors instead of one.** TrueForge's approval gate is *per tool*, not
  per namespace - one connector would either gate the harmless sandbox trials too or gate nothing.
  So `k8s-sandbox` authenticates as a ServiceAccount whose RBAC only covers `sentinel-sandbox`
  ([`trueforge/sandbox-rbac.yaml`](trueforge/sandbox-rbac.yaml)); the cluster itself refuses to let it
  touch anything else. `k8s-prod` has your admin context, with every write tool gated.
  `scripts/start-k8s-mcp.sh` proves this boundary (`kubectl auth can-i`) before starting anything.
- **Explicit tool allow-lists.** `enable_tools` is enforced by TrueForge, so dangerous tools that
  the MCP server also exposes (`exec_in_pod`, `kubectl_generic`, `kubectl_delete` on prod, ...)
  cannot be called at all. Approval names are literal because `kubectl_create` carries no
  "destructive" annotation, so the `@write`/`@destructive` selectors would miss it.
- **`call_tool` routing.** With preloaded tools TrueForge flattens both servers into one list and
  renames collisions (`kubectl_apply` vs `kubectl_apply1`) with no hint which is prod. The agent
  therefore loads tools on demand and calls `call_tool(mcp_server="k8s-prod"|"k8s-sandbox", ...)`.
- **The sandbox namespace is never watched**, so a failing trial pod can't start another agent run.
  Kind's own `local-path-storage` namespace is skipped too: it's infrastructure, not a workload.
- **One run per workload, not per pod.** Three crashing replicas of a Deployment are one problem
  with one fix, so runs are keyed on the owning workload (`default/Deployment/web`, derived from the
  pod's owner references). The other replicas are suppressed and you get one approval prompt.
- **A denial sticks.** If a human denies a fix, Sentinel stays quiet about that workload for
  `SENTINEL_DENY_COOLDOWN_MINUTES` (default 30) instead of re-prompting every 5 minutes while the
  pod keeps failing. An approval or a decision made in the UI does not start a cooldown.
- **Severity doesn't depend on timing.** A crash-looping container shows `CrashLoopBackOff` while
  backing off but `Error`/`OOMKilled` the instant it dies. All three are ranked by restart count
  (1-4 HIGH, 5+ CRITICAL) so the same pod isn't scored differently depending on when it was seen.
- **No auto-approve exists.** The only decision paths are a human at the terminal or in the UI.
- Secrets: the MCP servers are protected by an `X-MCP-AUTH` token generated into the git-ignored
  `.sentinel/` folder; model/sandbox keys come from environment variables. Nothing is committed.

### Where things live

| Path | What |
|---|---|
| `trueforge/sentinel-agent.json` | Agent definition (tools, approval gates, sandbox, limits) |
| `trueforge/sentinel-instructions.md` | The agent's system prompt |
| `trueforge/mcp-k8s-config.json` | The two Kubernetes MCP connectors |
| `trueforge/sandbox-rbac.yaml` | ServiceAccount/Role that confine `k8s-sandbox` to `sentinel-sandbox` |
| `demo/broken-pod.yaml`, `demo/broken-pod-oom.yaml` | Deliberately broken workloads |
| `demo/mock-llm/mock_llm.py` | Scripted model for `--rehearse` |
| `scripts/` | Setup, MCP start/stop, TrueForge registration, demo |

TrueForge defines agents as JSON manifests saved through its API (there is no YAML agent file), so
`scripts/setup-trueforge.sh` merges the prompt into the manifest and registers it.

### Prerequisites

Docker, [kind](https://kind.sigs.k8s.io), `kubectl`, Java 21 + Maven, Node 22.14+.

### Run the demo

```bash
# 1. TrueForge, in its own terminal (UI: http://localhost:8790)
npx @truefoundry/trueforge@latest

# 2a. Dry run with a scripted model - no keys, no cost. Proves the whole pipeline.
./scripts/demo.sh --rehearse

# 2b. Real run. Point TrueForge at your TrueFoundry AI gateway (or add the model in the UI:
#     Settings -> Models), and ideally enable the isolated Daytona sandbox.
export TRUEFOUNDRY_GATEWAY_URL=...        # your gateway base URL
export TRUEFOUNDRY_API_KEY=...            # gateway key
export SENTINEL_GATEWAY_MODEL_ID=...      # the Claude model id on your gateway
export DAYTONA_API_KEY=...                # optional but recommended, see caveats
./scripts/demo.sh            # CrashLoopBackOff: missing env var
./scripts/demo.sh oom        # OOMKilled: memory limit too low
```

`demo.sh` creates the Kind cluster if needed, sets up `sentinel-sandbox`, starts both MCP servers,
registers connectors + the agent in TrueForge, deploys a broken workload, and starts the watcher.
About 30-60 seconds later the terminal shows the approval prompt:

```
======================= APPROVAL REQUIRED =======================
  connector : k8s-prod
  tool      : kubectl_patch
  input     : { "resourceType": "deployment", "name": "sentinel-demo-app", ... }
  run       : http://localhost:8790/sessions/<id>
Approve this production change? [y = approve / N = deny / u = decide in UI]:
```

`y` applies it (the agent then waits and verifies recovery), `N`/Enter denies (the agent is told
why and stops), `u` leaves it to the TrueForge UI (Sessions -> the run -> resume). Without a terminal
Sentinel defers to the UI automatically.

Individual pieces, if you want them: `scripts/setup-sandbox.sh`, `scripts/teardown-sandbox.sh`,
`scripts/start-k8s-mcp.sh` / `stop-k8s-mcp.sh`, `scripts/setup-trueforge.sh` (idempotent).

### Configuration (environment)

| Variable | Default | Purpose |
|---|---|---|
| `TRUEFORGE_URL` | `http://localhost:8790` | TrueForge base URL |
| `TRUEFORGE_TOKEN` | - | ID token, only if TrueForge login (OIDC) is enabled |
| `SENTINEL_AGENT_NAME` | `sentinel-agent` | Agent to run |
| `SENTINEL_APPROVAL_MODE` | `auto` | `auto` = terminal prompt if there is a TTY else UI; `ui` = always UI |
| `SENTINEL_KUBE_CONTEXT` | current context | Pins the watcher to one kube context (`demo.sh` sets the Kind one) |
| `SENTINEL_POLL_MS` / `SENTINEL_MAX_RUN_MINUTES` | `2000` / `60` | Run-follow polling and give-up time |
| `SENTINEL_DENY_COOLDOWN_MINUTES` | `30` | How long a denied fix silences that workload |

### Honest caveats

- **The sandbox tool cannot reach the cluster.** The isolated (Daytona) sandbox has no `kubectl`
  or cluster access, so it *validates the fix artifacts*; the *cluster-level* trial is the
  `sentinel-sandbox` namespace. Without `DAYTONA_API_KEY`, TrueForge falls back to a **local**
  sandbox that runs on your machine and is **not isolated** - set the key before a real demo.
- **What was verified live vs. not.** Verified end to end on Kind with TrueForge running: detection,
  MCP tool discovery and calls, sandbox `exec`, sandbox-namespace trial, the approval pause, and all
  three decision paths (approve -> production patched and pod `Running`; deny -> production untouched;
  decide-in-UI -> run resumed), 3 crashing replicas -> one run and one prompt (one approval fixed
  all three), and the watcher reconnecting after the API server was restarted mid-run. The *model* in those runs was the scripted mock LLM. A run with a
  real Claude model through the TrueFoundry gateway, and with Daytona, needs your credentials and
  has **not** been run.
- `--rehearse` scripts only the crash scenario. The OOM scenario needs a real model.
- TrueForge's local (`npx`) mode has no login and is for localhost only.

## Stack

- **Agent harness**: [TrueForge](https://github.com/truefoundry/trueforge) (HTTP API: agent -> session -> turn)

- **Kubernetes client**: [fabric8 `kubernetes-client`](https://github.com/fabric8io/kubernetes-client)
- **Claude**: no official Anthropic Java SDK exists, so `RealClaudeClient` calls the Messages API
  directly over `java.net.http.HttpClient` (built into the JDK) with Jackson for JSON.
- **Concurrency**: Java 21 virtual threads for handoffs and for following each agent run, so the
  watch loop never blocks. A 5s sweep re-checks pods serving their failure wait, so a pod stuck
  in Kubernetes' long crash backoff (which emits no events) is still handled on time.
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

`DetectionModuleTest`, `PodInfoTest`, `DiagnosisModuleTest` and `TrueForgeAgentTriggerTest` (53 tests) run against
fabricated pods, mock clients and a fake TrueForge HTTP server - no cluster or API key needed.

## Run the detector on its own against a real cluster (Kind)

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
[Sentinel] TrueForge agent triggered for pod: crash-test
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

### Handoff

`DetectionModule.extractPodInfo()` returns a `PodInfo` record:
`(podId, podName, namespace, status, reason, restartCount, severity, podObject)`. This is
what `TrueForgeAgentTrigger.trigger(...)` (and the local `DiagnosisModule.diagnose(PodInfo)`) consume.

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

### Using the local DiagnosisModule instead of TrueForge

By default a detected failure goes to the TrueForge agent. To run this in-process diagnosis
instead (offline, or with your own Claude key), inject it before starting the watcher:

```java
DetectionModule.setDiagnosisModule(new DiagnosisModule(
    new RealK8sClient(), new RealClaudeClient(apiKeyFromTrueForge)));
DetectionModule.watchPodEvents();
```

Pass `null` to go back to TrueForge. If TrueForge is unreachable, the failure is logged, the
watcher keeps running, and the pod's dedup entry is cleared so the next event retries.

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
