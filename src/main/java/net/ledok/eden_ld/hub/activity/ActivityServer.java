package net.ledok.eden_ld.hub.activity;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
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
 * recording finished runs (stats, the {@code complete_dungeon} daily objective).
 */
public final class ActivityServer {

    /** What each online player was last told is new (so the chat line comes once per unlock). */
    private static final Map<UUID, Set<String>> TOLD = new HashMap<>();

    private ActivityServer() {
    }

    public static void initialize() {
        // Arenas_LD first: its ids win over a datapack file with the same id.
        ArenasHook.initialize();
        DatapackActivities.initialize();
        Activities.register(DatapackActivities.INSTANCE);
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ActivitiesRequest.TYPE,
                (payload, context) -> send(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ActivityOpen.TYPE,
                (payload, context) -> open(context.player(), payload.id()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ActivitiesSeen.TYPE,
                (payload, context) -> seen(context.player()));
        // After the join sync settles (see HubServer): the badge and Home card for unseen unlocks.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            ServerPlayer player = handler.getPlayer();
            TOLD.put(player.getUUID(), new LinkedHashSet<>(fresh(server, player).keySet()));
            pushUnlocks(server, player);
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TOLD.remove(handler.getPlayer().getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int every = Math.max(5, EdenLD.CONFIG.activities.unlock_check_seconds) * 20;
            if (Activities.takeChanged() || server.getTickCount() % every == 0) {
                server.getPlayerList().getPlayers().forEach(player -> checkUnlocks(server, player));
            }
        });
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

    // ---------------------------------------------------------------- runs

    /**
     * A finished run (from Arenas_LD, or reported through the API): stats for every player, and
     * a win counts for the {@code complete_dungeon} daily objective (target = the activity id).
     */
    public static void recordRun(MinecraftServer server, ResourceLocation id, @Nullable String difficulty, boolean won,
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
