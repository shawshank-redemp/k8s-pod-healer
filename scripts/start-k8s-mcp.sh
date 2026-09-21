#!/usr/bin/env bash
# Starts the two Kubernetes MCP servers TrueForge's Sentinel agent talks to (Streamable HTTP):
#
#   k8s-sandbox  :3001  authenticates as the sentinel-sandbox-sa ServiceAccount (RBAC-limited to
#                       the sentinel-sandbox namespace)
#   k8s-prod     :3002  authenticates with your Kind admin context; every write tool is gated
#                       behind human approval by the agent config
#
# Both bind to localhost only and require an X-MCP-AUTH header (token generated once and kept in
# .sentinel/, which is git-ignored). Uses https://github.com/Flux159/mcp-server-kubernetes
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SANDBOX_PORT="${SANDBOX_MCP_PORT:-3001}"
PROD_PORT="${PROD_MCP_PORT:-3002}"

require_kind_context
"$SENTINEL_ROOT/scripts/setup-sandbox.sh" >/dev/null

# --- shared secret for the X-MCP-AUTH header -------------------------------------------------
TOKEN_FILE="$SENTINEL_RUN_DIR/mcp-auth-token"
if [ ! -s "$TOKEN_FILE" ]; then
  (umask 077; openssl rand -hex 24 > "$TOKEN_FILE")
fi
MCP_AUTH_TOKEN="$(cat "$TOKEN_FILE")"

# --- sandbox-only kubeconfig -----------------------------------------------------------------
SANDBOX_KUBECONFIG="$SENTINEL_RUN_DIR/sandbox-kubeconfig.yaml"
SA_TOKEN="$(kc create token sentinel-sandbox-sa -n "$SANDBOX_NAMESPACE" --duration=24h)"
ADMIN_USER="$(kc config view --minify -o jsonpath='{.users[0].name}')"

rm -f "$SANDBOX_KUBECONFIG"
(umask 077; kc config view --raw --minify --flatten > "$SANDBOX_KUBECONFIG")
kubectl --kubeconfig "$SANDBOX_KUBECONFIG" config set-credentials sentinel-sandbox-sa --token="$SA_TOKEN" >/dev/null
kubectl --kubeconfig "$SANDBOX_KUBECONFIG" config set-context --current \
  --user=sentinel-sandbox-sa --namespace="$SANDBOX_NAMESPACE" >/dev/null
# Drop the admin credentials from this file entirely so the sandbox connector never holds them.
kubectl --kubeconfig "$SANDBOX_KUBECONFIG" config unset "users.$ADMIN_USER" >/dev/null

# --- prove the RBAC boundary before starting anything ----------------------------------------
can_i() { kubectl --kubeconfig "$SANDBOX_KUBECONFIG" auth can-i "$@" 2>/dev/null || true; }
IN_SANDBOX="$(can_i create deployments -n "$SANDBOX_NAMESPACE")"
IN_DEFAULT="$(can_i create deployments -n default)"
if [ "$IN_SANDBOX" != "yes" ] || [ "$IN_DEFAULT" != "no" ]; then
  echo "ERROR: sandbox RBAC check failed (create deployments in sandbox=$IN_SANDBOX, in default=$IN_DEFAULT)." >&2
  exit 1
fi
echo "RBAC check ok: sandbox identity can write in $SANDBOX_NAMESPACE, cannot write in default"

# --- (re)start the servers -------------------------------------------------------------------
"$SENTINEL_ROOT/scripts/stop-k8s-mcp.sh" >/dev/null 2>&1 || true

start_server() {
  local name="$1" port="$2"; shift 2
  env ENABLE_UNSAFE_STREAMABLE_HTTP_TRANSPORT=1 PORT="$port" HOST=127.0.0.1 \
      MCP_AUTH_TOKEN="$MCP_AUTH_TOKEN" "$@" \
      nohup npx --yes mcp-server-kubernetes > "$SENTINEL_RUN_DIR/mcp-$name.log" 2>&1 &
  echo $! > "$SENTINEL_RUN_DIR/mcp-$name.pid"
  if wait_for_http "http://127.0.0.1:$port/health" 90; then
    echo "k8s-$name MCP server up: http://127.0.0.1:$port/mcp"
  else
    echo "ERROR: k8s-$name MCP server did not start; see $SENTINEL_RUN_DIR/mcp-$name.log" >&2
    exit 1
  fi
}

start_server sandbox "$SANDBOX_PORT" KUBECONFIG_PATH="$SANDBOX_KUBECONFIG" K8S_NAMESPACE="$SANDBOX_NAMESPACE"
start_server prod "$PROD_PORT" K8S_CONTEXT="$KUBE_CONTEXT"

echo "Register them in TrueForge with: scripts/setup-trueforge.sh"
