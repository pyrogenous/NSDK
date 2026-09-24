package cc.nitea.fabric;

import cc.nitea.NiteaConsent;
import cc.nitea.fabric.client.NiteaFabricClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric side of the Nitea library. On Fabric, Nitea is a library mod without an entry point. {@code Nitea.init}
 * calls {@link #install()}, i.e. from the initializer of the first mod that starts Nitea, and it sets up Nitea's own
 * screens once for the whole game.
 */
public final class NiteaFabric {
    private static boolean installed;

    private NiteaFabric() {}

    /** Called by the core; safe to call any number of times. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        FabricLoader loader = FabricLoader.getInstance();
        NiteaConsent.useGameDir(loader.getGameDir());
        // Screens only exist on the client; a dedicated server reads the choice from the settings file
        if (loader.getEnvironmentType() == EnvType.CLIENT) NiteaFabricClient.install();
    }
}
