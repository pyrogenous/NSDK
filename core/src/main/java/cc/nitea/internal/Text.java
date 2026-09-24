package cc.nitea.internal;

/** String limits matching what the API accepts. */
public final class Text {
    private Text() {}

    /** Shortens {@code value} to at most {@code max} characters (null stays null). */
    public static String cut(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max - 1) + "…";
    }

    /** True when {@code value} is empty or only whitespace (Java 8 has no String.isBlank). */
    public static boolean isBlank(String value) {
        return value.trim().isEmpty();
    }
}
