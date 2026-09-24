package cc.nitea.neoforge.client;

import cc.nitea.NiteaConsent;
import cc.nitea.internal.Browser;
import cc.nitea.internal.ConsentText;
import cc.nitea.internal.Engine;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.fml.ModList;

/**
 * Nitea's consent screen. Shown once, the first time the title screen opens ({@code firstTime}: the player must pick
 * an answer), then from the Nitea button on the title screen as the preferences screen, where they can opt in or
 * out again. The choice belongs to Nitea and covers every mod using it.
 */
public final class NiteaConsentScreen extends Screen {
    private static final int TEXT_WIDTH = 320;
    private static final int BUTTON_WIDTH = 150;

    private final Screen parent;
    private final boolean firstTime;
    // Wrapped text lines with their color; null is a paragraph break
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();

    public NiteaConsentScreen(Screen parent, boolean firstTime) {
        super(Component.literal(firstTime ? ConsentText.CONSENT_TITLE : ConsentText.PREFERENCES_TITLE));
        this.parent = parent;
        this.firstTime = firstTime;
    }

    @Override
    protected void init() {
        lines.clear();
        colors.clear();
        String consent = Engine.consent();
        if (!firstTime) {
            paragraph(Component.literal(ConsentText.STATUS).append(colored(ConsentText.status(consent), ConsentText.statusColor(consent))), ConsentText.WHITE);
        }
        paragraph(Component.literal(ConsentText.INTRO), ConsentText.WHITE);
        paragraph(Component.literal(modNames()), ConsentText.WHITE);
        paragraph(Component.literal(ConsentText.DETAILS), ConsentText.GRAY);
        paragraph(Component.literal(ConsentText.CHANGE), ConsentText.GRAY);

        int center = width / 2;
        NiteaConsent.State state = NiteaConsent.state();
        Button allow = addRenderableWidget(Button.builder(Component.literal(ConsentText.ALLOW), b -> choose(true))
                .bounds(center - BUTTON_WIDTH - 5, height - 52, BUTTON_WIDTH, 20).build());
        Button deny = addRenderableWidget(Button.builder(Component.literal(firstTime ? ConsentText.DENY : ConsentText.STOP), b -> choose(false))
                .bounds(center + 5, height - 52, BUTTON_WIDTH, 20).build());
        if (!firstTime) {
            allow.active = state != NiteaConsent.State.GRANTED;
            deny.active = state != NiteaConsent.State.DENIED;
        }
        Button.OnPress privacy = b -> Browser.open(ConsentText.PRIVACY_POLICY_URL);
        if (firstTime) {
            addRenderableWidget(Button.builder(Component.literal(ConsentText.PRIVACY), privacy)
                    .bounds(center - BUTTON_WIDTH / 2, height - 28, BUTTON_WIDTH, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal(ConsentText.PRIVACY), privacy)
                    .bounds(center - BUTTON_WIDTH - 5, height - 28, BUTTON_WIDTH, 20).build());
            addRenderableWidget(Button.builder(Component.literal(ConsentText.DONE), b -> onClose())
                    .bounds(center + 5, height - 28, BUTTON_WIDTH, 20).build());
        }
    }

    private void paragraph(Component text, int color) {
        if (!lines.isEmpty()) {
            lines.add(null);
            colors.add(0);
        }
        for (FormattedCharSequence line : font.split(text, Math.min(TEXT_WIDTH, width - 40))) {
            lines.add(line);
            colors.add(color);
        }
    }

    private static MutableComponent colored(String text, int rgb) {
        return Component.literal(text).withStyle(style -> style.withColor(TextColor.fromRgb(rgb)));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 20, ConsentText.WHITE);
        int y = 45;
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            if (line == null) {
                y += font.lineHeight / 2 + 2;
                continue;
            }
            graphics.drawCenteredString(font, line, width / 2, y, colors.get(i));
            y += font.lineHeight + 1;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void choose(boolean allow) {
        if (allow) NiteaConsent.grant();
        else NiteaConsent.deny();
        if (firstTime) onClose();
        else init(minecraft, width, height);
    }

    // "Example Mod, Other Mod", by display name
    private static String modNames() {
        StringBuilder names = new StringBuilder();
        for (String modId : NiteaConsent.mods()) {
            if (names.length() > 0) names.append(", ");
            names.append(ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getDisplayName()).orElse(modId));
        }
        return names.toString();
    }

    // The first time, the player has to pick an answer
    @Override
    public boolean shouldCloseOnEsc() {
        return !firstTime;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
