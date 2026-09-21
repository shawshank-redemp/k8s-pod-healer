#!/usr/bin/env bash
# Stops the MCP servers started by start-k8s-mcp.sh.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

for name in sandbox prod; do
  pidfile="$SENTINEL_RUN_DIR/mcp-$name.pid"
  if [ -f "$pidfile" ]; then
    pid="$(cat "$pidfile")"
    # npx spawns a child node process; kill the whole group/children, then the parent.
    pkill -P "$pid" 2>/dev/null || true
    kill "$pid" 2>/dev/null || true
    rm -f "$pidfile"
    echo "stopped k8s-$name MCP server"
  fi
done
