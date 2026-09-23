package cc.nitea;

import cc.nitea.internal.Browser;
import cc.nitea.internal.CrashReports;
import cc.nitea.internal.Engine;
import cc.nitea.internal.Json;
import cc.nitea.internal.Log;
import cc.nitea.internal.Platform;
import cc.nitea.internal.RateLimiter;
import cc.nitea.internal.Settings;
import cc.nitea.internal.StackTraces;
import cc.nitea.internal.Text;
import cc.nitea.internal.Transport;
import cc.nitea.internal.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reports one mod's errors, crashes and player feedback to Nitea. Create it once with {@link Nitea#init} and keep it
 * in a static field. Every method is thread-safe, never throws and returns quickly: events are sent in the
 * background. When reporting is off (no SDK key, or the player opted out) every call is a no-op.
 *
 * <p>Nothing is sent before the player allows it. Nitea asks them once, on behalf of every mod using it (see
 * {@link NiteaConsent}); until they answer, events wait in memory and are sent only if they say yes.
 *
 * <p>Nothing that identifies a player is collected: no usernames, player UUIDs or IP addresses. Only the game
 * setup (Minecraft, loader, Java and OS versions, hardware class) and what the mod itself passes in.
 */
public final class NiteaClient {
    private final NiteaOptions options;
    private final Log log;
    private final Settings settings;
    private final Transport transport;
    private final StackTraces traces;
    private final RateLimiter limiter = new RateLimiter();
    private final Deque<Map<String, Object>> breadcrumbs = new ArrayDeque<>();
    private final Map<String, String> tags = new LinkedHashMap<>();
    // Events captured before the player chose, sent if they opt in
    private final Deque<Map<String, Object>> pending = new ArrayDeque<>();
    private volatile Platform platform;

    private static final int MAX_PENDING = 25;

    NiteaClient(NiteaOptions options, String sdkKey, String endpoint, Log log) {
        this.options = options;
        this.log = log;
        this.traces = new StackTraces(options.inAppPackages);
        this.settings = Settings.of(options.gameDir);
        tags.putAll(options.tags);

        if (!Engine.modEnabled(options.modId)) {
            log.info("Reporting for this mod is turned off in config/nitea/nitea.properties");
            transport = null;
        } else if (sdkKey == null || sdkKey.isBlank()) {
            log.warn("No SDK key found, reporting is off. Put sdkKey=nt_... in src/main/resources/nitea/" + options.modId + ".properties (see the Nitea docs).");
            transport = null;
        } else {
            if (!sdkKey.startsWith("nt_")) log.warn("The SDK key should start with nt_; check it in your project settings");
            transport = new Transport(options.modId, endpoint, sdkKey.trim(), log);
            log.info("Reporting to " + endpoint + " (release " + options.release + ")");
        }
    }

    /** True when events are being sent: an SDK key is set, the player opted in and the key was accepted. */
    public boolean isEnabled() {
        return transport != null && transport.usable() && Engine.GRANTED.equals(Engine.consent());
    }

    // Events can still be captured: sent right away once the player opted in, kept in memory while they haven't chosen
    private boolean capturing() {
        return transport != null && transport.usable() && !Engine.DENIED.equals(Engine.consent());
    }

    /** The mod ID this client reports for. */
    public String modId() {
        return options.modId;
    }

