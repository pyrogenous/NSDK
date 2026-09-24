package cc.nitea.internal;

/**
 * The texts of Nitea's consent and preferences screens, shared by every loader and Minecraft version. A library has
 * no resource pack to hold translations, so they live here.
 */
public final class ConsentText {
    public static final String BUTTON = "Nitea analytics";
    public static final String BUTTON_SHORT = "N";
    public static final String BUTTON_TOOLTIP = "Nitea analytics preferences";
    public static final String CONSENT_TITLE = "Help mod developers fix bugs?";
    public static final String PREFERENCES_TITLE = "Nitea analytics preferences";
    public static final String INTRO = "Some of your mods use Nitea to send anonymous crash and error reports to their developers:";
    public static final String DETAILS = "A report contains what went wrong and your game setup: Minecraft, loader, Java and OS versions, CPU and memory. Never your username, player UUID or IP address. Each mod only receives the errors it caused.";
    public static final String CHANGE = "This choice applies to every mod using Nitea. You can change it any time from the Nitea button on the title screen.";
    public static final String STATUS = "Reports are currently: ";
    public static final String STATUS_GRANTED = "allowed";
    public static final String STATUS_DENIED = "not allowed";
    public static final String STATUS_UNDECIDED = "not chosen yet";
    public static final String ALLOW = "Allow reports";
    public static final String DENY = "Don't allow";
    public static final String STOP = "Stop reports";
    public static final String PRIVACY = "Privacy policy";
    public static final String DONE = "Done";
    public static final String PRIVACY_POLICY_URL = "https://nitea.cc/legal/privacy-policy";

    /** Colors (0xRRGGBB) for the status line. */
    public static final int GREEN = 0x55FF55;
    public static final int RED = 0xFF5555;
    public static final int YELLOW = 0xFFFF55;
    public static final int GRAY = 0xA0A0A0;
    public static final int WHITE = 0xFFFFFF;

    private ConsentText() {}

    /** The status word for the current choice. */
    public static String status(String consent) {
        if (Engine.GRANTED.equals(consent)) return STATUS_GRANTED;
        if (Engine.DENIED.equals(consent)) return STATUS_DENIED;
        return STATUS_UNDECIDED;
    }

    /** The status color for the current choice. */
    public static int statusColor(String consent) {
        if (Engine.GRANTED.equals(consent)) return GREEN;
        if (Engine.DENIED.equals(consent)) return RED;
        return YELLOW;
    }
}
