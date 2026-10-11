package net.ledok.eden_ld.hub.kits;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.KitsCompat;
import net.ledok.eden_ld.hub.BusyLocks;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;

/**
 * The hub's Kits tab, server side ({@code docs/PLAN-kits.md}): the kit list on join (the tab and its badge appear) and
 * on request, claims (refused while busy, like every hub reward — the tutorial, a duel), and {@code /kits}, which
 * opens the tab for players with Eden on their client (vanilla clients keep Kits_LD's chat listing).
 */
public final class KitsHub {

    private static final Field COMMAND = commandField();

    private KitsHub() {
    }

    public static void initialize() {
        if (!KitsCompat.LOADED) {
            return;
        }
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.KitsRequest.TYPE,
                (payload, context) -> context.server().execute(() -> send(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.KitClaim.TYPE,
                (payload, context) -> context.server().execute(() -> claim(context.player(), payload.kit())));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> send(handler.getPlayer())));
        // After BusyBlockedCommands (initialized first): /reload rebuilds the commands, so both wrap again.
        ServerLifecycleEvents.SERVER_STARTED.register(KitsHub::wrapKitsCommand);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> wrapKitsCommand(server));
    }

    /** The kits this player sees, to their hub. */
    public static void send(ServerPlayer player) {
        if (player.hasDisconnected() || !KitsCompat.ready()
                || !ServerPlayNetworking.canSend(player, HubPayloads.KitsData.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, new HubPayloads.KitsData(KitsCompat.kits(player)));
    }

    private static void claim(ServerPlayer player, String id) {
        if (!BusyLocks.refuse(player)) {
            KitsCompat.Claim result = KitsCompat.claim(player, id);
            ServerPlayNetworking.send(player, new HubPayloads.HubNotice(result.message(), result.ok()));
            if (result.ok()) {
                EdenLD.LOGGER.info("[Kits] {} claimed the kit {} from the hub.", player.getGameProfile().getName(), id);
            }
        }
        send(player);
    }

    // ---------------------------------------------------------------- /kits

    /** {@code /kits} (no arguments) opens the hub's Kits tab instead of Kits_LD's window, for Eden clients. */
    @SuppressWarnings("unchecked")
    private static void wrapKitsCommand(MinecraftServer server) {
        if (COMMAND == null) {
            return;
        }
        CommandNode<CommandSourceStack> node = server.getCommands().getDispatcher().getRoot().getChild("kits");
        Command<CommandSourceStack> command = node == null ? null : node.getCommand();
        if (command == null || command instanceof ToHub) {
            return;
        }
        try {
            COMMAND.set(node, new ToHub(command));
        } catch (IllegalAccessException e) {
            EdenLD.LOGGER.error("[Kits] Can't point /kits at the hub.", e);
        }
    }

    private record ToHub(Command<CommandSourceStack> original) implements Command<CommandSourceStack> {
        @Override
        public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            ServerPlayer player = context.getSource().getPlayer();
            if (player != null && ServerPlayNetworking.canSend(player, HubPayloads.OpenHub.TYPE)) {
                HubServer.openHub(player, "kits");
                return 1;
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
            EdenLD.LOGGER.error("[Kits] Brigadier changed; /kits keeps Kits_LD's window.", e);
            return null;
        }
    }
}
