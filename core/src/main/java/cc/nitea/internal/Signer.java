package cc.nitea.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs requests to the Nitea API (protocol 2), so the SDK key alone isn't enough to send events:
 *
 * <ul>
 *   <li>The signature is an HMAC-SHA256 over the method, path, timestamp, mod ID, owner class and body hash, keyed
 *       with the SHA-256 of the SDK key (the only form the server keeps). A key copied into another mod, or into a
 *       hand-written request, is refused unless the whole request is rebuilt the way the library does.</li>
 *   <li>The proof of work is a nonce that makes {@code SHA-256(signature ":" nonce)} start with a number of zero bits
 *       the server chooses. It costs the library a few milliseconds per event, on its background thread, and makes
 *       flooding the API expensive. The server checks it before touching the database.</li>
 * </ul>
 *
 * <p>The format is shared with the website ({@code apps/web/lib/sdk-protocol.ts}): change both together, and keep
 * accepting the old format for as long as AGENTS.md says.
 */
public final class Signer {
    /** Version of the signature format, sent as the prefix of the signature header ({@code v1=<hex>}). */
    public static final String VERSION = "v1";
    /** The server can ask for more work (HTTP 428), but never more than this: past it the event is dropped. */
    public static final int MAX_POW_BITS = 24;

    private final byte[] key;

    public Signer(String sdkKey) {
        this.key = hex(sha256(sdkKey.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
    }

    /** The text the signature covers: one field per line, the body as its SHA-256. */
    public static String canonical(String method, String path, long timestamp, String modId, String owner, byte[] body) {
        return "nitea-" + VERSION + "\n" + method + "\n" + path + "\n" + timestamp + "\n" + modId + "\n" + (owner != null ? owner : "") + "\n" + hex(sha256(body));
    }

    /** Hex HMAC-SHA256 of {@code canonical}. */
    public String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is missing from this JVM", e);
        }
    }

    /** The smallest nonce for which {@code SHA-256(signature ":" nonce)} starts with {@code bits} zero bits. */
    public static long solve(String signature, int bits) {
        if (bits <= 0) return 0;
        MessageDigest digest = digest();
        byte[] prefix = (signature + ":").getBytes(StandardCharsets.UTF_8);
        for (long nonce = 0; ; nonce++) {
            digest.reset();
            digest.update(prefix);
            digest.update(Long.toString(nonce).getBytes(StandardCharsets.UTF_8));
            if (leadingZeroBits(digest.digest()) >= bits) return nonce;
        }
    }

    /** True when {@code nonce} is a valid proof of work of {@code bits} bits for {@code signature}. */
    public static boolean verify(String signature, long nonce, int bits) {
        return leadingZeroBits(sha256((signature + ":" + nonce).getBytes(StandardCharsets.UTF_8))) >= bits;
    }

    static int leadingZeroBits(byte[] hash) {
        int bits = 0;
        for (byte b : hash) {
            if (b == 0) {
                bits += 8;
                continue;
            }
            return bits + Integer.numberOfLeadingZeros(b & 0xff) - 24;
        }
        return bits;
    }

    public static byte[] sha256(byte[] data) {
        return digest().digest(data);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    public static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >> 4) & 0xf];
            out[i * 2 + 1] = digits[bytes[i] & 0xf];
        }
        return new String(out);
    }
}
