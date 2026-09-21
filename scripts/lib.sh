#!/usr/bin/env bash
# Shared helpers for the Sentinel scripts. Source this file; don't execute it.

SENTINEL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SENTINEL_RUN_DIR="${SENTINEL_RUN_DIR:-$SENTINEL_ROOT/.sentinel}"
mkdir -p "$SENTINEL_RUN_DIR"

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-sentinel-demo}"
KUBE_CONTEXT="${KUBE_CONTEXT:-kind-$KIND_CLUSTER_NAME}"
SANDBOX_NAMESPACE="${SANDBOX_NAMESPACE:-sentinel-sandbox}"

# Sentinel applies changes to whatever cluster kubectl points at. Refuse to touch anything that
# isn't a local Kind cluster so a stray current-context can never aim these scripts at a real one.
require_kind_context() {
  case "$KUBE_CONTEXT" in
    kind-*) ;;
    *)
      if [ "${SENTINEL_ALLOW_NON_KIND:-}" != "1" ]; then
        echo "ERROR: kube context '$KUBE_CONTEXT' is not a Kind context. Refusing to continue." >&2
        echo "       (set SENTINEL_ALLOW_NON_KIND=1 only if you really mean it)" >&2
        exit 1
      fi
      ;;
  esac
  if ! kubectl --context "$KUBE_CONTEXT" cluster-info >/dev/null 2>&1; then
    echo "ERROR: cannot reach cluster for context '$KUBE_CONTEXT'. Is the Kind cluster running?" >&2
    echo "       Try: kind create cluster --name $KIND_CLUSTER_NAME" >&2
    exit 1
  fi
}

kc() { kubectl --context "$KUBE_CONTEXT" "$@"; }

# Poll until a URL answers with any HTTP status (i.e. the port is serving), or time out.
wait_for_http() {
  local url="$1" tries="${2:-40}"
  for _ in $(seq 1 "$tries"); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)" != "000" ]; then return 0; fi
    sleep 1
  done
  return 1
}

# Homebrew's openjdk@21 is keg-only, so `java`/`mvn` may not be on PATH. Use it if it's there.
ensure_java() {
  if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q 'version "2[1-9]'; then
    for jdk in /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21; do
      if [ -x "$jdk/bin/java" ]; then export JAVA_HOME="$jdk"; export PATH="$jdk/bin:$PATH"; break; fi
    done
  fi
  command -v mvn >/dev/null 2>&1 || { echo "ERROR: Maven (mvn) not found. brew install maven" >&2; exit 1; }
  java -version 2>&1 | grep -q 'version "2[1-9]' || { echo "ERROR: Java 21+ required. brew install openjdk@21" >&2; exit 1; }
}
