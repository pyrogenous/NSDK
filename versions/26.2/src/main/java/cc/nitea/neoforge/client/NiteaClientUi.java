package cc.nitea.neoforge.client;

import cc.nitea.NiteaConsent;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client side of Nitea: asks the player once, on the title screen, whether mods may send reports, and adds a
 * Nitea button next to the title screen's small icon buttons to change that choice later.
 *
 * <p>Nitea is a library, not a mod, so it has no resource pack: its texts are in {@link NiteaText} and its icon is
 * read from the jar and uploaded as a texture of its own.
 */
public final class NiteaClientUi {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("nitea", "dynamic/icon");
    private static final int ICON_SIZE = 20;
    private static final int ICON_SPACING = 4;

    // Asked at most once per launch, even if the player leaves the screen some other way
    private static boolean askedThisLaunch;
    private static boolean iconLoaded;

    private NiteaClientUi() {}

    public static void install() {
        NiteaConsent.markPromptAvailable();
        NeoForge.EVENT_BUS.addListener(NiteaClientUi::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(NiteaClientUi::onScreenInit);
    }

    // The first time the title screen opens, ask instead; the answer is saved, so a choice is never asked again
    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (askedThisLaunch || !(event.getNewScreen() instanceof TitleScreen title)) return;
        if (!NiteaConsent.shouldAsk() || NiteaConsent.mods().isEmpty()) return;
        askedThisLaunch = true;
        event.setNewScreen(new NiteaConsentScreen(title, true));
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen title)) return;
        Button button = new IconButton(pressed -> Minecraft.getInstance().gui.setScreen(new NiteaConsentScreen(title, false)));
        button.setTooltip(Tooltip.create(NiteaText.BUTTON_TOOLTIP));
        placeInIconRow(button, event.getListenersList());
        event.addListener(button);
    }

    // The title screen centres a row of square buttons (mods, friends, language, accessibility): join it and keep
    // it centred. When another mod rearranged the screen and there's no such row, fall back to the top-left corner.
    private static void placeInIconRow(Button button, List<GuiEventListener> listeners) {
        Map<Integer, List<AbstractWidget>> rows = new HashMap<>();
        for (GuiEventListener listener : listeners) {
            if (listener instanceof AbstractWidget widget && widget.getWidth() == ICON_SIZE && widget.getHeight() == ICON_SIZE) {
                rows.computeIfAbsent(widget.getY(), y -> new ArrayList<>()).add(widget);
            }
        }
        List<AbstractWidget> row = rows.values().stream().max(Comparator.comparingInt(List::size)).orElse(List.of());
        if (row.size() < 2) {
            button.setPosition(4, 4);
            return;
        }
        int shift = (ICON_SIZE + ICON_SPACING) / 2;
        int right = Integer.MIN_VALUE;
        for (AbstractWidget widget : row) {
            widget.setX(widget.getX() - shift);
            right = Math.max(right, widget.getX() + widget.getWidth());
        }
        button.setPosition(right + ICON_SPACING, row.get(0).getY());
    }

    // Uploads icon.png from this jar the first time the button is drawn
    private static boolean loadIcon() {
        if (iconLoaded) return true;
        try (InputStream in = NiteaClientUi.class.getResourceAsStream("icon.png")) {
            if (in == null) return false;
            Minecraft.getInstance().getTextureManager().register(ICON, new DynamicTexture(() -> "Nitea icon", NativeImage.read(in)));
            iconLoaded = true;
        } catch (IOException ignored) {
            // No icon: the button still works, it's just blank
        }
        return iconLoaded;
    }

    /** A square button like the title screen's language and accessibility buttons, with Nitea's icon. */
    private static final class IconButton extends Button {
        IconButton(OnPress onPress) {
            super(0, 0, ICON_SIZE, ICON_SIZE, NiteaText.BUTTON, onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            extractDefaultSprite(graphics);
            if (loadIcon()) graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, getX() + 2, getY() + 2, 0, 0, 16, 16, 16, 16);
        }
    }
}
