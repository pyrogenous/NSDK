package cc.nitea.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.nitea.Nitea;
import cc.nitea.NiteaConsent;
import cc.nitea.NiteaOptions;
import cc.nitea.internal.Compat;
import cc.nitea.internal.Engine;
import cc.nitea.internal.Transport;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Graceful degradation across Nitea versions, with the real old releases kept in src/test/resources/compat:
 * <ul>
 *   <li>a mod built against any release of the supported window runs unchanged on the current Nitea</li>
 *   <li>copies of different releases in the same game (a mod shading its own) share consent, installation ID and
 *       the list of mods, so the player is asked once and each error is reported once</li>
 * </ul>
 */
class LegacyCompatibilityTest {
    @TempDir
    Path gameDir;

    @TempDir
    Path work;

    @BeforeEach
    void freshGame() {
        Engine.reset();
        Transport.resetShared();
    }

    @AfterEach
    void cleanUp() {
        Engine.reset();
    }

    /** Kept releases whose mods must keep working: the current line and the {@link Compat#SUPPORTED_LINES} before it. */
    static List<Fixtures.Release> supportedReleases() throws Exception {
        int current = Compat.LINES.indexOf(Compat.CURRENT_LINE);
        return Fixtures.releases().stream()
                .filter(r -> Compat.LINES.indexOf(r.line()) >= current - Compat.SUPPORTED_LINES)
                .collect(Collectors.toList());
    }

    static List<Fixtures.Release> allReleases() throws Exception {
        return Fixtures.releases();
    }

    @Test
    void theSupportedWindowIsFullyCovered() throws Exception {
        int current = Compat.LINES.indexOf(Compat.CURRENT_LINE);
        List<String> covered = supportedReleases().stream().map(Fixtures.Release::line).collect(Collectors.toList());
        for (int i = Math.max(0, current - Compat.SUPPORTED_LINES); i < current; i++) {
            String line = Compat.LINES.get(i);
            assertTrue(covered.contains(line), "No compat/nitea-core-" + line + ".x.jar: mods built against " + line + " aren't tested");
            assertTrue(Files.exists(legacyMod(line)), "No " + legacyMod(line) + ": write the mod of that release (see AGENTS.md)");
        }
    }

    private static Path legacyMod(String line) {
        return Fixtures.resources().resolve("compat").resolve("mods").resolve(line);
    }

