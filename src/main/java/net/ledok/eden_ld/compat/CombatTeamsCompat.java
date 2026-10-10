package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Party-aware friendly fire for Spell Engine (AoE targeting) and Better Combat
 * (sweep targeting) via their {@code registerTeamMatcher} hooks. Both consult
 * matchers on the client (target selection/highlights) and the server (hits):
 * server side we answer from {@link PartyManager}; client side from the synced
 * party data. Friendly fire between partymates is always off.
 * <p>
 * Server side, players (and their pets) also count as teammates without friendly fire where PvP is off
 * — a dimension's own setting, else the server's ({@link net.ledok.eden_ld.world.WorldRules#pvpAt}) —
 * because Spell Engine and Better Combat never ask vanilla's PvP switch: Spell Engine then treats them as
 * {@code ALLY} (no harmful spell, healing as before), and projectiles fly through them.
 *
 * <p>The inner hook classes are the only ones importing Spell Engine / Better
 * Combat types, so this class is safe to load when either mod is absent.
 */
public final class CombatTeamsCompat {

    /** Party relation: teammates + whether hitting each other is allowed. */
    public record Relation(boolean teammates, boolean friendlyFire) {
    }

    private CombatTeamsCompat() {
    }

    public static void initialize() {
        if (FabricLoader.getInstance().isModLoaded("spell_engine")) {
            SpellEngineHook.register();
            EdenLD.LOGGER.info("[Party] Spell Engine team matcher registered.");
        }
        if (FabricLoader.getInstance().isModLoaded("bettercombat")) {
            BetterCombatHook.register();
            EdenLD.LOGGER.info("[Party] Better Combat team matcher registered.");
        }
    }

    /**
     * The party relation between two entities, each standing in for its "side": a player is
     * their own side, a tamed/owned entity (pets, horses) is its owner's. Null when either
     * has no side, or both belong to the same player (vanilla rules apply to your own pets).
     */
    @Nullable
    public static Relation resolve(Entity attacker, Entity target) {
        UUID a = sideOf(attacker);
        UUID t = sideOf(target);
        if (a == null || t == null || a.equals(t)) {
            return null;
        }
        if (attacker.level().isClientSide()) {
            return net.ledok.eden_ld.client.hub.ClientPartyRelation.resolve(a, t);
        }
        MinecraftServer server = attacker.getServer();
        if (server == null) {
            return null;
        }
        if (PartyManager.protects(server, a, t)) {
            return new Relation(true, false);
        }
        if (!net.ledok.eden_ld.world.WorldRules.pvpAt(target, a, t)) {
            return new Relation(true, false);
        }
        return null;
    }

    /** Player uuid, or the owner's uuid for owned entities (both sides — owners are synced). */
    @Nullable
    public static UUID sideOf(Entity entity) {
        if (entity instanceof Player player) {
            return player.getUUID();
        }
        if (entity instanceof OwnableEntity owned) {
            return owned.getOwnerUUID();
        }
        return null;
    }

    private static final class SpellEngineHook {
        static void register() {
            net.spell_engine.internals.target.EntityRelations.registerTeamMatcher("eden_ld_party",
                    (attacker, target) -> {
                        Relation relation = resolve(attacker, target);
                        return relation == null ? null
                                : new net.spell_engine.internals.target.EntityRelations.TeamRelation(
                                        relation.teammates(), relation.friendlyFire());
                    });
        }
    }

    private static final class BetterCombatHook {
        static void register() {
            net.bettercombat.logic.TargetHelper.registerTeamMatcher("eden_ld_party",
                    (attacker, target) -> {
                        Relation relation = resolve(attacker, target);
                        return relation == null ? null
                                : new net.bettercombat.logic.TargetHelper.TeamRelation(
                                        relation.teammates(), relation.friendlyFire());
                    });
        }
    }
}
