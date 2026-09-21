package com.sentinel.trueforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.detection.PodInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands a detected pod failure to the TrueForge "sentinel-agent" and shepherds the run.
 *
 * <p>TrueForge's HTTP API is Agent -> Session -> Turn. {@link #start} opens a session for the
 * agent and posts the alert as the first turn (non-streaming, so the call returns immediately).
 * The run is then followed on its own virtual thread: when the agent hits a gated tool (a change
 * to production) the turn ENDS with {@code tool.approval_required}; a human decides through the
 * {@link ApprovalHandler}, and the decision is posted back as a new turn that resumes the agent.
 *
 * <p>Configuration is environment-only - no credentials live in code:
 * {@code TRUEFORGE_URL} (default http://localhost:8790), {@code TRUEFORGE_TOKEN} (only if
 * TrueForge login is enabled), {@code SENTINEL_AGENT_NAME} (default sentinel-agent),
 * {@code SENTINEL_POLL_MS}, {@code SENTINEL_MAX_RUN_MINUTES}.
 */
public class TrueForgeAgentTrigger {

    public enum Outcome { COMPLETED, FAILED, CANCELLED, TIMED_OUT, ABANDONED }

    /** A started run. {@code completion} resolves when the agent run reaches a final state. */
    public record Run(String podId, String sessionId, String turnId, CompletableFuture<Outcome> completion) {}

    private static final int MAX_PRINTED_CHARS = 1500;

    private final String baseUrl;
    /** Base URLs to try, in order. For "localhost" this also includes both loopback addresses. */
    private final List<String> candidateBases;
    private volatile String workingBase;
    private final String agentName;
    private final String token;
    private final ApprovalHandler approvalHandler;
    private final Duration pollInterval;
    private final Duration maxRun;
    private final Duration denyCooldown;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /** workloadId -> sessionId for runs still in flight (including runs paused for approval). */
    private final Map<String, String> activeRuns = new ConcurrentHashMap<>();

    /** workloadId -> when a human's denial stops suppressing new runs for it. */
    private final Map<String, Instant> cooldownUntil = new ConcurrentHashMap<>();

    public TrueForgeAgentTrigger(
        String baseUrl, String agentName, String token, ApprovalHandler approvalHandler,
        Duration pollInterval, Duration maxRun) {
        this(baseUrl, agentName, token, approvalHandler, pollInterval, maxRun, Duration.ofMinutes(30));
    }

    /**
     * @param denyCooldown how long, after a human DENIES a fix, Sentinel stays quiet about that
     *     workload. The pod keeps failing, so without this it would re-alert and re-prompt as soon
     *     as the 5-minute dedup window lapsed - nagging someone who already said no.
     */
    public TrueForgeAgentTrigger(
        String baseUrl, String agentName, String token, ApprovalHandler approvalHandler,
        Duration pollInterval, Duration maxRun, Duration denyCooldown) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.candidateBases = expandLocalhost(this.baseUrl);
        this.workingBase = candidateBases.get(0);
        this.agentName = agentName;
        this.token = token;
        this.approvalHandler = approvalHandler;
        this.pollInterval = pollInterval;
        this.maxRun = maxRun;
        this.denyCooldown = denyCooldown;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    // ------------------------------------------------------------------------------------------
    // Static entry point used by DetectionModule
    // ------------------------------------------------------------------------------------------

    private static volatile TrueForgeAgentTrigger defaultInstance;

    private static TrueForgeAgentTrigger defaultInstance() {
        TrueForgeAgentTrigger d = defaultInstance;
        if (d == null) {
            synchronized (TrueForgeAgentTrigger.class) {
                if (defaultInstance == null) defaultInstance = fromEnv();
                d = defaultInstance;
            }
        }
        return d;
    }

    /** Replace (or clear, with null) the shared instance - mainly for tests. */
    public static void setDefault(TrueForgeAgentTrigger trigger) {
        defaultInstance = trigger;
    }

