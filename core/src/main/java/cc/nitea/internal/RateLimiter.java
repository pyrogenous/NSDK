package cc.nitea.internal;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps an error thrown every tick from flooding the API: at most {@link #PER_MINUTE} events a minute overall, and
 * {@link #SAME_PER_MINUTE} a minute with the same signature.
 */
public final class RateLimiter {
    private static final int PER_MINUTE = 30;
    private static final int SAME_PER_MINUTE = 3;
    private static final long WINDOW = 60_000;

    private long windowStart;
    private int count;
    private final Map<String, Integer> perSignature = new HashMap<>();

    public synchronized boolean allow(String signature) {
        long now = System.currentTimeMillis();
        if (now - windowStart >= WINDOW) {
            windowStart = now;
            count = 0;
            perSignature.clear();
        }
        Integer seen = perSignature.get(signature);
        int same = seen != null ? seen : 0;
        if (count >= PER_MINUTE || same >= SAME_PER_MINUTE) return false;
        count++;
        perSignature.put(signature, same + 1);
        return true;
    }
}
