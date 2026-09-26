package cc.nitea.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The old releases kept in src/test/resources/compat/nitea-core-&lt;version&gt;.jar (see AGENTS.md). */
public final class Fixtures {
    private static final Pattern JAR = Pattern.compile("nitea-core-((\\d+)\\.(\\d+)\\.\\d+.*)\\.jar");

    private Fixtures() {}

    public record Release(String version, String line, Path jar) {
        @Override
        public String toString() {
            return version;
        }
    }

    public static Path resources() {
        return Paths.get(System.getProperty("nitea.testResources", "src/test/resources"));
    }

    /** Every kept release, oldest first. */
    public static List<Release> releases() throws IOException {
        try (Stream<Path> files = Files.list(resources().resolve("compat"))) {
            return files.map(file -> {
                        Matcher m = JAR.matcher(file.getFileName().toString());
                        return m.matches() ? new Release(m.group(1), m.group(2) + "." + m.group(3), file) : null;
                    })
                    .filter(r -> r != null)
                    .sorted((a, b) -> compareVersions(a.version(), b.version()))
                    .collect(Collectors.toList());
        }
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            try {
                int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
                if (c != 0) return c;
            } catch (NumberFormatException e) {
                int c = x[i].compareTo(y[i]);
                if (c != 0) return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }
}