    public static TrueForgeAgentTrigger fromEnv() {
        Map<String, String> env = System.getenv();
        String mode = env.getOrDefault("SENTINEL_APPROVAL_MODE", "auto").toLowerCase();
        ConsoleApprovalHandler console = new ConsoleApprovalHandler();
        ApprovalHandler handler = switch (mode) {
            case "ui" -> a -> {
                System.out.println("[Sentinel] APPROVAL REQUIRED for " + a.toolName() + " on "
                    + a.connector() + " - decide in the TrueForge UI: " + a.sessionUrl());
                return ApprovalHandler.Decision.defer();
            };
            default -> console; // "console"/"auto": prompt if there is a terminal, else defer to the UI
        };
        return new TrueForgeAgentTrigger(
            env.getOrDefault("TRUEFORGE_URL", "http://localhost:8790"),
            env.getOrDefault("SENTINEL_AGENT_NAME", "sentinel-agent"),
            env.get("TRUEFORGE_TOKEN"),
            handler,
            Duration.ofMillis(Long.parseLong(env.getOrDefault("SENTINEL_POLL_MS", "2000"))),
            Duration.ofMinutes(Long.parseLong(env.getOrDefault("SENTINEL_MAX_RUN_MINUTES", "60"))),
            Duration.ofMinutes(Long.parseLong(env.getOrDefault("SENTINEL_DENY_COOLDOWN_MINUTES", "30"))));
    }

    /**
     * Start a TrueForge agent run for this failing pod. Returns as soon as the run is started (the
     * run itself continues in the background), or null if nothing was started because the pod's
     * workload already has a run in flight or a human recently denied a fix for it. Throws
     * TrueForgeException if TrueForge can't be reached - the caller decides how to degrade.
     */
    public static Run trigger(PodInfo podInfo) {
        return defaultInstance().start(podInfo);
    }

    // ------------------------------------------------------------------------------------------
    // Starting a run
    // ------------------------------------------------------------------------------------------

    /** Returns null (and does nothing) if this pod's workload is already being handled or was denied. */
    public Run start(PodInfo podInfo) {
        String podId = podInfo.podId();
        String workload = podInfo.workloadId() != null ? podInfo.workloadId() : podId;

        Instant until = cooldownUntil.get(workload);
        if (until != null) {
            if (Instant.now().isBefore(until)) {
                System.out.println("[Sentinel] Not starting a run for " + podId + ": a fix for " + workload
                    + " was denied; staying quiet for another "
                    + Math.max(1, Duration.between(Instant.now(), until).toMinutes()) + " min");
                return null;
            }
            cooldownUntil.remove(workload);
        }
        if (activeRuns.putIfAbsent(workload, "starting") != null) {
            System.out.println("[Sentinel] TrueForge run already in progress for " + workload
                + " - not starting another (pod " + podId + ")");
            return null;
        }
        try {
            ObjectNode session = mapper.createObjectNode();
            session.putObject("agent").put("name", agentName);
            ObjectNode meta = session.putObject("metadata");
            meta.put("source", "sentinel");
            meta.put("pod", truncate(podId, 128));
            meta.put("workload", truncate(workload, 128));
            meta.put("severity", podInfo.severity());

            String sessionId = call("POST", "/sessions", session).path("data").path("id").asText();
            if (sessionId.isEmpty()) throw new TrueForgeException("TrueForge returned no session id");
            activeRuns.put(workload, sessionId);

            String turnId = postTurn(sessionId, userMessage(buildTriggerMessage(podInfo)));

            System.out.println("[Sentinel] TrueForge agent '" + agentName + "' started for " + podId
                + " (session " + sessionId + ") - watch it at " + sessionUrl(sessionId));

            CompletableFuture<Outcome> completion = new CompletableFuture<>();
            Thread.startVirtualThread(() -> {
                Outcome outcome;
                try {
                    outcome = follow(podId, workload, sessionId, turnId);
                } catch (Throwable t) {
                    System.err.println("[Sentinel] Lost track of TrueForge run for " + podId + ": " + t.getMessage());
                    outcome = Outcome.ABANDONED;
                } finally {
                    activeRuns.remove(workload);
                }
                completion.complete(outcome);
            });
            return new Run(podId, sessionId, turnId, completion);
        } catch (RuntimeException e) {
            activeRuns.remove(workload);
            throw e;
        }
    }

