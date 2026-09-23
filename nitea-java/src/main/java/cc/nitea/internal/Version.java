package cc.nitea.internal;

/** The library version, read from the jar manifest. */
public final class Version {
    private Version() {}

    public static String get() {
        String version = Version.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
