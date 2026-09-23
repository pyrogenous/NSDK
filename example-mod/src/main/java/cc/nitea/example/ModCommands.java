package cc.nitea.example;

import static cc.nitea.example.NiteaExample.NITEA;

import cc.nitea.Level;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.net.URI;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The mod's own command, {@code /examplemod} (alias {@code /em}). Nitea doesn't add commands: each mod decides where
 * players send reports from, here as subcommands next to the mod's other ones.
 * <ul>
 *   <li>{@code /examplemod heal}: an ordinary mod feature</li>
 *   <li>{@code /examplemod bug <text>} and {@code /examplemod suggest <text>}: player reports sent through Nitea</li>
 *   <li>{@code /examplemod nitea}: whether reporting is on</li>
 *   <li>{@code /examplemod test error|warning|uncaught|gamecrash}: send test events</li>
 * </ul>
 */
final class ModCommands {
    static final String COMMAND = "examplemod";
    static final String ALIAS = "em";

    private ModCommands() {}

    static void register(RegisterCommandsEvent event) {
        LiteralCommandNode<CommandSourceStack> root = event.getDispatcher().register(Commands.literal(COMMAND)
                .then(Commands.literal("heal").executes(ctx -> {
                    NITEA.addBreadcrumb("command", "/" + COMMAND + " heal");
                    if (ctx.getSource().getEntity() instanceof LivingEntity entity) entity.heal(4f);
                    return reply(ctx, "Healed!");
                }))
                .then(Commands.literal("nitea").executes(ctx -> reply(ctx, NITEA.isEnabled()
                        ? "Nitea reporting is on for " + NITEA.modId()
                        : "Nitea reporting is off (no SDK key, the player hasn't allowed it, or the key was rejected)")))
                .then(Commands.literal("bug")
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                            NITEA.addBreadcrumb("command", "/" + COMMAND + " bug");
                            return sent(ctx, NITEA.reportBug(StringArgumentType.getString(ctx, "text"), link -> sendLink(ctx.getSource(), link)), "Bug report");
                        })))
                .then(Commands.literal("suggest")
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                            NITEA.addBreadcrumb("command", "/" + COMMAND + " suggest");
                            return sent(ctx, NITEA.reportSuggestion(StringArgumentType.getString(ctx, "text"), link -> sendLink(ctx.getSource(), link)), "Suggestion");
                        })))
                .then(Commands.literal("test")
                        .then(Commands.literal("error").executes(ctx -> {
                            NITEA.addBreadcrumb("command", "/" + COMMAND + " test error");
                            try {
                                loadBrokenConfig();
                            } catch (RuntimeException e) {
                                return sent(ctx, NITEA.captureException(e), "Test error");
                            }
                            return 0;
                        }))
                        .then(Commands.literal("warning").executes(ctx -> {
                            NITEA.addBreadcrumb("command", "/" + COMMAND + " test warning");
                            return sent(ctx, NITEA.captureMessage("Test warning: example block count exceeded the soft limit", Level.WARNING), "Test warning");
                        }))
                        .then(Commands.literal("uncaught").executes(ctx -> {
                            NITEA.addBreadcrumb("command", "/" + COMMAND + " test uncaught");
                            // Nobody catches this: the uncaught exception handler reports it
                            Thread thread = new Thread(ModCommands::workerFails, "Nitea example worker");
                            thread.start();
                            return reply(ctx, "Threw an uncaught exception on a worker thread");
                        }))
                        .then(Commands.literal("gamecrash").executes(ctx -> {
                            NITEA.addBreadcrumb("command", "/" + COMMAND + " test gamecrash");
                            ActionTracker.crashNextTick = true;
                            return reply(ctx, "The server crashes on the next tick. The crash report is sent on the next launch.");
                        }))));
        // /em is a shortcut for /examplemod
        event.getDispatcher().register(Commands.literal(ALIAS).redirect(root));
    }

    private static void loadBrokenConfig() {
        try {
            Integer.parseInt("forty-two");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for 'magicNumber' in the example config", e);
        }
    }

    private static void workerFails() {
        Object[] cache = new Object[4];
        cache[8] = "boom";
    }

    // When the mod has a public page, Nitea returns a link to complete the report. In singleplayer the library opens
    // it in the browser by itself; this chat link covers players on a dedicated server.
    private static void sendLink(CommandSourceStack source, String link) {
        Component message = Component.literal("Add details and pictures to your report: ")
                .append(Component.literal(link).withStyle(style -> style
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(link)))
                        .withUnderlined(true)
                        .withColor(ChatFormatting.AQUA)));
        // Called on Nitea's thread; chat must be sent from the server thread
        source.getServer().execute(() -> source.sendSuccess(() -> message, false));
    }

    private static int sent(CommandContext<CommandSourceStack> ctx, UUID eventId, String what) {
        return reply(ctx, eventId != null ? what + " sent to Nitea (event " + eventId + ")" : what + " not sent: reporting is off or rate limited");
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
