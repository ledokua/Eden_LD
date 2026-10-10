package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.chat.ChatLinks;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.ledok.eden_ld.client.chat.EmojiFont;
import net.ledok.eden_ld.client.chat.EmojiTabs;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasRenderContext;
import net.ledok.vectorlib.client.canvas.Interactive;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.ShapeNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Grid;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.Toast;
import net.ledok.vectorlib.client.canvas.widget.VirtualList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The emoji picker of the hub's party and guild chat, in the hub's look: a button between the
 * chat field and Send opens a panel beside the chat — tabs as in the chat screen's picker
 * ({@link EmojiTabs}), a search field, the emoji grid. Click inserts the emoji into the chat
 * field (Shift+click keeps the panel open), right-click stars or unstars it. Only for players
 * with {@code eden_ld.emoji_picker}, like the chat screen's button.
 */
final class HubEmojiPicker {

    /** Panel width: a side column's (108), eight emojis a row. */
    static final float WIDTH = 108;
    private static final float PADDING = 3;
    private static final float INNER = WIDTH - PADDING * 2;
    private static final int CELL = 12;
    /** VirtualList's scrollbar lane (bar 5 + 1). */
    private static final float LANE = 6;
    private static final int COLUMNS = (int) ((INNER - LANE) / CELL);
    private static final int TAB_COLUMNS = (int) (INNER / CELL);
    /** The Stickers tab: three a row. */
    private static final int STICKER_COLUMNS = 3;
    private static final float STICKER_CELL = (INNER - LANE) / STICKER_COLUMNS;

    /** One chat's picker state (party, guild); survives the tab's rebuilds (every push rebuilds it). */
    static final class State {
        boolean open;
        int tab;
        String query = "";
        private TextField search;
    }

    private HubEmojiPicker() {
    }

    /** Whether this player gets the picker (permission {@code eden_ld.emoji_picker}). */
    static boolean available() {
        return ClientChatChannel.emojiPicker();
    }

    /** Whether the panel should show now. */
    static boolean showing(State state) {
        return state.open && available();
    }

    /** The button between the chat field and Send: opens or closes the panel. */
    static Button button(State state, Runnable rebuild) {
        Button button = new Button(16, 16, EmojiTabs.icon(EmojiTabs.SERVER), () -> {
            state.open = !state.open;
            if (state.open) {
                state.tab = EmojiTabs.initial();
                state.query = "";
            }
            rebuild.run();
        });
        button.tooltip(Component.translatable("chat.eden_ld.emoji.button"));
        if (state.open) {
            button.style(EdenParchment.TAB_ACTIVE);
        }
        return button;
    }

