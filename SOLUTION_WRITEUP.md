# Sentinel — Autonomous Kubernetes Incident Response

**The problem.** Kubernetes pods fail for many reasons — bad config, out-of-memory, bad
images — and someone has to notice, investigate, and fix it, usually manually. Sentinel
automates the investigate-and-fix loop while keeping a human in control of the one step
that matters: touching production.

**Architecture.** A Java watcher (`DetectionModule`) plugs into Kubernetes' own Watch API,
filters out transient/self-recovering failures and deduplicates across replicas of the same
workload, and hands each real incident to a TrueForge agent session as a structured alert
(pod, owning workload, failure reason, restart count). Sentinel's own code contains **zero
AI logic** — all investigation, diagnosis, and remediation happen inside TrueForge.

**How TrueForge was used.** TrueForge runs the agent loop against GPT-4.1 (registered as a
model provider) and gives it two Kubernetes connectors over MCP: `k8s-prod` (reads run
freely; every write tool is paused by TrueForge for human approval) and `k8s-sandbox`
(RBAC-restricted at the Kubernetes permission level to a disposable `sentinel-sandbox`
namespace — enforced by the cluster, not just a prompt rule). TrueForge's own
approval-pause mechanism is what freezes the session before any production write; we did
not build that gate ourselves.

**What the agent reaches, and where it stops.** The agent describes and reads logs on the
real failing pod, fetches the real Deployment YAML, reproduces the exact failure inside
`sentinel-sandbox`, applies and verifies its own fix there, writes a plain-English
blast-radius report (target, change, expected effect, evidence, risk), and only then
attempts the production write — which TrueForge pauses for a human to approve or deny.
If sandbox verification fails, the agent stops on its own and never requests production
approval at all; this refusal path was verified live, including a case where a deliberately
wrong fix left the sandbox pod genuinely still broken.

**Real vs. mocked.** Kubernetes, the MCP tool calls, the RBAC sandbox boundary, the
TrueForge session, and the approval gate are all real and verified live end to end,
including with a real GPT-4.1 model reasoning about a genuine cluster failure it had never
seen scripted for it. A separate scripted rehearsal mode exists (an OpenAI-API-compatible
stand-in that follows the identical real tool-call path) so the demo can be rehearsed
without spending API credits — it is clearly labeled and not the model used for the graded
run.

**Known limits.** Without a Daytona key, the code-execution sandbox falls back to a local
(non-isolated) mode. Only the missing-configuration failure scenario is fully exercised
with a real model; an OOM scenario exists but has only been run against the scripted
rehearsal, not a real model.
