package cc.nitea.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * Sends events to {@code POST /api/v1/events} on one background daemon thread, so the game thread never waits on
 * the network. Failed sends are retried with backoff; every event carries its own ID, so a retry of an event the
 * server already stored is ignored by the API.
 *
 * <p>Every request is signed and carries a proof of work (see {@link Signer}). The API's answers are followed:
 * <ul>
 *   <li>429: sending pauses for as long as {@code Retry-After} says</li>
 *   <li>428: the server wants more proof of work; solved and sent again</li>
 *   <li>400 {@code clock_skew}: the game's clock is off; corrected from the server's time and sent again</li>
 *   <li>401 (bad key), 403 (blocked project, key of another mod), 426 (Nitea too old): reporting stops for this
 *       mod until the next launch, with a message in the log saying why</li>
 * </ul>
 *
 * <p>Uses {@link HttpURLConnection}, the HTTP client every Java version has, so the library runs on Java 8.
 */
public final class Transport {
    /** Version of the wire protocol, sent as {@code X-Nitea-Protocol}. 1 was the unsigned protocol of Nitea 0.3 and older. */
    public static final int PROTOCOL = 2;
    static final String PATH = "/api/v1/events";

    private static final int QUEUE_SIZE = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 15_000;
    // A Retry-After up to this long is waited out; a longer one drops the event and pauses sending
    private static final long MAX_RETRY_WAIT = 30_000;
    private static final int DEFAULT_POW_BITS = 16;

    // Shared by every mod in this copy of the library: they talk to the same server
    private static final AtomicInteger POW_BITS = new AtomicInteger(DEFAULT_POW_BITS);
    private static final AtomicLong CLOCK_OFFSET = new AtomicLong();

    private final URL eventsUrl;
    private final String modId;
    private final String owner;
    private final String authorization;
    private final Signer signer;
    private final Log log;
    private final ExecutorService worker;
    private volatile String disabledReason;
    private volatile long pausedUntil;

