package legacy;

import cc.nitea.Level;
import cc.nitea.Nitea;
import cc.nitea.NiteaClient;
import cc.nitea.NiteaConsent;
import cc.nitea.NiteaOptions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A mod written and compiled against Nitea 0.3, calling every public method it had, the way its docs said to.
 * The compatibility tests compile it against compat/nitea-core-0.3.x.jar and run it on the current Nitea.
 * Never edit this file: it stands for mods already released.
 */
public final class LegacyMod {
    private LegacyMod() {}

    /** Returns what the mod observed, in order, so the test can check each call behaved. */
    public static List<Object> run(String endpoint, Path gameDir) {
        List<Object> seen = new ArrayList<>();
        NiteaConsent.useGameDir(gameDir);
        NiteaClient nitea = Nitea.init(NiteaOptions.builder("legacymod")
                .sdkKey("nt_legacy_key")
                .endpoint(endpoint)
                .owner(LegacyMod.class)
                .inAppPackages("legacy")
                .release("1.0.0")
                .environment("production")
                .gameDir(gameDir)
                .tag("edition", "classic")
                .minecraftVersion("26.2")
                .loader("neoforge", "26.2.0.88")
                .side("server")
                .maxBreadcrumbs(10)
                .captureUncaught(true)
                .scanCrashReports(false)
                .openReportLinks(false)
                .debug(false)
                .build());
        seen.add(Nitea.get("legacymod") == nitea);
        seen.add(nitea.modId());
        seen.add(NiteaConsent.shouldAsk());
        seen.add(NiteaConsent.state().name());
        NiteaConsent.onChange(() -> { });
        NiteaConsent.markPromptAvailable();
        NiteaConsent.grant();
        seen.add(NiteaConsent.state() == NiteaConsent.State.GRANTED);
        seen.add(nitea.isEnabled());
        seen.add(nitea.installationId() != null);
        seen.add(NiteaConsent.mods().contains("legacymod"));

        nitea.addBreadcrumb("world", "Loaded");
        nitea.addBreadcrumb("world", "Saved", Level.WARNING);
        nitea.setTag("feature", "wand");
        List<UUID> ids = new ArrayList<>();
        ids.add(nitea.captureException(new IllegalStateException("legacy one")));
        ids.add(nitea.captureException(new IllegalArgumentException("legacy two"), Level.WARNING));
        ids.add(nitea.captureException(new UnsupportedOperationException("legacy three"), Level.FATAL, Collections.singletonMap("k", "v")));
        ids.add(nitea.captureMessage("legacy message", Level.INFO));
        ids.add(nitea.reportBug("legacy bug"));
        ids.add(nitea.reportBug("legacy bug with link", link -> { }));
        ids.add(nitea.reportSuggestion("legacy suggestion"));
        ids.add(nitea.reportSuggestion("legacy suggestion with link", link -> { }));
        seen.add(ids.stream().allMatch(id -> id != null));
        seen.add(Level.valueOf("ERROR") == Level.ERROR && Level.values().length == 5);
        seen.add(NiteaConsent.State.valueOf("DENIED").ordinal() + NiteaConsent.State.values().length);
        seen.add(NiteaOptions.DEFAULT_ENDPOINT);
        nitea.close(Duration.ofSeconds(5));
        return seen;
    }
}
