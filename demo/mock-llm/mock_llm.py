#!/usr/bin/env python3
"""Scripted OpenAI-compatible mock LLM for rehearsing Sentinel WITHOUT a real model.

TrueForge talks to models over the OpenAI /v1/chat/completions streaming API. This server plays
back a fixed "SRE runbook" as tool calls so you can exercise the complete pipeline - real TrueForge,
real Kubernetes MCP tools, real Kind cluster, real approval pause - with no gateway key and no cost.
It is a stand-in for the model only: every tool call it emits is executed for real by TrueForge
against the real cluster, so what the terminal/UI shows (pod status, logs, restart counts) is real
data, not fabricated text - only the CHOICE of tool call and the narration text are scripted.

Two scenarios (--scenario):
  crash (default) - the full happy path. Reads the failing pod (list -> describe -> logs), reproduces
      the exact failure in sentinel-sandbox from the unmodified broken manifest, applies a patch on
      top of that reproduction, verifies the sandbox pod actually recovers (status + logs), writes a
      blast-radius report, applies the same patch to production (PAUSES for human approval), then
      verifies production recovered the same way.
  refuse - investigates identically, but the scripted patch is deliberately wrong (a typo'd env var
      name), so the sandbox reproduction genuinely stays broken after the "fix". The script never
      calls a production write tool at all - it stops and explains why, which is the demo's proof
      that the agent knows when NOT to act. The verification failure is real (the pod really doesn't
      recover), not asserted.

Because pod names carry a random suffix, this script shells out to `kubectl` itself (the same way a
human operator would) to resolve the current pod name for `kubectl_describe`/`kubectl_logs` calls,
so those tool calls carry real arguments even though the sequence of steps is fixed.

Usage:
  python3 demo/mock-llm/mock_llm.py [--port 9911] [--scenario crash|refuse]
Then register it as a custom model provider in TrueForge (Settings -> Models):
  type custom, name mockllm, base URL http://127.0.0.1:9911/v1, any API key, model id "mock".
Stdlib only.
"""
import argparse
import json
import os
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APP = "sentinel-demo-app"
CONTAINER = "demo-app"
LABEL_SELECTOR = "app=sentinel-demo"
PROD_NS = "default"
SANDBOX_NS = "sentinel-sandbox"
KUBE_CONTEXT = os.environ.get("SENTINEL_KUBE_CONTEXT", "")

# Same broken container spec as demo/broken-pod.yaml, retargeted at the sandbox namespace, so the
# sandbox reproduction is a genuine copy of the production failure rather than a hand-authored one.
BROKEN_MANIFEST_SANDBOX = f"""apiVersion: apps/v1
kind: Deployment
metadata:
  name: {APP}
  namespace: {SANDBOX_NS}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: sentinel-demo
  template:
    metadata:
      labels:
        app: sentinel-demo
    spec:
      containers:
      - name: {CONTAINER}
        image: busybox:latest
        command: ["/bin/sh", "-c"]
        args:
        - |
          if [ -z "$REQUIRED_CONFIG" ]; then
            echo "ERROR: REQUIRED_CONFIG environment variable is not set"
            exit 1
          fi
          echo "App running with config: $REQUIRED_CONFIG"
          sleep 3600
"""

FIX_PATCH = {
    "spec": {"template": {"spec": {"containers": [
        {"name": CONTAINER, "env": [{"name": "REQUIRED_CONFIG", "value": "production"}]}]}}}
}

# Deliberately wrong (typo'd variable name) so the "refuse" scenario's sandbox check genuinely
# fails - the container still finds REQUIRED_CONFIG unset and exits, for real.
BAD_FIX_PATCH = {
    "spec": {"template": {"spec": {"containers": [
        {"name": CONTAINER, "env": [{"name": "REQUIRD_CONFIG", "value": "production"}]}]}}}
}


def kubectl(*args):
    cmd = ["kubectl"]
    if KUBE_CONTEXT:
        cmd += ["--context", KUBE_CONTEXT]
    cmd += list(args)
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        return out.stdout.strip()
    except Exception:
        return ""