    static String buildTriggerMessage(PodInfo podInfo) {
        return String.format("""
            ALERT: Pod failure detected
            Pod: %s
            Namespace: %s
            Owning workload: %s
            Failure reason: %s
            Pod phase: %s
            Restart count: %d
            Severity: %s

            Sandbox namespace for trial runs: sentinel-sandbox

            Please investigate and remediate.
            """,
            podInfo.podName(), podInfo.namespace(),
            podInfo.workloadId() != null ? podInfo.workloadId() : podInfo.podId(), podInfo.reason(),
            podInfo.status(), podInfo.restartCount(), podInfo.severity());
    }

    // ------------------------------------------------------------------------------------------
    // Following a run
    // ------------------------------------------------------------------------------------------

    private Outcome follow(String podId, String workload, String sessionId, String firstTurnId) throws InterruptedException {
        Instant deadline = Instant.now().plus(maxRun);
        String turnId = firstTurnId;

        while (Instant.now().isBefore(deadline)) {
            JsonNode state = call("GET", "/sessions/" + sessionId + "/turns/" + turnId, null).path("data").path("state");
            String status = state.path("status").asText("running");

            switch (status) {
                case "error" -> {
                    System.err.println("[Sentinel] TrueForge run for " + podId + " failed: " + state.path("message").asText("(no message)"));
                    return Outcome.FAILED;
                }
                case "cancelled" -> {
                    System.out.println("[Sentinel] TrueForge run for " + podId + " was cancelled");
                    return Outcome.CANCELLED;
                }
                case "done" -> {
                    JsonNode actions = state.path("required_actions");
                    if (!actions.isArray() || actions.isEmpty()) {
                        String output = state.path("output").path("content").asText("");
                        System.out.println("[Sentinel] Agent finished for " + podId + ": " + truncate(output, MAX_PRINTED_CHARS));
                        return Outcome.COMPLETED;
                    }
                    String next = resolvePause(podId, workload, sessionId, turnId, actions);
                    if (next == null) return Outcome.ABANDONED;
                    turnId = next;
                    continue; // follow the resumed turn straight away
                }
                default -> { /* running - keep polling */ }
            }
            Thread.sleep(pollInterval.toMillis());
        }
        System.err.println("[Sentinel] Gave up following TrueForge run for " + podId + " after " + maxRun);
        return Outcome.TIMED_OUT;
    }

