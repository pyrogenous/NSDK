package cc.nitea.internal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps mods built against an older Nitea working with the newer copy the loader picked.
 *
 * <p>Every mod bundles its own Nitea, but the game runs only one copy: the newest. A mod built against Nitea 0.3 may
 * therefore run against 0.9. Nitea guarantees that the last {@link #SUPPORTED_LINES} release lines before the
 * current one keep working exactly as documented (see AGENTS.md and the compatibility tests). A mod built against an
 * even older line still starts, and every public method still exists so it can't crash on a missing one, but Nitea
 * is turned off for it and says so in the log.
 *
 * <p>Which Nitea a mod was built with is read from the mod's own jar: the nested jar it bundles
 * ({@code META-INF/jarjar/nitea-neoforge-26.2-0.3.0.jar} on NeoForge and Forge, {@code META-INF/jars/...} on Fabric)
 * or the metadata the loader keeps about it. When that can't be found (a development environment, a mod that shades
 * Nitea into its own classes) the mod is assumed to be up to date: Nitea never turns itself off on a guess.
 */
public final class Compat {
    /**
     * Every Nitea release line (major.minor), oldest first. Add the new line here when releasing it, together with
     * its API snapshot and compatibility fixture (see AGENTS.md).
     */
    public static final List<String> LINES = Collections.unmodifiableList(Arrays.asList("0.2", "0.3", "0.4"));

    /** The line this copy of the library belongs to. Must match nitea_version in gradle.properties. */
    public static final String CURRENT_LINE = "0.4";

    /** How many release lines before the current one are fully supported. Older ones are turned off. */
    public static final int SUPPORTED_LINES = 5;

    // What check() counts with; only replaced by tests, to simulate releases that don't exist yet
    static volatile List<String> knownLines = LINES;
    static volatile String runningLine = CURRENT_LINE;

    // nitea-<loader>-<minecraft>-<version>.jar, as bundled by Jar-in-Jar (NeoForge, Forge) and Loom's include (Fabric)
    private static final Pattern NESTED_JAR = Pattern.compile("nitea-(?:neoforge|forge|fabric)-[0-9][0-9.]*-([0-9][0-9A-Za-z.+-]*?)\\.jar");
    private static final Pattern FABRIC_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private Compat() {}

    /** What {@link #check} found. */
    public static final class Result {
        /** The Nitea version the mod was built with, or null when it couldn't be told. */
        public final String builtWith;
        /** How many release lines the mod's Nitea is behind the running one (0 when current or unknown). */
        public final int linesBehind;

        Result(String builtWith, int linesBehind) {
            this.builtWith = builtWith;
            this.linesBehind = linesBehind;
        }

        /** True when the mod was built against a Nitea older than the supported window: Nitea stays off for it. */
        public boolean tooOld() {
            return linesBehind > SUPPORTED_LINES;
        }

        /** True when the mod was built against a newer Nitea than the one running (another mod shipped an old copy). */
        public boolean newerThanRunning() {
            return linesBehind < 0;
        }
    }

    /** Checks the Nitea version the mod owning {@code owner} was built with against the running one. */
    public static Result check(String modId, Class<?> owner) {
        String builtWith;
        try {
            builtWith = owner != null ? builtWith(modId, owner) : null;
        } catch (RuntimeException | LinkageError e) {
            builtWith = null;
        }
        return new Result(builtWith, builtWith != null ? linesBehind(builtWith, runningLine, knownLines) : 0);
    }

    /**
     * How many release lines {@code version} is behind {@code current}: the number of known lines newer than
     * {@code version}'s, up to and including {@code current}. Negative when {@code version} is newer than
     * {@code current}. Lines missing from {@code lines} are placed by comparing version numbers.
     */
    static int linesBehind(String version, String current, List<String> lines) {
        String line = line(version);
        String currentLine = line(current);
        if (line == null || currentLine == null) return 0;
        int order = compare(line, currentLine);
        if (order == 0) return 0;
        if (order > 0) return -1;
        int behind = 0;
        for (String known : lines) {
            if (compare(known, line) > 0 && compare(known, currentLine) <= 0) behind++;
        }
        // The current line itself may not be listed yet (a development build): it still counts as one step
        if (!lines.contains(currentLine)) behind++;
        return behind;
    }

    /** "0.3.1" -> "0.3"; null when it isn't a version number. */
    static String line(String version) {
        if (version == null) return null;
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(version.trim());
        return m.find() ? Integer.parseInt(m.group(1)) + "." + Integer.parseInt(m.group(2)) : null;
    }

    // Compares two "major.minor" lines numerically
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < 2; i++) {
            int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (c != 0) return c;
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Finding which Nitea a mod bundles
    // ------------------------------------------------------------------------------------------------------------

    /** The Nitea version bundled in the jar of the mod owning {@code owner}, or null. */
    static String builtWith(String modId, Class<?> owner) {
        // NeoForge and Forge put mods in named modules: META-INF isn't a package, so the module's own file is readable
        if (Engine.moduleName(owner) != null) {
            String found = nestedVersion(read(owner, "/META-INF/jarjar/metadata.json"));
            if (found != null) return found;
        }
        // The mod's jar itself, when the loader gives its location (Fabric, and most loaders)
        File jar = jarOf(owner);
        if (jar != null) {
            String found = nestedVersionInJar(jar);
            if (found != null) return found;
        }
        // Fabric: the mod's fabric.mod.json lists the jars it includes
        return fabricIncluded(modId, owner.getClassLoader());
    }

    /** The version in the first {@code nitea-<loader>-<minecraft>-<version>.jar} mentioned in {@code text}. */
    static String nestedVersion(String text) {
        if (text == null) return null;
        Matcher m = NESTED_JAR.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    static String nestedVersionInJar(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("META-INF/jarjar/") || name.startsWith("META-INF/jars/")) {
                    String found = nestedVersion(name.substring(name.lastIndexOf('/') + 1));
                    if (found != null) return found;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not a readable jar
        }
        return null;
    }

    private static File jarOf(Class<?> owner) {
        try {
            CodeSource source = owner.getProtectionDomain().getCodeSource();
            URL location = source != null ? source.getLocation() : null;
            if (location == null || !"file".equals(location.getProtocol())) return null;
            File file = new File(location.toURI());
            return file.isFile() ? file : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static String fabricIncluded(String modId, ClassLoader loader) {
        if (loader == null) return null;
        try {
            Enumeration<URL> files = loader.getResources("fabric.mod.json");
            while (files.hasMoreElements()) {
                String json = read(files.nextElement());
                if (json == null) continue;
                Matcher id = FABRIC_ID.matcher(json);
                if (id.find() && modId.equals(id.group(1))) return nestedVersion(json);
            }
        } catch (IOException | RuntimeException e) {
            // No fabric.mod.json to read
        }
        return null;
    }

    private static String read(Class<?> owner, String resource) {
        try (InputStream in = owner.getResourceAsStream(resource)) {
            return in != null ? read(in) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String read(URL url) {
        try (InputStream in = url.openStream()) {
            return read(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        // Metadata files are small; anything bigger isn't one
        while ((n = in.read(buffer)) != -1 && out.size() < 1 << 20) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
