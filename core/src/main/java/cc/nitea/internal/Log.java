package cc.nitea.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Logs through SLF4J when the game provides it (Fabric, NeoForge and Forge all do), so messages land in the normal
 * game log. Falls back to stderr. Looked up reflectively to keep the library dependency-free.
 */
public final class Log {
    private final String prefix;
    private final boolean debugEnabled;
    private final Object logger;
    private final MethodHandle info;
    private final MethodHandle warn;
    private final MethodHandle error;

    public Log(String modId, boolean debugEnabled) {
        this.prefix = "[Nitea/" + modId + "] ";
        this.debugEnabled = debugEnabled;
        Object logger = null;
        MethodHandle info = null;
        MethodHandle warn = null;
        MethodHandle error = null;
        try {
            Class<?> factory = Class.forName("org.slf4j.LoggerFactory");
            Class<?> loggerClass = Class.forName("org.slf4j.Logger");
            logger = factory.getMethod("getLogger", String.class).invoke(null, "Nitea");
            MethodType type = MethodType.methodType(void.class, String.class);
            info = MethodHandles.publicLookup().findVirtual(loggerClass, "info", type);
            warn = MethodHandles.publicLookup().findVirtual(loggerClass, "warn", type);
            error = MethodHandles.publicLookup().findVirtual(loggerClass, "error", type);
        } catch (Throwable ignored) {
            // No SLF4J: stderr it is
        }
        this.logger = logger;
        this.info = info;
        this.warn = warn;
        this.error = error;
    }

    public void info(String message) {
        emit(info, message);
    }

    public void warn(String message) {
        emit(warn, message);
    }

    /** Something the mod's author has to fix; Nitea is off for the mod until they do. */
    public void error(String message) {
        emit(error, message);
    }

    /** Only logged when the options enable debug output. */
    public void debug(String message) {
        if (debugEnabled) emit(info, message);
    }

    private void emit(MethodHandle handle, String message) {
        if (logger != null && handle != null) {
            try {
                handle.invoke(logger, prefix + message);
                return;
            } catch (Throwable ignored) {
                // fall through
            }
        }
        System.err.println(prefix + message);
    }
}
