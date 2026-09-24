package cc.nitea.fabric.mixin;

import cc.nitea.client.NiteaScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds Nitea's button once the title screen created its widgets (NeoForge's ScreenEvent.Init.Post). */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    private TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void nitea$initialized(CallbackInfo info) {
        NiteaScreens.initialized(this, children(), widget -> addRenderableWidget(widget));
    }
}
