package cc.nitea.example;

import static cc.nitea.example.NiteaExample.NITEA;

import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * A wand that heals its user, but fails one cast in three with an exception. The mod catches it and reports it to
 * Nitea as a handled error, together with the breadcrumbs of what happened before.
 */
public class FaultyWandItem extends Item {
    private int casts;

    public FaultyWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(level instanceof ServerLevel)) return InteractionResult.SUCCESS;
        casts++;
        try {
            float healed = channelMana(level.getRandom().nextInt(3));
            player.heal(healed);
            NITEA.addBreadcrumb("item", "Faulty wand cast #" + casts + " healed " + healed);
            player.sendSystemMessage(Component.literal("The wand glows and heals you."));
        } catch (RuntimeException e) {
            NITEA.addBreadcrumb("item", "Faulty wand cast #" + casts + " failed", cc.nitea.Level.ERROR);
            NITEA.captureException(e, cc.nitea.Level.ERROR, Map.of("item", "faulty_wand"));
            player.sendSystemMessage(Component.literal("The wand fizzles. The error was reported to Nitea."));
        }
        return InteractionResult.SUCCESS;
    }

    private static float channelMana(int flow) {
        try {
            // Integer division: a flow of 0 throws
            return 4 / flow;
        } catch (ArithmeticException e) {
            throw new IllegalStateException("Mana channel collapsed (flow " + flow + ")", e);
        }
    }
}
