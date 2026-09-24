package cc.nitea.forge;

import cc.nitea.NiteaConsent;
import cc.nitea.forge.client.NiteaForgeClient;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge side of the Nitea library. Nitea is a game library, not a mod: it has no entry point and doesn't show up in
 * the mod list. {@code Nitea.init} calls {@link #install()}, i.e. from the constructor of the first mod that starts
 * Nitea, and it sets up Nitea's own screens once for the whole game.
 */
public final class NiteaForge {
    private static boolean installed;

    private NiteaForge() {}

    /** Called by the core; safe to call any number of times. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        NiteaConsent.useGameDir(FMLPaths.GAMEDIR.get());
        // Screens only exist on the client; a dedicated server reads the choice from the settings file
        if (FMLEnvironment.dist.isClient()) NiteaForgeClient.install();
    }
}
