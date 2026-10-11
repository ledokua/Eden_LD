package net.ledok.eden_ld.client.hub;

import net.ledok.vectorlib.client.presentation.Spotlight;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Names for the Hub's parts, so tutorials (NPCs_LD dialog lines, VectorLib {@link Spotlight}) can point at them and
 * wait for them: the tab row, every tab, the page area and each tab's page (present only while that tab is open), the
 * help button and window, maximize and close, the balance on the Home page, and the Hub button in the inventory.
 * Ids are stable — dialogs store them.
 */
public final class HubSpotlights {
    private HubSpotlights() {}

    public static final String PREFIX = "eden_ld:hub/";
    public static final String TABS = PREFIX + "tabs";
    public static final String PAGE = PREFIX + "page";
    public static final String HELP = PREFIX + "help";
    public static final String HELP_WINDOW = PREFIX + "help_window";
    public static final String MAXIMIZE = PREFIX + "maximize";
    public static final String CLOSE = PREFIX + "close";
    /** The player's balance on the Home page's card (only where the server runs an economy). */
    public static final String BALANCE = PREFIX + "balance";
    /** The Hub button in the player's inventory ({@link InventoryHubButton}), found through a VectorLib locator. */
    public static final String INVENTORY_BUTTON = "eden_ld:inventory/hub_button";

    /** The tab's bookmark in the tab row. */
    public static String tab(HubTab tab) {
        return PREFIX + "tab/" + key(tab);
    }

    /** The tab's page: the content under the page title while that tab is open. */
    public static String page(HubTab tab) {
        return PREFIX + "page/" + key(tab);
    }

    private static String key(HubTab tab) {
        return tab.name().toLowerCase(Locale.ROOT);
    }

    /** Lists the parts in VectorLib's catalog, for editors' pickers. Client init. */
    public static void register() {
        Spotlight.register(TABS, Component.literal("Hub: tab row (every page)"));
        for (HubTab tab : HubTab.values()) {
            Spotlight.register(tab(tab), Component.literal("Hub: " + label(tab) + " tab"));
        }
        Spotlight.register(PAGE, Component.literal("Hub: page area"));
        for (HubTab tab : HubTab.values()) {
            Spotlight.register(page(tab), Component.literal("Hub: " + label(tab) + " page (shown while open)"));
        }
        Spotlight.register(HELP, Component.literal("Hub: ? help button"));
        Spotlight.register(HELP_WINDOW, Component.literal("Hub: help window"));
        Spotlight.register(MAXIMIZE, Component.literal("Hub: maximize button"));
        Spotlight.register(CLOSE, Component.literal("Hub: close button"));
        Spotlight.register(BALANCE, Component.literal("Hub: balance on the Home page"));
        Spotlight.register(INVENTORY_BUTTON, Component.literal("Inventory: Hub button"));
    }

    private static String label(HubTab tab) {
        String n = key(tab).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
