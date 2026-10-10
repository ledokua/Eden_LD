package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * The ignore list in the hub ({@code IgnoreList} on the server): the right-click item that toggles it,
 * the mark on an ignored player's name, and where lists put them — after everyone else online, before
 * the offline.
 */
final class Ignoring {

    /** Before an ignored player's name in lists. */
    static final String MARK = "⊘ ";

    private Ignoring() {
    }

    /** "Ignore" or "Stop ignoring" for {@code uuid}. */
    static Component menuLabel(UUID uuid) {
        return Component.translatable(HubState.ignores(uuid) ? "gui.eden_ld.hub.ignore.stop" : "gui.eden_ld.hub.ignore.start");
    }

    static void toggle(UUID uuid) {
        ClientPlayNetworking.send(new HubPayloads.IgnoreAction(uuid, !HubState.ignores(uuid)));
    }

    static void stop(UUID uuid) {
        ClientPlayNetworking.send(new HubPayloads.IgnoreAction(uuid, false));
    }

    /** List order: 0 online, 1 online but ignored, 2 offline. */
    static int order(UUID uuid, boolean online) {
        return !online ? 2 : HubState.ignores(uuid) ? 1 : 0;
    }
}
