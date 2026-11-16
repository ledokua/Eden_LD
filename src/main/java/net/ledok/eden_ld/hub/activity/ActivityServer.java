package net.ledok.eden_ld.hub.activity;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.command.EdenPermissions;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.compat.arenas.ArenasHook;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.daily.DailyQuests;
import net.ledok.eden_ld.hub.lang.ServerLang;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of the Activities tab: its data, opening an activity (the level checked first),
 * new-unlock notices (a periodic check of online players: an activity their level opened that they
 * haven't seen gives a chat line once, the tab badge and a Home card until they open the tab), and
 * recording finished runs (the server's run history, player stats, the {@code complete_dungeon} daily
 * objective).
 */
public final class ActivityServer {

    /** What each online player was last told is new (so the chat line comes once per unlock). */
    private static final Map<UUID, Set<String>> TOLD = new HashMap<>();
    /** The open lobbies each online player last got (the 2 s poll answers only when they changed). */
    private static final Map<UUID, HubPayloads.ActivityLobbies> LOBBIES_SENT = new HashMap<>();

    private ActivityServer() {
    }

    public static void initialize() {
        // Arenas_LD first: its ids win over a datapack file with the same id.
        ArenasHook.initialize();
        DatapackActivities.initialize();
        Activities.register(DatapackActivities.INSTANCE);
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ActivitiesRequest.TYPE, (payload, context) -> {
            send(context.player());
            sendLobbies(context.player(), true);
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ActivityOpen.TYPE,
                (payload, context) -> open(context.player(), payload.id()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.OwnLobbyRequest.TYPE, (payload, context) -> {
            sendOwnLobby(context.player());
            sendLobbies(context.player(), false);
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.OwnLobbyOpen.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!Activities.openOwnLobby(player)) {
                ServerPlayNetworking.send(player, new HubPayloads.HubNotice(
                        net.minecraft.network.chat.Component.translatable("message.eden_ld.activities.no_lobby"), false));
                sendOwnLobby(player);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ActivityInfoRequest.TYPE,
                (payload, context) -> sendInfo(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.LobbyCreate.TYPE,
                (payload, context) -> createLobby(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.LobbyJoin.TYPE,
                (payload, context) -> joinLobby(context.player(), payload.activity(), payload.lobby()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ActivitiesSeen.TYPE,
                (payload, context) -> seen(context.player()));
        // After the join sync settles (see HubServer): the badge and Home card for unseen unlocks.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            ServerPlayer player = handler.getPlayer();
            TOLD.put(player.getUUID(), new LinkedHashSet<>(fresh(server, player).keySet()));
            pushUnlocks(server, player);
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                    TOLD.remove(handler.getPlayer().getUUID());
                    LOBBIES_SENT.remove(handler.getPlayer().getUUID());
                }));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int every = Math.max(5, EdenLD.CONFIG.activities.unlock_check_seconds) * 20;
            if (Activities.takeChanged() || server.getTickCount() % every == 0) {
                server.getPlayerList().getPlayers().forEach(player -> checkUnlocks(server, player));
            }
        });
    }

    /** The Activities tab's "My lobby" button: the lobby the player is in, or none. */
    private static void sendOwnLobby(ServerPlayer player) {
        HubPayloads.OwnLobbyData data = Activities.ownLobby(player)
                .map(lobby -> new HubPayloads.OwnLobbyData(true, lobby.name(), lobby.members(), lobby.maxMembers(),
                        lobby.inRun(), lobby.activity() == null ? "" : lobby.activity().toString(), lobby.owner()))
                .orElse(HubPayloads.OwnLobbyData.NONE);
        ServerPlayNetworking.send(player, data);
    }

    /**
     * The cards' lobby badges (1.3.98): the lobbies forming now that the player may see. With {@code force} always
     * (the tab's data was asked for), otherwise only when they changed since the last send.
     */
    private static void sendLobbies(ServerPlayer player, boolean force) {
        if (player.hasDisconnected() || !ServerPlayNetworking.canSend(player, HubPayloads.ActivityLobbies.TYPE)) {
            return;
        }
        List<HubPayloads.LobbyBadge> badges = new ArrayList<>();
        Activities.openLobbies(player).forEach((activity, lobbies) -> {
            List<HubPayloads.LobbyLine> lines = new ArrayList<>();
            for (ActivitySource.OpenLobby lobby : lobbies) {
                lines.add(new HubPayloads.LobbyLine(lobby.lobbyId(), lobby.owner(), lobby.members(), lobby.maxMembers(),
                        lobby.difficulty(), lobby.hardcore(), lobby.request(), lobby.member(), lobby.ready(),
                        lobby.minLevel(), lobby.modifiers().stream().map(name -> ServerLang.localize(player, name)).toList(),
                        lobby.createdAt()));
            }
            badges.add(new HubPayloads.LobbyBadge(activity, lines));
        });
        HubPayloads.ActivityLobbies data = new HubPayloads.ActivityLobbies(Activities.listsLobbies(),
                Activities.createsLobbies(), badges);
        if (!force && data.equals(LOBBIES_SENT.get(player.getUUID()))) {
            return;
        }
        LOBBIES_SENT.put(player.getUUID(), data);
        ServerPlayNetworking.send(player, data);
    }

    // ---------------------------------------------------------------- unlocks

    /** Open activities the player hasn't seen yet: id → name. */
    private static Map<String, Component> fresh(MinecraftServer server, ServerPlayer player) {
        Map<String, Component> fresh = new java.util.LinkedHashMap<>();
        if (!ActivityLevels.gated()) {
            return fresh;
        }
        Set<String> seen = ActivityProgress.seen(server, player.getUUID());
        int level = ActivityLevels.level(player);
        for (Activity activity : Activities.visible(server)) {
            // Activities open from the start aren't news.
            if (activity.unlockLevel() > 0 && level >= activity.unlockLevel() && !seen.contains(activity.id().toString())) {
                fresh.put(activity.id().toString(), activity.name());
            }
        }
        return fresh;
    }

    private static void checkUnlocks(MinecraftServer server, ServerPlayer player) {
        Map<String, Component> fresh = fresh(server, player);
        Set<String> told = TOLD.computeIfAbsent(player.getUUID(), uuid -> new LinkedHashSet<>());
        List<Component> news = new ArrayList<>();
        fresh.forEach((id, name) -> {
            if (told.add(id)) {
                news.add(name);
            }
        });
        told.retainAll(fresh.keySet());
        if (news.isEmpty()) {
            return;
        }
        for (Component name : news) {
            player.sendSystemMessage(Component.translatable("message.eden_ld.activities.unlocked",
                            ServerLang.localize(player, name))
                    .withStyle(ChatFormatting.GOLD)
                    .append(" ")
                    .append(Component.translatable("message.eden_ld.party.invited_open")
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                            .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hub")))));
        }
        pushUnlocks(server, player);
    }

    /** The unseen unlocks to the client (badge, Home card). */
    public static void pushUnlocks(MinecraftServer server, ServerPlayer player) {
        if (player.hasDisconnected() || !ServerPlayNetworking.canSend(player, HubPayloads.ActivityUnlocks.TYPE)) {
            return;
        }
        List<Component> names = new ArrayList<>();
        fresh(server, player).values().forEach(name -> names.add(ServerLang.localize(player, name)));
        ServerPlayNetworking.send(player, new HubPayloads.ActivityUnlocks(names));
    }

    private static void seen(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Set<String> fresh = fresh(server, player).keySet();
        if (!fresh.isEmpty()) {
            ActivityProgress.markSeen(server, player.getUUID(), Set.copyOf(fresh));
            pushUnlocks(server, player);
        }
    }

    // ---------------------------------------------------------------- tab

    public static void send(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || player.hasDisconnected()) {
            return;
        }
        boolean gated = ActivityLevels.gated();
        int level = gated ? ActivityLevels.level(player) : 0;
        Set<String> fresh = fresh(server, player).keySet();
        List<HubPayloads.ActivityEntry> entries = new ArrayList<>();
        for (Activity activity : Activities.visible(server)) {
            entries.add(new HubPayloads.ActivityEntry(activity.id(), activity.type().ordinal(),
                    ServerLang.localize(player, activity.name()),
                    activity.cardArt() == null ? "" : activity.cardArt().toString(), activity.unlockLevel(),
                    !gated || level >= activity.unlockLevel(), fresh.contains(activity.id().toString())));
        }
        // Online partymates' levels: a card warns when one of them can't come along.
        List<HubPayloads.PartyLevel> party = new ArrayList<>();
        net.ledok.eden_ld.hub.party.Party own = gated
                ? net.ledok.eden_ld.hub.party.PartyManager.get(server).partyOf(player.getUUID()) : null;
        if (own != null) {
            for (UUID member : own.members.keySet()) {
                ServerPlayer online = server.getPlayerList().getPlayer(member);
                if (online != null && !member.equals(player.getUUID())) {
                    party.add(new HubPayloads.PartyLevel(online.getGameProfile().getName(), ActivityLevels.level(online)));
                }
            }
        }
        // Closed ones first, so the cards are dimmed when the data arrives (an older client has no use for it).
        if (ServerPlayNetworking.canSend(player, HubPayloads.ActivitiesClosed.TYPE)) {
            List<ResourceLocation> closed = new ArrayList<>();
            for (Activity activity : Activities.visible(server)) {
                if (Activities.closed(server, activity.id())) {
                    closed.add(activity.id());
                }
            }
            ServerPlayNetworking.send(player, new HubPayloads.ActivitiesClosed(closed,
                    LuckPermsCompat.hasPermission(player, EdenPermissions.DUNGEONS)));
        }
        ServerPlayNetworking.send(player, new HubPayloads.ActivitiesData(level, gated, entries, party));
    }

    private static void open(ServerPlayer player, ResourceLocation id) {
        Component refused = ActivityGate.check(player, id, null);
        if (refused != null) {
            player.sendSystemMessage(refused.copy().withStyle(ChatFormatting.RED));
            ServerPlayNetworking.send(player, new HubPayloads.HubNotice(refused));
            return;
        }
        if (!Activities.open(player, id)) {
            ServerPlayNetworking.send(player, new HubPayloads.HubNotice(
                    Component.translatable("message.eden_ld.activities.unknown")));
        }
    }

    /**
     * The «Лобі» list's Join / Request (1.3.99): the level and closed checks as opening the activity, then the source's
     * own flow, which opens the lobby screen. Refused: the hub shows why (the source may tell the chat too).
     */
    private static void joinLobby(ServerPlayer player, ResourceLocation id, UUID lobby) {
        Component refused = ActivityGate.check(player, id, null);
        if (refused == null) {
            refused = Activities.joinLobby(player, id, lobby);
        }
        if (refused != null) {
            ServerPlayNetworking.send(player, new HubPayloads.HubNotice(refused, false));
            sendLobbies(player, true);
        }
    }

    // ---------------------------------------------------------------- «Створити лобі» (1.3.103)

    /** The panel's data for an activity: what it offers, the possible drops, and with edit the own lobby's settings. */
    private static void sendInfo(ServerPlayer player, HubPayloads.ActivityInfoRequest request) {
        MinecraftServer server = player.getServer();
        Activity activity = server == null ? null : Activities.get(server, request.activity());
        if (activity == null || !ServerPlayNetworking.canSend(player, HubPayloads.ActivityInfoData.TYPE)) {
            return;
        }
        ActivitySource source = Activities.sourceFor(server, activity.id());
        ActivitySource.CreateInfo info = source == null || !source.createsLobbies() ? null
                : source.createInfo(server, activity.id()).orElse(null);
        List<String> difficulties = info == null ? List.of() : info.difficulties();
        List<Integer> levels = new ArrayList<>();
        for (String difficulty : difficulties) {
            levels.add(Math.max(0, activity.levelFor(difficulty)));
        }
        List<HubPayloads.ModifierLine> modifiers = new ArrayList<>();
        if (info != null) {
            for (ActivitySource.ModifierView modifier : info.modifiers()) {
                modifiers.add(new HubPayloads.ModifierLine(modifier.id(), ServerLang.localize(player, modifier.name()),
                        ServerLang.localize(player, modifier.description()), ServerLang.localize(player, modifier.rewards()),
                        modifier.conflicts()));
            }
        }
        String dropsFor = request.difficulty();
        // Off by default (LeDok 2026-11-16, until Arenas_LD reads the gear tables): no lookup, a total of -1 hides it.
        boolean showDrops = EdenLD.CONFIG.activities.show_possible_drops;
        List<net.minecraft.world.item.ItemStack> drops = source == null || !showDrops ? List.of()
                : source.possibleDrops(server, activity.id(), dropsFor.isEmpty() ? null : dropsFor);
        if (showDrops && drops.isEmpty() && !dropsFor.isEmpty() && source != null) {
            // Nothing listed for that difficulty: every difficulty's drops (the panel says so), not an empty box.
            dropsFor = "";
            drops = source.possibleDrops(server, activity.id(), null);
        }
        int dropsTotal = !showDrops ? -1 : Math.max(drops.size(), source == null ? -1
                : source.possibleDropsCount(server, activity.id(), dropsFor.isEmpty() ? null : dropsFor));
        ActivitySource.LobbySettings current = request.edit() && source != null
                ? source.ownLobbySettings(player).orElse(null) : null;
        ServerPlayNetworking.send(player, new HubPayloads.ActivityInfoData(activity.id(), info != null, difficulties,
                levels, modifiers, info == null ? 0 : info.maxMembers(), info != null && info.levelsActive(), dropsFor,
                drops, dropsTotal, current != null, current == null ? "" : current.difficulty(),
                current != null && current.hardcore(), current == null ? List.of() : current.modifiers(),
                current == null ? 0 : current.visibility(), current == null ? 0 : current.minLevel()));
    }

    /**
     * «Створити лобі» / «Зберегти»: the level and closed checks for the picked difficulty, then the source's own flow
     * (a new lobby's screen opens). Refused: the hub shows why.
     */
    private static void createLobby(ServerPlayer player, HubPayloads.LobbyCreate create) {
        MinecraftServer server = player.getServer();
        ActivitySource source = server == null ? null : Activities.sourceFor(server, create.activity());
        Component refused = ActivityGate.check(player, create.activity(),
                create.difficulty().isEmpty() ? null : create.difficulty());
        if (refused == null) {
            refused = source == null || !source.createsLobbies()
                    ? Component.translatable("message.eden_ld.activities.unknown")
                    : source.createLobby(player, create.activity(), new ActivitySource.LobbySettings(create.difficulty(),
                            create.hardcore(), List.copyOf(create.modifiers()), Math.max(0, Math.min(2, create.visibility())),
                            Math.max(0, Math.min(1000, create.minLevel()))), create.edit());
        }
        if (refused != null) {
            ServerPlayNetworking.send(player, new HubPayloads.HubNotice(ServerLang.localize(player, refused), false));
        } else if (create.edit()) {
            ServerPlayNetworking.send(player, new HubPayloads.HubNotice(
                    Component.translatable("message.eden_ld.activities.lobby_saved"), true));
        }
        sendOwnLobby(player);
        sendLobbies(player, true);
    }

    // ---------------------------------------------------------------- runs

    /**
     * A finished run (from Arenas_LD, or reported through the API): once into the server's
     * {@link ActivityHistory}, then stats for every player, and a win counts for the
     * {@code complete_dungeon} daily objective (target = the activity id).
     */
    public static void recordRun(MinecraftServer server, ResourceLocation id, @Nullable String difficulty, boolean won,
                                 long durationMillis, int wave, List<UUID> players) {
        recordRun(server, id, difficulty, won, durationMillis, wave, players, false);
    }

    /**
     * As above; {@code forced}: staff ended the run (Moderation → End run). It's listed in the history
     * but counts for nothing — no stats, no player progress, no daily.
     */
    public static void recordRun(MinecraftServer server, ResourceLocation id, @Nullable String difficulty, boolean won,
                                 long durationMillis, int wave, List<UUID> players, boolean forced) {
        ActivityHistory.get(server).record(id, new ActivityHistory.Run(System.currentTimeMillis() / 1000L, difficulty,
                won && !forced, forced, Math.max(0, durationMillis), Math.max(0, wave), players));
        if (!forced) {
            credit(server, id, difficulty, won, durationMillis, wave, players);
        }
    }

    /**
     * Player progress only, without a history entry: stats for every player and the daily for a win
     * (staff recording a clear — Moderation's Complete, {@code /activities complete}).
     */
    public static void credit(MinecraftServer server, ResourceLocation id, @Nullable String difficulty, boolean won,
                              long durationMillis, int wave, List<UUID> players) {
        for (UUID uuid : players) {
            ActivityProgress.record(server, uuid, id, difficulty, won, durationMillis, wave);
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (won && online != null && DailyQuests.report(online, DailyQuests.COMPLETE_DUNGEON,
                    DailyQuests.idMatcher(id), 1)) {
                net.ledok.eden_ld.hub.HubServer.pushDailies(online);
            }
        }
    }
}
