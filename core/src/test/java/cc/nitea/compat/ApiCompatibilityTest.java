package cc.nitea.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cc.nitea.Nitea;
import cc.nitea.internal.Compat;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Binary compatibility: the game runs only the newest Nitea any mod bundles, so every mod calls into a Nitea that may
 * be newer than the one it was built with. A missing or changed signature would crash that mod with a
 * {@link NoSuchMethodError}. So every signature ever released stays, forever, even for releases outside the
 * supported window (there Nitea is only turned off, see {@link Compat}).
 *
 * <p>The API of each release is kept in src/test/resources/api/&lt;line&gt;.txt. Run
 * {@code ./gradlew -p core test -PupdateApi} to write the current one after adding API.
 */
class ApiCompatibilityTest {
    private static final boolean UPDATE = Boolean.getBoolean("nitea.updateApi");

    private static TreeSet<String> current() throws Exception {
        return ApiSurface.of(Nitea.class.getClassLoader(), ApiSurface.classesOfBuild(Nitea.class));
    }

    private static Path snapshot(String line) {
        return Fixtures.resources().resolve("api").resolve(line + ".txt");
    }

    private static TreeSet<String> read(Path file) throws Exception {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static void write(Path file, String header, TreeSet<String> api) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("# " + header);
        lines.add("# Every signature below must exist, unchanged, in every later Nitea release (see AGENTS.md).");
        lines.addAll(api);
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    @Test
    void currentApiSnapshotIsUpToDate() throws Exception {
        Path file = snapshot(Compat.CURRENT_LINE);
        TreeSet<String> api = current();
        if (UPDATE || !Files.exists(file)) write(file, "Public API of Nitea " + Compat.CURRENT_LINE, api);
        assertEquals(read(file), api, "The public API changed: run ./gradlew -p core test -PupdateApi and commit " + file.getFileName()
                + " (only additions are allowed, see AGENTS.md)");
    }

    @Test
    void snapshotsOfKeptReleasesMatchTheirJars() throws Exception {
        for (Fixtures.Release release : Fixtures.releases()) {
            TreeSet<String> api;
            try (URLClassLoader loader = new URLClassLoader(new URL[] {release.jar().toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                api = ApiSurface.of(loader, ApiSurface.classesInJar(release.jar()));
            }
            Path file = snapshot(release.line());
            // The current line's snapshot is the build's own, checked above
            if (release.line().equals(Compat.CURRENT_LINE)) continue;
            if (UPDATE || !Files.exists(file)) write(file, "Public API of Nitea " + release.line() + " (from compat/" + release.jar().getFileName() + ")", api);
            assertEquals(read(file), api, "api/" + release.line() + ".txt doesn't match " + release.jar().getFileName());
        }
    }

    @Test
    void everySignatureEverReleasedStillExists() throws Exception {
        TreeSet<String> api = current();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(Fixtures.resources().resolve("api"))) {
            for (Path file : files.sorted().collect(Collectors.toList())) {
                for (String signature : read(file)) {
                    if (!api.contains(signature)) missing.add(file.getFileName() + ": " + signature);
                }
            }
        }
        if (!missing.isEmpty()) {
            fail("Signatures removed or changed since an earlier release. Mods built against it would crash with "
                    + "NoSuchMethodError; put them back (deprecated, as no-ops if needed):\n  " + String.join("\n  ", missing));
        }
    }

    @Test
    void everyReleasedLineHasASnapshotAndAFixture() throws Exception {
        List<String> kept = Fixtures.releases().stream().map(Fixtures.Release::line).collect(Collectors.toList());
        for (String line : Compat.LINES) {
            assertTrue(Files.exists(snapshot(line)), "api/" + line + ".txt is missing");
            if (!line.equals(Compat.CURRENT_LINE)) {
                assertTrue(kept.contains(line), "compat/nitea-core-" + line + ".x.jar is missing: run ./gradlew -p core compatFixture when releasing " + line);
            }
        }
        assertEquals(Compat.CURRENT_LINE, Compat.LINES.get(Compat.LINES.size() - 1), "Compat.LINES must end with CURRENT_LINE");
    }

    @Test
    void currentLineMatchesTheBuildVersion() {
        String version = System.getProperty("nitea.version");
        if (version == null) return;
        assertTrue(version.startsWith(Compat.CURRENT_LINE + "."),
                "gradle.properties says " + version + " but Compat.CURRENT_LINE is " + Compat.CURRENT_LINE);
    }
}
