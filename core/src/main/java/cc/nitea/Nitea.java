package cc.nitea;

import cc.nitea.internal.Compat;
import cc.nitea.internal.Engine;
import cc.nitea.internal.Log;
import cc.nitea.internal.Text;
import cc.nitea.internal.Version;
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

    /**
     * Creates the client for a mod, or returns the existing one if the mod already initialised it. Never throws:
     * when Nitea can't run for the mod (built against a Nitea too old for the one in the game, or an unexpected
     * error), the mod gets a client that does nothing and the reason is logged. The mod itself keeps working.
     */
    public static NiteaClient init(NiteaOptions options) {
        return CLIENTS.computeIfAbsent(options.modId, id -> {
            Log log = new Log(id, options.debug);
            try {
                return start(options, log);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                return new NiteaClient(options, null, NiteaOptions.DEFAULT_ENDPOINT, log,
                        "Nitea could not start and is off for this mod; the mod keeps working. Please report this to Nitea: " + e);
            }
        });
    }

    private static NiteaClient start(NiteaOptions options, Log log) {
        String id = options.modId;
        Engine.useGameDir(options.gameDir);
        Class<?> owner = options.owner;

        // Mods built against a Nitea older than the supported window are left out: the running copy may no longer
        // behave the way they expect. They are not registered, so nothing is ever reported for them.
        Compat.Result compat = Compat.check(id, owner);
        if (compat.tooOld()) {
            return new NiteaClient(options, null, NiteaOptions.DEFAULT_ENDPOINT, log, "This mod was built with Nitea " + compat.builtWith
                    + ", more than " + Compat.SUPPORTED_LINES + " versions older than the Nitea " + Version.get()
                    + " running in this game (bundled by another mod). Nitea is turned off for this mod to avoid errors;"
                    + " the mod itself keeps working. Mod author: update Nitea to " + Compat.CURRENT_LINE + " or newer.");
        }
        if (compat.newerThanRunning()) {
            log.warn("This mod was built with Nitea " + compat.builtWith + " but another mod bundles the older Nitea " + Version.get()
                    + ", which the game loaded instead. Newer Nitea features won't work until that mod updates.");
        }

        Engine.registerMod(id, options.inAppPackages, owner != null ? Engine.moduleName(owner) : null);
        Properties bundled = bundledConfig(options);
        String sdkKey = resolve(options.sdkKey, id, "sdkKey", "SDK_KEY", bundled);
        String endpoint = resolve(options.endpoint, id, "endpoint", "ENDPOINT", bundled);
        NiteaClient client = new NiteaClient(options, sdkKey, endpoint != null ? endpoint : NiteaOptions.DEFAULT_ENDPOINT, log);
        installHooks();
        installScreens(log);
        client.start();
        return client;
    }

    // Each artifact (one per loader and Minecraft version) ships one of these classes with the in-game screens
    // (consent, title screen button). The core is the same in every artifact, so it looks them up by name.
    private static final String[] LOADER_HOOKS = {
        "cc.nitea.neoforge.NiteaNeoForge", "cc.nitea.forge.NiteaForge", "cc.nitea.fabric.NiteaFabric",
    };

    // Without a loader (e.g. in unit tests), or when the game's classes differ from the ones the screens were built
    // against, there are no screens and the rest of the library works the same.
    private static void installScreens(Log log) {
        for (String name : LOADER_HOOKS) {
            Class<?> hooks;
            try {
                hooks = Class.forName(name, false, Nitea.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            try {
                hooks.getMethod("install").invoke(null);
            } catch (java.lang.reflect.InvocationTargetException e) {
                log.warn("Could not start Nitea's in-game screens: " + e.getCause());
            } catch (ReflectiveOperationException | LinkageError e) {
                log.warn("Could not start Nitea's in-game screens: " + e);
            }
            return;
        }
        log.debug("No mod loader found, no in-game screens");
    }

    /** The client a mod created with {@link #init}, or null. */
    public static NiteaClient get(String modId) {
        return CLIENTS.get(modId);
    }

    // Explicit option, then system property, then environment variable, then the resource bundled in the mod jar
    private static String resolve(String explicit, String modId, String property, String envSuffix, Properties bundled) {
        if (explicit != null && !Text.isBlank(explicit)) return explicit.trim();
        String value = System.getProperty("nitea." + modId + "." + property);
        if (value == null || Text.isBlank(value)) value = System.getenv("NITEA_" + modId.toUpperCase(Locale.ROOT).replace('-', '_') + "_" + envSuffix);
        if (value == null || Text.isBlank(value)) value = bundled.getProperty(property);
        return value == null || Text.isBlank(value) ? null : value.trim();
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
