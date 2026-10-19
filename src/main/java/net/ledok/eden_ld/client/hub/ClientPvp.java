package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.client.ClientCombatTag;
import net.ledok.eden_ld.combat.PvpMode;
import net.ledok.eden_ld.combat.PvpRelation;
import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The PvP toggle as this client knows it (docs/PLAN-pvp-toggle.md): the player's own flag, cooldown and the mode where
 * they are ({@link HubPayloads.PvpState}), and who else has PvP on or is dueling ({@link HubPayloads.PvpPlayers}). The
 * combat mods' matchers ask {@link #relation} for targeting and highlights, the nameplates {@link #showsIcon}, the
 * inventory button the rest; the server stays authoritative. Until a server sends its state (an older Eden), the
 * relation is the party and guild alone, as before the toggle.
 */
public final class ClientPvp {

    private static volatile boolean known;
    private static volatile boolean on;
    private static volatile PvpMode mode = PvpMode.OFF;
    /** {@link System#currentTimeMillis} when the switch cooldown ends. */
    private static volatile long cooldownUntil;
    /** Online players with PvP on or in a running duel (everyone else: off, no duel). */
    private static volatile Map<UUID, HubPayloads.PvpPlayer> players = Map.of();

    private ClientPvp() {
    }

    public static void reset() {
        known = false;
        on = false;
        mode = PvpMode.OFF;
        cooldownUntil = 0;
        players = Map.of();
    }

    public static void onState(HubPayloads.PvpState state) {
        on = state.on();
        mode = PvpMode.byId(state.mode());
        cooldownUntil = System.currentTimeMillis() + state.cooldownMillis();
        if (state.combatMillis() > 0) {
            // After a relog the server still has the tag; the tag's own sync only comes with the next hit.
            ClientCombatTag.onSync(state.combatMillis());
        }
        known = true;
    }

    public static void onPlayers(HubPayloads.PvpPlayers payload) {
        Map<UUID, HubPayloads.PvpPlayer> map = new HashMap<>();
        for (HubPayloads.PvpPlayer player : payload.players()) {
            map.put(player.uuid(), player);
        }
        players = map;
    }

    /** Whether this server sent the player's PvP state (it runs the toggle). */
    public static boolean known() {
        return known;
    }

    /** The player's own flag. */
    public static boolean on() {
        return on;
    }

    /** The mode where the player is. */
    public static PvpMode mode() {
        return mode;
    }

    /** Milliseconds before the player may switch again (0 when they may). */
    public static long cooldownMillis() {
        return Math.max(0, cooldownUntil - System.currentTimeMillis());
    }

    /** A player's own flag: this player's from their state, anyone else's from the list. */
    private static boolean flag(UUID player) {
        LocalPlayer local = Minecraft.getInstance().player;
        if (local != null && local.getUUID().equals(player)) {
            return on;
        }
        HubPayloads.PvpPlayer entry = players.get(player);
        return entry != null && entry.on();
    }

    private static int match(UUID player) {
        HubPayloads.PvpPlayer entry = players.get(player);
        return entry == null ? PvpRelation.NO_MATCH : entry.match();
    }

    /** Whether the player's effective state here is on — their nameplate shows the crossed swords. */
    public static boolean showsIcon(UUID player) {
        return known && mode.effective(flag(player));
    }

    /**
     * {@code CombatTeamsCompat.resolve} on the client: the same matrix as the server's, from the synced data — the
     * two sides' flags, duels and the mode here (every entity this client sees is in its dimension), and the player's
     * own party and guild.
     */
    @Nullable
    public static CombatTeamsCompat.Relation relation(UUID a, UUID t, boolean runTeam) {
        if (!known) {
            // An older server: party and guild friendly fire only, as before the toggle.
            return runTeam || ClientPartyRelation.protects(a, t) ? CombatTeamsCompat.Relation.FRIENDS : null;
        }
        PvpMode here = mode;
        return CombatTeamsCompat.Relation.of(PvpRelation.of(ClientPartyRelation.protects(a, t), runTeam, match(a), match(t),
                here.effective(flag(a)), here.effective(flag(t))));
    }
}