    /**
     * The turn ended paused. Collect a human decision for every pending approval and resume the
     * agent with one new turn; if the human is deciding elsewhere (the UI), just wait for the
     * resumed turn to appear. Returns the id of the turn to follow next, or null to stop.
     */
    private String resolvePause(String podId, String workload, String sessionId, String pausedTurnId, JsonNode actions)
        throws InterruptedException {

        List<ObjectNode> approvals = new ArrayList<>();
        boolean deferToUi = false;

        for (JsonNode action : actions) {
            String type = action.path("type").asText();
            if (!"tool.approval_required".equals(type)) {
                System.out.println("[Sentinel] Run for " + podId + " needs a human action (" + type
                    + ") - continue it in the TrueForge UI: " + sessionUrl(sessionId));
                deferToUi = true;
                continue;
            }
            String threadId = action.path("thread_id").asText("main");
            for (JsonNode toolCall : action.path("tool_calls")) {
                PendingApproval pending = describe(sessionId, pausedTurnId, threadId, toolCall);
                ApprovalHandler.Decision decision = approvalHandler.decide(pending);
                switch (decision.verdict()) {
                    case ALLOW -> approvals.add(approvalItem(threadId, pending.toolCallId(), "allow", null));
                    case DENY -> approvals.add(approvalItem(threadId, pending.toolCallId(), "deny", decision.reason()));
                    case DEFER -> deferToUi = true;
                }
            }
        }

        if (!deferToUi && !approvals.isEmpty()) {
            try {
                ArrayNode input = mapper.createArrayNode();
                approvals.forEach(input::add);
                String resumed = postTurn(sessionId, input);
                boolean allowed = approvals.stream().anyMatch(a -> "allow".equals(a.path("approval").path("status").asText()));
                System.out.println("[Sentinel] " + (allowed ? "Approval sent" : "Denial sent") + " to TrueForge for " + podId);
                if (approvals.stream().anyMatch(a -> "deny".equals(a.path("approval").path("status").asText()))) {
                    cooldownUntil.put(workload, Instant.now().plus(denyCooldown));
                }
                return resumed;
            } catch (TrueForgeException e) {
                // Most likely someone resumed it in the UI at the same moment - fall through and follow that.
                System.err.println("[Sentinel] Could not post the decision (" + e.getMessage() + "); checking whether the run was resumed elsewhere");
            }
        }
        return awaitResumedTurn(podId, sessionId, pausedTurnId);
    }

    /** Wait for a turn chained after {@code pausedTurnId} (created when a human resumes in the UI). */
    private String awaitResumedTurn(String podId, String sessionId, String pausedTurnId) throws InterruptedException {
        System.out.println("[Sentinel] Waiting for a human to resume the run for " + podId + " in TrueForge: " + sessionUrl(sessionId));
        Instant deadline = Instant.now().plus(maxRun);
        while (Instant.now().isBefore(deadline)) {
            for (JsonNode t : listAll("/sessions/" + sessionId + "/turns", 25)) {
                if (pausedTurnId.equals(t.path("previous_turn_id").asText(null))) {
                    return t.path("id").asText();
                }
            }
            Thread.sleep(pollInterval.toMillis());
        }
        System.err.println("[Sentinel] No one resumed the paused run for " + podId + " within " + maxRun + " - leaving it paused");
        return null;
    }

    /** Work out what the paused call actually is, from the model message that made it. */
    private PendingApproval describe(String sessionId, String turnId, String threadId, JsonNode toolCallRef) {
        String toolCallId = toolCallRef.path("id").asText();
        String sourceEventId = toolCallRef.path("source_event_id").asText("");
        String connector = null;
        String toolName = "(unknown tool)";
        String input = "(details unavailable - open the run in TrueForge)";

        try {
            for (JsonNode e : listAll("/sessions/" + sessionId + "/turns/" + turnId + "/events", 100)) {
                if (!"model.message".equals(e.path("type").asText()) || !sourceEventId.equals(e.path("id").asText())) continue;
                for (JsonNode tc : e.path("tool_calls")) {
                    if (!toolCallId.equals(tc.path("id").asText())) continue;
                    String fn = tc.path("function").path("name").asText();
                    JsonNode args = mapper.readTree(tc.path("function").path("arguments").asText("{}"));
                    if ("call_tool".equals(fn)) {
                        connector = args.path("mcp_server").asText(null);
                        toolName = args.path("tool_name").asText(fn);
                        input = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(args.path("input"));
                    } else {
                        toolName = fn;
                        input = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(args);
                    }
                }
            }
        } catch (Exception e) {
            // Keep the placeholder text: the human is still asked, just with less detail.
        }
        return new PendingApproval(sessionId, threadId, toolCallId, connector, toolName, truncate(input, MAX_PRINTED_CHARS), sessionUrl(sessionId));
    }

    // ------------------------------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------------------------------

    private ArrayNode userMessage(String content) {
        ArrayNode input = mapper.createArrayNode();
        input.addObject().put("type", "user.message").put("content", content);
        return input;
    }

