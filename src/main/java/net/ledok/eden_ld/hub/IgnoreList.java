package net.ledok.eden_ld.hub;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Players a player ignores (the Friends tab's right-click menu, Settings → Privacy, {@code /ignore}).
 * Their chat lines, whispers, deaths and advancements are hidden from the player (the client hides
 * marked lines, so stopping brings earlier lines back), their mentions don't ping, and their party
 * invites, guild invites and friend-request notices don't reach the player. Anyone can be ignored,
 * staff too, and the ignored player isn't told. Kept in the player's hub data as {uuid, name}: the
 * name they had when last seen, for the lists while they're offline.
 */
public final class IgnoreList {

    /** The most players one player can ignore. */
    public static final int MAX = 200;
    private static final String KEY = "ignored";

    public record Entry(UUID uuid, String name) {
    }

    public enum Result { ADDED, REMOVED, SELF, ALREADY, NOT_IGNORED, FULL }

    private IgnoreList() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.IgnoreAction.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (payload.ignore()) {
                ServerPlayer online = server.getPlayerList().getPlayer(payload.target());
                String name = online != null ? online.getGameProfile().getName() : knownName(server, payload.target());
                change(player, payload.target(), name == null ? "?" : name, true);
            } else {
                change(player, payload.target(), null, false);
            }
        });
        // The list arrives with the rest of the hub's login data: chat lines from before the hub opens hide too.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> {
                    if (!handler.getPlayer().hasDisconnected()) {
                        send(handler.getPlayer());
                    }
                }));
    }

    // ---------------------------------------------------------------- the list

    /** Whether {@code viewer} ignores {@code other}. */
    public static boolean ignores(MinecraftServer server, UUID viewer, UUID other) {
        if (viewer.equals(other)) {
            return false;
        }
        ListTag list = HubData.get(server).peek(viewer).getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("uuid") && entry.getUUID("uuid").equals(other)) {
                return true;
            }
        }
        return false;
    }

    /** Who {@code viewer} ignores, in the order they were added; online players under their current name. */
    public static List<Entry> list(MinecraftServer server, UUID viewer) {
        ListTag list = HubData.get(server).peek(viewer).getList(KEY, Tag.TAG_COMPOUND);
        List<Entry> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("uuid")) {
                continue;
            }
            UUID uuid = entry.getUUID("uuid");
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            entries.add(new Entry(uuid, online != null ? online.getGameProfile().getName() : entry.getString("name")));
        }
        return entries;
    }

    private static Result add(MinecraftServer server, UUID viewer, UUID target, String name) {
        if (viewer.equals(target)) {
            return Result.SELF;
        }
        if (ignores(server, viewer, target)) {
            return Result.ALREADY;
        }
        CompoundTag player = HubData.get(server).player(viewer);
        ListTag list = player.getList(KEY, Tag.TAG_COMPOUND);
        if (list.size() >= MAX) {
            return Result.FULL;
        }
        CompoundTag entry = new CompoundTag();
        entry.putUUID("uuid", target);
        entry.putString("name", name);
        list.add(entry);
        player.put(KEY, list);
        return Result.ADDED;
    }

    private static Result remove(MinecraftServer server, UUID viewer, UUID target) {
        CompoundTag player = HubData.get(server).player(viewer);
        ListTag list = player.getList(KEY, Tag.TAG_COMPOUND);
        boolean removed = list.removeIf(tag -> tag instanceof CompoundTag entry
                && entry.hasUUID("uuid") && entry.getUUID("uuid").equals(target));
        player.put(KEY, list);
        return removed ? Result.REMOVED : Result.NOT_IGNORED;
    }

    // ---------------------------------------------------------------- changes

    /**
     * Ignores {@code target} (or stops), tells the player, and refreshes what follows from it: their
     * client's list, and everyone's party and guild invite lists (an ignored player can't invite them).
     */
    private static boolean change(ServerPlayer player, UUID target, String name, boolean ignore) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        if (!ignore) {
            name = list(server, player.getUUID()).stream().filter(entry -> entry.uuid().equals(target))
                    .map(Entry::name).findFirst().orElse("?");
        }
        Result result = ignore ? add(server, player.getUUID(), target, name) : remove(server, player.getUUID(), target);
        boolean changed = result == Result.ADDED || result == Result.REMOVED;
        Component message = switch (result) {
            case ADDED -> Component.translatable("message.eden_ld.ignore.added", name, name).withStyle(ChatFormatting.GREEN);
            case REMOVED -> Component.translatable("message.eden_ld.ignore.removed", name).withStyle(ChatFormatting.GREEN);
            case SELF -> Component.translatable("message.eden_ld.ignore.self").withStyle(ChatFormatting.RED);
            case ALREADY -> Component.translatable("message.eden_ld.ignore.already", name).withStyle(ChatFormatting.RED);
            case NOT_IGNORED -> Component.translatable("message.eden_ld.ignore.not_ignored", name).withStyle(ChatFormatting.RED);
            case FULL -> Component.translatable("message.eden_ld.ignore.full", MAX).withStyle(ChatFormatting.RED);
        };
        player.sendSystemMessage(message);
        if (changed) {
            send(player);
            HubServer.syncParties(server);
            net.ledok.eden_ld.hub.guild.GuildServer.syncAll(server);
        }
        return changed;
    }

    /** The player's list to their client (it hides lines, sorts lists and fills Settings → Privacy). */
    public static void send(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !ServerPlayNetworking.canSend(player, HubPayloads.IgnoreData.TYPE)) {
            return;
        }
        List<HubPayloads.IgnoredEntry> entries = new ArrayList<>();
        for (Entry entry : list(server, player.getUUID())) {
            entries.add(new HubPayloads.IgnoredEntry(entry.uuid(), entry.name()));
        }
        ServerPlayNetworking.send(player, new HubPayloads.IgnoreData(entries));
    }

    /** A name the server has seen for {@code uuid}: online, or in the user cache. */
    private static String knownName(MinecraftServer server, UUID uuid) {
        GameProfileCache cache = server.getProfileCache();
        return cache == null ? null : cache.get(uuid).map(GameProfile::getName).orElse(null);
    }

    // ---------------------------------------------------------------- commands

    /** {@code /ignore} (the list), {@code /ignore <player>} (online or known to the server) and {@code /unignore <player>}. */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ignore")
                .executes(IgnoreList::showList)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                context.getSource().getOnlinePlayerNames(), builder))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(context, "player");
                            Optional<GameProfile> profile = profile(context.getSource().getServer(), name);
                            if (profile.isEmpty()) {
                                context.getSource().sendFailure(Component.translatable("message.eden_ld.ignore.unknown", name));
                                return 0;
                            }
                            return change(player, profile.get().getId(), profile.get().getName(), true) ? 1 : 0;
                        })));
        dispatcher.register(Commands.literal("unignore")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            return SharedSuggestionProvider.suggest(player == null ? List.of()
                                    : list(context.getSource().getServer(), player.getUUID()).stream()
                                    .map(Entry::name).toList(), builder);
                        })
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(context, "player");
                            Entry entry = list(context.getSource().getServer(), player.getUUID()).stream()
                                    .filter(e -> e.name().equalsIgnoreCase(name)).findFirst().orElse(null);
                            if (entry == null) {
                                context.getSource().sendFailure(
                                        Component.translatable("message.eden_ld.ignore.not_ignored", name));
                                return 0;
                            }
                            return change(player, entry.uuid(), entry.name(), false) ? 1 : 0;
                        })));
    }

    private static int showList(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        List<Entry> entries = list(context.getSource().getServer(), player.getUUID());
        if (entries.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.eden_ld.ignore.list_empty").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        player.sendSystemMessage(Component.translatable("message.eden_ld.ignore.list", entries.size(),
                String.join(", ", entries.stream().map(Entry::name).toList())).withStyle(ChatFormatting.GRAY));
        return entries.size();
    }

    /** An online player by name, else one the server has seen (the user cache). */
    private static Optional<GameProfile> profile(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        GameProfileCache cache = server.getProfileCache();
        return cache == null ? Optional.empty() : cache.get(name);
    }
}