    /** Anonymous random ID of this game installation (shared by every mod using Nitea), or null unless the player opted in. */
    public String installationId() {
        return Engine.installationId();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------------------------------------------------

    /** Reports a caught exception as an error. Returns the event ID, or null when nothing was sent. */
    public UUID captureException(Throwable throwable) {
        return captureException(throwable, Level.ERROR);
    }

    /** Reports a caught exception with the given level. Returns the event ID, or null when nothing was sent. */
    public UUID captureException(Throwable throwable, Level level) {
        return captureException(throwable, level, Map.of());
    }

    /** Reports a caught exception with extra tags for this event only. */
    public UUID captureException(Throwable throwable, Level level, Map<String, String> extraTags) {
        if (throwable == null || !capturing()) return null;
        String signature = signature(throwable);
        if (!limiter.allow(signature)) return null;
        Map<String, Object> event = event("error", level, null, extraTags);
        event.put("exception", Map.of("values", traces.values(throwable)));
        return send(event, false);
    }

    /** Reports a message that is not an exception, e.g. a failed sanity check. */
    public UUID captureMessage(String message, Level level) {
        if (message == null || message.isBlank() || !capturing()) return null;
        if (!limiter.allow("message:" + message)) return null;
        return send(event("error", level, message, Map.of()), false);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Player feedback (each report becomes its own issue on the dashboard)
    //
    // When the mod has a public page on Nitea, the API answers with a one-time link where the player adds a
    // description, steps to reproduce and images; only then does the report appear on the public roadmap. On the
    // client, the library opens that link in the player's browser. On a dedicated server the reporting player is on
    // another machine, so pass a callback and send them the link (e.g. as a clickable chat message).
    // ------------------------------------------------------------------------------------------------------------

    /** Sends a bug report written by a player. Recent breadcrumbs are attached for context. */
    public UUID reportBug(String description) {
        return reportBug(description, null);
    }

    /** Sends a bug report; {@code onCompletionLink} receives the link to complete it, when the mod has a public page. */
    public UUID reportBug(String description, Consumer<String> onCompletionLink) {
        return report("bug", Level.WARNING, description, onCompletionLink);
    }

    /** Sends a suggestion written by a player. */
    public UUID reportSuggestion(String suggestion) {
        return reportSuggestion(suggestion, null);
    }

    /** Sends a suggestion; {@code onCompletionLink} receives the link to complete it, when the mod has a public page. */
    public UUID reportSuggestion(String suggestion, Consumer<String> onCompletionLink) {
        return report("suggestion", Level.INFO, suggestion, onCompletionLink);
    }

    private UUID report(String kind, Level level, String text, Consumer<String> onCompletionLink) {
        if (text == null || text.isBlank() || transport == null) return null;
        // The player is waiting for a link, so reports are never held back: they need consent now
        if (!isEnabled()) {
            log.info("Player " + kind + " report not sent: the player hasn't allowed Nitea reports");
            return null;
        }
        if (!limiter.allow(kind)) return null;
        Map<String, Object> event = event(kind, level, text.trim(), Map.of());
        UUID id = UUID.fromString((String) event.get("eventId"));
        event.put("installationId", Engine.installationId());
        transport.send(Json.write(event), reply -> {
            String link = Json.readString(reply, "completeUrl");
            if (link == null) return;
            log.info("Complete the " + kind + " report at " + link);
            if (onCompletionLink != null) onCompletionLink.accept(link);
            // The player is at this computer only on the client (singleplayer, or a client-side command)
            if (options.openReportLinks && "client".equals(first(options.side, platform().side))) Browser.open(link, log);
        });
        return id;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Context
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Records something the mod did. The most recent breadcrumbs are attached to the next event, so an issue shows
     * what led up to it. Never put player names, UUIDs, chat messages or addresses here.
     */
    public void addBreadcrumb(String category, String message) {
        addBreadcrumb(category, message, Level.INFO);
    }

    public void addBreadcrumb(String category, String message, Level level) {
        if (options.maxBreadcrumbs == 0 || !capturing()) return;
        Map<String, Object> crumb = new LinkedHashMap<>();
        crumb.put("timestamp", now());
        crumb.put("category", Text.cut(category, 100));
        crumb.put("message", Text.cut(message, 1000));
        crumb.put("level", level.breadcrumbName());
        synchronized (breadcrumbs) {
            if (breadcrumbs.size() == options.maxBreadcrumbs) breadcrumbs.removeFirst();
            breadcrumbs.addLast(crumb);
        }
        log.debug("breadcrumb [" + category + "] " + message);
    }

    /** Sets a tag sent with every following event (key up to 50 characters, value up to 200). */
    public void setTag(String key, String value) {
        synchronized (tags) {
            if (value == null) tags.remove(key);
            else tags.put(key, value);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    /** Sends what is still queued (waiting up to {@code timeout}) and stops. Called automatically on JVM exit. */
    public void close(Duration timeout) {
        if (transport != null) transport.close(timeout);
    }

    /**
     * From the uncaught exception handler, which only calls the mod that caused the error. The thread (maybe the
     * whole game) is dying, so send right away.
     */
    void captureUncaught(Thread thread, Throwable throwable) {
        if (!capturing()) return;
        if (!limiter.allow(signature(throwable))) return;
        String name = thread.getName();
        // An exception escaping the game's main threads takes the game down with it
        boolean fatal = name.equals("main") || name.equals("Render thread") || name.equals("Server thread");
        Map<String, Object> event = event(fatal ? "crash" : "error", fatal ? Level.FATAL : Level.ERROR, null, Map.of("thread", name, "mechanism", "uncaught"));
        event.put("exception", Map.of("values", traces.values(throwable)));
        send(event, true);
    }

    /** Called once by {@link Nitea#init}: follows the player's choice and handles the previous launch's crashes. */
    void start() {
        if (transport == null) return;
        Engine.onConsentChange(this::consentChanged);
        String consent = Engine.consent();
        if (Engine.GRANTED.equals(consent)) scanCrashReports();
        else if (Engine.DENIED.equals(consent)) log.info("The player opted out of Nitea reports, nothing is sent");
        else log.info("Nothing is sent until the player allows Nitea reports (asked in game, or consent=granted in config/nitea/nitea.properties)");
    }

    private void consentChanged() {
        List<Map<String, Object>> queued;
        synchronized (pending) {
            queued = new ArrayList<>(pending);
            pending.clear();
        }
        if (Engine.GRANTED.equals(Engine.consent())) {
            log.info("The player allowed Nitea reports");
            for (Map<String, Object> event : queued) send(event, false);
            scanCrashReports();
        } else {
            log.info("The player opted out of Nitea reports, nothing more is sent");
            synchronized (breadcrumbs) {
                breadcrumbs.clear();
            }
        }
    }

    /** Reports Minecraft crash reports written since the last launch (and since the player opted in) that this mod caused. */
    void scanCrashReports() {
        if (!isEnabled() || !options.scanCrashReports) return;
        transport.background(() -> {
            for (CrashReports.Found crash : CrashReports.scan(options.gameDir, options.modId, settings)) {
                Map<String, Object> event = event("crash", Level.FATAL, crash.description(), Map.of("mechanism", "crash-report", "crash_report", crash.fileName()));
                event.put("timestamp", crash.time().truncatedTo(ChronoUnit.MILLIS).toString());
                List<Map<String, Object>> values = traces.parse(crash.text());
                if (!values.isEmpty()) event.put("exception", Map.of("values", values));
                // Breadcrumbs belong to this launch, not the one that crashed
                event.remove("breadcrumbs");
                send(event, false);
                log.info("Reported crash report " + crash.fileName());
            }
        });
    }

    boolean capturesUncaught() {
        return options.captureUncaught;
    }

    // ------------------------------------------------------------------------------------------------------------

    private UUID send(Map<String, Object> event, boolean now) {
        UUID id = UUID.fromString((String) event.get("eventId"));
        String consent = Engine.consent();
        if (Engine.DENIED.equals(consent)) return null;
        if (!Engine.GRANTED.equals(consent)) {
            synchronized (pending) {
                if (pending.size() == MAX_PENDING) pending.removeFirst();
                pending.addLast(event);
            }
            return id;
        }
        event.put("installationId", Engine.installationId());
        String json = Json.write(event);
        if (now) transport.sendNow(json);
        else transport.send(json);
        return id;
    }

    private Map<String, Object> event(String kind, Level level, String message, Map<String, String> extraTags) {
        Platform p = platform();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("kind", kind);
        event.put("level", level.eventName());
        event.put("timestamp", now());
        event.put("message", Text.cut(message, 10000));
        event.put("release", Text.cut(options.release, 100));
        event.put("environment", Text.cut(first(options.environment, p.environment, "production"), 50));
        event.put("contexts", contexts(p));
        event.put("tags", tags(p, extraTags));
        synchronized (breadcrumbs) {
            if (!breadcrumbs.isEmpty()) event.put("breadcrumbs", new ArrayList<>(breadcrumbs));
        }
        return event;
    }

    private Map<String, Object> contexts(Platform p) {
        Map<String, Object> contexts = new LinkedHashMap<>();
        String mc = first(options.minecraftVersion, p.minecraftVersion);
        if (mc != null) contexts.put("minecraft", Map.of("version", Text.cut(mc, 50)));
        String loader = first(options.loaderName, p.loaderName);
        if (loader != null) {
            Map<String, Object> loaderContext = new LinkedHashMap<>();
            loaderContext.put("name", Text.cut(loader, 50));
            loaderContext.put("version", Text.cut(first(options.loaderVersion, p.loaderVersion), 50));
            contexts.put("loader", loaderContext);
        }
        contexts.put("java", Map.of(
                "version", Text.cut(System.getProperty("java.version", "unknown"), 50),
                "vendor", Text.cut(System.getProperty("java.vendor", "unknown"), 100)));
        contexts.put("os", Map.of(
                "name", Text.cut(System.getProperty("os.name", "unknown"), 100),
                "version", Text.cut(System.getProperty("os.version", "unknown"), 100)));
        return contexts;
    }

    private Map<String, String> tags(Platform p, Map<String, String> extraTags) {
        Map<String, String> all = new LinkedHashMap<>();
        String side = first(options.side, p.side);
        if (side != null) all.put("side", side);
        all.put("arch", System.getProperty("os.arch", "unknown"));
        all.put("cpu_cores", Integer.toString(Runtime.getRuntime().availableProcessors()));
        all.put("memory_max_mb", Long.toString(Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        all.put("nitea_version", Version.get());
        synchronized (tags) {
            all.putAll(tags);
        }
        all.putAll(extraTags);
        Map<String, String> cut = new LinkedHashMap<>();
        all.forEach((key, value) -> {
            if (key != null && value != null && !key.isEmpty()) cut.put(Text.cut(key, 50), Text.cut(value, 200));
        });
        return cut;
    }

    // Loader APIs may not be ready while mods are constructed, so look them up on first use
    private Platform platform() {
        Platform p = platform;
        if (p == null) {
            ClassLoader loader = options.owner != null ? options.owner.getClassLoader() : NiteaClient.class.getClassLoader();
            p = Platform.detect(loader);
            if (p.minecraftVersion != null) platform = p;
        }
        return p;
    }

    private static String signature(Throwable throwable) {
        StackTraceElement[] stack = throwable.getStackTrace();
        return throwable.getClass().getName() + (stack.length > 0 ? "@" + stack[0] : "");
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
