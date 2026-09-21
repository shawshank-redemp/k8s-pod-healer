#!/usr/bin/env bash
# Creates the sentinel-sandbox namespace (plus the ServiceAccount/Role the sandbox MCP connector
# authenticates as) in the local Kind cluster. Safe to re-run.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require_kind_context

kc create namespace "$SANDBOX_NAMESPACE" --dry-run=client -o yaml | kc apply -f -
kc label namespace "$SANDBOX_NAMESPACE" purpose=sentinel-testing --overwrite
kc apply -f "$SENTINEL_ROOT/trueforge/sandbox-rbac.yaml"

echo "sentinel-sandbox namespace ready (context: $KUBE_CONTEXT)"
