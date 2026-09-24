package cc.nitea.neoforge.client;

import cc.nitea.NiteaConsent;
import cc.nitea.internal.ConsentText;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Client side of Nitea: asks the player once, on the title screen, whether mods may send reports, and adds a
 * Nitea button next to the title screen's square buttons to change that choice later.
 */
public final class NiteaClientUi {
    private static final ResourceLocation ICON = new ResourceLocation("nitea", "dynamic/icon");
    private static final int ICON_SIZE = 20;
    private static final int ICON_SPACING = 4;

    // Asked at most once per launch, even if the player leaves the screen some other way
    private static boolean askedThisLaunch;
    private static boolean iconLoaded;

    private NiteaClientUi() {}

    public static void install() {
        NiteaConsent.markPromptAvailable();
        MinecraftForge.EVENT_BUS.addListener(NiteaClientUi::onScreenOpening);
        MinecraftForge.EVENT_BUS.addListener(NiteaClientUi::onScreenInit);
    }

    // The first time the title screen opens, ask instead; the answer is saved, so a choice is never asked again
    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (askedThisLaunch || !(event.getNewScreen() instanceof TitleScreen)) return;
        if (!NiteaConsent.shouldAsk() || NiteaConsent.mods().isEmpty()) return;
        askedThisLaunch = true;
        event.setNewScreen(new NiteaConsentScreen(event.getNewScreen(), true));
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;
        Screen title = event.getScreen();
        Button button = new IconButton(pressed -> Minecraft.getInstance().setScreen(new NiteaConsentScreen(title, false)));
        button.setTooltip(Tooltip.create(Component.literal(ConsentText.BUTTON_TOOLTIP)));
        place(button, event.getListenersList());
        event.addListener(button);
    }

    // Left of the leftmost square button (language, accessibility...), on its row. When another mod rearranged the
    // screen and there is none, the top-left corner.
    private static void place(Button button, List<GuiEventListener> listeners) {
        AbstractWidget leftmost = null;
        for (GuiEventListener listener : listeners) {
            if (!(listener instanceof AbstractWidget)) continue;
            AbstractWidget widget = (AbstractWidget) listener;
            if (widget.getWidth() != ICON_SIZE || widget.getHeight() != ICON_SIZE) continue;
            if (leftmost == null || widget.getX() < leftmost.getX()) leftmost = widget;
        }
        if (leftmost == null || leftmost.getX() < ICON_SIZE + ICON_SPACING) button.setPosition(4, 4);
        else button.setPosition(leftmost.getX() - ICON_SIZE - ICON_SPACING, leftmost.getY());
    }

    // Uploads icon.png from this jar the first time the button is drawn
    private static boolean loadIcon() {
        if (iconLoaded) return true;
        try (InputStream in = NiteaClientUi.class.getResourceAsStream("icon.png")) {
            if (in == null) return false;
            Minecraft.getInstance().getTextureManager().register(ICON, new DynamicTexture(NativeImage.read(in)));
            iconLoaded = true;
        } catch (IOException ignored) {
            // No icon: the button still works, it's just blank
        }
        return iconLoaded;
    }

    /** A square button like the title screen's language and accessibility buttons, with Nitea's icon. */
    private static final class IconButton extends Button {
        IconButton(OnPress onPress) {
            super(0, 0, ICON_SIZE, ICON_SIZE, Component.literal(ConsentText.BUTTON), onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // The button background without its label, then the icon on top
            Component label = getMessage();
            setMessage(Component.empty());
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            setMessage(label);
            if (loadIcon()) graphics.blit(ICON, getX() + 2, getY() + 2, 0, 0, 16, 16, 16, 16);
        }
    }
}