    /**
     * @param owner the mod's main class ({@code NiteaOptions.owner}), checked by the server against the Java package
     *              registered for the project; null when the mod didn't set one
     */
    public Transport(String modId, String owner, String endpoint, String sdkKey, Log log) {
        try {
            this.eventsUrl = new URL(endpoint.replaceAll("/+$", "") + PATH);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid Nitea endpoint: " + endpoint, e);
        }
        this.modId = modId;
        this.owner = owner;
        this.authorization = "Bearer " + sdkKey;
        this.signer = new Signer(sdkKey);
        this.log = log;
        // Bounded queue: if the API is unreachable for a long time, new events are dropped instead of piling up
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_SIZE), runnable -> {
            Thread thread = new Thread(runnable, "Nitea-" + modId);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardPolicy());
    }

    /** False once the API refused this mod for good (bad key, blocked, wrong mod, Nitea too old) until the next launch. */
    public boolean usable() {
        return disabledReason == null;
    }

    /** Why the API refused this mod, or null. */
    public String disabledReason() {
        return disabledReason;
    }

    /** Queues an event for sending in the background. */
    public void send(String json) {
        send(json, null);
    }

    /** Queues an event; {@code onAccepted} receives the API's JSON reply once the event is stored. */
    public void send(String json, Consumer<String> onAccepted) {
        if (!usable()) return;
        try {
            worker.execute(() -> deliver(json, true, onAccepted));
        } catch (RejectedExecutionException ignored) {
            // Shut down
        }
    }

    /** Runs a task on the sending thread (used for slow startup work like scanning crash reports). */
    public void background(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Shut down
        }
    }

    /**
     * Sends an event on the calling thread, without retries. Used when the JVM may be about to exit (an uncaught
     * exception), where a queued event would be lost.
     */
    public void sendNow(String json) {
        if (usable()) deliver(json, false, null);
    }

    /** Waits up to {@code timeout} for queued events to be sent, then stops the sending thread. */
    public void close(Duration timeout) {
        worker.shutdown();
        try {
            worker.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deliver(String json, boolean retry, Consumer<String> onAccepted) {
        if (System.currentTimeMillis() < pausedUntil) {
            log.debug("Event dropped: the Nitea API asked to slow down");
            return;
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        int attempts = retry ? MAX_ATTEMPTS : 1;
        // Answers fixed by sending again right away (clock, proof of work) don't use up an attempt, a few times
        int corrections = 2;
        for (int attempt = 1; attempt <= attempts && usable(); attempt++) {
            try {
                Reply reply = post(body);
                log.debug("POST " + eventsUrl + " -> " + reply.status + " " + reply.body);
                if (reply.status >= 200 && reply.status < 300) {
                    accepted(reply.body, onAccepted);
                    return;
                }
                String code = Json.readString(reply.body, "code");
                if (reply.status == 400 && "clock_skew".equals(code) && corrections-- > 0) {
                    Long serverTime = Json.readLong(reply.body, "serverTime");
                    if (serverTime != null) CLOCK_OFFSET.set(serverTime - System.currentTimeMillis());
                    attempt--;
                    continue;
                }
                if (reply.status == 428 && corrections-- > 0) {
                    Long bits = Json.readLong(reply.body, "bits");
                    if (bits == null || bits < 0 || bits > Signer.MAX_POW_BITS) {
                        log.warn("The Nitea API asked for too much proof of work (" + bits + " bits), event dropped");
                        return;
                    }
                    POW_BITS.set(bits.intValue());
                    attempt--;
                    continue;
                }
                if (reply.status == 429) {
                    long retryAfter = reply.retryAfterMillis > 0 ? reply.retryAfterMillis : 60_000;
                    if (!retry || attempt == attempts || retryAfter > MAX_RETRY_WAIT) {
                        pausedUntil = System.currentTimeMillis() + retryAfter;
                        log.warn("The Nitea API is rate limiting this mod; events are dropped for the next " + retryAfter / 1000 + "s");
                        return;
                    }
                    sleep(retryAfter);
                    continue;
                }
                if (refused(reply.status, code, reply.body)) return;
                // Other 4xx: the event itself is invalid or too large, sending it again will not help
                if (reply.status < 500) {
                    log.warn("Event rejected (" + reply.status + "): " + reply.body);
                    return;
                }
                if (attempt == attempts) log.warn("The Nitea API kept failing (" + reply.status + "), event dropped");
            } catch (IOException e) {
                if (attempt == attempts) log.warn("Could not reach " + eventsUrl + ", event dropped: " + e);
                else log.debug("Send failed, retrying: " + e);
            }
            if (attempt < attempts) sleep(attempt * attempt * 2000L);
        }
    }

    // Answers that stop reporting for this mod until the next launch. True when the reply was one of them.
    private boolean refused(int status, String code, String body) {
        String error = Json.readString(body, "error");
        // HttpURLConnection can't read the body of a 401 sent in streaming mode, so a 401 always means a bad key
        if (status == 401) {
            disable("The Nitea API rejected the SDK key; reporting is off until the next launch. Check the key in your project settings.", false);
            return true;
        }
        if (status == 403 && "mod_mismatch".equals(code)) {
            disable((error != null ? error : "This SDK key belongs to another mod") + ". Reporting is off for " + modId
                    + ": check the mod ID and Java package in your project settings on nitea.cc.", true);
            return true;
        }
        if (status == 403 && "project_blocked".equals(code)) {
            String reason = Json.readString(body, "reason");
            disable("This project has been blocked by Nitea moderators" + (reason != null && !reason.isEmpty() ? " (" + reason + ")" : "") + "; reporting is off.", false);
            return true;
        }
        if (status == 426) {
            disable((error != null ? error : "This version of Nitea is no longer accepted by the API")
                    + ". Reporting is off; the mod keeps working. Mod author: update Nitea.", true);
            return true;
        }
        return false;
    }

    private void disable(String message, boolean authorMustFix) {
        if (disabledReason != null) return;
        disabledReason = message;
        if (authorMustFix) log.error(message);
        else log.warn(message);
    }

    private void accepted(String reply, Consumer<String> onAccepted) {
        if (onAccepted == null) return;
        try {
            onAccepted.accept(reply);
        } catch (RuntimeException e) {
            log.warn("Report callback failed: " + e);
        }
    }

    private Reply post(byte[] body) throws IOException {
        long timestamp = System.currentTimeMillis() + CLOCK_OFFSET.get();
        String signature = signer.sign(Signer.canonical("POST", PATH, timestamp, modId, owner, body));
        long nonce = Signer.solve(signature, POW_BITS.get());

        HttpURLConnection connection = (HttpURLConnection) eventsUrl.openConnection();
        if (connection instanceof HttpsURLConnection) {
            SSLSocketFactory factory = Tls.socketFactory();
            if (factory != null) ((HttpsURLConnection) connection).setSSLSocketFactory(factory);
        }
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", authorization);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "nitea-java/" + Version.get());
        connection.setRequestProperty("X-Nitea-Protocol", Integer.toString(PROTOCOL));
        connection.setRequestProperty("X-Nitea-Mod", modId);
        if (owner != null) connection.setRequestProperty("X-Nitea-Owner", owner);
        connection.setRequestProperty("X-Nitea-Timestamp", Long.toString(timestamp));
        connection.setRequestProperty("X-Nitea-Signature", Signer.VERSION + "=" + signature);
        connection.setRequestProperty("X-Nitea-Pow", Long.toString(nonce));
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        int status;
        try {
            status = connection.getResponseCode();
        } catch (HttpRetryException e) {
            // A 401 while streaming the body: HttpURLConnection won't give the reply
            connection.disconnect();
            return new Reply(e.responseCode(), "", 0);
        }
        String reply = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        long retryAfter = retryAfter(connection.getHeaderField("Retry-After"));
        String bits = connection.getHeaderField("X-Nitea-Pow-Bits");
        connection.disconnect();
        // The server says how much work it wants on every answer, so the next request gets it right the first time
        if (bits != null) {
            try {
                int wanted = Integer.parseInt(bits.trim());
                if (wanted >= 0 && wanted <= Signer.MAX_POW_BITS) POW_BITS.set(wanted);
            } catch (NumberFormatException ignored) {
                // Keep the current difficulty
            }
        }
        return new Reply(status, reply, retryAfter);
    }

    private static long retryAfter(String header) {
        if (header == null) return 0;
        try {
            return Math.min(Long.parseLong(header.trim()), 3600) * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Only for tests: forgets what the server said about proof of work and the clock. */
    public static void resetShared() {
        POW_BITS.set(DEFAULT_POW_BITS);
        CLOCK_OFFSET.set(0);
    }

    private static final class Reply {
        final int status;
        final String body;
        final long retryAfterMillis;

        Reply(int status, String body, long retryAfterMillis) {
            this.status = status;
            this.body = body;
            this.retryAfterMillis = retryAfterMillis;
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
