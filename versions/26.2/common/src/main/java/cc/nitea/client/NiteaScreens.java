package cc.nitea.client;

import cc.nitea.NiteaConsent;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Nitea's in-game screens for this Minecraft version, shared by every loader: asks the player once, on the title
 * screen, whether mods may send reports, and adds a Nitea button next to the title screen's small icon buttons to
 * change that choice later. Each loader forwards two moments here: a screen about to open, and the title screen
 * creating its widgets (NeoForge and Forge through their screen events, Fabric through mixins).
 *
 * <p>Nitea is a library, not a mod, so it has no resource pack: its texts are in {@link NiteaText} and its icon is
 * read from the jar and uploaded as a texture of its own.
 */
public final class NiteaScreens {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("nitea", "dynamic/icon");
    private static final int ICON_SIZE = 20;
    private static final int ICON_SPACING = 4;

    // Nothing happens until a mod calls Nitea.init: Fabric's mixins run whether or not a mod uses Nitea
    private static volatile boolean installed;
    private static Function<String, String> displayNames = modId -> null;
    // Asked at most once per launch, even if the player leaves the screen some other way
    private static boolean askedThisLaunch;
    private static boolean iconLoaded;

    private NiteaScreens() {}

    /** Turns the screens on. {@code displayNames} gives a mod's name from the loader, or null. */
    public static void install(Function<String, String> displayNames) {
        NiteaScreens.displayNames = displayNames;
        installed = true;
        NiteaConsent.markPromptAvailable();
    }

    /**
     * A screen is about to open: the first time it's the title screen, ask instead. The answer is saved, so a
     * choice is never asked again. Returns the screen to open.
     */
    public static Screen opening(Screen screen) {
        if (!installed || askedThisLaunch || !(screen instanceof TitleScreen)) return screen;
        if (!NiteaConsent.shouldAsk() || NiteaConsent.mods().isEmpty()) return screen;
        askedThisLaunch = true;
        return new NiteaConsentScreen(screen, true);
    }

    /** A screen created its widgets: on the title screen, adds the Nitea button with {@code add}. */
    public static void initialized(Screen screen, List<? extends GuiEventListener> widgets, Consumer<AbstractWidget> add) {
        if (!installed || !(screen instanceof TitleScreen)) return;
        Button button = new IconButton(pressed -> Minecraft.getInstance().gui.setScreen(new NiteaConsentScreen(screen, false)));
        button.setTooltip(Tooltip.create(NiteaText.BUTTON_TOOLTIP));
        placeInIconRow(button, widgets);
        add.accept(button);
    }

    static String displayName(String modId) {
        String name = displayNames.apply(modId);
        return name != null ? name : modId;
    }

    // When the title screen centres a row of square buttons (mods, friends, language, accessibility), join it and
    // keep it centred. When another mod rearranged the screen and there's no such row, fall back to the top-left corner.
    private static void placeInIconRow(Button button, List<? extends GuiEventListener> listeners) {
        Map<Integer, List<AbstractWidget>> rows = new HashMap<>();
        for (GuiEventListener listener : listeners) {
            if (listener instanceof AbstractWidget widget && widget.getWidth() == ICON_SIZE && widget.getHeight() == ICON_SIZE) {
                rows.computeIfAbsent(widget.getY(), y -> new ArrayList<>()).add(widget);
            }
        }
        List<AbstractWidget> row = rows.values().stream().max(Comparator.comparingInt(List::size)).orElse(List.of());
        if (row.isEmpty()) {
            button.setPosition(4, 4);
            return;
        }
        row.sort(Comparator.comparingInt(AbstractWidget::getX));
        AbstractWidget first = row.get(0);
        boolean adjacent = row.size() >= 2;
        for (int i = 1; i < row.size(); i++) {
            if (row.get(i).getX() - (row.get(i - 1).getX() + ICON_SIZE) > ICON_SPACING * 2) adjacent = false;
        }
        // Square buttons at the ends of the main buttons (language on the left, accessibility on the right):
        // go left of the leftmost one instead of rearranging them
        if (!adjacent) {
            if (first.getX() < ICON_SIZE + ICON_SPACING) button.setPosition(4, 4);
            else button.setPosition(first.getX() - ICON_SIZE - ICON_SPACING, first.getY());
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
        try (InputStream in = NiteaScreens.class.getResourceAsStream("icon.png")) {
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
