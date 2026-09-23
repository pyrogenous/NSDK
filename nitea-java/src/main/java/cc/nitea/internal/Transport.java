package cc.nitea.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Sends events to {@code POST /api/v1/events} on one background daemon thread, so the game thread never waits on
 * the network. Failed sends are retried with backoff; every event carries its own ID, so a retry of an event the
 * server already stored is ignored by the API.
 */
public final class Transport {
    private static final int QUEUE_SIZE = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final URI eventsUri;
    private final String authorization;
    private final Log log;
    private final HttpClient http;
    private final ExecutorService worker;
    private volatile boolean rejectedKey;

    public Transport(String modId, String endpoint, String sdkKey, Log log) {
        this.eventsUri = URI.create(endpoint.replaceAll("/+$", "") + "/api/v1/events");
        this.authorization = "Bearer " + sdkKey;
        this.log = log;
        // HTTP/1.1: the default HTTP/2 tries an h2c upgrade over plain http://, which servers like `next dev` drop
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
        // Bounded queue: if the API is unreachable for a long time, new events are dropped instead of piling up
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_SIZE), runnable -> {
            Thread thread = new Thread(runnable, "Nitea-" + modId);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardPolicy());
    }

    /** False once the API has rejected the SDK key; nothing more is sent until the next launch. */
    public boolean usable() {
        return !rejectedKey;
    }

    /** Queues an event for sending in the background. */
    public void send(String json) {
        send(json, null);
    }

    /** Queues an event; {@code onAccepted} receives the API's JSON reply once the event is stored. */
    public void send(String json, Consumer<String> onAccepted) {
        if (rejectedKey) return;
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
        if (!rejectedKey) deliver(json, false, null);
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
        HttpRequest request = HttpRequest.newBuilder(eventsUri)
                .timeout(TIMEOUT)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .header("User-Agent", "nitea-java/" + Version.get())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        int attempts = retry ? MAX_ATTEMPTS : 1;
        for (int attempt = 1; attempt <= attempts && !rejectedKey; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                log.debug("POST " + eventsUri + " -> " + status + " " + response.body());
                if (status >= 200 && status < 300) {
                    if (onAccepted != null) {
                        try {
                            onAccepted.accept(response.body());
                        } catch (RuntimeException e) {
                            log.warn("Report callback failed: " + e);
                        }
                    }
                    return;
                }
                if (status == 401) {
                    rejectedKey = true;
                    log.warn("The Nitea API rejected the SDK key; reporting is off until the next launch. Check the key in your project settings.");
                    return;
                }
                // Other 4xx: the event itself is invalid or too large, sending it again will not help
                if (status != 429 && status < 500) {
                    log.warn("Event rejected (" + status + "): " + response.body());
                    return;
                }
                if (attempt == attempts) log.warn("The Nitea API kept failing (" + status + "), event dropped");
            } catch (IOException e) {
                if (attempt == attempts) log.warn("Could not reach " + eventsUri + ", event dropped: " + e);
                else log.debug("Send failed, retrying: " + e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (attempt < attempts) sleep(attempt * attempt * 2000L);
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
