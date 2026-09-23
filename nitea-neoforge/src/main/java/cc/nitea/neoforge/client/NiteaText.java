package cc.nitea.neoforge.client;

import net.minecraft.network.chat.Component;

/** Nitea's texts. A library has no resource pack to hold translations, so they live here. */
final class NiteaText {
    static final Component BUTTON = Component.literal("Nitea analytics");
    static final Component BUTTON_TOOLTIP = Component.literal("Nitea analytics preferences");
    static final Component CONSENT_TITLE = Component.literal("Help mod developers fix bugs?");
    static final Component PREFERENCES_TITLE = Component.literal("Nitea analytics preferences");
    static final Component INTRO = Component.literal("Some of your mods use Nitea to send anonymous crash and error reports to their developers:");
    static final Component DETAILS = Component.literal("A report contains what went wrong and your game setup: Minecraft, loader, Java and OS versions, CPU and memory. Never your username, player UUID or IP address. Each mod only receives the errors it caused.");
    static final Component CHANGE = Component.literal("This choice applies to every mod using Nitea. You can change it any time from the Nitea button on the title screen.");
    static final String STATUS = "Reports are currently: ";
    static final Component STATUS_GRANTED = Component.literal("allowed");
    static final Component STATUS_DENIED = Component.literal("not allowed");
    static final Component STATUS_UNDECIDED = Component.literal("not chosen yet");
    static final Component ALLOW = Component.literal("Allow reports");
    static final Component DENY = Component.literal("Don't allow");
    static final Component STOP = Component.literal("Stop reports");
    static final Component PRIVACY = Component.literal("Privacy policy");

    private NiteaText() {}
}
