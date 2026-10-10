package net.ledok.eden_ld.flight;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.api.flight.FlightRule;
import net.ledok.eden_ld.api.flight.FlightVerdict;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * Eden_LD's own rule, driven by the {@code flight} config section:
 * dimensions where flight is blocked entirely, and dimensions where
 * firework boosting is blocked (with the armor-threshold exemption
 * carried over from Yggdrasil_LD: low-armor players may still boost).
 */
public class ConfigFlightRule implements FlightRule {

    @Override
    public FlightVerdict testFlight(LivingEntity entity) {
        String dimension = entity.level().dimension().location().toString();
        return EdenLD.CONFIG.flight.flight_disabled_dimensions.contains(dimension)
                ? FlightVerdict.DENY
                : FlightVerdict.PASS;
    }

    @Override
    public FlightVerdict testBoost(Player player) {
        String dimension = player.level().dimension().location().toString();
        if (!EdenLD.CONFIG.flight.boost_disabled_dimensions.contains(dimension)) {
            return FlightVerdict.PASS;
        }
        if (EdenLD.CONFIG.flight.boost_armor_threshold_enabled
                && player.getAttributeValue(Attributes.ARMOR) < EdenLD.CONFIG.flight.boost_armor_threshold) {
            return FlightVerdict.PASS;
        }
        return FlightVerdict.DENY;
    }

    @Override
    public Component flightDenyMessage() {
        return Component.translatable("message.eden_ld.flight.disabled").withStyle(ChatFormatting.RED);
    }

    @Override
    public Component boostDenyMessage() {
        return Component.translatable("message.eden_ld.boost.disabled").withStyle(ChatFormatting.RED);
    }
}
