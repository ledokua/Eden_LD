package net.ledok.eden_ld.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/** The local player's combat tag as last synced by the server (flight rule, client side). */
public final class ClientCombatTag {

    private static volatile long taggedUntil;

    private ClientCombatTag() {
    }

    public static void onSync(long millis) {
        taggedUntil = System.currentTimeMillis() + millis;
    }

    public static void reset() {
        taggedUntil = 0;
    }

    /** Only the local player's tag is known here; other players always pass. */
    public static boolean isTagged(Player player) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && player.getUUID().equals(mc.player.getUUID())
                && System.currentTimeMillis() < taggedUntil;
    }
}