    /**
     * The panel: fixed width, as tall as its place. {@code input} is the chat field (looked up
     * on use — a rebuild replaces it); {@code rebuild} rebuilds the tab.
     */
    static Flex panel(HubScreen screen, State state, Supplier<TextField> input, Runnable rebuild) {
        Flex panel = Flex.column().gap(3).padding(Insets.of(PADDING));
        panel.sizing(Sizing.fixed(WIDTH), Sizing.fill());
        panel.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));

        VirtualList<EmojiFont.Entry[]> grid = new VirtualList<>(INNER, 100, CELL,
                (row, index, width) -> row(screen, state, row, input, rebuild));
        VirtualList<List<ChatLinks.Sticker>> stickerGrid = new VirtualList<>(INNER, 100, STICKER_CELL,
                (row, index, width) -> stickerRow(screen, state, row, input, rebuild));
        stickerGrid.emptyText(Component.translatable("gui.eden_ld.hub.sticker.empty"));
        Runnable refill = () -> {
            boolean stickers = EmojiTabs.stickers(state.tab, state.query);
            grid.visible(!stickers);
            stickerGrid.visible(stickers);
            if (stickers) {
                stickerGrid.items(stickerRows(EmojiFont.stickers()));
                stickerGrid.scrollTo(0);
                return;
            }
            List<EmojiFont.Entry> entries = EmojiTabs.entries(state.tab, state.query);
            grid.emptyText(Component.translatable(state.query.isBlank() && state.tab == EmojiTabs.FAVORITES
                    ? "gui.eden_ld.hub.emoji.no_favorites" : "gui.eden_ld.hub.emoji.none"));
            grid.items(rows(entries));
            grid.scrollTo(0);
        };

        Grid tabs = Grid.of(TAB_COLUMNS).cell(CELL, CELL);
        for (int tab = 0; tab < EmojiTabs.count(); tab++) {
            tabs.item(new TabCell(state, tab, () -> {
                state.query = "";
                if (state.search != null) {
                    state.search.text("");
                }
                refill.run();
            }));
        }
        panel.item(tabs);

        // Typing here survives rebuilds (a chat line landing): same text, focus carried over.
        boolean hadFocus = state.search != null && state.search.isFocused();
        TextField search = new TextField(INNER, state.query, query -> {
            state.query = query;
            refill.run();
        });
        search.sizing(Sizing.fill(), Sizing.fixed(14));
        search.maxLength(40);
        search.hint(Component.translatable("chat.eden_ld.emoji.search").getString());
        search.onSubmit(() -> {
            List<EmojiFont.Entry> found = EmojiTabs.entries(state.tab, state.query);
            if (!found.isEmpty()) {
                insert(screen, state, found.get(0), input, rebuild);
            }
        });
        state.search = search;
        panel.item(search);

        grid.sizing(Sizing.fill(), Sizing.expand());
        panel.item(grid);
        stickerGrid.sizing(Sizing.fill(), Sizing.expand());
        panel.item(stickerGrid);
        refill.run();
        if (hadFocus) {
            screen.canvas().focus(search);
        }
        return panel;
    }

    private static List<EmojiFont.Entry[]> rows(List<EmojiFont.Entry> entries) {
        List<EmojiFont.Entry[]> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += COLUMNS) {
            EmojiFont.Entry[] row = new EmojiFont.Entry[COLUMNS];
            for (int c = 0; c < COLUMNS && i + c < entries.size(); c++) {
                row[c] = entries.get(i + c);
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<List<ChatLinks.Sticker>> stickerRows(List<ChatLinks.Sticker> stickers) {
        List<List<ChatLinks.Sticker>> rows = new ArrayList<>();
        for (int i = 0; i < stickers.size(); i += STICKER_COLUMNS) {
            rows.add(stickers.subList(i, Math.min(stickers.size(), i + STICKER_COLUMNS)));
        }
        return rows;
    }

    /** Saved stickers: click puts one in the chat field (a FakeNitro link), right-click drops it. */
    private static Flex stickerRow(HubScreen screen, State state, List<ChatLinks.Sticker> stickers,
                                   Supplier<TextField> input, Runnable rebuild) {
        Flex row = Flex.row();
        for (ChatLinks.Sticker sticker : stickers) {
            row.item(new StickerNode(sticker, STICKER_CELL, () -> {
                TextField field = input.get();
                if (field == null) {
                    return;
                }
                field.insert(net.ledok.eden_ld.client.chat.EmojiFont.stickerCharacter(sticker));
                screen.canvas().focus(field);
                if (!Screen.hasShiftDown()) {
                    state.open = false;
                    rebuild.run();
                }
            }, rebuild, Component.translatable("chat.eden_ld.sticker.insert")));
        }
        return row;
    }

    private static Flex row(HubScreen screen, State state, EmojiFont.Entry[] entries, Supplier<TextField> input,
                            Runnable rebuild) {
        Flex row = Flex.row();
        for (EmojiFont.Entry entry : entries) {
            if (entry != null) {
                row.item(new EmojiCell(entry, () -> insert(screen, state, entry, input, rebuild),
                        () -> toggleFavorite(screen, state, entry, rebuild)));
            }
        }
        return row;
    }

    /** Into the chat field at its cursor; the panel closes unless Shift is held. */
    private static void insert(HubScreen screen, State state, EmojiFont.Entry entry, Supplier<TextField> input,
                               Runnable rebuild) {
        TextField field = input.get();
        if (field == null) {
            return;
        }
        field.insert(Character.toString(entry.codepoint()));
        screen.canvas().focus(field);
        if (!Screen.hasShiftDown()) {
            state.open = false;
            rebuild.run();
        }
    }

    private static void toggleFavorite(HubScreen screen, State state, EmojiFont.Entry entry, Runnable rebuild) {
        if (!EmojiFont.toggleFavorite(entry.id())) {
            Toast.text(screen.canvas(), Component.translatable("chat.eden_ld.emoji.favorites_full",
                    EmojiFont.favoritesLimit()).withStyle(ChatFormatting.RED), EdenParchment.DANGER);
        } else if (state.tab == EmojiTabs.FAVORITES && state.query.isBlank()) {
            rebuild.run();
        }
    }

    /** A highlight square behind a centered emoji glyph. */
    private abstract static class Cell extends CanvasGroup implements Interactive {
        private final ShapeNode back;
        private boolean hovered;

        Cell(Component glyph) {
            back = add(Shapes.rect(0, 0, CELL, CELL));
            int width = Minecraft.getInstance().font.width(glyph);
            add(TextNode.of(glyph).shadow(false).at((CELL - width) / 2f + 1, 2));
        }

        abstract boolean selected();

        @Override
        public Rect bounds() {
            return new Rect(0, 0, CELL, CELL);
        }

        @Override
        protected void draw(CanvasRenderContext ctx) {
            int accent = EdenParchment.ACCENT & 0xFFFFFF;
            back.fill(selected() ? 0x70000000 | accent : hovered ? 0x40000000 | accent : 0);
            super.draw(ctx);
        }

        @Override
        public void onHoverChanged(boolean hovered) {
            this.hovered = hovered;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }

    private static final class TabCell extends Cell {
        private final State state;
        private final int tab;
        private final Runnable changed;

        TabCell(State state, int tab, Runnable changed) {
            super(EmojiTabs.icon(tab));
            this.state = state;
            this.tab = tab;
            this.changed = changed;
            tooltip(() -> List.of(EmojiTabs.name(tab)));
        }

        @Override
        boolean selected() {
            return state.tab == tab && state.query.isBlank();
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            state.tab = tab;
            changed.run();
            return true;
        }
    }

    private static final class EmojiCell extends Cell {
        private final Runnable pick;
        private final Runnable favorite;

        EmojiCell(EmojiFont.Entry entry, Runnable pick, Runnable favorite) {
            super(EmojiTabs.glyph(entry));
            this.pick = pick;
            this.favorite = favorite;
            tooltip(() -> List.of(Component.literal(":" + entry.name() + ":"),
                    Component.translatable(EmojiFont.isFavorite(entry.id())
                            ? "chat.eden_ld.emoji.unfavorite" : "chat.eden_ld.emoji.favorite")
                            .withStyle(ChatFormatting.GRAY)));
        }

        @Override
        boolean selected() {
            return false;
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button == 0) {
                pick.run();
                return true;
            }
            if (button == 1) {
                favorite.run();
                return true;
            }
            return false;
        }
    }
}