def pod_name(namespace, fallback=f"{APP}-unknown"):
    return kubectl("get", "pods", "-n", namespace, "-l", LABEL_SELECTOR,
                   "-o", "jsonpath={.items[0].metadata.name}") or fallback


def call_tool(mcp_server, tool_name, input_):
    return {"mcp_server": mcp_server, "tool_name": tool_name, "input": input_}


BLAST_RADIUS_REPORT = f"""Blast-radius report before touching production:
- Target: Deployment/{APP} in namespace {PROD_NS}
- Change: set container env REQUIRED_CONFIG=production on container "{CONTAINER}"
- Expected effect: a rolling restart of this Deployment's pod(s), no other resources touched
- Evidence: sentinel-sandbox reproduction failed with the identical error, and after this exact \
patch the sandbox pod reached Running/Ready with clean startup logs
- Risk: brief unavailability during the rollout restart; no data or other workloads affected

Applying the same, already-verified patch to production now. This write needs human approval."""


def crash_steps():
    return [
        ("The alert names a crashing pod in the default namespace. Listing pods there to find it.",
         "call_tool", lambda: call_tool("k8s-prod", "kubectl_get",
            {"resourceType": "pods", "namespace": PROD_NS, "output": "json"})),
        ("Describing the specific pod for full detail: container command/args, env, restart count, "
         "and the last termination reason.",
         "call_tool", lambda: call_tool("k8s-prod", "kubectl_describe",
            {"resourceType": "pods", "name": pod_name(PROD_NS), "namespace": PROD_NS})),
        ("Reading the container's logs to confirm the exact failure message rather than guessing "
         "from the exit reason alone.",
         "call_tool", lambda: call_tool("k8s-prod", "kubectl_logs",
            {"resourceType": "pod", "name": pod_name(PROD_NS), "namespace": PROD_NS,
             "container": CONTAINER, "tail": 20})),
        ("Evidence: the container's command checks for $REQUIRED_CONFIG and exits with \"ERROR: "
         "REQUIRED_CONFIG environment variable is not set\" when it's missing; describe confirms "
         "the container has no env vars configured at all. Root cause: the Deployment's pod spec "
         "is missing the REQUIRED_CONFIG environment variable. I'll validate a patch that adds it "
         "in my code sandbox before it goes near the cluster.",
         "exec", lambda: {"intent": "Validate the remediation before applying it anywhere",
             "command": "python3 - <<'PY'\nimport json\npatch = " + json.dumps(FIX_PATCH) +
                        "\nenv = patch['spec']['template']['spec']['containers'][0]['env'][0]\n"
                        "assert env['name'] == 'REQUIRED_CONFIG' and env['value']\n"
                        "print('patch validated (structurally):', env)\nPY"}),
        ("Validated structurally - that only proves the patch is well-formed, not that it fixes "
         "the problem. Reproducing the exact failure in sentinel-sandbox first, from the "
         "unmodified broken manifest, via the sandbox connector.",
         "call_tool", lambda: call_tool("k8s-sandbox", "kubectl_apply",
            {"manifest": BROKEN_MANIFEST_SANDBOX, "namespace": SANDBOX_NS})),
        ("Waiting for the sandbox pod to schedule and fail before I trust the reproduction.",
         "exec", lambda: {"intent": "Wait for the sandbox reproduction to fail",
             "command": "sleep 6"}),
        ("Checking that the sandbox reproduction actually failed the same way as production.",
         "call_tool", lambda: call_tool("k8s-sandbox", "kubectl_get",
            {"resourceType": "pods", "namespace": SANDBOX_NS, "output": "wide"})),
        ("Reproduction confirmed broken. Applying the validated patch on top of it.",
         "call_tool", lambda: call_tool("k8s-sandbox", "kubectl_patch",
            {"resourceType": "deployment", "name": APP, "namespace": SANDBOX_NS,
             "patchType": "strategic", "patchData": FIX_PATCH})),
        ("Waiting for the sandbox rollout before checking health.",
         "exec", lambda: {"intent": "Wait for the sandbox fix to roll out", "command": "sleep 6"}),
        ("Checking sandbox pod status after the fix.",
         "call_tool", lambda: call_tool("k8s-sandbox", "kubectl_get",
            {"resourceType": "pods", "namespace": SANDBOX_NS, "output": "wide"})),
        ("Status alone isn't proof - reading the sandbox pod's logs to confirm a clean startup.",
         "call_tool", lambda: call_tool("k8s-sandbox", "kubectl_logs",
            {"resourceType": "pod", "name": pod_name(SANDBOX_NS), "namespace": SANDBOX_NS,
             "container": CONTAINER, "tail": 20})),
        (BLAST_RADIUS_REPORT,
         "call_tool", lambda: call_tool("k8s-prod", "kubectl_patch",
            {"resourceType": "deployment", "name": APP, "namespace": PROD_NS,
             "patchType": "strategic", "patchData": FIX_PATCH})),
        ("Approved and applied. Waiting before checking that production recovered.",
         "exec", lambda: {"intent": "Wait before verifying production recovery",
             "command": "sleep 6"}),
        ("Checking production pod status.",
         "call_tool", lambda: call_tool("k8s-prod", "kubectl_get",
            {"resourceType": "pods", "namespace": PROD_NS, "output": "wide"})),
        ("Reading production logs to confirm the application actually started cleanly, not just "
         "that the pod phase changed.",
         "call_tool", lambda: call_tool("k8s-prod", "kubectl_logs",
            {"resourceType": "pod", "name": pod_name(PROD_NS), "namespace": PROD_NS,
             "container": CONTAINER, "tail": 20})),
    ]


