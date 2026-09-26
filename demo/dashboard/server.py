#!/usr/bin/env python3
"""Sentinel incident dashboard: turns a live TrueForge run into a judge-readable status
tracker with the real tool-call evidence attached, instead of a chat transcript.

It is a thin, read-mostly layer over TrueForge's real API - nothing is invented. It polls
GET /sessions (the newest sentinel-agent session started after THIS server started - see
SERVER_START below), GET /sessions/{id}/turns and .../events, and renders progress as a vertical
stage tracker (Detected -> Investigating -> Sandbox verification -> Blast-radius report ->
Awaiting approval -> Production -> Resolved/Stopped). Under each stage sits the real tool calls
that happened during it, collapsed by default - click one to see its actual request and response
JSON, exactly as TrueForge executed it. The one write path is the Approve/Deny button, which posts
the exact same user.tool_approval resume turn a human clicking "Allow" in the TrueForge UI would
send.

Every fact this page shows (sandbox reproduced broken, fix verified healthy, production verified,
who approved) is read either from TrueForge's own turn state or from the literal content of a
tool.response - never from the model's own narration text. That holds regardless of whether the
model is the scripted rehearsal or a real one, since it's reading what Kubernetes actually said,
not what the model claimed.

SERVER_START matters for demos: sessions created before this process started are never shown, so
the page always opens on "monitoring, no active incident" - even though TrueForge's own history
has old runs in it - and only starts filling in once a NEW incident happens in front of you.

Usage:
  python3 demo/dashboard/server.py [--port 9912] [--agent sentinel-agent]
Stdlib only. Reads TRUEFORGE_URL from the environment (default http://localhost:8790).
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TRUEFORGE_URL = os.environ.get("TRUEFORGE_URL", "http://localhost:8790").rstrip("/")
AGENT_NAME = os.environ.get("SENTINEL_AGENT_NAME", "sentinel-agent")
SERVER_START = datetime.now(timezone.utc)
WRITE_TOOLS = {"kubectl_apply", "kubectl_patch", "kubectl_scale", "kubectl_rollout", "kubectl_create"}
MAX_FIELD = 2500


def parse_ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def trunc(val):
    s = val if isinstance(val, str) else json.dumps(val, indent=2)
    return s if len(s) <= MAX_FIELD else s[:MAX_FIELD] + "\n... (truncated)"


def tf_get(path):
    req = urllib.request.Request(f"{TRUEFORGE_URL}/api/v1{path}")
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.loads(r.read()).get("data")


def tf_post(path, body):
    req = urllib.request.Request(
        f"{TRUEFORGE_URL}/api/v1{path}", data=json.dumps(body).encode(),
        headers={"content-type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.loads(r.read())


def status_of(done, reached, finished):
    if done:
        return "done"
    if not reached:
        return "pending"
    return "stopped" if finished else "active"


def compute_state():
    try:
        sessions = tf_get("/sessions?limit=10") or []
    except (urllib.error.URLError, TimeoutError) as e:
        return {"phase": "error", "error": f"Cannot reach TrueForge at {TRUEFORGE_URL}: {e}"}

    agent_sessions = [s for s in sessions if (s.get("agent") or {}).get("name") == AGENT_NAME
                       and parse_ts(s["created_at"]) >= SERVER_START]
    if not agent_sessions:
        return {"phase": "idle", "model": get_agent_model()}
    session = max(agent_sessions, key=lambda s: s["created_at"])
    sid = session["id"]

    try:
        turns = tf_get(f"/sessions/{sid}/turns?limit=25") or []
    except (urllib.error.URLError, TimeoutError) as e:
        return {"phase": "error", "error": f"Cannot reach TrueForge at {TRUEFORGE_URL}: {e}"}
    turns_sorted = sorted(turns, key=lambda t: t["created_at"])

    calls = []          # ordered [{connector, tool, request, response}]
    pending_by_id = {}  # tool_call_id -> call dict, filled in once its response arrives
    blast_radius = None
    decision = None     # "allow" | "deny" | None - read from the real resume turn's input
    last_text = None

    for t in turns_sorted:
        for item in (t.get("input") or []):
            if item.get("type") == "user.tool_approval":
                decision = (item.get("approval") or {}).get("status")
        try:
            t_events = tf_get(f"/sessions/{sid}/turns/{t['id']}/events?limit=100") or []
        except (urllib.error.URLError, TimeoutError):
            t_events = []
        for ev in t_events:
            et = ev.get("type")
            if et == "model.message":
                text = ev.get("content") or ""
                last_text = text
                # Match "blast-radius report" or "blast radius report" - the scripted rehearsal
                # writes the former, a real model (seen live: GPT-4.1) may write the latter.
                if blast_radius is None and "blast radius report" in text.lower().replace("-", " "):
                    blast_radius = text
                for tc in (ev.get("tool_calls") or []):
                    fn = tc.get("function") or {}
                    name = fn.get("name")
                    try:
                        args = json.loads(fn.get("arguments") or "{}")
                    except json.JSONDecodeError:
                        args = {}
                    if name == "call_tool":
                        connector, tool, request = args.get("mcp_server", "?"), args.get("tool_name", "?"), args.get("input", {})
                    else:
                        connector, tool, request = "code sandbox", (name or "exec"), args
                    # Meta calls (list_tools, get_tool_info, exec, ...) all render the same generic
                    # tool name, so two different real calls can look like an identical repeat -
                    # pull out whichever field actually says what THIS call is about.
                    detail = None
                    if isinstance(request, dict):
                        for key in ("tool_name", "name", "intent", "command"):
                            v = request.get(key)
                            if isinstance(v, str) and v:
                                detail = v[:70]
                                break
                    pending_by_id[tc.get("id")] = {"connector": connector, "tool": tool, "detail": detail,
                                                     "request": trunc(request), "response": None}
            elif et == "tool.response":
                call = pending_by_id.get(ev.get("tool_call_id"))
                if call is not None:
                    call["response"] = trunc(ev.get("content") or "")
                    calls.append(call)

    latest = turns_sorted[-1]
    required_actions = (latest.get("state") or {}).get("required_actions") or []
    approval = next((a for a in required_actions if a["type"] == "tool.approval_required"), None)
    run_status = (latest.get("state") or {}).get("status")
    finished = run_status == "done" and not required_actions
    final_text = last_text if finished else None

    if approval:
        phase = "paused"
        tcs = approval.get("tool_calls") or [{}]
        approval_info = {"thread_id": approval.get("thread_id"), "tool_call_id": tcs[0].get("id")}
    else:
        phase = "running" if run_status == "running" else "done"
        approval_info = None

    outcome = None
    if final_text:
        outcome = "resolved" if decision == "allow" else ("denied" if decision == "deny" else "stopped")

    # Bucket the real calls by position: everything before the first k8s-sandbox call is
    # "investigating"; from there up to (not including) the production write call is "sandbox";
    # the write call onward is "production". Positional, not keyword-based - it works the same
    # whether the model is the scripted rehearsal or a real one, since every path in
    # sentinel-instructions.md follows this same order.
    sandbox_start = next((i for i, c in enumerate(calls) if c["connector"] == "k8s-sandbox"), None)
    prod_write_idx = None
    if sandbox_start is not None:
        for i in range(sandbox_start, len(calls)):
            if calls[i]["connector"] == "k8s-prod" and calls[i]["tool"] in WRITE_TOOLS:
                prod_write_idx = i
                break
    investigating_calls = calls[:sandbox_start] if sandbox_start is not None else calls
    sandbox_calls = (calls[sandbox_start:prod_write_idx] if prod_write_idx is not None else calls[sandbox_start:]) if sandbox_start is not None else []
    production_calls = calls[prod_write_idx:] if prod_write_idx is not None else []

    def last_status_healthy(cs):
        """True only if BOTH the last status check and the last log read look healthy.

        A single kubectl_get snapshot of a crash-looping pod can catch it mid-restart and show
        "Running" for an instant before it crashes again - exactly what happened live in testing
        (restart count already climbing, yet that one snapshot read Running). The pod's own logs
        from the same moment showed the real failure. Status alone is never proof; this mirrors
        the same rule sentinel-instructions.md gives the agent itself.
        """
        gets = [c for c in cs if c["tool"] == "kubectl_get"]
        if not gets:
            return False
        resp = (gets[-1]["response"] or "").lower()
        status_ok = "running" in resp and "crashloopbackoff" not in resp and '"status": "error"' not in resp
        if not status_ok:
            return False
        logs = [c for c in cs if c["tool"] == "kubectl_logs"]
        if logs and "error" in (logs[-1]["response"] or "").lower():
            return False
        return True

    sandbox_reached = sandbox_start is not None
    sandbox_fixed = last_status_healthy(sandbox_calls)
    production_verified = last_status_healthy(production_calls)

    investigating_status = status_of(sandbox_reached or finished, True, finished)
    sandbox_status = status_of(sandbox_fixed, sandbox_reached, finished)
    blast_status = status_of(blast_radius is not None, sandbox_status == "done", finished)
    approval_status = "done" if decision is not None else ("active" if phase == "paused" else "pending")
    production_status = status_of(production_verified, decision == "allow", finished)

    # The first turn's own input is the literal alert Sentinel wrote to open this TrueForge
    # session - real evidence of the handoff, not narration, so show it as the "request" that
    # started everything.
    first_input = (turns_sorted[0].get("input") or []) if turns_sorted else []
    alert_text = first_input[0].get("content") if first_input and first_input[0].get("type") == "user.message" else None

    stages = [
        {"key": "detected", "status": "done", "title": "Pod failure detected",
         "oneliner": "Sentinel's watcher caught this in real time from the Kubernetes API and handed it to a TrueForge agent session.",
         "card": alert_text,
         "link": {"href": f"{TRUEFORGE_URL}/sessions/{sid}", "label": "Open this session in TrueForge ↗"}},
        {"key": "investigating", "status": investigating_status, "title": "Investigating",
         "oneliner": "The agent (via TrueForge) is reading the pod's spec, logs, and events." if investigating_status != "done" else "The agent (via TrueForge) read the pod's spec, logs, and events.",
         "calls": investigating_calls},
        {"key": "sandbox", "status": sandbox_status, "title": "Sandbox verification (broken → healthy)",
         "oneliner": "The agent is reproducing the exact failure, then applying and verifying its fix." if sandbox_status != "done" else "The agent reproduced the exact failure, then verified its fix recovers it.",
         "calls": sandbox_calls},
        {"key": "blast_radius", "status": blast_status, "title": "Blast-radius report (written by the agent)", "card": blast_radius},
        {"key": "approval", "status": approval_status, "title": "Awaiting your approval",
         "oneliner": "The agent's TrueForge session is paused. Production write requires a human - nothing happens without you." if phase == "paused" else None,
         "decision": decision, "show_buttons": phase == "paused"},
        {"key": "production", "status": production_status, "title": "Production",
         "oneliner": "The agent is applying the approved patch, then verifying recovery." if production_status != "done" else "The agent applied the patch and verified it recovered.",
         "calls": production_calls},
    ]

    pod_ref = (session.get("metadata") or {}).get("pod")
    return {
        "phase": phase, "session_id": sid, "session_url": f"{TRUEFORGE_URL}/sessions/{sid}",
        "pod": pod_ref,
        "workload": (session.get("metadata") or {}).get("workload"),
        "severity": (session.get("metadata") or {}).get("severity"),
        "model": get_agent_model(),
        "live_pod": get_live_pod_status(pod_ref),
        "decision": decision, "outcome": outcome, "final_text": final_text, "stages": stages,
        "approval": approval_info,
    }


def get_agent_model():
    """Whichever model is actually configured for the agent right now - read live from TrueForge
    rather than hardcoded, so this never goes stale if the provider is swapped (e.g. OpenAI <->
    Anthropic <-> the rehearsal mock)."""
    try:
        agents = tf_get("/agents?limit=100") or []
        agent = next((a for a in agents if a.get("name") == AGENT_NAME), None)
        if agent:
            return ((agent.get("manifest") or {}).get("model") or {}).get("name")
    except (urllib.error.URLError, TimeoutError):
        pass
    return None


def get_live_pod_status(pod_ref):
    """Real, right-now status of the originally-alerted pod, straight from kubectl - not TrueForge,
    not cached, re-fetched on every poll. If the pod no longer exists (e.g. replaced by a rollout
    after a fix), that's reported plainly rather than hidden - no fabricated numbers, ever."""
    if not pod_ref or "/" not in pod_ref:
        return None
    namespace, name = pod_ref.split("/", 1)
    try:
        out = subprocess.run(
            ["kubectl", "get", "pod", name, "-n", namespace, "-o", "json"],
            capture_output=True, text=True, timeout=5)
        if out.returncode != 0:
            return {"exists": False, "namespace": namespace, "name": name}
        pod = json.loads(out.stdout)
        containers = (pod.get("status") or {}).get("containerStatuses") or [{}]
        cs = containers[0]
        return {
            "exists": True, "namespace": namespace, "name": name,
            "phase": (pod.get("status") or {}).get("phase"),
            "ready": bool(cs.get("ready", False)),
            "restart_count": cs.get("restartCount", 0),
            "node": (pod.get("spec") or {}).get("nodeName"),
        }
    except Exception:
        return None


