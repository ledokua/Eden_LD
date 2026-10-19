package net.ledok.eden_ld.combat;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.ledok.eden_ld.compat.duels.DuelsHook;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.ledok.eden_ld.world.WorldRules;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's own PvP switch (docs/PLAN-pvp-toggle.md): off for everyone until they turn it on ({@code /pvp on}, the
 * inventory button), with a minute's cooldown after any switch and none while combat-tagged ({@link PvpSwitch});
 * admins set it freely ({@code /eden pvp player}). The flag and the switch time live in {@link HubSettings}, so both
 * survive relogs and restarts. A dimension's mode ({@link WorldRules#pvpMode}) decides whether the flag counts there.
 * <p>
 * {@link #relation} is the server's answer to "how do these two players' sides treat each other" for
 * {@code CombatTeamsCompat}; the clients get what they need to answer the same for their own targeting:
 * {@link HubPayloads.PvpState} (their own flag, cooldown, combat tag, the mode where they are) and
 * {@link HubPayloads.PvpPlayers} (who has PvP on, who is dueling).
 */
public final class PlayerPvp {

    /** A player who hits someone they ignore hears why at most this often. */
    private static final long IGNORED_MESSAGE_MS = 2000;

    /** Flags as read from {@link HubSettings} (only this class writes them); cleared when the server stops. */
    private static final Map<UUID, Boolean> FLAGS = new ConcurrentHashMap<>();
    /** When each attacker last got an "ignored" action bar line. */
    private static final Map<UUID, Long> IGNORED_MESSAGE_AT = new ConcurrentHashMap<>();
    /** {@link HubPayloads.PvpPlayers} goes out at the end of the tick when set (one event often touches several players). */
    private static volatile boolean playersDirty;
    /** Players who need the list even when it didn't change (they just joined). */
    private static final Set<UUID> PLAYERS_WANTED = ConcurrentHashMap.newKeySet();
    /** The last list sent to everyone. */
    private static List<HubPayloads.PvpPlayer> lastPlayers = List.of();

    private PlayerPvp() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.PvpToggle.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            PvpSwitch.Result result = request(player, payload.on());
            // The inventory is open: the action bar shows over it.
            player.displayClientMessage(message(player, result, payload.on()), true);
        });
        // Deferred one tick, as the hub's own join pushes: Fabric's JOIN fires before the player is in the list.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            ServerPlayer player = handler.getPlayer();
            if (!player.hasDisconnected()) {
                sendState(player);
                PLAYERS_WANTED.add(player.getUUID());
            }
            playersChanged();
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            IGNORED_MESSAGE_AT.remove(handler.getPlayer().getUUID());
            server.execute(PlayerPvp::playersChanged);
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> sendState(player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> sendState(newPlayer));
        ServerTickEvents.END_SERVER_TICK.register(PlayerPvp::flushPlayers);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            FLAGS.clear();
            IGNORED_MESSAGE_AT.clear();
            PLAYERS_WANTED.clear();
            lastPlayers = List.of();
            playersDirty = false;
        });
    }

    // ---------------------------------------------------------------- state

    /** The player's own flag (absent = off). Online or not: pets of an offline owner count as their owner. */
    public static boolean flag(MinecraftServer server, UUID player) {
        Boolean cached = FLAGS.get(player);
        if (cached == null) {
            cached = HubSettings.intSetting(server, player, HubSettings.PVP) != 0;
            FLAGS.put(player, cached);
        }
        return cached;
    }

    /** Seconds before the player may switch again themselves, 0 when they may. */
    public static long cooldownLeft(MinecraftServer server, UUID player) {
        return PvpSwitch.cooldownLeft(nowSeconds(), HubSettings.intSetting(server, player, HubSettings.PVP_SWITCHED));
    }

    /**
     * How the sides {@code a} (attacking) and {@code t} treat each other where {@code target} stands. {@code runTeam}:
     * both players are on the same scoreboard team with friendly fire off.
     */
    public static PvpRelation relation(MinecraftServer server, Entity target, UUID a, UUID t, boolean runTeam) {
        int matchA = DuelsHook.match(a);
        int matchT = DuelsHook.match(t);
        if (matchA != PvpRelation.NO_MATCH || matchT != PvpRelation.NO_MATCH || runTeam) {
            // Duels and run teams don't look at anyone's flag.
            return PvpRelation.of(false, runTeam, matchA, matchT, false, false);
        }
        PvpMode mode = mode(server, target);
        return PvpRelation.of(PartyManager.protects(server, a, t), false, PvpRelation.NO_MATCH, PvpRelation.NO_MATCH,
                mode.effective(flag(server, a)), mode.effective(flag(server, t)));
    }

    private static PvpMode mode(MinecraftServer server, Entity where) {
        return where.level() instanceof ServerLevel level ? WorldRules.pvpMode(level)
                : PvpMode.serverDefault(server.isPvpAllowed());
    }

    // ---------------------------------------------------------------- switching

    /** The player switches their own PvP ({@code /pvp on|off}, the inventory button): cooldown and combat tag apply. */
    public static PvpSwitch.Result request(ServerPlayer player, boolean wanted) {
        MinecraftServer server = player.server;
        UUID uuid = player.getUUID();
        long now = nowSeconds();
        PvpSwitch.Result result = PvpSwitch.check(flag(server, uuid), wanted, now,
                HubSettings.intSetting(server, uuid, HubSettings.PVP_SWITCHED), CombatTag.millisLeft(uuid));
        if (result == PvpSwitch.Result.SWITCHED) {
            store(server, uuid, wanted, now);
            EdenLD.LOGGER.info("[PvP] {} turned their PvP {}.", player.getGameProfile().getName(), wanted ? "on" : "off");
        }
        // Even a refusal: the button shows the cooldown or combat time the server knows.
        sendState(player);
        return result;
    }

    /** An admin's change ({@code /eden pvp player}): no cooldown, no combat check, the player's own cooldown untouched. */
    public static boolean adminSet(MinecraftServer server, UUID player, boolean on, String who) {
        if (flag(server, player) == on) {
            return false;
        }
        store(server, player, on, -1);
        EdenLD.LOGGER.info("[PvP] {} turned {}'s PvP {}.", who, player, on ? "on" : "off");
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            sendState(online);
            online.sendSystemMessage(Component.translatable(on ? "message.eden_ld.pvp.admin_on" : "message.eden_ld.pvp.admin_off"));
        }
        return true;
    }

    private static void store(MinecraftServer server, UUID player, boolean on, long switchedAt) {
        HubSettings.setPvp(server, player, on, switchedAt);
        FLAGS.put(player, on);
        playersChanged();
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    // ---------------------------------------------------------------- messages

    /** The line for a switch request's result (chat for {@code /pvp}, the action bar for the button). */
    public static Component message(ServerPlayer player, PvpSwitch.Result result, boolean wanted) {
        MinecraftServer server = player.server;
        UUID uuid = player.getUUID();
        return switch (result) {
            case SWITCHED -> withHere(player, Component.translatable(wanted ? "message.eden_ld.pvp.switched_on"
                    : "message.eden_ld.pvp.switched_off"));
            case ALREADY -> Component.translatable(wanted ? "message.eden_ld.pvp.already_on" : "message.eden_ld.pvp.already_off");
            case IN_COMBAT -> Component.translatable("message.eden_ld.pvp.in_combat",
                    seconds(CombatTag.millisLeft(uuid)));
            case COOLDOWN -> Component.translatable("message.eden_ld.pvp.cooldown", cooldownLeft(server, uuid));
        };
    }

    /** {@code /pvp}: the player's flag, the cooldown left, and what the dimension makes of it. */
    public static Component status(ServerPlayer player) {
        MinecraftServer server = player.server;
        UUID uuid = player.getUUID();
        Component text = Component.translatable(flag(server, uuid) ? "message.eden_ld.pvp.status_on"
                : "message.eden_ld.pvp.status_off");
        long cooldown = cooldownLeft(server, uuid);
        if (cooldown > 0) {
            text = Component.empty().append(text).append(" ")
                    .append(Component.translatable("message.eden_ld.pvp.status_cooldown", cooldown));
        } else if (CombatTag.millisLeft(uuid) > 0) {
            text = Component.empty().append(text).append(" ")
                    .append(Component.translatable("message.eden_ld.pvp.status_combat", seconds(CombatTag.millisLeft(uuid))));
        }
        return withHere(player, text);
    }

    /** Adds "PvP is off here for everyone" / "on here for everyone" where the dimension overrides the flag. */
    private static Component withHere(ServerPlayer player, Component text) {
        PvpMode mode = WorldRules.pvpMode(player.serverLevel());
        if (mode == PvpMode.TOGGLE) {
            return text;
        }
        return Component.empty().append(text).append(" ").append(Component.translatable(
                mode == PvpMode.OFF ? "message.eden_ld.pvp.here_off" : "message.eden_ld.pvp.here_on"));
    }

    private static long seconds(long millis) {
        return (millis + 999) / 1000;
    }

    /**
     * A player is attacking {@code target} directly (melee — vanilla's or Better Combat's): when the two ignore each other,
     * the action bar says why ("Bob has PvP off", "Bob is in a duel", …), at most every 2 seconds. The hit itself is
     * refused by the damage checks.
     */
    public static void attacking(ServerPlayer attacker, Entity target) {
        UUID t = CombatTeamsCompat.sideOf(target);
        UUID a = attacker.getUUID();
        if (t == null || t.equals(a)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = IGNORED_MESSAGE_AT.get(a);
        if (last != null && now - last < IGNORED_MESSAGE_MS) {
            return;
        }
        CombatTeamsCompat.Relation relation = CombatTeamsCompat.resolve(attacker, target);
        if (relation == null || !relation.ignore()) {
            return;
        }
        MinecraftServer server = attacker.server;
        PvpMode mode = mode(server, target);
        PvpRelation.Reason reason = PvpRelation.reason(DuelsHook.match(a), DuelsHook.match(t), mode,
                mode.effective(flag(server, a)), mode.effective(flag(server, t)));
        String name = name(server, t);
        if (name == null && (reason == PvpRelation.Reason.TARGET_DUEL || reason == PvpRelation.Reason.TARGET_OFF)) {
            return;
        }
        IGNORED_MESSAGE_AT.put(a, now);
        attacker.displayClientMessage(switch (reason) {
            case TARGET_DUEL -> Component.translatable("message.eden_ld.pvp.ignored.target_duel", name);
            case OWN_DUEL -> Component.translatable("message.eden_ld.pvp.ignored.own_duel");
            case WORLD_OFF -> Component.translatable("message.eden_ld.pvp.ignored.world_off");
            case TARGET_OFF -> Component.translatable("message.eden_ld.pvp.ignored.target_off", name);
            case OWN_OFF -> Component.translatable("message.eden_ld.pvp.ignored.own_off");
        }, true);
    }

    /** A side's player name: online, else from the server's profile cache (a pet's absent owner); null when unknown. */
    @Nullable
    private static String name(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        var cache = server.getProfileCache();
        return cache == null ? null : cache.get(player).map(com.mojang.authlib.GameProfile::getName).orElse(null);
    }

    // ---------------------------------------------------------------- sync

    /** Tells the player their own PvP state (older clients without the payload are skipped). */
    public static void sendState(ServerPlayer player) {
        if (player.hasDisconnected() || !ServerPlayNetworking.canSend(player, HubPayloads.PvpState.TYPE)) {
            return;
        }
        MinecraftServer server = player.server;
        UUID uuid = player.getUUID();
        long switched = HubSettings.intSetting(server, uuid, HubSettings.PVP_SWITCHED);
        long cooldownMillis = PvpSwitch.cooldownLeft(nowSeconds(), switched) == 0 ? 0
                : Math.clamp((switched + PvpSwitch.COOLDOWN_SECONDS) * 1000L - System.currentTimeMillis(), 0,
                PvpSwitch.COOLDOWN_SECONDS * 1000L);
        ServerPlayNetworking.send(player, new HubPayloads.PvpState(flag(server, uuid),
                WorldRules.pvpMode(player.serverLevel()).ordinal(), cooldownMillis, CombatTag.millisLeft(uuid)));
    }

    /** A dimension's mode changed ({@code /eden pvp}, World settings, {@code /reload}): the players there hear it. */
    public static void modeChanged(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            sendState(player);
        }
    }

    /** Someone's flag, the online players or a duel changed: {@link HubPayloads.PvpPlayers} goes out at the end of the tick. */
    public static void playersChanged() {
        playersDirty = true;
    }

    private static void flushPlayers(MinecraftServer server) {
        if (!playersDirty) {
            return;
        }
        playersDirty = false;
        List<HubPayloads.PvpPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            boolean on = flag(server, uuid);
            int match = DuelsHook.match(uuid);
            if (on || match != PvpRelation.NO_MATCH) {
                players.add(new HubPayloads.PvpPlayer(uuid, on, match));
            }
        }
        boolean changed = !players.equals(lastPlayers);
        if (!changed && PLAYERS_WANTED.isEmpty()) {
            return;
        }
        lastPlayers = List.copyOf(players);
        HubPayloads.PvpPlayers payload = new HubPayloads.PvpPlayers(lastPlayers);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean wanted = PLAYERS_WANTED.remove(player.getUUID());
            if ((changed || wanted) && ServerPlayNetworking.canSend(player, HubPayloads.PvpPlayers.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
        PLAYERS_WANTED.clear();
    }
}
