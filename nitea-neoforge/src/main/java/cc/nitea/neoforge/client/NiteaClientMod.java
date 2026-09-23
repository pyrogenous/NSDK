package cc.nitea.neoforge.client;

import cc.nitea.NiteaConsent;
import cc.nitea.neoforge.NiteaMod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client side of Nitea: asks the player once, on the title screen, whether mods may send reports, and adds a
 * Nitea button next to the title screen's small icon buttons to change that choice later.
 */
@Mod(value = NiteaMod.MOD_ID, dist = Dist.CLIENT)
public final class NiteaClientMod {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath(NiteaMod.MOD_ID, "icon/nitea");
    private static final int ICON_SIZE = 20;
    private static final int ICON_SPACING = 4;

    // Asked at most once per launch, even if the player leaves the screen some other way
    private static boolean askedThisLaunch;

    public NiteaClientMod() {
        NiteaConsent.markPromptAvailable();
        NeoForge.EVENT_BUS.addListener(NiteaClientMod::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(NiteaClientMod::onScreenInit);
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
        SpriteIconButton button = SpriteIconButton.builder(
                        Component.translatable("nitea.button"),
                        pressed -> Minecraft.getInstance().gui.setScreen(new NiteaConsentScreen(title, false)),
                        true)
                .width(ICON_SIZE)
                .sprite(ICON, 15, 15)
                .build();
        button.setTooltip(Tooltip.create(Component.translatable("nitea.button.tooltip")));
        placeInIconRow(button, event.getListenersList());
        event.addListener(button);
    }

    // The title screen centres a row of square buttons (mods, friends, language, accessibility): join it and keep
    // it centred. When another mod rearranged the screen and there's no such row, fall back to the top-left corner.
    private static void placeInIconRow(SpriteIconButton button, List<GuiEventListener> listeners) {
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
}
