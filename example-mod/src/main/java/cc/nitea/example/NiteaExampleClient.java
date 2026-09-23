package cc.nitea.example;

import static cc.nitea.example.NiteaExample.NITEA;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Client-only breadcrumbs. This class never loads on a dedicated server. */
@Mod(value = NiteaExample.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = NiteaExample.MODID, value = Dist.CLIENT)
public final class NiteaExampleClient {
    @SubscribeEvent
    static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> NITEA.setTag("language", Minecraft.getInstance().options.languageCode));
        NITEA.addBreadcrumb("lifecycle", "Client setup done");
    }

    @SubscribeEvent
    static void joinedWorld(ClientPlayerNetworkEvent.LoggingIn event) {
        // Only the kind of world, never the server address
        NITEA.addBreadcrumb("world", Minecraft.getInstance().isLocalServer() ? "Joined a singleplayer world" : "Joined a multiplayer server");
    }

    @SubscribeEvent
    static void leftWorld(ClientPlayerNetworkEvent.LoggingOut event) {
        NITEA.addBreadcrumb("world", "Left the world");
    }

    @SubscribeEvent
    static void screenOpened(ScreenEvent.Opening event) {
        NITEA.addBreadcrumb("ui", "Opened " + event.getNewScreen().getClass().getSimpleName(), cc.nitea.Level.DEBUG);
    }
}
