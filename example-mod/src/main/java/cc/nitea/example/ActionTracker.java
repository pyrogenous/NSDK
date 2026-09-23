package cc.nitea.example;

import static cc.nitea.example.NiteaExample.NITEA;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Records what happens in the game as Nitea breadcrumbs, so every reported error shows the steps that led to it.
 * Only anonymous facts are recorded: never player names, UUIDs, chat, coordinates or server addresses.
 */
public final class ActionTracker {
    /** Set by {@code /examplemod test gamecrash}: the next server tick throws, which crashes the game for real. */
    static volatile boolean crashNextTick;

    private ActionTracker() {}

    @SubscribeEvent
    static void serverStarted(ServerStartedEvent event) {
        boolean dedicated = event.getServer().isDedicatedServer();
        NITEA.setTag("server_type", dedicated ? "dedicated" : "integrated");
        NITEA.addBreadcrumb("server", (dedicated ? "Dedicated" : "Singleplayer") + " server started");
    }

    @SubscribeEvent
    static void serverStopping(ServerStoppingEvent event) {
        NITEA.addBreadcrumb("server", "Server stopping");
    }

    @SubscribeEvent
    static void levelLoaded(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) NITEA.addBreadcrumb("world", "Loaded " + level.dimension().identifier());
    }

    @SubscribeEvent
    static void playerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        int online = event.getEntity().level().getServer().getPlayerCount();
        NITEA.addBreadcrumb("player", "A player joined (" + online + " online)");
    }

    @SubscribeEvent
    static void playerLeft(PlayerEvent.PlayerLoggedOutEvent event) {
        NITEA.addBreadcrumb("player", "A player left");
    }

    @SubscribeEvent
    static void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        NITEA.addBreadcrumb("world", "A player moved from " + event.getFrom().identifier() + " to " + event.getTo().identifier());
    }

    @SubscribeEvent
    static void blockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getPlacedBlock().is(NiteaExample.EXAMPLE_BLOCK.get())) NITEA.addBreadcrumb("block", "Example block placed");
    }

    @SubscribeEvent
    static void blockBroken(BreakBlockEvent event) {
        if (event.getState().is(NiteaExample.EXAMPLE_BLOCK.get())) NITEA.addBreadcrumb("block", "Example block broken");
    }

    @SubscribeEvent
    static void itemUsed(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() instanceof Player && event.getItem().is(NiteaExample.EXAMPLE_ITEM.get())) {
            NITEA.addBreadcrumb("item", "A player ate the example item");
        }
    }

    @SubscribeEvent
    static void playerDied(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player) NITEA.addBreadcrumb("player", "A player died (" + event.getSource().getMsgId() + ")", cc.nitea.Level.WARNING);
    }

    @SubscribeEvent
    static void advancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        NITEA.addBreadcrumb("player", "Advancement earned: " + event.getAdvancement().id());
    }

    @SubscribeEvent
    static void serverTick(ServerTickEvent.Post event) {
        if (crashNextTick) {
            crashNextTick = false;
            NITEA.addBreadcrumb("test", "Crashing the server on purpose", cc.nitea.Level.ERROR);
            throw new IllegalStateException("Test game crash from /examplemod test gamecrash");
        }
    }
}
