#!/usr/bin/env bash
# Registers everything Sentinel needs with a RUNNING TrueForge (default http://localhost:8790):
#   1. the two Kubernetes MCP connectors      (always; needs scripts/start-k8s-mcp.sh run first)
#   2. a model provider on the TrueFoundry AI gateway  (only if the env vars below are set)
#   3. the Daytona sandbox provider           (only if DAYTONA_API_KEY is set)
#   4. the "sentinel-agent" agent definition
# Idempotent: safe to re-run. No secret is stored in the repo or printed; they come from env vars.
#
# Optional env vars:
#   TRUEFORGE_URL              TrueForge base URL              (default http://localhost:8790)
#   TRUEFORGE_TOKEN            ID token, only if TrueForge login (OIDC) is enabled
#   TRUEFOUNDRY_GATEWAY_URL    base URL of your TrueFoundry AI gateway  \  configure the model
#   TRUEFOUNDRY_API_KEY        gateway API key                          /  provider when both set
#   SENTINEL_GATEWAY_MODEL_ID  gateway model id to expose (e.g. your Claude model)
#   OPENAI_API_KEY             direct OpenAI key (or put it in .sentinel/openai-api-key, git-ignored)
#   SENTINEL_OPENAI_MODEL      which OpenAI model id to use            (default gpt-4.1)
#   SENTINEL_MODEL             model FQN the agent should use; auto-detected if unset
#   DAYTONA_API_KEY            enables the isolated Daytona code sandbox
#   SENTINEL_REHEARSE=1        use the scripted mock LLM (demo/mock-llm) instead of a real model
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

TRUEFORGE_URL="${TRUEFORGE_URL:-http://localhost:8790}"
API="$TRUEFORGE_URL/api/v1"
TOKEN_FILE="$SENTINEL_RUN_DIR/mcp-auth-token"

# api METHOD PATH [JSON_BODY]  -> prints the response body; exits on an API error.
api() {
  local method="$1" path="$2" body="${3:-}" out
  local args=(-sS -X "$method" -H 'content-type: application/json')
  [ -n "${TRUEFORGE_TOKEN:-}" ] && args+=(-H "Authorization: Bearer $TRUEFORGE_TOKEN")
  [ -n "$body" ] && args+=(--data "$body")
  out="$(curl "${args[@]}" "$API$path")" || { echo "ERROR: cannot reach $API$path" >&2; exit 1; }
  if printf '%s' "$out" | python3 -c 'import sys,json; sys.exit(0 if "error" in json.load(sys.stdin) else 1)' 2>/dev/null; then
    echo "ERROR: $method $path failed: $out" >&2
    exit 1
  fi
  printf '%s' "$out"
}

py() { python3 -c "$@"; }

echo "==> Checking TrueForge at $TRUEFORGE_URL"
if ! curl -sf "$API/capabilities" >/dev/null 2>&1; then
  echo "ERROR: TrueForge is not reachable at $TRUEFORGE_URL. Start it with:  npx @truefoundry/trueforge@latest" >&2
  exit 1
fi

# --- 1. Kubernetes MCP connectors -------------------------------------------------------------
[ -s "$TOKEN_FILE" ] || { echo "ERROR: $TOKEN_FILE missing - run scripts/start-k8s-mcp.sh first" >&2; exit 1; }
MCP_AUTH_TOKEN="$(cat "$TOKEN_FILE")"
echo "==> Registering Kubernetes MCP connectors (k8s-sandbox, k8s-prod)"
export MCP_AUTH_TOKEN
py '
import json, os, sys
cfg = json.load(open(sys.argv[1]))
tok = os.environ["MCP_AUTH_TOKEN"]
for s in cfg["mcp_servers"]:
    s["auth"]["headers"] = {k: v.replace("__MCP_AUTH_TOKEN__", tok) for k, v in s["auth"]["headers"].items()}
    print(json.dumps({"manifest": s}))
' "$SENTINEL_ROOT/trueforge/mcp-k8s-config.json" | while IFS= read -r payload; do
  api PUT /settings/mcp-servers "$payload" >/dev/null
done
for name in k8s-sandbox k8s-prod; do
  count="$(api GET "/mcp-servers/$name/tools" | py 'import sys,json; print(len(json.load(sys.stdin)["data"]))')"
  echo "    $name reachable through TrueForge: $count tools"
done

# --- 2. model provider (TrueFoundry AI gateway) -----------------------------------------------
if [ -n "${TRUEFOUNDRY_GATEWAY_URL:-}" ] && [ -n "${TRUEFOUNDRY_API_KEY:-}" ] && [ -n "${SENTINEL_GATEWAY_MODEL_ID:-}" ]; then
  echo "==> Configuring TrueFoundry AI gateway model provider"
  export TRUEFOUNDRY_GATEWAY_URL TRUEFOUNDRY_API_KEY SENTINEL_GATEWAY_MODEL_ID
  payload="$(py '
import json, os, re
mid = os.environ["SENTINEL_GATEWAY_MODEL_ID"]
name = re.sub(r"[^a-z0-9-]+", "-", mid.lower()).strip("-")[:60] or "sentinel-model"
print(json.dumps({"manifest": {
    "type": "truefoundry",
    "base_url": os.environ["TRUEFOUNDRY_GATEWAY_URL"],
    "auth": {"api_key": os.environ["TRUEFOUNDRY_API_KEY"]},
    "models": [{"model_id": mid, "name": name,
                "properties": {"context_length": 200000, "max_output_tokens": 8192}}]}}))
')"
  api PUT /settings/model-providers "$payload" >/dev/null
