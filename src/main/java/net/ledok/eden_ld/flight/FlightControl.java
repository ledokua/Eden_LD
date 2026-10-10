package net.ledok.eden_ld.flight;

import net.fabricmc.fabric.api.entity.event.v1.EntityElytraEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.api.flight.EdenFlightApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Enforces {@link EdenFlightApi} verdicts.
 *
 * Fabric's {@code EntityElytraEvents.ALLOW} gates vanilla elytra flight
 * AND every custom wing flying through {@code EntityElytraEvents.CUSTOM}
 * (Icarus wings do), on activation and on every flight tick — so one
 * listener covers them all, with no Icarus-specific code.
 */
public final class FlightControl {

    /** Deny messages repeat every tick while gliding is held; throttle them. */
    private static final int MESSAGE_THROTTLE_TICKS = 60;

    private static final Map<ServerPlayer, Long> LAST_DENY_MESSAGE = new WeakHashMap<>();

    private FlightControl() {
    }

    public static void initialize() {
        EdenFlightApi.register(ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "config"),
                1000, new ConfigFlightRule());

        EntityElytraEvents.ALLOW.register(FlightControl::allowFlight);
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide() && player.isFallFlying() && stack.is(Items.FIREWORK_ROCKET)) {
                EdenFlightApi.Result result = EdenFlightApi.testBoost(player);
                if (!result.allowed()) {
                    if (result.message() != null && player instanceof ServerPlayer serverPlayer) {
                        serverPlayer.sendSystemMessage(result.message(), true);
                    }
                    return InteractionResultHolder.fail(stack);
                }
            }
            return InteractionResultHolder.pass(stack);
        });
    }

    private static boolean allowFlight(LivingEntity entity) {
        EdenFlightApi.Result result = EdenFlightApi.testFlight(entity);
        if (result.allowed()) {
            return true;
        }

        if (entity instanceof ServerPlayer player) {
            // A mid-flight deny would drop the player out of the sky.
            if (EdenLD.CONFIG.flight.safe_landing_on_deny && player.isFallFlying()) {
                player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false, true));
            }
            if (result.message() != null) {
                long now = player.level().getGameTime();
                Long last = LAST_DENY_MESSAGE.get(player);
                if (last == null || now - last >= MESSAGE_THROTTLE_TICKS) {
                    LAST_DENY_MESSAGE.put(player, now);
                    player.sendSystemMessage(result.message(), true);
                }
            }
        }
        return false;
    }
}
