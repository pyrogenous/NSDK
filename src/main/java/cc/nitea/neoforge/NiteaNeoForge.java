package cc.nitea.neoforge;

import cc.nitea.NiteaConsent;
import cc.nitea.neoforge.client.NiteaClientUi;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

/**
 * NeoForge side of the Nitea library. Nitea is a game library, not a mod: it has no entry point and doesn't show
 * up in the mod list. {@code Nitea.init} calls {@link #install()}, i.e. from the constructor of the first mod that
 * starts Nitea, and it sets up Nitea's own screens once for the whole game.
 */
public final class NiteaNeoForge {
    private static boolean installed;

    private NiteaNeoForge() {}

    /** Called by the core; safe to call any number of times. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        NiteaConsent.useGameDir(FMLPaths.GAMEDIR.get());
        // Screens only exist on the client; a dedicated server reads the choice from the settings file
        if (FMLEnvironment.getDist().isClient()) NiteaClientUi.install();
    }
}
