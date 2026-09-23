package cc.nitea.internal;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Small per-mod state in {@code config/nitea/state/<modId>.properties} (e.g. when crash reports were last
 * scanned). The player-facing settings (consent, installation ID) belong to the {@link Engine}.
 */
public final class Settings {
    private final Path dir;

    private Settings(Path dir) {
        this.dir = dir;
    }

    public static Settings of(Path gameDir) {
        return new Settings(gameDir.resolve("config").resolve("nitea"));
    }

    /** Reads a value from this mod's private state file. */
    public String state(String modId, String key) {
        return Engine.read(stateFile(modId)).getProperty(key);
    }

    public void saveState(String modId, String key, String value) {
        Path file = stateFile(modId);
        Properties props = Engine.read(file);
        props.setProperty(key, value);
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(writer, " Internal Nitea state, safe to delete");
            }
        } catch (IOException ignored) {
            // Worst case, old crash reports are checked again next launch
        }
    }

    private Path stateFile(String modId) {
        return dir.resolve("state").resolve(modId + ".properties");
    }
}
