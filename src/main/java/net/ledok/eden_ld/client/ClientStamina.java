package net.ledok.eden_ld.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.combat.Stamina;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * The local player's stamina ({@link Stamina}): the server's value, regenerated here between its updates, and a roll
 * paid at once when the player rolls so the next roll's check doesn't wait for the server. Active once the server has
 * sent a value (a server without Eden's stamina leaves Combat Roll's charges alone).
 */
public final class ClientStamina {

    private static boolean active;
    private static double value;
    private static int exhaustedTicks;
    private static int pauseTicks;
    private static long tick;
    private static long lastRoll = Long.MIN_VALUE / 2;

    private ClientStamina() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(Stamina.Sync.TYPE, (payload, context) -> {
            active = true;
            value = payload.value();
            exhaustedTicks = payload.exhaustedTicks();
            pauseTicks = payload.pauseTicks();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> active = false);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tick++;
            Player player = client.player;
            if (!active || player == null) {
                return;
            }
            if (exhaustedTicks > 0) {
                exhaustedTicks--;
            } else if (pauseTicks > 0) {
                pauseTicks--;
            } else {
                value = Math.min(Stamina.max(player), value + Stamina.regen(player) / 20.0);
            }
        });
    }

    public static boolean active() {
        return active;
    }

    /** Whether a roll is allowed now: stamina above 0, not exhausted, the gap since the last roll over. */
    public static boolean canRoll() {
        return value > 0 && exhaustedTicks <= 0 && tick - lastRoll >= EdenLD.CONFIG.combat.roll_gap_ticks;
    }

    /** The local player just rolled: pay for it now (the server confirms with its next update). */
    public static void rolled() {
        Player player = Minecraft.getInstance().player;
        if (!active || player == null) {
            return;
        }
        lastRoll = tick;
        value -= Stamina.cost(player);
        pauseTicks = EdenLD.CONFIG.combat.stamina_regen_pause_ticks;
        if (value <= 0) {
            value = 0;
            exhaustedTicks = Stamina.exhaustionTicks(player);
        }
    }

    public static double value() {
        return value;
    }

    public static boolean exhausted() {
        return exhaustedTicks > 0;
    }

    /** The bar shows while stamina isn't full or the player is exhausted. */
    public static boolean showBar(Player player) {
        return active && (exhausted() || value < Stamina.max(player) - 0.01);
    }
}
