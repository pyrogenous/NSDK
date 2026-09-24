package cc.nitea.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns exceptions (live or from crash report text) into the API's {@code exception.values} shape. */
public final class StackTraces {
    private static final int MAX_CHAIN = 10;
    private static final int MAX_FRAMES = 250;

    private final List<String> inAppPackages;

    public StackTraces(List<String> inAppPackages) {
        this.inAppPackages = inAppPackages;
    }

    /** True when a class belongs to the mod's own code. */
    public boolean inApp(String className) {
        for (String pkg : inAppPackages) {
            if (className.equals(pkg) || className.startsWith(pkg + ".")) return true;
        }
        return false;
    }

    /** Thrown exception first, then its "Caused by" chain; frames newest first, as in a Java stack trace. */
    public List<Map<String, Object>> values(Throwable throwable) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Throwable t : chain(throwable)) {
            List<Map<String, Object>> frames = new ArrayList<>();
            for (StackTraceElement element : t.getStackTrace()) {
                if (frames.size() == MAX_FRAMES) break;
                frames.add(frame(element.getClassName(), element.getMethodName(), element.getFileName(), element.getLineNumber()));
            }
            values.add(exception(t.getClass().getName(), t.getMessage(), frames));
        }
        return values;
    }

    private static List<Throwable> chain(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = throwable; t != null && chain.size() < MAX_CHAIN && seen.add(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    private Map<String, Object> frame(String className, String method, String file, int line) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("module", Text.cut(className, 500));
        frame.put("function", Text.cut(method, 500));
        frame.put("filename", Text.cut(file, 500));
        if (line >= 0) frame.put("lineno", line);
        frame.put("inApp", inApp(className));
        return frame;
    }

    private static Map<String, Object> exception(String type, String message, List<Map<String, Object>> frames) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", Text.cut(type, 500));
        value.put("value", Text.cut(message, 5000));
        if (!frames.isEmpty()) value.put("stacktrace", Map.of("frames", frames));
        return value;
    }

    // --- Stack traces printed as text (Minecraft crash reports) ---

    // "java.lang.IllegalStateException: message" or "Caused by: ..."; the type must look like a class name
    private static final Pattern HEADER = Pattern.compile("^(?:Caused by: )?((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*)(?::\\s?(.*))?$");
    // "\tat module@1.0/com.example.Foo.bar(Foo.java:12) ~[...]"
    private static final Pattern FRAME = Pattern.compile("^\\s+at\\s+(?:\\S*/)?([\\w$.]+)\\.([\\w$<>]+)\\(([^:)]*)(?::(\\d+))?\\)");

    /** Parses the first stack trace in {@code text}. Returns an empty list when there is none. */
    public List<Map<String, Object>> parse(String text) {
        List<Map<String, Object>> values = new ArrayList<>();
        String type = null;
        String message = null;
        List<Map<String, Object>> frames = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher frame = FRAME.matcher(line);
            if (type != null && frame.find()) {
                if (frames.size() < MAX_FRAMES) {
                    int lineNo = frame.group(4) != null ? Integer.parseInt(frame.group(4)) : -1;
                    String file = frame.group(3).isEmpty() ? null : frame.group(3);
                    frames.add(frame(frame.group(1), frame.group(2), file, lineNo));
                }
                continue;
            }
            if (line.trim().startsWith("...") && type != null) continue; // "... 12 more"
            Matcher header = HEADER.matcher(line);
            boolean isCause = line.startsWith("Caused by: ");
            if (header.matches() && (type == null || isCause)) {
                if (type != null) values.add(exception(type, message, frames));
                if (values.size() == MAX_CHAIN) return values;
                type = header.group(1);
                message = header.group(2);
                frames = new ArrayList<>();
                continue;
            }
            // Anything else after frames ends the trace
            if (type != null && !frames.isEmpty()) break;
        }
        if (type != null) values.add(exception(type, message, frames));
        return values;
    }
}
