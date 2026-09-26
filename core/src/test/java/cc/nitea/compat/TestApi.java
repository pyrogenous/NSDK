package cc.nitea.compat;

import cc.nitea.internal.Signer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A stand-in for {@code POST /api/v1/events} that checks requests the way the website does
 * ({@code apps/web/lib/sdk-protocol.ts}): signature, proof of work, mod ID. Scripted answers can be queued to test
 * how the library reacts.
 */
public final class TestApi implements AutoCloseable {
    public record Request(String protocol, String modId, String owner, long timestamp, String signatureError, String body, String userAgent) {
        public boolean valid() {
            return signatureError == null;
        }
    }

    public record Answer(int status, String body, String retryAfter) {}

    private final HttpServer server;
    private final String sdkKey;
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private final Deque<Answer> script = new ArrayDeque<>();
    private volatile int powBits;

    public TestApi(String sdkKey, int powBits) throws IOException {
        this.sdkKey = sdkKey;
        this.powBits = powBits;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/events", this::handle);
        server.start();
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public BlockingQueue<Request> requests() {
        return requests;
    }

    public void powBits(int bits) {
        powBits = bits;
    }

    /** The next requests get these answers, in order, instead of 202. */
    public synchronized void answer(int status, String body, String retryAfter) {
        script.add(new Answer(status, body, retryAfter));
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String protocol = exchange.getRequestHeaders().getFirst("X-Nitea-Protocol");
        String modId = exchange.getRequestHeaders().getFirst("X-Nitea-Mod");
        String owner = exchange.getRequestHeaders().getFirst("X-Nitea-Owner");
        String error = "2".equals(protocol) ? verify(exchange, body, modId, owner) : "unsigned";
        String timestamp = exchange.getRequestHeaders().getFirst("X-Nitea-Timestamp");
        requests.add(new Request(protocol, modId, owner, timestamp != null ? Long.parseLong(timestamp) : 0, error, new String(body, StandardCharsets.UTF_8), exchange.getRequestHeaders().getFirst("User-Agent")));

        Answer answer;
        synchronized (this) {
            answer = script.poll();
        }
        if (answer == null) {
            String text = new String(body, StandardCharsets.UTF_8);
            answer = new Answer(202, text.contains("\"kind\":\"bug\"") ? "{\"id\":\"x\",\"completeUrl\":\"http://localhost/r/abc\"}" : "{\"id\":\"x\"}", null);
        }
        if (answer.retryAfter() != null) exchange.getResponseHeaders().add("Retry-After", answer.retryAfter());
        exchange.getResponseHeaders().add("X-Nitea-Pow-Bits", Integer.toString(powBits));
        byte[] reply = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(answer.status(), reply.length);
        exchange.getResponseBody().write(reply);
        exchange.close();
    }

    private String verify(HttpExchange exchange, byte[] body, String modId, String owner) {
        String timestamp = exchange.getRequestHeaders().getFirst("X-Nitea-Timestamp");
        String signatureHeader = exchange.getRequestHeaders().getFirst("X-Nitea-Signature");
        String pow = exchange.getRequestHeaders().getFirst("X-Nitea-Pow");
        if (timestamp == null || signatureHeader == null || pow == null || modId == null) return "missing headers";
        if (!signatureHeader.startsWith(Signer.VERSION + "=")) return "unknown signature version";
        String signature = signatureHeader.substring(Signer.VERSION.length() + 1);
        String expected = new Signer(sdkKey).sign(Signer.canonical("POST", "/api/v1/events", Long.parseLong(timestamp), modId, owner, body));
        if (!expected.equals(signature)) return "bad signature";
        if (!Signer.verify(signature, Long.parseLong(pow), powBits)) return "not enough proof of work";
        return null;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
