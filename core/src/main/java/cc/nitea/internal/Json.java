package cc.nitea.internal;

import java.util.Collection;
import java.util.Map;

/** Minimal JSON writer, so the library needs no dependencies. Handles maps, collections, strings, numbers and booleans. */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(1024);
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            string(out, (String) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                // Absent optional fields are left out rather than sent as null
                if (entry.getValue() == null) continue;
                if (!first) out.append(',');
                first = false;
                string(out, String.valueOf(entry.getKey()));
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?>) {
            Collection<?> list = (Collection<?>) value;
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
        } else {
            string(out, value.toString());
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    /** Reads a top-level string field from a small JSON response, e.g. {@code "id"}. Good enough for API replies. */
    public static String readString(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Reads a top-level number field from a small JSON response, or null. */
    public static Long readLong(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*(-?\\d{1,18})").matcher(json);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }
}