def refuse_steps():
    return [
        ("The alert names a crashing pod in the default namespace. Listing pods there to find it.",
         "call_tool", lambda: call_tool("k8s-prod", "kubectl_get",
            {"resourceType": "pods", "namespace": PROD_NS, "output": "json"})),
        ("Describing the specific pod for full detail: container command/args, env, restart count, "
         "and the last termination reason.",
         "call_tool", lambda: call_tool("k8s-prod", "kubectl_describe",
            {"resourceType": "pods", "name": pod_name(PROD_NS), "namespace": PROD_NS})),
        ("Reading the container's logs to confirm the exact failure message.",
         "call_tool", lambda: call_tool("k8s-prod", "kubectl_logs",
            {"resourceType": "pod", "name": pod_name(PROD_NS), "namespace": PROD_NS,
             "container": CONTAINER, "tail": 20})),
        ("Evidence: the container exits because it doesn't find a configuration value in its "
         "environment. Proposing a fix that sets it, and validating the patch structurally before "
         "it goes near the cluster.",
         "exec", lambda: {"intent": "Validate the remediation before applying it anywhere",
             "command": "python3 - <<'PY'\nimport json\npatch = " + json.dumps(BAD_FIX_PATCH) +
                        "\nenv = patch['spec']['template']['spec']['containers'][0]['env'][0]\n"
                        "assert env['name'] and env['value']\n"
                        "print('patch validated (structurally):', env)\nPY"}),
        ("Structural validation only proves the patch is well-formed, not that it's correct. "
         "Reproducing the exact failure in sentinel-sandbox first, from the unmodified broken "
         "manifest.",
         "call_tool", lambda: call_tool("k8s-sandbox", "kubectl_apply",
            {"manifest": BROKEN_MANIFEST_SANDBOX, "namespace": SANDBOX_NS})),
        ("Waiting for the sandbox reproduction to fail.",
         "exec", lambda: {"intent": "Wait for the sandbox reproduction to fail",
             "command": "sleep 6"}),
        ("Checking that the sandbox reproduction failed the same way as production.",
         "call_tool", lambda: call_tool("k8s-sandbox", "kubectl_get",
            {"resourceType": "pods", "namespace": SANDBOX_NS, "output": "wide"})),
        ("Reproduction confirmed broken. Applying my proposed patch on top of it.",
         "call_tool", lambda: call_tool("k8s-sandbox", "kubectl_patch",
            {"resourceType": "deployment", "name": APP, "namespace": SANDBOX_NS,
             "patchType": "strategic", "patchData": BAD_FIX_PATCH})),
        ("Waiting for the sandbox rollout before checking health.",
         "exec", lambda: {"intent": "Wait for the sandbox fix to roll out", "command": "sleep 6"}),
        ("Checking whether the sandbox pod recovered.",
         "call_tool", lambda: call_tool("k8s-sandbox", "kubectl_get",
            {"resourceType": "pods", "namespace": SANDBOX_NS, "output": "wide"})),
        ("Reading logs to confirm health, not just pod phase.",
         "call_tool", lambda: call_tool("k8s-sandbox", "kubectl_logs",
            {"resourceType": "pod", "name": pod_name(SANDBOX_NS), "namespace": SANDBOX_NS,
             "container": CONTAINER, "tail": 20})),
    ]


