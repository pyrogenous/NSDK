package cc.nitea;

import cc.nitea.internal.Engine;
import cc.nitea.internal.Log;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point of the Nitea library. Call {@link #init} once, early in the mod's initialisation (Fabric
 * {@code onInitialize}, NeoForge/Forge mod constructor):
 *
 * <pre>{@code
 * public static final NiteaClient NITEA = Nitea.init(NiteaOptions.builder("mymod").owner(MyMod.class).release("1.0.0").build());
 * }</pre>
 *
 * <p>Each mod gets its own client, so any number of mods can embed the library side by side. They share one engine:
 * the player's consent is asked once for all of them, and an uncaught error or crash is only reported by the mod
 * that caused it.
 */
public final class Nitea {
    private static final Map<String, NiteaClient> CLIENTS = new ConcurrentHashMap<>();
    private static boolean hooksInstalled;

    private Nitea() {}

    /** Creates the client for a mod, or returns the existing one if the mod already initialised it. */
    public static NiteaClient init(NiteaOptions options) {
        return CLIENTS.computeIfAbsent(options.modId, id -> {
            Log log = new Log(id, options.debug);
            Engine.useGameDir(options.gameDir);
            Class<?> owner = options.owner;
            Engine.registerMod(id, options.inAppPackages, owner != null && owner.getModule().isNamed() ? owner.getModule().getName() : null);
            Properties bundled = bundledConfig(options);
            String sdkKey = resolve(options.sdkKey, id, "sdkKey", "SDK_KEY", bundled);
            String endpoint = resolve(options.endpoint, id, "endpoint", "ENDPOINT", bundled);
            NiteaClient client = new NiteaClient(options, sdkKey, endpoint != null ? endpoint : NiteaOptions.DEFAULT_ENDPOINT, log);
            installHooks();
            client.start();
            return client;
        });
    }

    /** The client a mod created with {@link #init}, or null. */
    public static NiteaClient get(String modId) {
        return CLIENTS.get(modId);
    }

    // Explicit option, then system property, then environment variable, then the resource bundled in the mod jar
    private static String resolve(String explicit, String modId, String property, String envSuffix, Properties bundled) {
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        String value = System.getProperty("nitea." + modId + "." + property);
        if (value == null || value.isBlank()) value = System.getenv("NITEA_" + modId.toUpperCase(Locale.ROOT) + "_" + envSuffix);
        if (value == null || value.isBlank()) value = bundled.getProperty(property);
        return value == null || value.isBlank() ? null : value.trim();
    }

    // nitea/<modId>.properties, looked up through the mod's own class loader so each mod finds its own file
    private static Properties bundledConfig(NiteaOptions options) {
        Properties props = new Properties();
        String path = "nitea/" + options.modId + ".properties";
        ClassLoader[] loaders = {
            options.owner != null ? options.owner.getClassLoader() : null,
            Thread.currentThread().getContextClassLoader(),
            Nitea.class.getClassLoader(),
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            URL url = loader.getResource(path);
            if (url == null) continue;
            try (InputStream in = url.openStream()) {
                props.load(in);
                return props;
            } catch (IOException ignored) {
                // try the next loader
            }
        }
        return props;
    }

    private static synchronized void installHooks() {
        if (hooksInstalled) return;
        hooksInstalled = true;

        // Chain in front of any existing handler (the game's, or another copy of this library) instead of replacing it.
        // Each copy only reports for its own clients, and only the culprit mod reports, so nothing is sent twice.
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String modId = Engine.culprit(throwable);
                NiteaClient culprit = modId != null ? CLIENTS.get(modId) : null;
                if (culprit != null && culprit.capturesUncaught()) culprit.captureUncaught(thread, throwable);
            } catch (Throwable ignored) {
                // Reporting must never hide the original error
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                // What the JVM prints when there is no handler
                System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                throwable.printStackTrace(System.err);
            }
        });

        // Send whatever is still queued when the game exits
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (NiteaClient client : CLIENTS.values()) client.close(Duration.ofSeconds(3));
        }, "Nitea-shutdown"));
    }
}