INDEX_HTML = r"""<!doctype html>
<html><head><meta charset="utf-8"><title>Sentinel - Incident Dashboard</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root { --bg:#0b0d12; --card:#12151c; --border:#232733; --dim:#5b6270; --text:#e6e9f0;
        --blue:#4c8dff; --green:#3ddc84; --amber:#ffb84c; --red:#ff5c5c; }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--text); font-family: -apple-system, "Segoe UI", Inter, sans-serif; }
.wrap { max-width: 920px; margin: 0 auto; padding: 32px 24px 80px; }
h1 { font-size: 26px; margin: 0 0 4px; display:flex; align-items:center; gap:12px; }
.dot { width:10px; height:10px; border-radius:50%; background:var(--dim); }
.dot.live { background:var(--green); box-shadow:0 0 0 4px rgba(61,220,132,.15); }
.sub { color:var(--dim); font-size:14px; margin-bottom:28px; }
.badgebar { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:28px; }
.badge { background:var(--card); border:1px solid var(--border); border-radius:999px; padding:6px 14px; font-size:13px; }
.badge b { color:var(--text); }
.sev-HIGH { border-color: var(--amber); color: var(--amber); }
.sev-CRITICAL { border-color: var(--red); color: var(--red); }
.livepod { display:flex; align-items:center; gap:14px; flex-wrap:wrap; background: var(--card);
  border:1px solid var(--border); border-radius:8px; padding:10px 14px; margin-bottom:28px; font-size:13px; color:#c9cedb; }
.livepod code { background:#0b0d12; padding:1px 5px; border-radius:4px; font-family: ui-monospace, "SF Mono", Menlo, monospace; }
.lp-lbl { font-size:11px; letter-spacing:.05em; color:var(--dim); font-weight:700; }
.lp-dot { width:8px; height:8px; border-radius:50%; display:inline-block; }
.lp-dot.good { background: var(--green); }
.lp-dot.bad { background: var(--red); }
.lp-dot.na { background: var(--dim); }
.stage { display:flex; gap:16px; padding: 16px 0; border-left: 2px solid var(--border); margin-left: 13px; padding-left: 28px; position:relative; }
.stage:last-child { border-left-color: transparent; }
.stage .marker { position:absolute; left:-15px; top:14px; width:28px; height:28px; border-radius:50%;
  background:var(--bg); border:2px solid var(--dim); display:flex; align-items:center; justify-content:center; font-size:14px; }
.stage.done .marker { border-color: var(--green); background: var(--green); color:#04170c; }
.stage.active .marker { border-color: var(--blue); animation: pulse 1.4s infinite; }
.stage.stopped .marker { border-color: var(--red); background: var(--red); color: #200; }
.stage.pending .marker { opacity: .35; }
@keyframes pulse { 0%{ box-shadow:0 0 0 0 rgba(76,141,255,.5);} 70%{ box-shadow:0 0 0 10px rgba(76,141,255,0);} 100%{box-shadow:0 0 0 0 rgba(76,141,255,0);} }
.stage h3 { margin:0 0 3px; font-size:16px; }
.stage.pending h3 { color: var(--dim); }
.stage p.detail { margin:2px 0 0; color:#8a91a1; font-size:13px; line-height:1.5; }
.card { background: var(--card); border:1px solid var(--border); border-radius:10px; padding:14px 16px; margin-top:8px; font-size:13px; line-height:1.6; white-space:pre-wrap; color:#c9cedb; }
.calls { margin-top:8px; display:flex; flex-direction:column; gap:6px; }
.call { background: var(--card); border:1px solid var(--border); border-radius:8px; overflow:hidden; }
.call summary { list-style:none; cursor:pointer; padding:8px 12px; font-size:13px; font-family: ui-monospace, "SF Mono", Menlo, monospace; display:flex; align-items:center; gap:8px; color:#d5d9e2; }
.call summary::-webkit-details-marker { display:none; }
.call summary .conn { color: var(--dim); font-family: -apple-system, sans-serif; }
.call summary .ok { color: var(--green); margin-left:auto; }
.call summary .care { color: var(--blue); }
.callbody { padding: 4px 12px 12px; border-top:1px solid var(--border); }
.callbody .lbl { font-size:11px; text-transform:uppercase; letter-spacing:.04em; color:var(--dim); margin:8px 0 4px; }
.callbody pre { margin:0; white-space:pre-wrap; word-break:break-word; font-size:12px; font-family: ui-monospace, "SF Mono", Menlo, monospace; color:#c9cedb; background:#0b0d12; padding:8px 10px; border-radius:6px; max-height:260px; overflow:auto; }
.btns { margin-top:14px; display:flex; gap:12px; }
button { font-size:15px; font-weight:600; padding:10px 22px; border-radius:8px; border:none; cursor:pointer; }
.allow { background: var(--green); color:#04170c; }
.deny { background: transparent; color: var(--red); border: 1.5px solid var(--red); }
button:disabled { opacity:.5; cursor:default; }
.outcome { margin-top: 20px; padding: 20px; border-radius: 12px; font-size: 15px; }
.outcome.resolved { background: rgba(61,220,132,.1); border:1px solid var(--green); }
.outcome.stopped, .outcome.denied { background: rgba(255,92,92,.08); border:1px solid var(--red); }
.outcome h2 { margin:0 0 8px; font-size: 18px; }
.outcome.resolved h2 { color: var(--green); }
.outcome.stopped h2, .outcome.denied h2 { color: var(--red); }
.idle { color: var(--dim); font-size: 15px; margin-top: 40px; text-align:center; }
.idle .big { font-size: 20px; color: var(--text); margin-bottom: 8px; }
.err { color: var(--red); margin-top: 20px; }
a.sess { color: var(--blue); font-size: 13px; }
#disconnected { display:none; position:fixed; top:0; left:0; right:0; z-index:100; background:var(--red);
  color:#200; font-weight:700; text-align:center; padding:10px; align-items:center; justify-content:center; gap:8px; }
</style></head>
<body>
<div id="disconnected">&#9888; Lost connection to Sentinel's dashboard server - what's shown below is frozen, not live. Restart it and reload this page.</div>
<div class="wrap">
  <h1><span class="dot" id="livedot"></span> Sentinel</h1>
  <div class="sub" id="sub">Monitoring Kubernetes &middot; agent runtime: TrueForge</div>
  <div id="root"></div>
</div>
<script>
const $ = document.getElementById.bind(document);
function esc(s){ return (s==null?'':String(s)).replace(/&/g,'&amp;').replace(/</g,'&lt;'); }

function callRow(c, key, openKeys) {
  const open = openKeys.has(key) ? ' open' : '';
  return `<details class="call" data-key="${key}"${open}><summary>
      <span class="care">${esc(c.tool)}</span><span class="conn">(${esc(c.connector)})</span>
      ${c.detail ? `<span class="conn">&mdash; ${esc(c.detail)}</span>` : ''}
      <span class="ok">&#10003;</span>
    </summary>
    <div class="callbody">
      <div class="lbl">Request</div><pre>${esc(c.request)}</pre>
      <div class="lbl">Response</div><pre>${esc(c.response)}</pre>
    </div></details>`;
}

function livePodEl(lp) {
  if (!lp) return '';
  if (!lp.exists) {
    return `<div class="livepod"><span class="lp-lbl">LIVE CLUSTER STATE</span><span class="lp-dot na"></span>` +
      `Original pod <code>${esc(lp.name)}</code> no longer exists in <code>${esc(lp.namespace)}</code> - likely replaced by a rollout.</div>`;
  }
  const healthy = lp.phase === 'Running' && lp.ready;
  return `<div class="livepod">
    <span class="lp-lbl">LIVE CLUSTER STATE</span>
    <span class="lp-dot ${healthy ? 'good' : 'bad'}"></span>
    <span>Phase: <b>${esc(lp.phase)}</b></span>
    <span>Ready: <b>${lp.ready ? 'Yes' : 'No'}</b></span>
    <span>Restarts: <b>${lp.restart_count}</b></span>
    <span>Node: <b>${esc(lp.node || 'unknown')}</b></span>
  </div>`;
}

function stageEl(s, openKeys) {
  const cls = s.status;
  const icon = s.status === 'done' ? '&#10003;' : s.status === 'stopped' ? '&#10007;' : '';
  let extra = '';
  if (s.oneliner) extra += `<p class="detail">${esc(s.oneliner)}</p>`;
  if (s.card) extra += `<div class="card">${esc(s.card)}</div>`;
  if (s.link) extra += `<p class="detail"><a class="sess" href="${s.link.href}" target="_blank">${esc(s.link.label)}</a></p>`;
  if (s.calls && s.calls.length) extra += `<div class="calls">${s.calls.map((c, i) => callRow(c, s.key + '-' + i, openKeys)).join('')}</div>`;
  if (s.decision) extra += `<p class="detail">Decision: <b>${s.decision === 'allow' ? 'Approved' : 'Denied'}</b> by a human.</p>`;
  if (s.show_buttons) extra += `<div class="btns">
      <button class="allow" id="allowBtn" onclick="approve('allow')">Approve production change</button>
      <button class="deny" id="denyBtn" onclick="approve('deny')">Deny</button>
    </div>`;
  return `<div class="stage ${cls}"><div class="marker">${icon}</div><div style="flex:1"><h3>${esc(s.title)}</h3>${extra}</div></div>`;
}

async function approve(status) {
  const a = $('allowBtn'), d = $('denyBtn');
  if (a) a.disabled = true; if (d) d.disabled = true;
  await fetch('/api/decide', {method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({status})});
  poll();
}

function render(st) {
  $('livedot').className = 'dot' + (st.phase === 'running' || st.phase === 'paused' ? ' live' : '');
  const root = $('root');
  const modelTag = st.model ? ` (${esc(st.model)})` : '';
  if (st.phase === 'idle') {
    $('sub').innerHTML = `Monitoring Kubernetes &middot; agent runtime: TrueForge${modelTag}`;
    root.innerHTML = '<div class="idle"><div class="big">&#128994; System healthy</div>No active incident.</div>';
    return;
  }
  if (st.phase === 'error') {
    $('sub').textContent = '';
    root.innerHTML = `<div class="err">${esc(st.error)}</div>`;
    return;
  }
  $('sub').innerHTML = `Agent runtime: TrueForge${modelTag} &middot; <a class="sess" href="${st.session_url}" target="_blank">session ${st.session_id} ↗</a>`;
  // The whole tracker re-renders every poll to stay live, which would otherwise snap any
  // manually-opened <details> row shut again on the next tick - carry the open set across.
  const openKeys = new Set(Array.from(root.querySelectorAll('details[open]')).map(d => d.dataset.key));
  let html = `<div class="badgebar">` +
    (st.pod ? `<div class="badge">pod <b>${esc(st.pod)}</b></div>` : '') +
    (st.workload ? `<div class="badge">workload <b>${esc(st.workload)}</b></div>` : '') +
    (st.severity ? `<div class="badge sev-${st.severity}">severity <b>${esc(st.severity)}</b></div>` : '') +
    `</div>`;
  html += livePodEl(st.live_pod);
  html += st.stages.map(s => stageEl(s, openKeys)).join('');
  if (st.outcome === 'resolved') {
    html += `<div class="outcome resolved"><h2>&#9989; Incident resolved</h2>${esc(st.final_text)}</div>`;
  } else if (st.outcome === 'denied') {
    html += `<div class="outcome denied"><h2>&#128721; Denied — production left untouched</h2>A human denied the production change. Sentinel did not apply it.</div>`;
  } else if (st.outcome === 'stopped') {
    html += `<div class="outcome stopped"><h2>&#128721; Agent stopped itself — verification failed</h2>${esc(st.final_text)}</div>`;
  }
  root.innerHTML = html;
}

let failCount = 0;
async function poll() {
  try {
    const r = await fetch('/api/state');
    if (!r.ok) throw new Error('http ' + r.status);
    render(await r.json());
    failCount = 0;
    $('disconnected').style.display = 'none';
  } catch (e) {
    failCount++;
    // A couple of misses can be a normal blip; only surface it once it's clearly not coming back,
    // so the page never just freezes silently on stale content with no sign anything is wrong.
    if (failCount >= 3) $('disconnected').style.display = 'flex';
  }
}
poll();
setInterval(poll, 1500);
</script>
</body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _json(self, obj, status=200):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/?"):
            body = INDEX_HTML.encode()
            self.send_response(200)
            self.send_header("content-type", "text/html; charset=utf-8")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/api/state":
            self._json(compute_state())
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path != "/api/decide":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("content-length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        status = body.get("status")
        if status not in ("allow", "deny"):
            self._json({"error": "status must be allow or deny"}, 400)
            return
        state = compute_state()
        approval = state.get("approval")
        if not approval:
            self._json({"error": "no pending approval right now"}, 409)
            return
        try:
            tf_post(f"/sessions/{state['session_id']}/turns", {
                "stream": False,
                "input": [{"type": "user.tool_approval", "thread_id": approval["thread_id"],
                           "tool_call_id": approval["tool_call_id"], "approval": {"status": status}}],
            })
            self._json({"ok": True})
        except (urllib.error.URLError, TimeoutError) as e:
            self._json({"error": str(e)}, 502)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9912)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--agent", default=None)
    a = ap.parse_args()
    if a.agent:
        AGENT_NAME = a.agent
    print(f"Sentinel dashboard on http://{a.host}:{a.port} (TrueForge: {TRUEFORGE_URL}, agent: {AGENT_NAME})",
          file=sys.stderr)
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()
