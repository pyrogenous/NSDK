package cc.nitea.internal;

import java.util.Locale;

/**
 * Opens a web page in the player's browser the way Minecraft itself does (through the OS, since the game runs with
 * AWT headless). Only used on the client, where the player is sitting in front of the game.
 */
public final class Browser {
    private Browser() {}

    public static boolean open(String url, Log log) {
        // Only plain web links, never anything the OS could interpret as a command
        if (url == null || !url.matches("https?://[\\w.:-]+(/[\\w./~%?&=+#-]*)?")) return false;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] command = os.contains("win")
                ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
                : os.contains("mac") ? new String[] {"open", url} : new String[] {"xdg-open", url};
        try {
            new ProcessBuilder(command).start();
            return true;
        } catch (Exception e) {
            log.warn("Could not open the browser: " + e);
            return false;
        }
    }
}
