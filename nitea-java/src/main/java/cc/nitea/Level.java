package cc.nitea;

/** Severity of an event or breadcrumb. */
public enum Level {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    FATAL;

    /** Level name accepted by the events API ({@code debug} is sent as {@code info}). */
    String eventName() {
        return this == DEBUG ? "info" : name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Level name accepted for breadcrumbs ({@code fatal} is sent as {@code error}). */
    String breadcrumbName() {
        return this == FATAL ? "error" : name().toLowerCase(java.util.Locale.ROOT);
    }
}
