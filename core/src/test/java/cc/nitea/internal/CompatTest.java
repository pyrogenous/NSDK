package cc.nitea.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.nitea.Nitea;
import cc.nitea.NiteaClient;
import cc.nitea.NiteaConsent;
import cc.nitea.NiteaOptions;
import cc.nitea.compat.Javac;
import cc.nitea.compat.TestApi;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The kill switch: mods built against a Nitea older than the supported window get Nitea turned off, and still run. */
class CompatTest {
    private static final List<String> LINES = Arrays.asList("0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0");

    @TempDir
    Path dir;

    @BeforeEach
    void freshGame() {
        Engine.reset();
        Engine.useGameDir(dir.resolve("game"));
        Transport.resetShared();
    }

    private final List<URLClassLoader> loaders = new java.util.ArrayList<>();

    @AfterEach
    void restoreLines() throws Exception {
        // Windows can't delete the temporary mod jars while a class loader holds them open
        for (URLClassLoader loader : loaders) loader.close();
        Compat.knownLines = Compat.LINES;
        Compat.runningLine = Compat.CURRENT_LINE;
        Engine.reset();
    }

    @Test
    void countsReleaseLinesBehind() {
        assertEquals(0, Compat.linesBehind("0.9.3", "0.9", LINES));
        assertEquals(1, Compat.linesBehind("0.8.0", "0.9", LINES));
        assertEquals(5, Compat.linesBehind("0.4.0", "0.9", LINES));
        assertEquals(6, Compat.linesBehind("0.3.0", "0.9", LINES));
        // Lines that were never released in between don't count
        assertEquals(1, Compat.linesBehind("0.9.0", "1.0", LINES));
        // Older than any release: every line counts
        assertEquals(8, Compat.linesBehind("0.1.0", "0.9", LINES));
        // Built with a newer Nitea than the one running
        assertEquals(-1, Compat.linesBehind("1.0.0", "0.9", LINES));
        // Not a version: never a reason to turn Nitea off
        assertEquals(0, Compat.linesBehind("dev", "0.9", LINES));
    }

    @Test
    void theSupportedWindowIsFiveLines() {
        assertEquals(5, Compat.SUPPORTED_LINES);
        assertFalse(new Compat.Result("0.4.0", Compat.linesBehind("0.4.0", "0.9", LINES)).tooOld());
        assertTrue(new Compat.Result("0.3.0", Compat.linesBehind("0.3.0", "0.9", LINES)).tooOld());
    }

    @Test
    void readsTheNestedJarOfEveryLoader() {
        // NeoForge and Forge Jar-in-Jar metadata
        assertEquals("0.3.0", Compat.nestedVersion("{\"jars\":[{\"identifier\":{\"group\":\"cc.nitea\",\"artifact\":\"nitea-neoforge-26.2\"},"
                + "\"version\":{\"range\":\"[0.3.0,)\",\"artifactVersion\":\"0.3.0\"},\"path\":\"META-INF/jarjar/nitea-neoforge-26.2-0.3.0.jar\"}]}"));
        // Fabric's fabric.mod.json
        assertEquals("0.4.1", Compat.nestedVersion("{\"id\":\"mymod\",\"jars\":[{\"file\":\"META-INF/jars/nitea-fabric-1.21.11-0.4.1.jar\"}]}"));
        assertEquals("1.0.0-beta.2", Compat.nestedVersion("META-INF/jarjar/nitea-forge-1.21.1-1.0.0-beta.2.jar"));
        assertNull(Compat.nestedVersion("META-INF/jarjar/othermod-26.2-1.0.0.jar"));
        assertNull(Compat.nestedVersion(null));
    }