elif [ -n "${OPENAI_API_KEY:-}" ] || [ -s "$SENTINEL_ROOT/.sentinel/openai-api-key" ]; then
  echo "==> Configuring OpenAI model provider"
  OPENAI_API_KEY="${OPENAI_API_KEY:-$(cat "$SENTINEL_ROOT/.sentinel/openai-api-key")}"
  export OPENAI_API_KEY
  OPENAI_MODEL_ID="${SENTINEL_OPENAI_MODEL:-gpt-4.1}"
  export OPENAI_MODEL_ID
  payload="$(py '
import json, os, re
mid = os.environ["OPENAI_MODEL_ID"]
name = re.sub(r"[^a-z0-9-]+", "-", mid.lower()).strip("-")[:60] or "openai-model"
print(json.dumps({"manifest": {
    "type": "openai",
    "auth": {"api_key": os.environ["OPENAI_API_KEY"]},
    "models": [{"model_id": mid, "name": name,
                "properties": {"context_length": 200000, "max_output_tokens": 8192}}]}}))
')"
  api PUT /settings/model-providers "$payload" >/dev/null
  echo "    registered model: $OPENAI_MODEL_ID"
else
  echo "==> Skipping model provider (set TRUEFOUNDRY_GATEWAY_URL, TRUEFOUNDRY_API_KEY, SENTINEL_GATEWAY_MODEL_ID,"
  echo "    or OPENAI_API_KEY / .sentinel/openai-api-key, to configure it here, or add one in TrueForge:"
  echo "    Settings -> Models)"
fi

# --- 3. sandbox provider (Daytona) ------------------------------------------------------------
if [ -n "${DAYTONA_API_KEY:-}" ]; then
  echo "==> Configuring Daytona sandbox provider"
  export DAYTONA_API_KEY
  payload="$(py '
import json, os
print(json.dumps({"manifest": {
    "type": "daytona", "auth": {"api_key": os.environ["DAYTONA_API_KEY"]},
    "exec_timeout_ms": 120000, "auto_stop_interval_in_minutes": 15,
    "auto_archive_interval_in_minutes": 60, "auto_delete_interval_in_minutes": 1440}}))
')"
  api PUT /settings/sandbox-providers "$payload" >/dev/null
else
  echo "==> DAYTONA_API_KEY not set: TrueForge will use its LOCAL sandbox fallback, which runs code on"
  echo "    THIS machine and is not isolated. Set DAYTONA_API_KEY (or Settings -> Sandbox providers) for the"
  echo "    isolated Daytona sandbox before a real demo."
fi

# --- 3b. rehearsal mode: scripted mock LLM instead of a real model ----------------------------
if [ "${SENTINEL_REHEARSE:-}" = "1" ]; then
  echo "==> REHEARSAL MODE: using the scripted mock LLM (demo/mock-llm) - no real model is involved"
  api PUT /settings/model-providers '{"manifest":{"type":"custom","name":"mockllm","base_url":"http://127.0.0.1:9911/v1","auth":{"api_key":"rehearsal"},"models":[{"model_id":"mock","name":"mock-model","properties":{"context_length":100000,"max_output_tokens":4096}}]}}' >/dev/null
  SENTINEL_MODEL="mockllm/mock-model"
fi

# --- 4. the agent -----------------------------------------------------------------------------
echo "==> Resolving model"
MODEL="${SENTINEL_MODEL:-}"
if [ -z "$MODEL" ]; then
  MODEL="$(api GET /models | SENTINEL_GATEWAY_MODEL_ID="${SENTINEL_GATEWAY_MODEL_ID:-}" py '
import sys, json, os
models = json.load(sys.stdin)["data"]
want = os.environ.get("SENTINEL_GATEWAY_MODEL_ID", "")
pick = next((m for m in models if want and m["model_id"] == want), None) \
    or next((m for m in models if "claude" in (m["name"] + m["model_id"]).lower()), None) \
    or (models[0] if len(models) == 1 else None)
print(pick["name"] if pick else "")
')"
fi
if [ -z "$MODEL" ]; then
  echo "ERROR: could not choose a model. Configure a model provider in TrueForge (Settings -> Models) or set" >&2
  echo "       SENTINEL_MODEL=<provider/model>. Available models:" >&2
  api GET /models | py 'import sys,json; [print("   -", m["name"]) for m in json.load(sys.stdin)["data"]]' >&2
  exit 1
fi
echo "    using model: $MODEL"

export SENTINEL_MODEL="$MODEL"
AGENT_JSON="$(py '
import json, os, sys
root = sys.argv[1]
a = json.load(open(root + "/trueforge/sentinel-agent.json"))
a["manifest"]["model"]["name"] = os.environ["SENTINEL_MODEL"]
a["manifest"]["instructions"] = open(root + "/trueforge/sentinel-instructions.md").read().strip()
print(json.dumps(a))
' "$SENTINEL_ROOT")"

AGENT_ID="$(api GET "/agents?limit=100" | py '
import sys, json
print(next((a["id"] for a in json.load(sys.stdin)["data"] if a["name"] == "sentinel-agent"), ""))
')"
if [ -n "$AGENT_ID" ]; then
  echo "==> Updating agent sentinel-agent ($AGENT_ID)"
  api PUT "/agents/$AGENT_ID" "$(printf '%s' "$AGENT_JSON" | py 'import sys,json; a=json.load(sys.stdin); print(json.dumps({"description": a["description"], "manifest": a["manifest"]}))')" >/dev/null
else
  echo "==> Creating agent sentinel-agent"
  api POST /agents "$AGENT_JSON" >/dev/null
fi

echo
echo "Done. Agent 'sentinel-agent' is ready. Open $TRUEFORGE_URL to watch runs (Sessions)."
