package cc.nitea.fabric.mixin;

import cc.nitea.client.NiteaScreens;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Lets Nitea replace the title screen with its consent screen the first time it opens (NeoForge's ScreenEvent.Opening). */
@Mixin(Gui.class)
abstract class ScreenOpeningMixin {
    // setScreen(screen) with a screen to open
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen nitea$opening(Screen screen) {
        return NiteaScreens.opening(screen);
    }

    // setScreen(null) outside a world picks the title screen itself
    @ModifyVariable(method = "setScreen", at = @At("STORE"), argsOnly = true)
    private Screen nitea$openingDefault(Screen screen) {
        return NiteaScreens.opening(screen);
    }
}
