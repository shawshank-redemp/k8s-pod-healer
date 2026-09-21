package com.sentinel.trueforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.detection.DetectionModule;
import com.sentinel.detection.PodInfo;
import com.sentinel.trueforge.TrueForgeAgentTrigger.Outcome;
import com.sentinel.trueforge.TrueForgeAgentTrigger.Run;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises TrueForgeAgentTrigger against a fake TrueForge HTTP API (shapes captured from a real one). */
class TrueForgeAgentTriggerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------ fake TrueForge server

    static class FakeTrueForge {
        final HttpServer server;
        final List<JsonNode> sessionBodies = new CopyOnWriteArrayList<>();
        final List<JsonNode> turnBodies = new CopyOnWriteArrayList<>();
        final Map<String, Deque<String>> turnGets = new ConcurrentHashMap<>();
        final AtomicInteger turnCounter = new AtomicInteger(0);
        volatile String turnsListJson = "{\"data\":[]}";
        /** When set, the turns list is served page by page; page N's token is "pN" (page 0 has none). */
        volatile List<String> turnsPages = null;
        final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

        FakeTrueForge() throws IOException {
            this("127.0.0.1");
        }

        FakeTrueForge(String bindHost) throws IOException {
            server = HttpServer.create(new InetSocketAddress(bindHost, 0), 0);
            server.createContext("/api/v1/", this::handle);
            server.start();
        }

        int port() { return server.getAddress().getPort(); }

        String url() { return "http://127.0.0.1:" + port(); }

        void whenGetTurn(String turnId, String... responses) {
            Deque<String> q = new ArrayDeque<>(List.of(responses));
            turnGets.put(turnId, q);
        }

        private void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            lastAuthHeader.set(ex.getRequestHeaders().getFirst("authorization"));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            // Enforce the real API's page-size caps (turns: 25, events: 100) - a client that
            // asks for more gets a 400, exactly like real TrueForge.
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : -1;
            boolean isTurnsList = method.equals("GET") && path.equals("/api/v1/sessions/sess-1/turns");
            boolean isEventsList = method.equals("GET") && path.matches("/api/v1/sessions/sess-1/turns/[^/]+/events");
            if ((isTurnsList && limit > 25) || (isEventsList && limit > 100)) {
                respond(ex, 400, "{\"error\":{\"message\":\"Too big: limit\"}}");
                return;
            }

            String out;
            if (method.equals("POST") && path.equals("/api/v1/sessions")) {
                sessionBodies.add(MAPPER.readTree(body));
                out = "{\"data\":{\"id\":\"sess-1\"}}";
            } else if (method.equals("POST") && path.equals("/api/v1/sessions/sess-1/turns")) {
                turnBodies.add(MAPPER.readTree(body));
                out = "{\"data\":{\"id\":\"turn-" + turnCounter.incrementAndGet() + "\",\"state\":{\"status\":\"running\"}}}";
            } else if (isTurnsList) {
                List<String> pages = turnsPages;
                if (pages == null) {
                    out = turnsListJson;
                } else {
                    String token = query.get("page_token");
                    int idx = token == null ? 0 : Integer.parseInt(token.substring(1));
                    out = pages.get(idx);
                }
            } else if (method.equals("GET") && path.matches("/api/v1/sessions/sess-1/turns/[^/]+/events")) {
                out = EVENTS_JSON;
            } else if (method.equals("GET") && path.matches("/api/v1/sessions/sess-1/turns/[^/]+")) {
                String turnId = path.substring(path.lastIndexOf('/') + 1);
                Deque<String> q = turnGets.get(turnId);
                if (q == null) { respond(ex, 404, "{\"error\":{\"message\":\"no turn\"}}"); return; }
                out = q.size() > 1 ? q.poll() : q.peek(); // last response repeats
            } else {
                respond(ex, 404, "{\"error\":{\"message\":\"not found\"}}");
                return;
            }
            respond(ex, 200, out);
        }

        private static Map<String, String> parseQuery(String raw) {
            Map<String, String> q = new ConcurrentHashMap<>();
            if (raw == null) return q;
            for (String pair : raw.split("&")) {
                int i = pair.indexOf('=');
                if (i > 0) q.put(pair.substring(0, i), java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
            return q;
        }

        private static void respond(HttpExchange ex, int code, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }

        void stop() { server.stop(0); }
    }

    // Shapes below were captured from a real TrueForge run.
    static final String RUNNING = "{\"data\":{\"id\":\"t\",\"state\":{\"status\":\"running\"}}}";

    static final String PAUSED_FOR_APPROVAL = """
        {"data":{"id":"turn-1","state":{"status":"done","output":null,"required_actions":[
          {"type":"tool.approval_required","id":"a1","thread_id":"main",
           "tool_calls":[{"id":"call_4","source_event_id":"ev-src"}]}]}}}""";

    static final String DONE = """
        {"data":{"state":{"status":"done","required_actions":[],
          "output":{"content":"Fixed the deployment."}}}}""";

    static final String EVENTS_JSON;
    static {
        try {
            String args = MAPPER.writeValueAsString(Map.of(
                "mcp_server", "k8s-prod", "tool_name", "kubectl_patch",
                "input", Map.of("resourceType", "deployment", "name", "sentinel-demo-app",
                    "namespace", "default", "patchData", Map.of("env", "REQUIRED_CONFIG"))));
            EVENTS_JSON = MAPPER.writeValueAsString(Map.of("data", List.of(
                Map.of("type", "turn.created", "id", "ev-0"),
                Map.of("type", "model.message", "id", "ev-src", "tool_calls", List.of(
                    Map.of("id", "call_4", "type", "function",
                        "function", Map.of("name", "call_tool", "arguments", args)))))));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ------------------------------------------------------------------ fixtures

    FakeTrueForge tf;

    @BeforeEach
    void setUp() throws IOException {
        tf = new FakeTrueForge();
    }

    @AfterEach
    void tearDown() {
        tf.stop();
        TrueForgeAgentTrigger.setDefault(null);
        DetectionModule.setDiagnosisModule(null);
        DetectionModule.processedPods.clear();
    }

    private static PodInfo pod(String name) {
        return new PodInfo("default/" + name, name, "default", "Running", "CrashLoopBackOff", 4, "HIGH", null);
    }

    private TrueForgeAgentTrigger trigger(ApprovalHandler handler) {
        return new TrueForgeAgentTrigger(tf.url(), "sentinel-agent", null, handler,
            Duration.ofMillis(20), Duration.ofSeconds(10));
    }

    private static Outcome await(Run run) throws Exception {
        return run.completion().get(10, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void startsSessionAndAlertTurnWithPodFacts() throws Exception {
        tf.whenGetTurn("turn-1", DONE);
        Run run = trigger(a -> ApprovalHandler.Decision.deny("unused")).start(pod("crashy"));

        assertNotNull(run);
        assertEquals("sess-1", run.sessionId());
        assertEquals(Outcome.COMPLETED, await(run));

        JsonNode session = tf.sessionBodies.get(0);
        assertEquals("sentinel-agent", session.path("agent").path("name").asText());
        assertEquals("default/crashy", session.path("metadata").path("pod").asText());

        JsonNode turn = tf.turnBodies.get(0);
        assertFalse(turn.path("stream").asBoolean(true), "must be non-streaming so the call returns immediately");
        assertEquals("user.message", turn.path("input").get(0).path("type").asText());
        String message = turn.path("input").get(0).path("content").asText();
        for (String expected : List.of("crashy", "default", "CrashLoopBackOff", "Running", "4", "HIGH", "sentinel-sandbox")) {
            assertTrue(message.contains(expected), "alert message should mention " + expected + " but was:\n" + message);
        }
    }

    @Test
    void approvedRunResumesAgentWithAllowDecision() throws Exception {
        tf.whenGetTurn("turn-1", RUNNING, PAUSED_FOR_APPROVAL);
        tf.whenGetTurn("turn-2", RUNNING, DONE);
        AtomicReference<PendingApproval> seen = new AtomicReference<>();

        Run run = trigger(a -> { seen.set(a); return ApprovalHandler.Decision.allow(); }).start(pod("crashy"));
        assertEquals(Outcome.COMPLETED, await(run));

        PendingApproval a = seen.get();
        assertEquals("k8s-prod", a.connector());
        assertEquals("kubectl_patch", a.toolName());
        assertTrue(a.input().contains("REQUIRED_CONFIG") && a.input().contains("sentinel-demo-app"),
            "human must see what will change: " + a.input());
        assertEquals("call_4", a.toolCallId());
        assertTrue(a.sessionUrl().endsWith("/sessions/sess-1"));

        assertEquals(2, tf.turnBodies.size());
        JsonNode item = tf.turnBodies.get(1).path("input").get(0);
        assertEquals("user.tool_approval", item.path("type").asText());
        assertEquals("main", item.path("thread_id").asText());
        assertEquals("call_4", item.path("tool_call_id").asText());
        assertEquals("allow", item.path("approval").path("status").asText());
    }

    @Test
    void deniedRunSendsDenyWithReasonAndNothingElse() throws Exception {
        tf.whenGetTurn("turn-1", PAUSED_FOR_APPROVAL);
        tf.whenGetTurn("turn-2", DONE);

        Run run = trigger(a -> ApprovalHandler.Decision.deny("too risky")).start(pod("crashy"));
        assertEquals(Outcome.COMPLETED, await(run));

        JsonNode approval = tf.turnBodies.get(1).path("input").get(0).path("approval");
        assertEquals("deny", approval.path("status").asText());
        assertEquals("too risky", approval.path("reason").asText());
    }

    @Test
    void deferredApprovalPostsNothingAndFollowsTheUiResume() throws Exception {
        tf.whenGetTurn("turn-1", PAUSED_FOR_APPROVAL);
        tf.whenGetTurn("turn-2", DONE);
        tf.turnsListJson = "{\"data\":[{\"id\":\"turn-1\",\"previous_turn_id\":null}]}";
        CountDownLatch asked = new CountDownLatch(1);

        Run run = trigger(a -> { asked.countDown(); return ApprovalHandler.Decision.defer(); }).start(pod("crashy"));
        assertTrue(asked.await(5, TimeUnit.SECONDS));
        Thread.sleep(150);
        assertEquals(1, tf.turnBodies.size(), "deferring must not post any decision of its own");
        assertFalse(run.completion().isDone(), "still waiting for the human");

        // A human approves in the TrueForge UI, which creates turn-2 chained after turn-1.
        tf.turnsListJson = "{\"data\":[{\"id\":\"turn-1\",\"previous_turn_id\":null},{\"id\":\"turn-2\",\"previous_turn_id\":\"turn-1\"}]}";
        assertEquals(Outcome.COMPLETED, await(run));
    }

    @Test
    void findsTheUiResumedTurnEvenWhenItIsOnALaterPage() throws Exception {
        tf.whenGetTurn("turn-1", PAUSED_FOR_APPROVAL);
        tf.whenGetTurn("turn-2", DONE);
        // Page 0 has 25 unrelated turns; the human's resume (turn-2 after turn-1) is only on page 1.
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 25; i++) filler.append(i > 0 ? "," : "").append("{\"id\":\"other-").append(i).append("\",\"previous_turn_id\":\"nope\"}");
        tf.turnsPages = List.of(
            "{\"data\":[" + filler + "],\"pagination\":{\"limit\":25,\"next_page_token\":\"p1\"}}",
            "{\"data\":[{\"id\":\"turn-2\",\"previous_turn_id\":\"turn-1\"}],\"pagination\":{\"limit\":25}}");

        Run run = trigger(a -> ApprovalHandler.Decision.defer()).start(pod("crashy"));
        assertEquals(Outcome.COMPLETED, await(run));
        assertEquals(1, tf.turnBodies.size(), "deferring posts nothing; the resume came from the UI");
    }

    @Test
    void secondAlertForSamePodWhileRunInFlightIsSuppressed() throws Exception {
        // The fake numbers turns globally: crashy=turn-1, other-pod=turn-2, crashy's resume=turn-3.
        tf.whenGetTurn("turn-1", PAUSED_FOR_APPROVAL);
        tf.whenGetTurn("turn-2", DONE);
        tf.whenGetTurn("turn-3", DONE);
        CountDownLatch release = new CountDownLatch(1);
        TrueForgeAgentTrigger t = trigger(a -> {
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return ApprovalHandler.Decision.allow();
        });

        Run first = t.start(pod("crashy"));
        assertNotNull(first);
        assertNull(t.start(pod("crashy")), "a paused run must not spawn a second agent run for the same pod");
        assertEquals(1, tf.sessionBodies.size());
        assertNotNull(t.start(pod("other-pod")), "a different pod is independent");

        release.countDown();
        assertEquals(Outcome.COMPLETED, await(first));
    }

    @Test
    void failedTurnReportsFailedOutcome() throws Exception {
        tf.whenGetTurn("turn-1", "{\"data\":{\"state\":{\"status\":\"error\",\"message\":\"model unavailable\"}}}");
        Run run = trigger(a -> ApprovalHandler.Decision.deny("x")).start(pod("crashy"));
        assertEquals(Outcome.FAILED, await(run));
    }

    @Test
    void unreachableTrueForgeThrowsAndDoesNotLeaveTheRunMarkedActive() {
        tf.stop();
        TrueForgeAgentTrigger t = trigger(a -> ApprovalHandler.Decision.deny("x"));
        assertThrows(TrueForgeException.class, () -> t.start(pod("crashy")));
        // If the failed start left the pod marked "in flight", this would return null instead of throwing.
        assertThrows(TrueForgeException.class, () -> t.start(pod("crashy")));
    }

    @Test
    void reachesAServerListeningOnlyOnIpv6LoopbackViaLocalhost() throws Exception {
        // Regression: TrueForge (Node) listens on [::1] only, while Java resolves "localhost" to
        // 127.0.0.1 first and doesn't fall back - so the very first call was refused.
        FakeTrueForge ipv6Only;
        try {
            ipv6Only = new FakeTrueForge("::1");
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.abort("no IPv6 loopback on this machine: " + e);
            return;
        }
        try {
            ipv6Only.whenGetTurn("turn-1", DONE);
            TrueForgeAgentTrigger t = new TrueForgeAgentTrigger("http://localhost:" + ipv6Only.port(),
                "sentinel-agent", null, a -> ApprovalHandler.Decision.deny("x"),
                Duration.ofMillis(20), Duration.ofSeconds(10));
            assertEquals(Outcome.COMPLETED, await(t.start(pod("crashy"))));
        } finally {
            ipv6Only.stop();
        }
    }

    @Test
    void localhostIsExpandedToBothLoopbackAddressesButOtherHostsAreLeftAlone() {
        assertEquals(List.of("http://localhost:8790", "http://127.0.0.1:8790", "http://[::1]:8790"),
            TrueForgeAgentTrigger.expandLocalhost("http://localhost:8790"));
        assertEquals(List.of("https://trueforge.example.com"),
            TrueForgeAgentTrigger.expandLocalhost("https://trueforge.example.com"));
    }

    @Test
    void unreachableErrorNamesTheProblemInsteadOfNull() {
        tf.stop();
        TrueForgeException e = assertThrows(TrueForgeException.class,
            () -> trigger(a -> ApprovalHandler.Decision.deny("x")).start(pod("crashy")));
        assertTrue(e.getMessage().contains("cannot reach TrueForge") && !e.getMessage().contains(": null"), e.getMessage());
    }

    @Test
    void bearerTokenIsSentWhenConfigured() throws Exception {
        tf.whenGetTurn("turn-1", DONE);
        TrueForgeAgentTrigger t = new TrueForgeAgentTrigger(tf.url(), "sentinel-agent", "tok-123",
            a -> ApprovalHandler.Decision.deny("x"), Duration.ofMillis(20), Duration.ofSeconds(10));
        assertEquals(Outcome.COMPLETED, await(t.start(pod("crashy"))));
        assertEquals("Bearer tok-123", tf.lastAuthHeader.get());
    }

    // ------------------------------------------------------------------ DetectionModule handoff

    @Test
    void detectionModuleHandsFailuresToTrueForgeByDefault() throws Exception {
        tf.whenGetTurn("turn-1", DONE);
        TrueForgeAgentTrigger.setDefault(trigger(a -> ApprovalHandler.Decision.deny("x")));

        DetectionModule.triggerDiagnosis(pod("crashy"));

        long deadline = System.currentTimeMillis() + 5000;
        while (tf.turnBodies.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, tf.sessionBodies.size(), "the failure should have opened a TrueForge session");
        assertTrue(tf.turnBodies.get(0).path("input").get(0).path("content").asText().contains("crashy"));
    }

    @Test
    void triggerFailureClearsDedupSoTheNextEventRetries() throws Exception {
        tf.stop();
        TrueForgeAgentTrigger.setDefault(trigger(a -> ApprovalHandler.Decision.deny("x")));
        DetectionModule.processedPods.put("default/crashy", System.currentTimeMillis() / 1000.0);

        DetectionModule.triggerDiagnosis(pod("crashy")); // must not throw into the watcher

        long deadline = System.currentTimeMillis() + 5000;
        while (DetectionModule.processedPods.containsKey("default/crashy") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(DetectionModule.processedPods.containsKey("default/crashy"),
            "a failed handoff must not suppress retries for the 5 minute dedup window");
    }

    @Test
    void sandboxAndClusterInfrastructureNamespacesAreNeverWatched() {
        assertTrue(DetectionModule.EXCLUDED_NAMESPACES.contains("sentinel-sandbox"),
            "a failing trial pod in the sandbox must not trigger another agent run");
        assertTrue(DetectionModule.EXCLUDED_NAMESPACES.contains("local-path-storage"),
            "Kind's storage provisioner is infrastructure, not something to remediate");
    }

    // ------------------------------------------------------------------ workload dedup + deny cooldown

    /** A pod owned by the Deployment "web" (via ReplicaSet web-abc123); all replicas share a workload. */
    private static PodInfo replica(String podName) {
        io.fabric8.kubernetes.api.model.Pod p = new io.fabric8.kubernetes.api.model.PodBuilder()
            .withNewMetadata().withName(podName).withNamespace("default")
            .withLabels(Map.of("pod-template-hash", "abc123"))
            .withOwnerReferences(new io.fabric8.kubernetes.api.model.OwnerReferenceBuilder()
                .withApiVersion("apps/v1").withKind("ReplicaSet").withName("web-abc123")
                .withUid("u1").withController(true).build())
            .endMetadata().build();
        return new PodInfo("default/" + podName, podName, "default", "Running", "CrashLoopBackOff", 4, "HIGH", p);
    }

    @Test
    void threeCrashingReplicasOfOneDeploymentStartOneRunAndOnePrompt() throws Exception {
        tf.whenGetTurn("turn-1", PAUSED_FOR_APPROVAL);
        tf.whenGetTurn("turn-2", DONE);
        AtomicInteger prompts = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        TrueForgeAgentTrigger t = trigger(a -> {
            prompts.incrementAndGet();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return ApprovalHandler.Decision.allow();
        });

        Run first = t.start(replica("web-abc123-aaaaa"));
        assertNotNull(first);
        assertNull(t.start(replica("web-abc123-bbbbb")), "same Deployment: already being handled");
        assertNull(t.start(replica("web-abc123-ccccc")), "same Deployment: already being handled");
        assertEquals(1, tf.sessionBodies.size(), "one problem, one agent session");
        assertEquals("default/Deployment/web", tf.sessionBodies.get(0).path("metadata").path("workload").asText());
        assertTrue(tf.turnBodies.get(0).path("input").get(0).path("content").asText().contains("default/Deployment/web"),
            "the agent is told which workload to fix");

        release.countDown();
        assertEquals(Outcome.COMPLETED, await(first));
        assertEquals(1, prompts.get(), "the human is asked once, not once per replica");
    }

    @Test
    void aDeniedFixSilencesThatWorkloadButNotOthers() throws Exception {
        tf.whenGetTurn("turn-1", PAUSED_FOR_APPROVAL);
        tf.whenGetTurn("turn-2", DONE);
        TrueForgeAgentTrigger t = trigger(a -> ApprovalHandler.Decision.deny("not now"));

        assertEquals(Outcome.COMPLETED, await(t.start(replica("web-abc123-aaaaa"))));

        // The pod is still failing and a replacement replica alerts: the human already said no.
        assertNull(t.start(replica("web-abc123-zzzzz")), "denial must suppress re-alerting for the workload");
        assertEquals(1, tf.sessionBodies.size(), "no new session was opened");

        tf.whenGetTurn("turn-3", DONE);
        assertNotNull(t.start(pod("unrelated")), "a different workload is unaffected");
    }

    @Test
    void anApprovedFixDoesNotSilenceTheWorkload() throws Exception {
        tf.whenGetTurn("turn-1", PAUSED_FOR_APPROVAL);
        tf.whenGetTurn("turn-2", DONE);
        tf.whenGetTurn("turn-3", DONE);
        TrueForgeAgentTrigger t = trigger(a -> ApprovalHandler.Decision.allow());

        assertEquals(Outcome.COMPLETED, await(t.start(replica("web-abc123-aaaaa"))));
        // If it fails again later, that is a new problem and must be handled.
        assertNotNull(t.start(replica("web-abc123-bbbbb")));
    }

    @Test
    void theDenyCooldownExpires() throws Exception {
        tf.whenGetTurn("turn-1", PAUSED_FOR_APPROVAL);
        tf.whenGetTurn("turn-2", DONE);
        tf.whenGetTurn("turn-3", DONE);
        TrueForgeAgentTrigger t = new TrueForgeAgentTrigger(tf.url(), "sentinel-agent", null,
            a -> ApprovalHandler.Decision.deny("no"), Duration.ofMillis(20), Duration.ofSeconds(10),
            Duration.ofMillis(300));

        assertEquals(Outcome.COMPLETED, await(t.start(replica("web-abc123-aaaaa"))));
        assertNull(t.start(replica("web-abc123-bbbbb")), "inside the cooldown");
        Thread.sleep(400);
        assertNotNull(t.start(replica("web-abc123-bbbbb")), "cooldown over: a still-failing workload is escalated again");
    }

    @Test
    void aDeferredDecisionIsNotTreatedAsADenial() throws Exception {
        tf.whenGetTurn("turn-1", PAUSED_FOR_APPROVAL);
        tf.whenGetTurn("turn-2", DONE);
        tf.turnsListJson = "{\"data\":[{\"id\":\"turn-1\",\"previous_turn_id\":null},{\"id\":\"turn-2\",\"previous_turn_id\":\"turn-1\"}]}";
        tf.whenGetTurn("turn-3", DONE);
        TrueForgeAgentTrigger t = trigger(a -> ApprovalHandler.Decision.defer());

        assertEquals(Outcome.COMPLETED, await(t.start(replica("web-abc123-aaaaa"))));
        assertNotNull(t.start(replica("web-abc123-bbbbb")), "deferring to the UI must not start a cooldown");
    }
}
