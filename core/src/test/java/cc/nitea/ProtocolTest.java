package cc.nitea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.nitea.compat.TestApi;
import cc.nitea.internal.Engine;
import cc.nitea.internal.Log;
import cc.nitea.internal.Signer;
import cc.nitea.internal.Transport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Protocol 2: signed requests with proof of work, and how the library follows the API's answers. */
class ProtocolTest {
    private static final String KEY = "nt_protocol_key";

    @TempDir
    Path gameDir;

    private TestApi api;

    @BeforeEach
    void start() throws Exception {
        Engine.reset();
        Engine.useGameDir(gameDir);
        Transport.resetShared();
        api = new TestApi(KEY, 8);
        NiteaConsent.grant();
    }

    @AfterEach
    void stop() {
        api.close();
        Engine.reset();
    }

    private NiteaClient client(String modId) {
        NiteaOptions options = NiteaOptions.builder(modId).sdkKey(KEY).endpoint(api.endpoint()).gameDir(gameDir).owner(ProtocolTest.class).build();
        NiteaClient client = new NiteaClient(options, options.sdkKey, options.endpoint, new Log(modId, false));
        client.start();
        return client;
    }

    private TestApi.Request next() throws InterruptedException {
        TestApi.Request request = api.requests().poll(10, TimeUnit.SECONDS);
        assertNotNull(request, "no request arrived");
        return request;
    }

    @Test
    void signsEveryRequestForItsModAndOwner() throws Exception {
        NiteaClient client = client("signedmod");
        client.captureMessage("hello", Level.INFO);
        TestApi.Request request = next();
        assertTrue(request.valid(), request.signatureError());
        assertEquals("2", request.protocol());
        assertEquals("signedmod", request.modId());
        assertEquals("cc.nitea.ProtocolTest", request.owner());
        assertTrue(request.userAgent().startsWith("nitea-java/"));
        client.close(Duration.ofSeconds(1));
    }

    @Test
    void signatureMatchesTheWebsiteTestVector() {
        // Same vector as apps/web/lib/sdk-protocol.test.ts: both sides must compute the same bytes
        byte[] body = "{\"kind\":\"error\"}".getBytes(StandardCharsets.UTF_8);
        String canonical = Signer.canonical("POST", "/api/v1/events", 1767225600000L, "examplemod", "com.example.examplemod.ExampleMod", body);
        assertEquals("nitea-v1\nPOST\n/api/v1/events\n1767225600000\nexamplemod\ncom.example.examplemod.ExampleMod\n" + VECTOR_BODY_SHA256, canonical);
        String signature = new Signer("nt_test_vector_key").sign(canonical);
        assertEquals(VECTOR_SIGNATURE, signature);
        assertEquals(VECTOR_POW_12, Signer.solve(signature, 12));
        assertTrue(Signer.verify(signature, VECTOR_POW_12, 12));
        assertFalse(Signer.verify(signature, VECTOR_POW_12, 40));
    }

    private static final String VECTOR_BODY_SHA256 = "a8eb8125c7bf13f618f00a3045f66cbc80887bec500cbd7fb114ab35e1085177";
    private static final String VECTOR_SIGNATURE = "258ebde71d7af85c3f28e50809394ccc2dbc33018044dc10bd58b20cb3e6dd7f";
    private static final long VECTOR_POW_12 = 8522;

    @Test
    void solvesMoreProofOfWorkWhenAsked() throws Exception {
        NiteaClient client = client("powmod");
        // The first answer tells the library the server wants 8 bits
        client.captureMessage("easy", Level.INFO);
        assertTrue(next().valid());
        api.powBits(14);
        api.answer(428, "{\"error\":\"More proof of work needed\",\"code\":\"pow_required\",\"bits\":14}", null);
        client.captureMessage("work harder", Level.INFO);
        TestApi.Request first = next();
        assertFalse(first.valid(), "8 bits aren't enough for 14");
        TestApi.Request second = next();
        assertTrue(second.valid(), second.signatureError());
        client.close(Duration.ofSeconds(1));
    }

    @Test
    void correctsTheGameClockFromTheServer() throws Exception {
        long serverTime = System.currentTimeMillis() + 3_600_000;
        api.answer(400, "{\"error\":\"Clock out of sync\",\"code\":\"clock_skew\",\"serverTime\":" + serverTime + "}", null);
        NiteaClient client = client("clockmod");
        client.captureMessage("what time is it", Level.INFO);
        next();
        TestApi.Request retried = next();
        assertTrue(Math.abs(retried.timestamp() - serverTime) < 60_000, "retried with " + retried.timestamp());
        assertTrue(client.isEnabled(), "a wrong clock isn't a wrong key");
        client.close(Duration.ofSeconds(1));
    }

    @Test
    void pausesWhenRateLimited() throws Exception {
        api.answer(429, "{\"error\":\"Too many events\",\"code\":\"rate_limited\"}", "120");
        NiteaClient client = client("floodmod");
        client.captureMessage("one", Level.INFO);
        next();
        client.captureMessage("two", Level.INFO);
        client.captureMessage("three", Level.INFO);
        assertNull(api.requests().poll(500, TimeUnit.MILLISECONDS), "sent while told to wait 120s");
        assertTrue(client.isEnabled(), "rate limited isn't turned off");
        client.close(Duration.ofSeconds(1));
    }

    @Test
    void stopsForAKeyOfAnotherMod() throws Exception {
        api.answer(403, "{\"error\":\"This SDK key belongs to the mod othermod, not stolenmod\",\"code\":\"mod_mismatch\"}", null);
        NiteaClient client = client("stolenmod");
        client.captureMessage("borrowed key", Level.INFO);
        next();
        waitUntilOff(client);
        assertTrue(client.turnedOffReason().contains("belongs to the mod othermod"), client.turnedOffReason());
        assertNull(client.captureMessage("again", Level.INFO));
        assertNull(api.requests().poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void stopsWhenTheApiNoLongerAcceptsThisNitea() throws Exception {
        api.answer(426, "{\"error\":\"Nitea 0.4.0 is no longer supported, update to 0.9.0 or newer\",\"code\":\"sdk_outdated\"}", null);
        NiteaClient client = client("outdatedmod");
        client.captureMessage("old", Level.INFO);
        next();
        waitUntilOff(client);
        assertTrue(client.turnedOffReason().contains("no longer supported"), client.turnedOffReason());
    }

    @Test
    void stopsForAnInvalidKeyButNotForOneBadSignature() throws Exception {
        api.answer(400, "{\"error\":\"Request signature doesn't match\",\"code\":\"bad_signature\"}", null);
        NiteaClient client = client("sigmod");
        client.captureMessage("first", Level.INFO);
        next();
        Thread.sleep(200);
        assertTrue(client.isEnabled());

        api.answer(401, "{\"error\":\"Invalid or missing SDK key\",\"code\":\"invalid_key\"}", null);
        client.captureMessage("second", Level.INFO);
        next();
        waitUntilOff(client);
    }

    private static void waitUntilOff(NiteaClient client) throws InterruptedException {
        for (int i = 0; i < 100 && client.isEnabled(); i++) Thread.sleep(50);
        assertFalse(client.isEnabled());
        assertNotNull(client.turnedOffReason());
    }
}
