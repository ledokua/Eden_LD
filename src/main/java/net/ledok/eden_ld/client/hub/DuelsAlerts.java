package net.ledok.eden_ld.client.hub;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Duels outside the hub: when the player's match starts, a bell and a notice in the corner of the
 * screen — they may be looking elsewhere while the queue pops. Settings → Notifications → Duels
 * turns it (and the Home card) off.
 */
public final class DuelsAlerts {

    private DuelsAlerts() {
    }

    public static void matchStarting(int mode) {
        if (!HubNotifications.enabled(HubNotifications.Kind.DUELS)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.2f, 1f));
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("gui.eden_ld.hub.duels.alert.title"),
                Component.translatable("gui.eden_ld.hub.duels.alert.text",
                        Component.translatable("gui.eden_ld.hub.duels.mode." + (mode == 1 ? "2v2" : "1v1"))));
    }
}
