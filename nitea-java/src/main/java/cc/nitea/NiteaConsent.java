package cc.nitea;

import cc.nitea.internal.Engine;
import java.nio.file.Path;
import java.util.Set;

/**
 * The player's choice about Nitea reporting. It belongs to Nitea, not to any single mod: the player is asked once,
 * and the answer applies to every mod using the library. Until they say yes, nothing is sent; after they say no,
 * nothing is ever sent and they are not asked again. They can change their mind from Nitea's button on the title
 * screen.
 *
 * <p>Mods don't need to call this class. It's used by Nitea's in-game screens, and is public for launchers or
 * mods that want to show the current state.
 */
public final class NiteaConsent {
    public enum State {
        /** Never asked: events wait in memory and are sent only if the player opts in. */
        UNDECIDED,
        /** The player allowed anonymous reports. */
        GRANTED,
        /** The player refused: nothing is sent and they're not asked again. */
        DENIED,
    }

    private NiteaConsent() {}

    /** Where {@code config/nitea/nitea.properties} lives. Set automatically by the first mod initialising Nitea. */
    public static void useGameDir(Path gameDir) {
        Engine.useGameDir(gameDir);
    }

    public static State state() {
        return switch (Engine.consent()) {
            case Engine.GRANTED -> State.GRANTED;
            case Engine.DENIED -> State.DENIED;
            default -> State.UNDECIDED;
        };
    }

    /** Opts in: queued and future events of every mod are sent. */
    public static void grant() {
        Engine.setConsent(true);
    }

    /** Opts out: queued events are dropped, nothing more is sent and the installation ID is deleted. */
    public static void deny() {
        Engine.setConsent(false);
    }

    /** True when the player hasn't chosen yet and should be asked. */
    public static boolean shouldAsk() {
        return state() == State.UNDECIDED;
    }

    /** IDs of every mod using Nitea in this game, to show the player who reports. */
    public static Set<String> mods() {
        return Engine.mods();
    }

    /** Called by Nitea's in-game screens once loaded, so mods know the player will be asked. */
    public static void markPromptAvailable() {
        Engine.markPromptAvailable();
    }

    /** Runs {@code listener} every time the player changes their choice. */
    public static void onChange(Runnable listener) {
        Engine.onConsentChange(listener);
    }
}