    // A released mod jar: its main class, plus the Nitea it bundles as a nested jar
    private Class<?> modBuiltWith(String niteaVersion, String modId, String nestedDir) throws Exception {
        Path src = dir.resolve("src").resolve(modId);
        Files.createDirectories(src.resolve(modId));
        Files.write(src.resolve(modId).resolve("Main.java"), ("package " + modId + "; public final class Main {}").getBytes(StandardCharsets.UTF_8));
        Path classes = dir.resolve("classes").resolve(modId);
        Javac.compile(src, List.of(), classes);
        Path jar = dir.resolve(modId + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(file));
                    out.closeEntry();
                }
            }
            out.putNextEntry(new JarEntry(nestedDir + "nitea-neoforge-26.2-" + niteaVersion + ".jar"));
            out.write(new byte[] {0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            out.closeEntry();
        }
        URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, CompatTest.class.getClassLoader());
        loaders.add(loader);
        return loader.loadClass(modId + ".Main");
    }

    @Test
    void findsTheNiteaAModJarBundles() throws Exception {
        assertEquals("0.3.0", Compat.check("jarjarmod", modBuiltWith("0.3.0", "jarjarmod", "META-INF/jarjar/")).builtWith);
        assertEquals("0.2.5", Compat.check("includemod", modBuiltWith("0.2.5", "includemod", "META-INF/jars/")).builtWith);
        // A development environment: classes in a directory, nothing to tell
        Compat.Result dev = Compat.check("devmod", CompatTest.class);
        assertNull(dev.builtWith);
        assertFalse(dev.tooOld());
        assertFalse(Compat.check("noowner", null).tooOld());
    }

    @Test
    void findsTheNiteaAFabricModIncludes() throws Exception {
        Path resources = dir.resolve("fabric");
        Files.createDirectories(resources);
        Files.write(resources.resolve("fabric.mod.json"),
                "{\"schemaVersion\":1,\"id\":\"fabricmod\",\"jars\":[{\"file\":\"META-INF/jars/nitea-fabric-1.21.1-0.3.2.jar\"}]}".getBytes(StandardCharsets.UTF_8));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {resources.toUri().toURL()}, null)) {
            assertEquals("0.3.2", Compat.fabricIncluded("fabricmod", loader));
            assertNull(Compat.fabricIncluded("othermod", loader));
        }
    }

    @Test
    void turnsNiteaOffForAModBuiltWithAVersionTooOld() throws Exception {
        // As if this were Nitea 0.9, five lines after 0.4: a mod built with 0.3 is out of the window
        Compat.knownLines = LINES;
        Class<?> owner = modBuiltWith("0.3.0", "ancientmod", "META-INF/jarjar/");
        try (TestApi api = new TestApi("nt_ancient", 0)) {
            NiteaClient client = initAs("ancientmod", owner, api, "0.9");
            NiteaConsent.grant();
            // The mod keeps working: every call is accepted and does nothing
            assertNull(client.captureException(new IllegalStateException("never sent")));
            assertNull(client.reportBug("never sent"));
            client.addBreadcrumb("a", "b");
            client.setTag("a", "b");
            assertFalse(client.isEnabled());
            assertEquals("ancientmod", client.modId());
            client.close(Duration.ofSeconds(1));
            // Not registered: its errors are nobody's, and the consent screen doesn't list it
            assertFalse(NiteaConsent.mods().contains("ancientmod"));
            assertNull(api.requests().poll(300, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void keepsNiteaOnForTheLastFiveLines() throws Exception {
        Compat.knownLines = LINES;
        Class<?> owner = modBuiltWith("0.4.0", "recentmod", "META-INF/jarjar/");
        try (TestApi api = new TestApi("nt_recent", 0)) {
            NiteaClient client = initAs("recentmod", owner, api, "0.9");
            NiteaConsent.grant();
            assertTrue(client.isEnabled());
            client.captureMessage("still supported", cc.nitea.Level.WARNING);
            TestApi.Request request = api.requests().poll(5, TimeUnit.SECONDS);
            assertTrue(request != null && request.valid() && request.body().contains("still supported"));
            assertTrue(NiteaConsent.mods().contains("recentmod"));
            client.close(Duration.ofSeconds(1));
        }
    }

    // Nitea.init as if the running Nitea were `running`
    private NiteaClient initAs(String modId, Class<?> owner, TestApi api, String running) {
        Compat.runningLine = running;
        return Nitea.init(NiteaOptions.builder(modId).owner(owner).sdkKey("nt_" + modId.replace("mod", "")).endpoint(api.endpoint())
                .gameDir(dir.resolve("game")).build());
    }
}