FINAL_TEXT = {
    "crash": (
        "Root cause: the Deployment's container was missing the REQUIRED_CONFIG environment "
        "variable - confirmed via kubectl_describe (no env configured) and the container logs "
        "(\"ERROR: REQUIRED_CONFIG environment variable is not set\"). I reproduced the exact "
        "failure in sentinel-sandbox from the unmodified manifest, applied REQUIRED_CONFIG="
        "production on top of it, and verified the sandbox pod reached Running/Ready with a clean "
        "startup log before requesting approval. After approval I applied the identical patch to "
        "the production Deployment and verified it recovered: Running, Ready, restart count no "
        "longer climbing, logs show \"App running with config: production\"."
    ),
    "refuse": (
        "Sandbox verification failed: after applying my patch, the sandbox pod is still exiting "
        "immediately and its logs still show \"ERROR: REQUIRED_CONFIG environment variable is not "
        "set\" - the value I set didn't take effect. I have not proven this fix works, so I am NOT "
        "applying it to production. Stopping here and requesting human input; the sandbox "
        "reproduction and logs above are the evidence that the fix needs another look."
    ),
}


def sse(handler, obj):
    handler.wfile.write(b"data: " + json.dumps(obj).encode() + b"\n\n")
    handler.wfile.flush()


def chunk(delta, finish=None):
    return {"id": "mock-1", "object": "chat.completion.chunk", "created": int(time.time()),
            "model": "mock", "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}


class Handler(BaseHTTPRequestHandler):
    scenario = "crash"

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"object":"list","data":[{"id":"mock","object":"model"}]}')

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("content-length", 0))) or b"{}")
        messages = body.get("messages", [])
        # Steps already issued = assistant messages that made tool calls. This also survives the
        # approval pause: the paused call is in the history when the resumed turn calls us again.
        issued = sum(1 for m in messages if m.get("role") == "assistant" and m.get("tool_calls"))
        steps = crash_steps() if self.scenario == "crash" else refuse_steps()

        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.send_header("cache-control", "no-cache")
        self.end_headers()

        if issued < len(steps):
            text, name, args_fn = steps[issued]
            sse(self, chunk({"role": "assistant", "content": text}))
            call = {"index": 0, "id": f"call_{issued + 1}", "type": "function",
                    "function": {"name": name, "arguments": ""}}
            sse(self, chunk({"tool_calls": [call]}))
            sse(self, chunk({"tool_calls": [{"index": 0, "function": {"arguments": json.dumps(args_fn())}}]}))
            sse(self, chunk({}, "tool_calls"))
        else:
            sse(self, chunk({"role": "assistant", "content": FINAL_TEXT[self.scenario]}))
            sse(self, chunk({}, "stop"))
        sse(self, {"id": "mock-1", "object": "chat.completion.chunk", "model": "mock", "choices": [],
                   "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}})
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9911)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--scenario", choices=["crash", "refuse"], default="crash")
    a = ap.parse_args()
    Handler.scenario = a.scenario
    print(f"mock LLM listening on http://{a.host}:{a.port}/v1 (scenario: {a.scenario})", file=sys.stderr)
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()
