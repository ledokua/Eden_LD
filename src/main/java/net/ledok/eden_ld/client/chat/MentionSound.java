package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The ping when someone @mentions the player, at the volume from Settings → Chat and nothing else:
 * no game slider changes it. It plays as a UI sound (the Master category, so the "Players" slider
 * doesn't apply), and its gain is the setting divided by the master volume, which the game applies
 * to every sound — so master cancels out ({@code SoundEngineMixin} lets that gain pass 1,
 * {@code AudioChannelMixin} lets OpenAL play it). Master at 0% mutes the whole game, the ping too.
 */
public final class MentionSound {

    /** The gain for the sound being started right now; -1 when it isn't the ping. */
    private static float starting = -1;

    private MentionSound() {
    }

    /** The player's setting, 0-100 (the default until the server sends settings). */
    public static int volume() {
        return HubState.settingsData != null ? HubState.settingsData.mentionVolume() : HubSettings.DEFAULT_MENTION_VOLUME;
    }

    /** For {@code SoundEngineMixin}: the ping's gain while it starts, else -1. */
    public static float startingGain() {
        return starting;
    }

    public static void play() {
        play(volume());
    }

    public static void play(int percent) {
        Minecraft minecraft = Minecraft.getInstance();
        float master = minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
        if (percent <= 0 || master <= 0) {
            return;
        }
        starting = percent / 100f / master;
        try {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f, 1f));
        } finally {
            starting = -1;
        }
    }
}
