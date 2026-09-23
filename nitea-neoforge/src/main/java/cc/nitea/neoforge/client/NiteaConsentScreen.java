package cc.nitea.neoforge.client;

import cc.nitea.NiteaConsent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.ModList;

/**
 * Nitea's consent screen. Shown once, the first time the title screen opens ({@code firstTime}: the player must pick
 * an answer), then from the Nitea button on the title screen as the preferences screen, where they can opt in or
 * out again. The choice belongs to Nitea and covers every mod using it.
 */
public final class NiteaConsentScreen extends Screen {
    private static final String PRIVACY_POLICY = "https://nitea.cc/legal/privacy-policy";
    private static final int TEXT_WIDTH = 320;
    private static final int GRAY = 0xFFA0A0A0;

    private final Screen parent;
    private final boolean firstTime;
    private HeaderAndFooterLayout layout;

    public NiteaConsentScreen(Screen parent, boolean firstTime) {
        super(Component.translatable(firstTime ? "nitea.consent.title" : "nitea.preferences.title"));
        this.parent = parent;
        this.firstTime = firstTime;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 33, 60);
        layout.addTitleHeader(title, font);

        LinearLayout content = layout.addToContents(LinearLayout.vertical().spacing(8));
        content.defaultCellSetting().alignHorizontallyCenter();
        if (!firstTime) content.addChild(text(Component.translatable("nitea.preferences.status", status())));
        content.addChild(text(Component.translatable("nitea.consent.intro")));
        content.addChild(text(modNames()));
        content.addChild(text(Component.translatable("nitea.consent.details").withColor(GRAY)));
        content.addChild(text(Component.translatable("nitea.consent.change").withColor(GRAY)));

        LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        LinearLayout choice = footer.addChild(LinearLayout.horizontal().spacing(8));
        NiteaConsent.State state = NiteaConsent.state();
        Button allow = choice.addChild(Button.builder(Component.translatable("nitea.consent.allow"), b -> choose(true)).build());
        Button deny = choice.addChild(Button.builder(
                Component.translatable(firstTime ? "nitea.consent.deny" : "nitea.preferences.stop"), b -> choose(false)).build());
        if (!firstTime) {
            allow.active = state != NiteaConsent.State.GRANTED;
            deny.active = state != NiteaConsent.State.DENIED;
        }
        LinearLayout other = footer.addChild(LinearLayout.horizontal().spacing(8));
        other.addChild(Button.builder(Component.translatable("nitea.consent.privacy"), ConfirmLinkScreen.confirmLink(this, PRIVACY_POLICY)).build());
        if (!firstTime) other.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    private MultiLineTextWidget text(Component component) {
        return new MultiLineTextWidget(component, font).setMaxWidth(Math.min(TEXT_WIDTH, width - 40)).setCentered(true);
    }

    private void choose(boolean allow) {
        if (allow) NiteaConsent.grant();
        else NiteaConsent.deny();
        if (firstTime) onClose();
        else rebuildWidgets();
    }

    private static Component status() {
        return switch (NiteaConsent.state()) {
            case GRANTED -> Component.translatable("nitea.preferences.status.granted").withColor(0xFF55FF55);
            case DENIED -> Component.translatable("nitea.preferences.status.denied").withColor(0xFFFF5555);
            case UNDECIDED -> Component.translatable("nitea.preferences.status.undecided").withColor(0xFFFFFF55);
        };
    }

    // "Example Mod, Other Mod", by display name
    private static Component modNames() {
        MutableComponent names = Component.empty();
        boolean first = true;
        for (String modId : NiteaConsent.mods()) {
            if (!first) names.append(", ");
            first = false;
            names.append(ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getDisplayName()).orElse(modId));
        }
        return names.withColor(0xFFFFFFFF);
    }

    // The first time, the player has to pick an answer
    @Override
    public boolean shouldCloseOnEsc() {
        return !firstTime;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
