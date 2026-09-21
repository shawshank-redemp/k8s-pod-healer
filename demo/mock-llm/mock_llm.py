#!/usr/bin/env python3
"""Scripted OpenAI-compatible mock LLM for rehearsing Sentinel WITHOUT a real model.

TrueForge talks to models over the OpenAI /v1/chat/completions streaming API. This server plays
back a fixed "SRE runbook" as tool calls so you can exercise the complete pipeline - real TrueForge,
real Kubernetes MCP tools, real Kind cluster, real approval pause - with no gateway key and no cost.
It is a stand-in for the model only: every tool call it emits is executed for real by TrueForge.

Scripted run (for the sentinel-demo-app CrashLoopBackOff demo):
  1. k8s-prod    kubectl_get      read the failing pod            (real tool, no approval)
  2. exec        python3 ...      validate the fix in the sandbox (code-execution sandbox)
  3. k8s-sandbox kubectl_apply    trial run in sentinel-sandbox   (real tool, no approval)
  4. k8s-prod    kubectl_patch    production fix                  (PAUSES for human approval)
  5. exec        sleep            wait, then k8s-prod kubectl_get to verify recovery
  6. final text summary

Usage:
  python3 demo/mock-llm/mock_llm.py [--port 9911]
Then register it as a custom model provider in TrueForge (Settings -> Models):
  type custom, name mockllm, base URL http://127.0.0.1:9911/v1, any API key, model id "mock".
Stdlib only.
"""
import argparse
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APP = "sentinel-demo-app"
CONTAINER = "demo-app"
PROD_NS = "default"
SANDBOX_NS = "sentinel-sandbox"

FIXED_SANDBOX_MANIFEST = f"""apiVersion: apps/v1
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
          if [ -z "$REQUIRED_CONFIG" ]; then echo "ERROR: REQUIRED_CONFIG is not set"; exit 1; fi
          echo "App running with config: $REQUIRED_CONFIG"; sleep 3600
        env:
        - name: REQUIRED_CONFIG
          value: production
"""

PROD_PATCH = {
    "spec": {"template": {"spec": {"containers": [
        {"name": CONTAINER, "env": [{"name": "REQUIRED_CONFIG", "value": "production"}]}]}}}
}

# Each step: (assistant text, tool name, tool arguments)
STEPS = [
    ("The alert names a pod in default. I'll read it from production first (read-only).",
     "call_tool",
     {"mcp_server": "k8s-prod", "tool_name": "kubectl_get",
      "input": {"resourceType": "pods", "namespace": PROD_NS, "output": "json"}}),
    ("The container exits because REQUIRED_CONFIG is unset. I'll validate my fix manifest in the "
     "code sandbox before it goes near the cluster.",
     "exec",
     {"intent": "Validate the remediation before applying it",
      "command": "python3 - <<'PY'\nimport json\npatch = " + json.dumps(PROD_PATCH) +
                 "\nenv = patch['spec']['template']['spec']['containers'][0]['env'][0]\n"
                 "assert env['name'] == 'REQUIRED_CONFIG' and env['value']\n"
                 "print('patch validated:', env)\nPY"}),
    ("Validated. Now a trial run in the sentinel-sandbox namespace via the sandbox connector.",
     "call_tool",
     {"mcp_server": "k8s-sandbox", "tool_name": "kubectl_apply",
      "input": {"manifest": FIXED_SANDBOX_MANIFEST, "namespace": SANDBOX_NS}}),
    ("The trial fix works in the sandbox. Applying the same fix to production; this write needs "
     "human approval.",
     "call_tool",
     {"mcp_server": "k8s-prod", "tool_name": "kubectl_patch",
      "input": {"resourceType": "deployment", "name": APP, "namespace": PROD_NS,
                "patchType": "strategic", "patchData": PROD_PATCH}}),
    ("Fix applied. Waiting before I check that production recovered.",
     "exec",
     {"intent": "Wait before verifying recovery", "command": "sleep 15"}),
    ("Checking production pod status.",
     "call_tool",
     {"mcp_server": "k8s-prod", "tool_name": "kubectl_get",
      "input": {"resourceType": "pods", "namespace": PROD_NS, "output": "wide"}}),
]

FINAL_TEXT = ("Root cause: the container exits immediately because the REQUIRED_CONFIG environment "
              "variable was missing. I validated the fix in the code sandbox, tested it in "
              "sentinel-sandbox, and after approval patched the production Deployment to set it.")


def sse(handler, obj):
    handler.wfile.write(b"data: " + json.dumps(obj).encode() + b"\n\n")
    handler.wfile.flush()


def chunk(delta, finish=None):
    return {"id": "mock-1", "object": "chat.completion.chunk", "created": int(time.time()),
            "model": "mock", "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}


class Handler(BaseHTTPRequestHandler):
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

        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.send_header("cache-control", "no-cache")
        self.end_headers()

        if issued < len(STEPS):
            text, name, args = STEPS[issued]
            sse(self, chunk({"role": "assistant", "content": text}))
            call = {"index": 0, "id": f"call_{issued + 1}", "type": "function",
                    "function": {"name": name, "arguments": ""}}
            sse(self, chunk({"tool_calls": [call]}))
            sse(self, chunk({"tool_calls": [{"index": 0, "function": {"arguments": json.dumps(args)}}]}))
            sse(self, chunk({}, "tool_calls"))
        else:
            sse(self, chunk({"role": "assistant", "content": FINAL_TEXT}))
            sse(self, chunk({}, "stop"))
        sse(self, {"id": "mock-1", "object": "chat.completion.chunk", "model": "mock", "choices": [],
                   "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}})
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9911)
    ap.add_argument("--host", default="127.0.0.1")
    a = ap.parse_args()
    print(f"mock LLM listening on http://{a.host}:{a.port}/v1", file=sys.stderr)
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()
