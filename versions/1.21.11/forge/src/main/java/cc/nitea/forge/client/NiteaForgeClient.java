package cc.nitea.forge.client;

import cc.nitea.client.NiteaScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.fml.ModList;

/** Connects Nitea's screens to Forge's screen events. Client only. */
public final class NiteaForgeClient {
    private NiteaForgeClient() {}

    public static void install() {
        NiteaScreens.install(modId -> ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getDisplayName()).orElse(null));
        ScreenEvent.Opening.BUS.addListener(NiteaForgeClient::opening);
        ScreenEvent.Init.Post.BUS.addListener(NiteaForgeClient::initialized);
    }

    private static void opening(ScreenEvent.Opening event) {
        Screen screen = NiteaScreens.opening(event.getNewScreen());
        if (screen != event.getNewScreen()) event.setNewScreen(screen);
    }

    private static void initialized(ScreenEvent.Init.Post event) {
        NiteaScreens.initialized(event.getScreen(), event.getListenersList(), event::addListener);
    }
}