    private ObjectNode approvalItem(String threadId, String toolCallId, String status, String reason) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "user.tool_approval");
        item.put("thread_id", threadId);
        item.put("tool_call_id", toolCallId);
        ObjectNode approval = item.putObject("approval");
        approval.put("status", status);
        if (reason != null) approval.put("reason", reason);
        return item;
    }

    /** Post a turn (non-streaming: returns immediately with the running turn) and return its id. */
    private String postTurn(String sessionId, ArrayNode input) {
        ObjectNode body = mapper.createObjectNode();
        body.put("stream", false);
        body.set("input", input);
        String turnId = call("POST", "/sessions/" + sessionId + "/turns", body).path("data").path("id").asText();
        if (turnId.isEmpty()) throw new TrueForgeException("TrueForge returned no turn id");
        return turnId;
    }

    private JsonNode call(String method, String path, JsonNode body) {
        String payload;
        try {
            payload = body != null ? mapper.writeValueAsString(body) : null;
        } catch (Exception e) {
            throw new TrueForgeException("could not encode request for " + method + " " + path, e);
        }

        // Try the last address that worked first, then the rest. Only connection failures move on
        // to the next address; an HTTP error from a reachable server is returned as-is.
        List<String> order = new ArrayList<>(candidateBases);
        order.remove(workingBase);
        order.add(0, workingBase);

        Exception lastConnectFailure = null;
        for (String base : order) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("accept", "application/json");
                if (token != null && !token.isBlank()) req.header("authorization", "Bearer " + token);
                if (payload != null) {
                    req.header("content-type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(payload));
                } else {
                    req.method(method, HttpRequest.BodyPublishers.noBody());
                }
                HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                workingBase = base;
                if (res.statusCode() / 100 != 2) {
                    throw new TrueForgeException(method + " " + path + " -> HTTP " + res.statusCode() + ": "
                        + truncate(res.body(), 300), res.statusCode(), null);
                }
                return mapper.readTree(res.body());
            } catch (TrueForgeException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TrueForgeException("interrupted calling TrueForge", e);
            } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
                lastConnectFailure = e;
            } catch (Exception e) {
                throw new TrueForgeException("error calling TrueForge (" + method + " " + path + "): " + describe(e), e);
            }
        }
        throw new TrueForgeException("cannot reach TrueForge at " + baseUrl + " (" + method + " " + path + "): "
            + describe(lastConnectFailure) + " - is it running? Start it with: npx @truefoundry/trueforge@latest", lastConnectFailure);
    }

    /** "localhost" can resolve to IPv4 while a server listens only on IPv6 loopback (or vice
     * versa) and Java's HttpClient does not fall back, so try both loopback addresses explicitly. */
    static List<String> expandLocalhost(String base) {
        URI uri = URI.create(base);
        if (!"localhost".equalsIgnoreCase(uri.getHost())) return List.of(base);
        String scheme = uri.getScheme() + "://";
        String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return List.of(base, scheme + "127.0.0.1" + port + path, scheme + "[::1]" + port + path);
    }

    private static String describe(Throwable t) {
        if (t == null) return "unknown error";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }

    /** GET a paginated list endpoint and return every item, following {@code next_page_token}.
     * pageSize must be within the endpoint's maximum (turns: 25, events: 100). */
    private List<JsonNode> listAll(String path, int pageSize) {
        List<JsonNode> all = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < 50; page++) { // hard cap so a misbehaving server can't loop us forever
            String url = path + "?limit=" + pageSize
                + (pageToken != null ? "&page_token=" + java.net.URLEncoder.encode(pageToken, java.nio.charset.StandardCharsets.UTF_8) : "");
            JsonNode res = call("GET", url, null);
            res.path("data").forEach(all::add);
            pageToken = res.path("pagination").path("next_page_token").asText(null);
            if (pageToken == null || pageToken.isEmpty()) break;
        }
        return all;
    }

    private String sessionUrl(String sessionId) {
        return baseUrl + "/sessions/" + sessionId;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
