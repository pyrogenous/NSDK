package cc.nitea.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Minecraft catches most crashes itself, writes {@code crash-reports/crash-*.txt} and exits, so an uncaught
 * exception handler never sees them. Instead, on the next launch, the reports written since the last scan are read
 * and the ones this mod caused (see {@link Engine#culprit(String)}) are sent as crashes. Reports written before the
 * player allowed reporting are never sent.
 */
public final class CrashReports {
    private static final String STATE_KEY = "crashReportsScannedAt";
    private static final int MAX_PER_LAUNCH = 5;

    /** A crash report that concerns the mod. */
    public record Found(String fileName, Instant time, String description, String text) {}

    private CrashReports() {}

    public static List<Found> scan(Path gameDir, String modId, Settings settings) {
        long now = System.currentTimeMillis();
        String last = settings.state(modId, STATE_KEY);
        settings.saveState(modId, STATE_KEY, Long.toString(now));
        // First launch with Nitea: older reports predate the mod's reporting, skip them
        if (last == null) return List.of();
        // Nor anything from before the player opted in
        long since = Math.max(parseLong(last), Engine.consentChangedAt());

        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) return List.of();
        List<Found> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> recent = files
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .filter(p -> modified(p) > since)
                    .sorted((a, b) -> Long.compare(modified(b), modified(a)))
                    .limit(MAX_PER_LAUNCH)
                    .toList();
            for (Path file : recent) {
                String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                String trace = trace(text);
                if (!modId.equals(Engine.culprit(trace))) continue;
                found.add(new Found(file.getFileName().toString(), Instant.ofEpochMilli(modified(file)), description(text), trace));
            }
        } catch (IOException ignored) {
            // Unreadable folder: nothing to report
        }
        return found;
    }

    // The main stack trace starts after the "Description:" line
    private static String trace(String text) {
        int start = text.indexOf("Description:");
        if (start < 0) return text;
        int end = text.indexOf("\n\n\nA detailed walkthrough", start);
        return text.substring(start, end > 0 ? end : text.length());
    }

    private static String description(String text) {
        for (String line : text.split("\\R")) {
            if (line.startsWith("Description:")) return line.substring("Description:".length()).trim();
        }
        return "Minecraft crashed";
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
