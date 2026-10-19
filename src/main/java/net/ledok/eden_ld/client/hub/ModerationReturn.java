package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Back to the Moderation window after an activity source's admin screen (Moderation → Activities →
 * Admin screen), like {@link ActivityReturn} does for the hub: the button arms this; the next screen the
 * server opens (and any screen of the same mod it opens in turn) is followed, and when the admin closes
 * it with Esc the Moderation window reopens on Activities with the same activity picked. Closed any
 * other way, nothing happens.
 */
public final class ModerationReturn {

    /** How long after the click the admin screen may arrive. */
    private static final long ARM_MILLIS = 5000;

    private static long armedAt;
    @Nullable
    private static Screen parent;
    private static String selected = "";
    @Nullable
    private static Screen followed;
    private static String followedPackage = "";
    private static boolean escaped;
    private static boolean check;

    private ModerationReturn() {
    }

    /** Admin screen asked for: follow the screen the server opens next; Esc there comes back to {@code selected}. */
    static void arm(@Nullable Screen moderationParent, String activity) {
        armedAt = System.currentTimeMillis();
        parent = moderationParent;
        selected = activity;
    }

    /**
     * No admin screen is coming after all: the source refused (a failed notice) or the admin left the
     * Moderation window himself. A screen already followed stays followed.
     */
    static void disarm() {
        armedAt = 0;
        if (followed == null) {
            parent = null;
            selected = "";
        }
    }

    public static void initialize() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof ModerationScreen || screen == followed) {
                return;
            }
            // Never one of the screens the admin opens by himself (Eden's own, chat, inventory, pause).
            if (screen.getClass().getName().startsWith("net.ledok.eden_ld.") || screen instanceof ChatScreen
                    || screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen
                    || screen instanceof PauseScreen) {
                return;
            }
            boolean fresh = armedAt > 0 && System.currentTimeMillis() - armedAt < ARM_MILLIS;
            // The admin screen may open another screen of its mod (a sub-page): keep following.
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
                Screen back = parent;
                String activity = selected;
                stop();
                ModerationScreen.openActivitiesFrom(back, activity);
            } else if (client.screen == null || client.screen instanceof ModerationScreen) {
                stop();
            }
            // Another screen opened: AFTER_INIT decides whether it's still the admin screen's.
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
        parent = null;
        selected = "";
    }
}
