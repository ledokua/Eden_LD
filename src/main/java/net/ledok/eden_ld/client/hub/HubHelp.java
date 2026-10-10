package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.Window;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The "?" window: what the open tab is for and how it works. The text is the lang key
 * {@code gui.eden_ld.hub.help.<tab>} (paragraphs split on blank lines), so it's translated and a
 * server can reword it through {@code config/eden_ld/lang/}.
 * <p>
 * A window over the hub, not a dialog: the player can move it, resize it, keep it open while using the
 * tab, and it switches to the help of whichever tab they open. "?" again closes it.
 */
final class HubHelp {

    private static final float WIDTH = 300;
    private static final float HEIGHT = 180;
    private static final float LANE = 6;

    @Nullable
    private static Window window;
    private static HubTab shown = HubTab.HOME;

    private HubHelp() {
    }

    static void open(HubScreen screen, HubTab tab) {
        var canvas = screen.canvas();
        if (window != null && window.canvas() == canvas) {
            window.close();
            return;
        }
        Window help = screen.window(title(tab), WIDTH, HEIGHT, "help");
        help.onResize((width, height) -> fill(help));
        help.onClose(() -> window = null);
        help.id(HubSpotlights.HELP_WINDOW);
        help.body().gap(0);
        window = help;
        shown = tab;
        fill(help);
        help.open(canvas);
    }

    /** The open tab changed: an open help window shows that tab's help. */
    static void follow(HubScreen screen, HubTab tab) {
        if (window == null || window.canvas() != screen.canvas() || tab == shown) {
            return;
        }
        shown = tab;
        window.title(title(tab));
        fill(window);
    }

    private static Component title(HubTab tab) {
        return Component.translatable("gui.eden_ld.hub.help.title", tab.displayName());
    }

    /** The help text, wrapped to the window's width, scrolling when it's taller than the window. */
    private static void fill(Window help) {
        Flex body = help.body();
        body.clear();
        int textWidth = (int) Math.max(40, help.width() - 16 - 4 - LANE);
        Flex paragraphs = Flex.column().gap(5).padding(Insets.of(2, 0, 2, 2));
        String text = Component.translatable("gui.eden_ld.hub.help." + shown.name().toLowerCase()).getString();
        for (String paragraph : text.split("\n\n")) {
            paragraphs.item(EdenParchment.text(Component.literal(paragraph), EdenParchment.INK_MID, textWidth));
        }
        ScrollPanel scroll = HubScroll.panel("help/" + shown.name(), textWidth + 2 + LANE, 100, paragraphs);
        scroll.sizing(Sizing.fill(), Sizing.expand());
        body.item(scroll);
    }
}
