package net.ledok.eden_ld.api.flight;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * A rule consulted whenever an entity tries to start or continue
 * elytra-style flight (vanilla elytra, Icarus wings, or any mod using
 * Fabric's elytra events), and whenever a player tries to firework-boost.
 *
 * Register with {@link EdenFlightApi#register}. Rules are evaluated in
 * ascending priority order; the first non-{@link FlightVerdict#PASS}
 * verdict wins. Rules must be stateless: they are shared between the
 * client and server threads.
 */
public interface FlightRule {

    /**
     * Called on activation and once per flight tick, server and client side.
     * Keep it cheap.
     */
    FlightVerdict testFlight(LivingEntity entity);

    /** Called when a player uses a firework rocket while fall-flying. */
    default FlightVerdict testBoost(Player player) {
        return FlightVerdict.PASS;
    }

    /** Action-bar message shown (throttled) when this rule denies flight. */
    @Nullable
    default Component flightDenyMessage() {
        return null;
    }

    /** Message shown when this rule denies a firework boost. */
    @Nullable
    default Component boostDenyMessage() {
        return null;
    }
}
