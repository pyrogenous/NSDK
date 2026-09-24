package cc.nitea.fabric.client;

import cc.nitea.client.NiteaScreens;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Turns on Nitea's screens on the Fabric client. Fabric has no screen events without Fabric API, so Nitea's mixins
 * (in {@code cc.nitea.fabric.mixin}) forward the two moments the screens need; they do nothing until this runs.
 */
public final class NiteaFabricClient {
    private NiteaFabricClient() {}

    public static void install() {
        NiteaScreens.install(modId -> FabricLoader.getInstance().getModContainer(modId).map(c -> c.getMetadata().getName()).orElse(null));
    }
}