    // The current Nitea as another copy, in its own class loader, like the one the game would pick
    private static URLClassLoader currentCopy(Path... extra) throws Exception {
        List<URL> urls = new ArrayList<>();
        URL classes = Nitea.class.getProtectionDomain().getCodeSource().getLocation();
        urls.add(classes);
        // build/classes/java/main -> build/resources/main (roots.pem)
        Path resources = new File(classes.toURI()).toPath().getParent().getParent().getParent().resolve("resources").resolve("main");
        if (Files.isDirectory(resources)) urls.add(resources.toUri().toURL());
        for (Path path : extra) urls.add(path.toUri().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    @ParameterizedTest(name = "a mod built against Nitea {0} runs on the current Nitea")
    @MethodSource("supportedReleases")
    void modsBuiltAgainstOldReleasesRunOnTheCurrentOne(Fixtures.Release release) throws Exception {
        Path classes = work.resolve("classes");
        // Built exactly like the mod was: against that release's jar
        Javac.compile(legacyMod(release.line()), List.of(release.jar()), classes);

        try (TestApi api = new TestApi("nt_legacy_key", 8); URLClassLoader game = currentCopy(classes)) {
            Class<?> mod = Class.forName("legacy.LegacyMod", true, game);
            Method run = mod.getMethod("run", String.class, Path.class);
            @SuppressWarnings("unchecked")
            List<Object> seen = (List<Object>) run.invoke(null, api.endpoint(), gameDir);

            assertEquals(Arrays.asList(true, "legacymod", true, "UNDECIDED", true, true, true, true, true, true, 5, NiteaOptions.DEFAULT_ENDPOINT), seen);

            // 3 exceptions, 1 message, 2 bugs, 2 suggestions: all sent with the current protocol
            List<TestApi.Request> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                TestApi.Request request = api.requests().poll(10, TimeUnit.SECONDS);
                assertNotNull(request, "only " + i + " of 8 events arrived");
                requests.add(request);
            }
            for (TestApi.Request request : requests) {
                assertTrue(request.valid(), request.signatureError() + ": " + request.body());
                assertEquals("2", request.protocol());
                assertEquals("legacymod", request.modId());
                assertEquals("legacy.LegacyMod", request.owner());
            }
            String all = requests.stream().map(TestApi.Request::body).collect(Collectors.joining("\n"));
            for (String expected : List.of("legacy one", "legacy two", "legacy three", "legacy message", "legacy bug", "legacy suggestion", "\"edition\":\"classic\"", "\"feature\":\"wand\"")) {
                assertTrue(all.contains(expected), expected + " missing from\n" + all);
            }
            assertNull(api.requests().poll(300, TimeUnit.MILLISECONDS), "more events than the mod sent");
        }
    }

    @ParameterizedTest(name = "a copy of Nitea {0} shares its state with the current Nitea")
    @MethodSource("allReleases")
    void oldCopiesShareConsentAndModsWithTheCurrentOne(Fixtures.Release release) throws Exception {
        try (URLClassLoader old = new URLClassLoader(new URL[] {release.jar().toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> oldConsent = old.loadClass("cc.nitea.NiteaConsent");
            Class<?> oldEngine = old.loadClass("cc.nitea.internal.Engine");
            oldConsent.getMethod("useGameDir", Path.class).invoke(null, gameDir);
            NiteaConsent.useGameDir(gameDir);

            // A mod on the old copy and a mod on the current one
            initOld(old, "oldmod", "com.oldmod");
            String newMod = "newmod" + release.line().replace(".", "");
            Nitea.init(NiteaOptions.builder(newMod).inAppPackages("com.newmod").gameDir(gameDir).build());

            @SuppressWarnings("unchecked")
            Set<String> oldSees = (Set<String>) oldConsent.getMethod("mods").invoke(null);
            assertTrue(oldSees.containsAll(List.of("oldmod", newMod)), "old copy sees " + oldSees);
            assertTrue(NiteaConsent.mods().containsAll(List.of("oldmod", newMod)), "current copy sees " + NiteaConsent.mods());

            // The player answers once, in whichever copy shows the screen
            assertEquals(NiteaConsent.State.UNDECIDED, NiteaConsent.state());
            oldConsent.getMethod("grant").invoke(null);
            assertEquals(NiteaConsent.State.GRANTED, NiteaConsent.state());
            assertEquals(Engine.installationId(), oldEngine.getMethod("installationId").invoke(null));
            assertNotNull(Engine.installationId());
            NiteaConsent.deny();
            assertEquals("DENIED", oldConsent.getMethod("state").invoke(null).toString());
            assertNull(oldEngine.getMethod("installationId").invoke(null));

            // Each error is attributed to the same mod by both copies, so it's reported once
            Method oldCulprit = oldEngine.getMethod("culprit", Throwable.class);
            Throwable fromOld = thrownBy("com.oldmod.Machine");
            Throwable fromNew = thrownBy("com.newmod.Wand");
            assertEquals("oldmod", Engine.culprit(fromOld));
            assertEquals("oldmod", oldCulprit.invoke(null, fromOld));
            assertEquals(newMod, Engine.culprit(fromNew));
            assertEquals(newMod, oldCulprit.invoke(null, fromNew));
        }
    }

    private static void initOld(ClassLoader old, String modId, String pkg) throws Exception {
        Class<?> options = old.loadClass("cc.nitea.NiteaOptions");
        Class<?> builderType = old.loadClass("cc.nitea.NiteaOptions$Builder");
        Object builder = options.getMethod("builder", String.class).invoke(null, modId);
        builderType.getMethod("inAppPackages", String[].class).invoke(builder, (Object) new String[] {pkg});
        Object built = builderType.getMethod("build").invoke(builder);
        old.loadClass("cc.nitea.Nitea").getMethod("init", options).invoke(null, built);
    }

    private static Throwable thrownBy(String className) {
        Throwable t = new IllegalStateException("boom");
        t.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("java.util.Objects", "requireNonNull", "Objects.java", 1),
            new StackTraceElement(className, "tick", "X.java", 42),
            new StackTraceElement("net.minecraft.server.MinecraftServer", "tick", "MinecraftServer.java", 1),
        });
        return t;
    }
}
