package cc.nitea;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for one mod's {@link NiteaClient}. Only the mod ID is required:
 *
 * <pre>{@code
 * NiteaClient nitea = Nitea.init(NiteaOptions.builder("mymod")
 *         .owner(MyMod.class)
 *         .release("1.2.0")
 *         .build());
 * }</pre>
 *
 * <p>When no SDK key is set explicitly, it is looked up in this order:
 * <ol>
 *   <li>system property {@code nitea.<modId>.sdkKey}</li>
 *   <li>environment variable {@code NITEA_<MODID>_SDK_KEY}</li>
 *   <li>classpath resource {@code nitea/<modId>.properties} (key {@code sdkKey}), usually generated from a
 *       git-ignored {@code .env} file at build time</li>
 * </ol>
 * The endpoint is resolved the same way ({@code nitea.<modId>.endpoint}, {@code NITEA_<MODID>_ENDPOINT},
 * {@code endpoint=} in the resource) and defaults to {@code https://nitea.cc}.
 */
public final class NiteaOptions {
    public static final String DEFAULT_ENDPOINT = "https://nitea.cc";

    final String modId;
    final String sdkKey;
    final String endpoint;
    final String release;
    final String environment;
    final Path gameDir;
    final Class<?> owner;
    final List<String> inAppPackages;
    final Map<String, String> tags;
    final String minecraftVersion;
    final String loaderName;
    final String loaderVersion;
    final String side;
    final int maxBreadcrumbs;
    final boolean captureUncaught;
    final boolean scanCrashReports;
    final boolean debug;
    final boolean openReportLinks;

    private NiteaOptions(Builder b) {
        modId = b.modId;
        sdkKey = b.sdkKey;
        endpoint = b.endpoint;
        release = b.release;
        environment = b.environment;
        gameDir = b.gameDir != null ? b.gameDir : Path.of("").toAbsolutePath();
        owner = b.owner;
        List<String> packages = new ArrayList<>(b.inAppPackages);
        // The owner's package is the mod's own code unless told otherwise
        if (packages.isEmpty() && owner != null && !owner.getPackageName().isEmpty()) packages.add(owner.getPackageName());
        inAppPackages = Collections.unmodifiableList(packages);
        tags = Collections.unmodifiableMap(new LinkedHashMap<>(b.tags));
        minecraftVersion = b.minecraftVersion;
        loaderName = b.loaderName;
        loaderVersion = b.loaderVersion;
        side = b.side;
        maxBreadcrumbs = b.maxBreadcrumbs;
        captureUncaught = b.captureUncaught;
        scanCrashReports = b.scanCrashReports;
        debug = b.debug;
        openReportLinks = b.openReportLinks;
    }

    /** Starts the options for a mod, using the same ID as in {@code fabric.mod.json} / {@code neoforge.mods.toml}. */
    public static Builder builder(String modId) {
        return new Builder(modId);
    }

    public String modId() {
        return modId;
    }

    public static final class Builder {
        private final String modId;
        private String sdkKey;
        private String endpoint;
        private String release;
        private String environment;
        private Path gameDir;
        private Class<?> owner;
        private final List<String> inAppPackages = new ArrayList<>();
        private final Map<String, String> tags = new LinkedHashMap<>();
        private String minecraftVersion;
        private String loaderName;
        private String loaderVersion;
        private String side;
        private int maxBreadcrumbs = 100;
        private boolean captureUncaught = true;
        private boolean scanCrashReports = true;
        private boolean debug;
        private boolean openReportLinks = true;

        private Builder(String modId) {
            this.modId = Objects.requireNonNull(modId, "modId");
            if (!modId.matches("[a-z][a-z0-9_]{1,63}")) throw new IllegalArgumentException("Invalid mod ID: " + modId);
        }

        /** The project's SDK key ({@code nt_...}). Prefer the build-time {@code .env} over hardcoding it. */
        public Builder sdkKey(String sdkKey) {
            this.sdkKey = sdkKey;
            return this;
        }

        /** Base URL of the Nitea API, e.g. {@code http://localhost:3000} while developing the website. */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** The mod version; lets the dashboard tell which releases an issue affects. */
        public Builder release(String release) {
            this.release = release;
            return this;
        }

        /** e.g. {@code production} or {@code development}. Detected from the loader when not set. */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /** The game directory (holds {@code config/} and {@code crash-reports/}). Defaults to the working directory. */
        public Builder gameDir(Path gameDir) {
            this.gameDir = gameDir;
            return this;
        }

        /**
         * A class of the mod, usually its main class. Its class loader is used to find the mod's resources and
         * the loader's APIs, and its package marks which stack frames are the mod's own ("in app") code.
         */
        public Builder owner(Class<?> owner) {
            this.owner = owner;
            return this;
        }

        /** Packages of the mod's own code. Replaces the default (the owner's package). */
        public Builder inAppPackages(String... packages) {
            inAppPackages.addAll(List.of(packages));
            return this;
        }

        /** A tag sent with every event. */
        public Builder tag(String key, String value) {
            tags.put(key, value);
            return this;
        }

        /** Overrides the detected Minecraft version. */
        public Builder minecraftVersion(String version) {
            this.minecraftVersion = version;
            return this;
        }

        /** Overrides the detected mod loader, e.g. {@code neoforge} and its version. */
        public Builder loader(String name, String version) {
            this.loaderName = name;
            this.loaderVersion = version;
            return this;
        }

        /** Overrides the detected side ({@code client} or {@code server}). */
        public Builder side(String side) {
            this.side = side;
            return this;
        }

        /** How many recent breadcrumbs are attached to each event (at most 100). */
        public Builder maxBreadcrumbs(int max) {
            this.maxBreadcrumbs = Math.max(0, Math.min(100, max));
            return this;
        }

        /** Report uncaught exceptions that went through the mod's code (default on). */
        public Builder captureUncaught(boolean capture) {
            this.captureUncaught = capture;
            return this;
        }

        /** On startup, report Minecraft crash reports written since the last run that mention the mod's code (default on). */
        public Builder scanCrashReports(boolean scan) {
            this.scanCrashReports = scan;
            return this;
        }

        /** Log every request and response. */
        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        /**
         * On the client, open the player's browser at the link to complete a bug report or suggestion (default on).
         * Only happens when the mod has a public page on Nitea.
         */
        public Builder openReportLinks(boolean open) {
            this.openReportLinks = open;
            return this;
        }

        public NiteaOptions build() {
            return new NiteaOptions(this);
        }
    }
}
