#!/usr/bin/env bash
# Cleans up all workloads in the sandbox namespace after testing. Only ever touches
# sentinel-sandbox, and only on a Kind cluster.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require_kind_context

kc delete all --all -n "$SANDBOX_NAMESPACE"
echo "sentinel-sandbox cleaned"
