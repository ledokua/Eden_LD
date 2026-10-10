package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * Back to the hub after an activity's lobby screen: a card click arms this; the next screen the
 * server opens (Arenas_LD's lobby screen, and any screen of the same mod it opens in turn) is
 * followed, and when the player closes it with Esc the hub reopens on the Activities tab. Closed any
 * other way (a run starting, the server closing it) nothing happens, so nobody gets the hub over a
 * dungeon.
 */
public final class ActivityReturn {

    /** How long after a card click the lobby screen may arrive. */
    private static final long ARM_MILLIS = 5000;

    private static long armedAt;
    /** The hub the card was clicked in was opened from the inventory: the reopened one goes back there too. */
    private static boolean backToInventory;
    private static Screen followed;
    private static String followedPackage = "";
    private static boolean escaped;
    private static boolean check;

    private ActivityReturn() {
    }

    /** A card was clicked: follow the screen the server opens next. */
    static void arm() {
        armedAt = System.currentTimeMillis();
        backToInventory = Minecraft.getInstance().screen instanceof HubScreen hub && hub.backToInventory();
    }

    public static void initialize() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof HubScreen || screen == followed) {
                return;
            }
            boolean fresh = armedAt > 0 && System.currentTimeMillis() - armedAt < ARM_MILLIS;
            // The lobby screen may open another screen of its mod (a sub-page): keep following.
            boolean sameMod = followed != null && !followedPackage.isEmpty()
                    && screen.getClass().getName().startsWith(followedPackage);
            if (!fresh && !sameMod) {
                return;
            }
            armedAt = 0;
            follow(screen);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!check) {
                return;
            }
            check = false;
            if (client.screen == null && escaped) {
                boolean toInventory = backToInventory;
                stop();
                HubScreen.open("activities", toInventory);
            } else if (client.screen == null || client.screen instanceof HubScreen) {
                stop();
            }
            // Another screen opened: AFTER_INIT decides whether it's still the lobby's.
        });
    }

    private static void follow(Screen screen) {
        followed = screen;
        escaped = false;
        String name = screen.getClass().getName();
        int dot = name.indexOf('.', name.indexOf('.', name.indexOf('.') + 1) + 1);
        // e.g. "net.ledok.arenas_ld." — the screen's mod package.
        followedPackage = dot > 0 ? name.substring(0, dot + 1) : "";
        ScreenKeyboardEvents.allowKeyPress(screen).register((current, key, scancode, modifiers) -> {
            if (key == GLFW.GLFW_KEY_ESCAPE && current == followed) {
                escaped = true;
            }
            return true;
        });
        ScreenEvents.remove(screen).register(current -> {
            if (current == followed) {
                check = true;
            }
        });
    }

    private static void stop() {
        followed = null;
        followedPackage = "";
        escaped = false;
        backToInventory = false;
    }
}
