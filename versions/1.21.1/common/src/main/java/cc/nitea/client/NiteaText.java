package cc.nitea.client;

import cc.nitea.internal.ConsentText;
import net.minecraft.network.chat.Component;

/** Nitea's texts (from {@link ConsentText}, shared by every loader and version) as game components. */
final class NiteaText {
    static final Component BUTTON = Component.literal(ConsentText.BUTTON);
    static final Component BUTTON_TOOLTIP = Component.literal(ConsentText.BUTTON_TOOLTIP);
    static final Component CONSENT_TITLE = Component.literal(ConsentText.CONSENT_TITLE);
    static final Component PREFERENCES_TITLE = Component.literal(ConsentText.PREFERENCES_TITLE);
    static final Component INTRO = Component.literal(ConsentText.INTRO);
    static final Component DETAILS = Component.literal(ConsentText.DETAILS);
    static final Component CHANGE = Component.literal(ConsentText.CHANGE);
    static final String STATUS = ConsentText.STATUS;
    static final Component STATUS_GRANTED = Component.literal(ConsentText.STATUS_GRANTED);
    static final Component STATUS_DENIED = Component.literal(ConsentText.STATUS_DENIED);
    static final Component STATUS_UNDECIDED = Component.literal(ConsentText.STATUS_UNDECIDED);
    static final Component ALLOW = Component.literal(ConsentText.ALLOW);
    static final Component DENY = Component.literal(ConsentText.DENY);
    static final Component STOP = Component.literal(ConsentText.STOP);
    static final Component PRIVACY = Component.literal(ConsentText.PRIVACY);

    private NiteaText() {}
}
