package net.ledok.eden_ld.combat;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.api.flight.EdenFlightApi;
import net.ledok.eden_ld.api.flight.FlightRule;
import net.ledok.eden_ld.api.flight.FlightVerdict;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PvP combat tag: when damage from one player lands on another, both are tagged for
 * {@code combat.pvp_tag_seconds}. Tagged players can't use hub waystone teleports and,
 * with {@code pvp_tag_blocks_flight}, can't fly. Only landed damage counts (AFTER_DAMAGE),
 * so hits Eden cancels between partymates never tag anyone; projectiles count through
 * their shooter. The tagged player's client is told how long, so flight is refused
 * client side too instead of rubber-banding. In memory; survives relogging, not restarts.
 */
public final class CombatTag {

    private static final Map<UUID, Long> TAGGED_UNTIL = new ConcurrentHashMap<>();
    /** Tag refreshes arrive every hit; only re-sync the client when it moved noticeably. */
    private static final long SYNC_THRESHOLD_MS = 1000;

    private CombatTag() {
    }

    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(Sync.TYPE, Sync.CODEC);

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            if (entity instanceof ServerPlayer victim
                    && source.getEntity() instanceof ServerPlayer attacker
                    && attacker != victim
                    && damage > 0) {
                tag(victim);
                tag(attacker);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> TAGGED_UNTIL.clear());

        // Ahead of the config rule (1000) and typical arena rules (100): combat always grounds.
        EdenFlightApi.register(ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "pvp_combat"), 50,
                new FlightRule() {
                    @Override
                    public FlightVerdict testFlight(LivingEntity entity) {
                        return EdenLD.CONFIG.combat.pvp_tag_blocks_flight && isTagged(entity)
                                ? FlightVerdict.DENY : FlightVerdict.PASS;
                    }

                    @Override
                    public @Nullable Component flightDenyMessage() {
                        return Component.translatable("message.eden_ld.combat.no_flight");
                    }
                });
    }

    private static void tag(ServerPlayer player) {
        long until = System.currentTimeMillis() + EdenLD.CONFIG.combat.pvp_tag_seconds * 1000L;
        Long previous = TAGGED_UNTIL.put(player.getUUID(), until);
        if (previous == null || until - previous >= SYNC_THRESHOLD_MS) {
            ServerPlayNetworking.send(player, new Sync(until - System.currentTimeMillis()));
        }
    }

    /** Milliseconds left on the player's combat tag, 0 when not tagged. */
    public static long millisLeft(UUID player) {
        Long until = TAGGED_UNTIL.get(player);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    /** Both sides: the server's tag map, or on the client the local player's synced tag. */
    private static boolean isTagged(LivingEntity entity) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        if (entity.level().isClientSide()) {
            return net.ledok.eden_ld.client.ClientCombatTag.isTagged(player);
        }
        return millisLeft(player.getUUID()) > 0;
    }

    /** S2C: "you are combat-tagged for this many more milliseconds". */
    public record Sync(long millis) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "combat_tag"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarLong(p.millis),
                buf -> new Sync(buf.readVarLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
