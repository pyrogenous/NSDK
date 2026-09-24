package cc.nitea.neoforge.client;

import cc.nitea.client.NiteaScreens;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Connects Nitea's screens to NeoForge's screen events. Client only. */
public final class NiteaNeoForgeClient {
    private NiteaNeoForgeClient() {}

    public static void install() {
        NiteaScreens.install(modId -> ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getDisplayName()).orElse(null));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, event -> {
            Screen screen = NiteaScreens.opening(event.getNewScreen());
            if (screen != event.getNewScreen()) event.setNewScreen(screen);
        });
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class,
                event -> NiteaScreens.initialized(event.getScreen(), event.getListenersList(), event::addListener));
    }
}
