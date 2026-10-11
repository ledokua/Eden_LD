package net.ledok.eden_ld.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.busylib.BusyState;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.BusyLocks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Config {@code commands.busy_blocked}: other mods' commands a busy player (BusyLib: a duel, a dungeon run, the
 * tutorial) can't run — paying or taking coins out, claiming kits. Every executable node under a listed root is
 * wrapped (by reflection, like {@link OpOnlyCommands}) once the server has all its commands, and again after /reload,
 * which rebuilds them. The command stays visible; running it while busy says why.
 */
public final class BusyBlockedCommands {

    private static final Field COMMAND = commandField();

    private BusyBlockedCommands() {
    }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(BusyBlockedCommands::apply);
        // OpOnlyCommands re-reads commands.json on /reload first (registered earlier).
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> apply(server));
    }

    private static void apply(MinecraftServer server) {
        if (COMMAND == null) {
            return;
        }
        CommandNode<CommandSourceStack> root = server.getCommands().getDispatcher().getRoot();
        int blocked = 0;
        for (String name : EdenLD.CONFIG.commands.busy_blocked) {
            String literal = name.startsWith("/") ? name.substring(1) : name;
            CommandNode<CommandSourceStack> node = root.getChild(literal);
            if (node == null) {
                EdenLD.LOGGER.info("[Commands] busy_blocked: no command /{} on this server.", literal);
                continue;
            }
            wrap(node, Collections.newSetFromMap(new IdentityHashMap<>()));
            blocked++;
        }
        if (blocked > 0) {
            EdenLD.LOGGER.info("[Commands] {} command(s) blocked while busy.", blocked);
        }
    }

    /** Wraps this node's command and its children's (once each; redirects aren't followed). */
    @SuppressWarnings("unchecked")
    private static void wrap(CommandNode<CommandSourceStack> node, Set<CommandNode<CommandSourceStack>> seen) {
        if (!seen.add(node)) {
            return;
        }
        Command<CommandSourceStack> command = node.getCommand();
        if (command != null && !(command instanceof Blocked)) {
            try {
                COMMAND.set(node, new Blocked(command));
            } catch (IllegalAccessException e) {
                EdenLD.LOGGER.error("[Commands] Can't block /{} while busy.", node.getName(), e);
            }
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            wrap(child, seen);
        }
    }

    /** Refuses a busy player (saying why); everyone else, the console and command blocks run it as before. */
    private record Blocked(Command<CommandSourceStack> original) implements Command<CommandSourceStack> {
        @Override
        public int run(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
                throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer player = context.getSource().getPlayer();
            if (player != null && BusyState.isBusy(player)) {
                context.getSource().sendFailure(BusyLocks.reason(player));
                return 0;
            }
            return original.run(context);
        }
    }

    private static Field commandField() {
        try {
            Field field = CommandNode.class.getDeclaredField("command");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            EdenLD.LOGGER.error("[Commands] Brigadier changed; commands.busy_blocked can't work.", e);
            return null;
        }
    }
}
