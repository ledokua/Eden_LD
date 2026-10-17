package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.chat.ui.EmojiPicker;
import net.ledok.vectorlib.client.canvas.BoxStyle;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.Toast;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The shared {@link EmojiPicker} in the hub's party and guild chat: the parchment look, a panel
 * beside the chat (eight emojis a row, as tall as its place), the button between the chat field
 * and Send, and Toasts for full lists.
 */
final class HubPicker {

    /** Emojis a row in the hub. */
    static final int COLUMNS = 8;
    /** The panel's width: a side column's (108). */
    static final float WIDTH = EmojiPicker.width(COLUMNS);

    private HubPicker() {
    }

    /** The parchment look; built per call, because a hub theme change replaces EdenParchment's colors. */
    static EmojiPicker.Look look() {
        int accent = EdenParchment.ACCENT & 0xFFFFFF;
        // Square cells, as the parchment's other highlights.
        return new EmojiPicker.Look(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE),
                BoxStyle.fill(0x40000000 | accent), BoxStyle.fill(0x70000000 | accent), EdenParchment.INK_DIM,
                EdenParchment.TEXT_SHADOW);
    }

    /**
     * The panel beside a tab's chat: fixed width, as tall as its place. {@code input} is the chat
     * field (looked up on use: a rebuild replaces it); {@code refresh} rebuilds the tab.
     */
    static EmojiPicker.Panel panel(HubScreen screen, EmojiPicker.State state, Supplier<TextField> input,
                                   Runnable refresh) {
        EmojiPicker.Panel panel = EmojiPicker.panel(state, host(screen, input, refresh), look(), COLUMNS);
        panel.sizing(Sizing.fixed(WIDTH), Sizing.fill());
        return panel;
    }

    /** The button between the chat field and Send: opens or closes the panel (a tab rebuild). */
    static Button button(EmojiPicker.State state, Runnable refresh) {
        return EmojiPicker.button(state, refresh, EdenParchment.TAB_ACTIVE);
    }

    /** A full favorites or stickers list, on the open hub screen. */
    static void notice(Component message) {
        if (Minecraft.getInstance().screen instanceof HubScreen screen) {
            Toast.text(screen.canvas(), message, EdenParchment.DANGER);
        }
    }

    /**
     * Picks go into the chat field, which keeps the keyboard; closing rebuilds the tab; a blank
     * Enter in the search inserts the tab's first emoji.
     */
    static EmojiPicker.Host host(HubScreen screen, Supplier<TextField> input, Runnable refresh) {
        return new EmojiPicker.Host() {
            @Override
            public void insert(String text) {
                TextField field = input.get();
                if (field != null) {
                    field.insert(text);
                }
            }

            @Override
            public void close() {
                refresh.run();
            }

            @Override
            public void notice(Component message) {
                Toast.text(screen.canvas(), message, EdenParchment.DANGER);
            }

            @Override
            public void focusInput() {
                focus(screen, input.get());
                // A click on a cell: CanvasInput drops the focus once the press is handled (a cell
                // can't hold it), so give it back on the next frame, to the field a rebuild made.
                Minecraft.getInstance().tell(() -> focus(screen, input.get()));
            }

            @Override
            public boolean enterInsertsFirstWhenBlank() {
                return true;
            }
        };
    }

    private static void focus(HubScreen screen, @Nullable TextField field) {
        if (field != null && Minecraft.getInstance().screen == screen && field.canvas() == screen.canvas()) {
            screen.canvas().focus(field);
        }
    }
}
