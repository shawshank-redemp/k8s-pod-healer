#!/usr/bin/env bash
# Sentinel end-to-end demo.
#
#   ./scripts/demo.sh                        CrashLoopBackOff scenario (missing env var)
#   ./scripts/demo.sh oom                    OOMKilled scenario
#   ./scripts/demo.sh --rehearse             scripted mock LLM (no gateway key / no cost): happy path,
#                                             real broken->fixed sandbox reproduction, real prod verify
#   ./scripts/demo.sh --rehearse --refuse    scripted mock LLM that proposes a WRONG fix: the sandbox
#                                             verification genuinely fails and the agent stops without
#                                             ever touching production - demonstrates the refusal gate
#
# Prerequisites: Docker, kind, kubectl, Java 21 + Maven, Node 22.14+, and TrueForge running:
#   npx @truefoundry/trueforge@latest            (UI at http://localhost:8790)
# For a real run, configure a model in TrueForge (Settings -> Models) or export
# TRUEFOUNDRY_GATEWAY_URL / TRUEFOUNDRY_API_KEY / SENTINEL_GATEWAY_MODEL_ID, and ideally DAYTONA_API_KEY.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SCENARIO="crash"
REHEARSE=0
REFUSE=0
for arg in "$@"; do
  case "$arg" in
    oom) SCENARIO="oom" ;;
    crash) SCENARIO="crash" ;;
    --rehearse) REHEARSE=1 ;;
    --refuse) REFUSE=1 ;;
    *) echo "usage: $0 [crash|oom] [--rehearse] [--refuse]" >&2; exit 2 ;;
  esac
done

if [ "$REHEARSE" = 1 ] && [ "$SCENARIO" = "oom" ]; then
  echo "ERROR: --rehearse only scripts the crash scenario (the mock model knows sentinel-demo-app)." >&2
  echo "       Use a real model for the oom scenario, or run: $0 crash --rehearse" >&2
  exit 2
fi
if [ "$REFUSE" = 1 ] && [ "$REHEARSE" = 0 ]; then
  echo "ERROR: --refuse only makes sense with --rehearse (it selects the mock LLM's refusal script)." >&2
  exit 2
fi

TRUEFORGE_URL="${TRUEFORGE_URL:-http://localhost:8790}"
MOCK_PID_FILE="$SENTINEL_RUN_DIR/mock-llm.pid"

cleanup() {
  if [ -f "$MOCK_PID_FILE" ]; then kill "$(cat "$MOCK_PID_FILE")" 2>/dev/null || true; rm -f "$MOCK_PID_FILE"; fi
}
trap cleanup EXIT

for tool in docker kind kubectl curl python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: '$tool' not found" >&2; exit 1; }
done
ensure_java

echo "=== SENTINEL DEMO ($SCENARIO scenario$([ "$REHEARSE" = 1 ] && echo ', REHEARSAL - scripted model')$([ "$REFUSE" = 1 ] && echo ', REFUSAL script')) ==="

echo "Step 1: Start Kind cluster (if not running)"
if kind get clusters 2>/dev/null | grep -qx "$KIND_CLUSTER_NAME"; then
  echo "Cluster already running"
else
  kind create cluster --name "$KIND_CLUSTER_NAME"
fi
require_kind_context

echo "Step 2: Set up sentinel-sandbox namespace + start the Kubernetes MCP servers"
"$SENTINEL_ROOT/scripts/setup-sandbox.sh"
"$SENTINEL_ROOT/scripts/start-k8s-mcp.sh"

echo "Step 3: Check TrueForge and register connectors + agent"
if ! curl -sf "$TRUEFORGE_URL/api/v1/capabilities" >/dev/null 2>&1; then
  echo "ERROR: TrueForge is not reachable at $TRUEFORGE_URL." >&2
  echo "       Start it in another terminal:  npx @truefoundry/trueforge@latest" >&2
  exit 1
fi
if [ "$REHEARSE" = 1 ]; then
  MOCK_SCENARIO="crash"; [ "$REFUSE" = 1 ] && MOCK_SCENARIO="refuse"
  echo "    starting the scripted mock LLM on :9911 (scenario: $MOCK_SCENARIO)"
  SENTINEL_KUBE_CONTEXT="$KUBE_CONTEXT" nohup python3 "$SENTINEL_ROOT/demo/mock-llm/mock_llm.py" \
    --scenario "$MOCK_SCENARIO" > "$SENTINEL_RUN_DIR/mock-llm.log" 2>&1 &
  echo $! > "$MOCK_PID_FILE"
  wait_for_http "http://127.0.0.1:9911/v1/models" 20 || { echo "ERROR: mock LLM did not start" >&2; exit 1; }
  export SENTINEL_REHEARSE=1
fi
"$SENTINEL_ROOT/scripts/setup-trueforge.sh"

echo "Step 4: Deploy a deliberately broken workload to trigger Sentinel"
if [ "$SCENARIO" = "oom" ]; then MANIFEST="$SENTINEL_ROOT/demo/broken-pod-oom.yaml"; else MANIFEST="$SENTINEL_ROOT/demo/broken-pod.yaml"; fi
kc delete -f "$MANIFEST" --ignore-not-found >/dev/null 2>&1 || true
"$SENTINEL_ROOT/scripts/teardown-sandbox.sh" >/dev/null 2>&1 || true
kc apply -f "$MANIFEST"

echo
echo "Step 5: Start the Sentinel watcher (auto-detects the broken pod, ~30-60s)"
echo
echo "Open TrueForge at $TRUEFORGE_URL and watch the run (Sessions). The agent will:"
echo "  1. Investigate with evidence: describe the pod + read its logs   (REAL TOOLS)"
echo "  2. Validate its fix structurally in the code sandbox             (SANDBOX)"
echo "  3. Reproduce the failure in sentinel-sandbox from the broken manifest, THEN apply and"
echo "     verify the fix there (status + logs)                         (K8S TEST, broken->healthy)"
echo "  4. Write a blast-radius report, then PAUSE for your approval     (APPROVAL GATE)"
echo "  5. Apply to production only after you answer 'y', then verify it recovered there too"
if [ "$REFUSE" = 1 ]; then
  echo
  echo "REFUSAL SCRIPT: the scripted fix is deliberately wrong. The sandbox verification will"
  echo "genuinely fail and the agent will stop WITHOUT ever proposing a production change - no"
  echo "approval prompt will appear. That refusal is the point of this run."
fi
echo
echo "(If you'd rather decide in the TrueForge UI, answer 'u' at the prompt.)"
echo

cd "$SENTINEL_ROOT"
# Pin the watcher to the Kind context so it can never watch whatever cluster kubectl happens to point at.
# "compile" first: exec:java alone runs whatever is already in target/, which may be stale.
SENTINEL_KUBE_CONTEXT="$KUBE_CONTEXT" mvn -q compile exec:java -Dexec.mainClass="com.sentinel.detection.DetectionModule"
