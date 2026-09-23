package cc.nitea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.nitea.internal.Engine;
import cc.nitea.internal.StackTraces;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NiteaClientTest {
    record Request(String authorization, String body) {}

    private HttpServer server;
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private String endpoint;

    @TempDir
    Path gameDir;

    @BeforeEach
    void startServer() throws IOException {
        // Every test starts like a fresh game
        Engine.reset();
        Engine.useGameDir(gameDir);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/events", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestHeaders().getFirst("Authorization"), body));
            // Player reports on a mod with a public page get a completion link back
            String json = body.contains("\"kind\":\"bug\"") ? "{\"id\":\"x\",\"completeUrl\":\"http://localhost:3000/p/testmod/report/abc_-1\"}" : "{\"id\":\"x\"}";
            byte[] reply = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private NiteaClient client(String modId) {
        NiteaConsent.grant();
        return undecidedClient(modId);
    }

    // A client whose game hasn't recorded the player's choice (unless the test set it)
    private NiteaClient undecidedClient(String modId) {
        NiteaOptions options = NiteaOptions.builder(modId)
                .sdkKey("nt_test_key")
                .endpoint(endpoint)
                .release("1.2.3")
                .gameDir(gameDir)
                .owner(NiteaClientTest.class)
                .loader("neoforge", "26.2.0.88")
                .minecraftVersion("26.2")
                .build();
        NiteaClient client = new NiteaClient(options, options.sdkKey, options.endpoint, new cc.nitea.internal.Log(modId, false));
        client.start();
        return client;
    }

    @Test
    void sendsCaughtExceptionWithBreadcrumbsAndContexts() throws Exception {
        NiteaClient client = client("testmod");
        client.addBreadcrumb("world", "Loaded dimension \"overworld\"");
        client.setTag("feature", "wand");
        assertNotNull(client.captureException(new IllegalStateException("boom", new RuntimeException("root cause"))));

        Request request = requests.poll(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("Bearer nt_test_key", request.authorization());
        String body = request.body();
        assertTrue(body.contains("\"kind\":\"error\""), body);
        assertTrue(body.contains("\"type\":\"java.lang.IllegalStateException\""), body);
        assertTrue(body.contains("\"value\":\"root cause\""), body);
        assertTrue(body.contains("\"inApp\":true"), body);
        assertTrue(body.contains("Loaded dimension \\\"overworld\\\""), body);
        assertTrue(body.contains("\"feature\":\"wand\""), body);
        assertTrue(body.contains("\"minecraft\":{\"version\":\"26.2\"}"), body);
        assertTrue(body.contains("\"release\":\"1.2.3\""), body);
        client.close(Duration.ofSeconds(1));
    }

    @Test
    void sendsPlayerReports() throws Exception {
        NiteaClient client = client("reportmod");
        client.reportBug("The wand deletes my torches");
        client.reportSuggestion("Add a blue wand");
        String bug = requests.poll(5, TimeUnit.SECONDS).body();
        String suggestion = requests.poll(5, TimeUnit.SECONDS).body();
        assertTrue(bug.contains("\"kind\":\"bug\"") && bug.contains("deletes my torches"), bug);
        assertTrue(suggestion.contains("\"kind\":\"suggestion\""), suggestion);
        client.close(Duration.ofSeconds(1));
    }

    @Test
    void passesCompletionLinkOfPlayerReports() throws Exception {
        NiteaClient client = client("linkmod");
        BlockingQueue<String> links = new LinkedBlockingQueue<>();
        client.reportBug("Torches vanish", links::add);
        assertEquals("http://localhost:3000/p/testmod/report/abc_-1", links.poll(5, TimeUnit.SECONDS));
        client.close(Duration.ofSeconds(1));
    }

    @Test
    void rateLimitsRepeatedErrors() {
        NiteaClient client = client("spammod");
        int sent = 0;
        for (int i = 0; i < 20; i++) {
            if (client.captureException(new RuntimeException("every tick")) != null) sent++;
        }
        assertEquals(3, sent);
        client.close(Duration.ofSeconds(1));
    }

    @Test
    void holdsEventsUntilThePlayerOptsIn() throws Exception {
        NiteaClient client = undecidedClient("waitmod");
        assertFalse(client.isEnabled());
        assertNotNull(client.captureException(new IllegalStateException("before choosing")));
        assertNull(requests.poll(500, TimeUnit.MILLISECONDS));

        NiteaConsent.grant();
        String body = requests.poll(5, TimeUnit.SECONDS).body();
        assertTrue(body.contains("before choosing"), body);
        assertTrue(body.contains("\"installationId\":\"" + client.installationId() + "\""), body);
        client.close(Duration.ofSeconds(1));
    }

    @Test
    void dropsEverythingWhenThePlayerOptsOut() throws Exception {
        NiteaClient client = undecidedClient("denymod");
        client.captureException(new IllegalStateException("before choosing"));
        NiteaConsent.deny();
        assertFalse(client.isEnabled());
        assertNull(client.captureException(new RuntimeException("after")));
        assertNull(client.reportBug("Please fix"));
        assertNull(requests.poll(500, TimeUnit.MILLISECONDS));
        assertNull(client.installationId());
        assertEquals(NiteaConsent.State.DENIED, NiteaConsent.state());
    }

    @Test
    void remembersTheChoiceAcrossLaunches() throws IOException {
        NiteaConsent.deny();
        Engine.reset();
        Engine.useGameDir(gameDir);
        assertEquals(NiteaConsent.State.DENIED, NiteaConsent.state());
        assertFalse(NiteaConsent.shouldAsk());
        Properties settings = settings();
        assertEquals("denied", settings.getProperty("consent"));
        assertNull(settings.getProperty("installationId"));
    }

    @Test
    void treatsTheOldOptOutSwitchAsDenied() throws IOException {
        Files.createDirectories(gameDir.resolve("config/nitea"));
        Files.writeString(gameDir.resolve("config/nitea/nitea.properties"), "enabled=false\ninstallationId=00000000-0000-0000-0000-000000000000\n");
        NiteaClient client = undecidedClient("optoutmod");
        assertFalse(client.isEnabled());
        assertNull(client.captureException(new RuntimeException()));
        assertNull(settings().getProperty("installationId"));
    }

    @Test
    void createsAnInstallationIdOnlyAfterOptIn() throws IOException {
        NiteaClient client = undecidedClient("idmod");
        assertNull(client.installationId());
        NiteaConsent.grant();
        Properties settings = settings();
        assertNotNull(client.installationId());
        assertEquals(client.installationId(), settings.getProperty("installationId"));
        assertEquals("granted", settings.getProperty("consent"));
    }

    @Test
    void honoursThePerModSwitch() throws IOException {
        Files.createDirectories(gameDir.resolve("config/nitea"));
        Files.writeString(gameDir.resolve("config/nitea/nitea.properties"), "offmod.enabled=false\n");
        NiteaClient client = client("offmod");
        assertFalse(client.isEnabled());
        assertTrue(client("onmod").isEnabled());
    }

    private Properties settings() throws IOException {
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(gameDir.resolve("config/nitea/nitea.properties"))) {
            props.load(reader);
        }
        return props;
    }

    // --- Attribution: only the mod that caused an error reports it ---

    private static Throwable thrownAt(String... frames) {
        RuntimeException e = new RuntimeException("boom");
        StackTraceElement[] stack = new StackTraceElement[frames.length];
        for (int i = 0; i < frames.length; i++) {
            int dot = frames[i].lastIndexOf('.');
            stack[i] = new StackTraceElement(frames[i].substring(0, dot), frames[i].substring(dot + 1), null, 1);
        }
        e.setStackTrace(stack);
        return e;
    }

    @Test
    void blamesTheModWhoseCodeThrew() {
        Engine.registerMod("alpha", List.of("com.alpha"), null);
        Engine.registerMod("beta", List.of("com.beta"), null);
        // beta's code throws while alpha's code called it: beta's bug
        assertEquals("beta", Engine.culprit(thrownAt("com.beta.Machine.tick", "com.alpha.Pipe.push", "net.minecraft.world.level.Level.tick")));
        // The game throws because alpha passed it something wrong: alpha's bug
        assertEquals("alpha", Engine.culprit(thrownAt("java.util.Objects.requireNonNull", "net.minecraft.world.item.ItemStack.<init>", "com.alpha.Pipe.push")));
    }

    @Test
    void ignoresErrorsOfOtherModsAndOfTheGame() {
        Engine.registerMod("alpha", List.of("com.alpha"), null);
        // A mod without Nitea threw, alpha's code is only further down the stack
        assertNull(Engine.culprit(thrownAt("org.gamma.Thing.run", "com.alpha.Pipe.push")));
        // Only game code
        assertNull(Engine.culprit(thrownAt("net.minecraft.server.MinecraftServer.tick", "java.lang.Thread.run")));
    }

    @Test
    void blamesModsForTheirMixins() {
        Engine.registerMod("alpha", List.of("com.alpha"), null);
        assertEquals("alpha", Engine.culprit(thrownAt("net.minecraft.world.entity.Entity.handler$zza000$alpha$onTick", "net.minecraft.world.level.Level.tick")));
        assertNull(Engine.culprit(thrownAt("net.minecraft.world.entity.Entity.handler$zza000$gamma$onTick", "com.alpha.Pipe.push")));
    }

    @Test
    void usesTheRootCause() {
        Engine.registerMod("alpha", List.of("com.alpha"), null);
        Engine.registerMod("beta", List.of("com.beta"), null);
        Throwable wrapper = thrownAt("com.alpha.Loader.load");
        wrapper.initCause(thrownAt("com.beta.Config.parse"));
        assertEquals("beta", Engine.culprit(wrapper));
    }

    @Test
    void attributesCrashReportText() {
        Engine.registerMod("testmod", List.of("cc.nitea.example"), null);
        Engine.registerMod("othermod", List.of("org.other"), null);
        String trace = """
                java.lang.RuntimeException: Error while ticking
                \tat TRANSFORMER/minecraft@26.2/net.minecraft.world.level.Level.tick(Level.java:512)
                Caused by: java.lang.IllegalStateException: Energy overflow
                \tat TRANSFORMER/testmod@1.0/cc.nitea.example.Generator.tick(Generator.java:131)
                \tat TRANSFORMER/othermod@1.0/org.other.Hook.call(Hook.java:3)
                \t... 2 more
                """;
        assertEquals("testmod", Engine.culprit(trace));
    }

    @Test
    void attributesByModuleWhenPackagesDontMatch() {
        Engine.registerMod("alpha", List.of("com.alpha"), "alpha_module");
        String trace = """
                java.lang.NullPointerException
                \tat TRANSFORMER/alpha_module@1.0/shaded.lib.Parser.parse(Parser.java:10)
                \tat TRANSFORMER/minecraft@26.2/net.minecraft.world.level.Level.tick(Level.java:512)
                """;
        assertEquals("alpha", Engine.culprit(trace));
    }

    @Test
    void parsesCrashReportStackTrace() {
        String report = """
                Description: Ticking entity

                java.lang.RuntimeException: Error while ticking
                \tat TRANSFORMER/minecraft@26.2/net.minecraft.world.level.Level.tick(Level.java:512)
                \tat java.base/java.lang.Thread.run(Thread.java:1583) [?:?]
                Caused by: java.lang.IllegalStateException: Energy overflow
                \tat TRANSFORMER/testmod@1.0/cc.nitea.example.Generator.tick(Generator.java:131)
                \t... 2 more
                """;
        List<Map<String, Object>> values = new StackTraces(List.of("cc.nitea.example")).parse(report);
        assertEquals(2, values.size());
        assertEquals("java.lang.RuntimeException", values.get(0).get("type"));
        assertEquals("Energy overflow", values.get(1).get("value"));
        @SuppressWarnings("unchecked")
        var frames = (List<Map<String, Object>>) ((Map<String, Object>) values.get(1).get("stacktrace")).get("frames");
        assertEquals("cc.nitea.example.Generator", frames.get(0).get("module"));
        assertEquals(131, frames.get(0).get("lineno"));
        assertEquals(true, frames.get(0).get("inApp"));
    }
}
