package net.ledok.eden_ld.command;

import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.util.function.Predicate;

/**
 * Config {@code commands.op_only}: other mods' commands only ops (level 2) may use — the console
 * and command blocks keep them. Brigadier has no way to add a requirement to a registered
 * command, so each listed root's own requirement is wrapped (by reflection) once the server has
 * all its commands: at start, and again after /reload, which rebuilds them.
 */
public final class OpOnlyCommands {

    private static final Field REQUIREMENT = requirementField();

    private OpOnlyCommands() {
    }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(OpOnlyCommands::apply);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            EdenLD.CONFIG.reloadCommands();
            apply(server);
            // Clients only show the commands they were sent.
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                server.getCommands().sendCommands(player);
            }
        });
    }

    /**
     * Replaces the op-only list (the Moderation window): saved to {@code commands.json} and applied
     * at once — commands taken off the list get their own requirement back — then everyone online
     * gets their command list again.
     */
    public static void set(MinecraftServer server, java.util.List<String> names) {
        EdenLD.CONFIG.commands.op_only = new java.util.ArrayList<>(names);
        EdenLD.CONFIG.saveCommands();
        if (REQUIREMENT != null) {
            java.util.Set<String> wanted = new java.util.HashSet<>(names);
            for (CommandNode<CommandSourceStack> node : server.getCommands().getDispatcher().getRoot().getChildren()) {
                if (node.getRequirement() instanceof OpOnly opOnly && !wanted.contains(node.getName())) {
                    try {
                        REQUIREMENT.set(node, opOnly.original());
                    } catch (IllegalAccessException e) {
                        EdenLD.LOGGER.error("[Commands] Can't open /{} again.", node.getName(), e);
                    }
                }
            }
        }
        apply(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
    }

    private static void apply(MinecraftServer server) {
        if (REQUIREMENT == null) {
            return;
        }
        CommandNode<CommandSourceStack> root = server.getCommands().getDispatcher().getRoot();
        int restricted = 0;
        for (String name : EdenLD.CONFIG.commands.op_only) {
            String literal = name.startsWith("/") ? name.substring(1) : name;
            CommandNode<CommandSourceStack> node = root.getChild(literal);
            if (node == null) {
                EdenLD.LOGGER.info("[Commands] op_only: no command /{} on this server.", literal);
                continue;
            }
            Predicate<CommandSourceStack> original = node.getRequirement();
            if (original instanceof OpOnly) {
                restricted++;
                continue;
            }
            try {
                REQUIREMENT.set(node, new OpOnly(original));
                restricted++;
            } catch (IllegalAccessException e) {
                EdenLD.LOGGER.error("[Commands] Can't restrict /{}.", literal, e);
            }
        }
        if (restricted > 0) {
            EdenLD.LOGGER.info("[Commands] {} command(s) restricted to ops.", restricted);
        }
    }

    /** The command's own requirement, plus op level 2. */
    private record OpOnly(Predicate<CommandSourceStack> original) implements Predicate<CommandSourceStack> {
        @Override
        public boolean test(CommandSourceStack source) {
            return source.hasPermission(2) && original.test(source);
        }
    }

    private static Field requirementField() {
        try {
            Field field = CommandNode.class.getDeclaredField("requirement");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            EdenLD.LOGGER.error("[Commands] Brigadier changed; commands.op_only can't work.", e);
            return null;
        }
    }
}
