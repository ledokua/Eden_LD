package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.chat.ChatLinks;
import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.ledok.eden_ld.client.chat.EmojiFont;
import net.ledok.eden_ld.client.chat.EmojiTabs;
import net.ledok.vectorlib.client.canvas.BoxStyle;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.CanvasRenderContext;
import net.ledok.vectorlib.client.canvas.Interactive;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.RoundedBox;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Grid;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.VirtualList;
import net.ledok.vectorlib.client.canvas.widget.WidgetStyle;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The emoji and sticker picker, shared by the hub's party and guild chat and the chat screen: tabs
 * ({@link EmojiTabs}) in a wrapping grid, a search field, then the emoji grid (or the saved
 * stickers). Click inserts through the {@link Host} (Shift+click keeps the panel open), right-click
 * stars or unstars an emoji, or saves or drops a sticker. Only for players with
 * {@code eden_ld.emoji_picker}.
 * <p>
 * The host owns the {@link State}, places and sizes the {@link Panel} (its width is
 * {@link #width(int)} for the column count it asked for; {@link Panel#preferredHeight} says how
 * tall its content wants to be) and gives it a {@link Look}. Changes made elsewhere (a star on a
 * chat line, the server's answer) reach an open panel through {@link EmojiFont#revision()}.
 */
public final class EmojiPicker {

    /** An emoji cell (and a tab cell). */
    public static final float CELL = 12;
    /** Around the panel's content, and between its rows. */
    public static final float PADDING = 3;
    /** VirtualList's scrollbar lane (bar 5 + 1). */
    public static final float LANE = 6;
    /** The search field's height. */
    public static final float SEARCH_HEIGHT = 14;

    /** One picker's state (the hub's party or guild chat, a chat screen); survives panel rebuilds. */
    public static final class State {
        public boolean open;
        public int tab;
        public String query = "";
        /** The search field of the panel built last (its focus carries over to the next one). */
        @Nullable
        TextField search;
        /**
         * The panel built last is to give its search the keyboard on its first draw and hasn't drawn yet: a second
         * rebuild before that (two hub pushes in one frame) carries the focus on, as its search never had it.
         */
        boolean searchFocusPending;

        /** The search field of the panel built last, or null before the first. */
        @Nullable
        public TextField search() {
            return search;
        }
    }

    /** What the panel needs from where it is shown. */
    public interface Host {
        /** Puts text (an emoji, a sticker's character) at the chat line's caret. */
        void insert(String text);

        /** A pick without Shift: the panel goes ({@link State#open} is already false). */
        void close();

        /** A favorites or stickers list is full: tell the player (the message is styled already). */
        void notice(Component message);

        /**
         * After a pick (before {@link #close}, Shift or not): typing goes back to the chat line. The
         * press that picked is still being handled, so a host that moves canvas focus defers it.
         */
        default void focusInput() {
        }

        /** The panel's content changed (a tab, a search, a refill): its preferred height may differ. */
        default void resized() {
        }

        /** Whether Enter in a blank search inserts the tab's first emoji (else it does nothing). */
        default boolean enterInsertsFirstWhenBlank() {
            return false;
        }
    }

    /**
     * The host's colors: the panel's background, a cell under the pointer, the selected tab, dim
     * text ("…" while a sticker has no image) and whether that text draws with a shadow.
     */
    public record Look(Function<Rect, CanvasNode> background, BoxStyle cellHover, BoxStyle cellLit, int inkDim,
                       boolean textShadow) {

        /** Dim text without a shadow. */
        public Look(Function<Rect, CanvasNode> background, BoxStyle cellHover, BoxStyle cellLit, int inkDim) {
            this(background, cellHover, cellLit, inkDim, false);
        }
    }

    private EmojiPicker() {
    }

    // ---------------------------------------------------------------- geometry (pure)

    /** The panel's width for {@code columns} emojis a row: 8 → 108 (hub), 10 → 132 (chat). */
    public static float width(int columns) {
        return columns * CELL + LANE + PADDING * 2;
    }

    /** Tab cells a row: as many as fit the inner width (the tabs have no scrollbar lane). */
    static int tabColumns(int columns) {
        return Math.max(1, (int) ((width(columns) - PADDING * 2) / CELL));
    }

    /** Saved stickers a row: four in the chat's 10 columns, three in the hub's 8. */
    static int stickerColumns(int columns) {
        return columns >= 10 ? 4 : 3;
    }

    /** A sticker cell's side: the emoji rows' width shared by the stickers of a row. */
    static float stickerCell(int columns) {
        return columns * CELL / stickerColumns(columns);
    }

    /**
     * How tall a panel wants to be: padding, the tab rows, the search, then {@code listRows} rows
     * (at least one, for the empty text) of {@code rowHeight}, at most {@code maxHeight}.
     */
    public static float preferredHeight(int tabCount, int columns, int listRows, float rowHeight, float maxHeight) {
        int tabColumns = tabColumns(columns);
        int tabRows = (Math.max(0, tabCount) + tabColumns - 1) / tabColumns;
        float height = PADDING * 2 + tabRows * CELL + PADDING + SEARCH_HEIGHT + PADDING
                + Math.max(1, listRows) * rowHeight;
        return Math.min(height, maxHeight);
    }

    // ---------------------------------------------------------------- state

    /** Whether this player gets the picker (permission {@code eden_ld.emoji_picker}). */
    public static boolean available() {
        return ClientChatChannel.emojiPicker();
    }

    /** Whether the panel should show now. */
    public static boolean showing(State state) {
        return state.open && available();
    }

    /** A picker opens on the first tab with something in it, without a search. */
    public static void opened(State state) {
        state.tab = EmojiTabs.initial();
        state.query = "";
        state.searchFocusPending = false;
    }

    /**
     * A button that opens or closes the panel (the hub's, between the chat field and Send); the
     * chat screen draws its own. {@code toggled} runs after the state changed; {@code openStyle}
     * is the button's look while the panel is open.
     */
    public static Button button(State state, Runnable toggled, @Nullable WidgetStyle openStyle) {
        Button button = new Button(16, 16, EmojiTabs.icon(EmojiTabs.SERVER), () -> {
            state.open = !state.open;
            if (state.open) {
                opened(state);
            }
            toggled.run();
        });
        button.tooltip(Component.translatable("chat.eden_ld.emoji.button"));
        if (state.open && openStyle != null) {
            button.style(openStyle);
        }
        return button;
    }

    /** The panel, the wheel at VectorLib's two rows a notch. The host sizes and places it. */
    public static Panel panel(State state, Host host, Look look, int columns) {
        return panel(state, host, look, columns, 0);
    }

    /** The panel, the wheel scrolling {@code wheelRows} rows a notch (0 or less: VectorLib's two). */
    public static Panel panel(State state, Host host, Look look, int columns, float wheelRows) {
        return new Panel(state, host, look, columns, wheelRows);
    }

    private static List<EmojiFont.Entry[]> rows(List<EmojiFont.Entry> entries, int columns) {
        List<EmojiFont.Entry[]> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += columns) {
            EmojiFont.Entry[] row = new EmojiFont.Entry[columns];
            for (int c = 0; c < columns && i + c < entries.size(); c++) {
                row[c] = entries.get(i + c);
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<List<ChatLinks.Sticker>> stickerRows(List<ChatLinks.Sticker> stickers, int columns) {
        List<List<ChatLinks.Sticker>> rows = new ArrayList<>();
        for (int i = 0; i < stickers.size(); i += columns) {
            rows.add(stickers.subList(i, Math.min(stickers.size(), i + columns)));
        }
        return rows;
    }

    // ---------------------------------------------------------------- the panel

    /**
     * The picker's panel: a column (tabs, search, list) on the look's background. It keeps itself
     * current: a change to {@link EmojiFont#revision()} refills the list when what it shows changed.
     */
    public static final class Panel extends Flex {
        private final State state;
        private final Host host;
        private final Look look;
        private final int columns;
        private final int stickerColumns;
        private final float stickerCell;
        private final Grid tabs;
        private final TextField search;
        private final VirtualList<EmojiFont.Entry[]> grid;
        private final VirtualList<List<ChatLinks.Sticker>> stickerGrid;
        /** What the list shows now (flat, before the rows), to tell a real change from a no-op bump. */
        private List<EmojiFont.Entry> shownEntries = List.of();
        private List<ChatLinks.Sticker> shownStickers = List.of();
        private boolean showingStickers;
        private int revision;
        private int tabCount;
        /**
         * The previous panel's search had the keyboard (or was to get it, never drawn): give it to this one once it is
         * on a canvas.
         */
        private boolean focusSearch;
        /** Built: the host hears about changes from now on (not while it is still being made). */
        private boolean built;

        private Panel(State state, Host host, Look look, int columns, float wheelRows) {
            super(Axis.COLUMN);
            this.state = state;
            this.host = host;
            this.look = look;
            this.columns = columns;
            this.stickerColumns = stickerColumns(columns);
            this.stickerCell = stickerCell(columns);
            float inner = EmojiPicker.width(columns) - PADDING * 2;
            gap(PADDING).padding(Insets.of(PADDING));
            background(look.background());

            grid = new VirtualList<>(inner, 100, CELL, (row, index, width) -> emojiRow(row));
            stickerGrid = new VirtualList<>(inner, 100, stickerCell, (row, index, width) -> stickerRow(row));
            stickerGrid.emptyText(Component.translatable("gui.eden_ld.hub.sticker.empty"));
            if (wheelRows > 0) {
                grid.wheelStep(CELL * wheelRows);
                stickerGrid.wheelStep(stickerCell * wheelRows);
            }

            tabs = Grid.of(tabColumns(columns)).cell(CELL, CELL);
            fillTabs();
            item(tabs);

            // Typing here survives rebuilds (a chat line landing, a theme change): same text, focus carried over.
            focusSearch = state.search != null && (state.search.isFocused() || state.searchFocusPending);
            state.searchFocusPending = focusSearch;
            search = new TextField(inner, state.query, query -> {
                state.query = query;
                refill(true);
            });
            search.sizing(Sizing.fill(), Sizing.fixed(SEARCH_HEIGHT));
            search.maxLength(40);
            search.hint(Component.translatable("chat.eden_ld.emoji.search").getString());
            search.onSubmit(() -> {
                if (state.query.isBlank() && !host.enterInsertsFirstWhenBlank()) {
                    return;
                }
                List<EmojiFont.Entry> found = EmojiTabs.entries(state.tab, state.query);
                if (!found.isEmpty()) {
                    pick(Character.toString(found.get(0).codepoint()));
                }
            });
            state.search = search;
            item(search);

            grid.sizing(Sizing.fill(), Sizing.expand());
            item(grid);
            stickerGrid.sizing(Sizing.fill(), Sizing.expand());
            item(stickerGrid);
            refill(true);
            built = true;
        }

        /** The column count this panel was built for. */
        public int columns() {
            return columns;
        }

        /** How tall the content wants to be (tabs, search, the list's rows), at most {@code maxHeight}. */
        public float preferredHeight(float maxHeight) {
            int listRows = showingStickers ? stickerGrid.size() : grid.size();
            return EmojiPicker.preferredHeight(tabCount, columns, listRows, showingStickers ? stickerCell : CELL,
                    maxHeight);
        }

        /** Shows the state's tab or search again; {@code resetScroll} goes back to the top. */
        public void refill(boolean resetScroll) {
            if (EmojiTabs.stickers(state.tab, state.query)) {
                show(null, EmojiFont.stickers(), resetScroll);
            } else {
                show(EmojiTabs.entries(state.tab, state.query), null, resetScroll);
            }
        }

        /**
         * Catches up with {@link EmojiFont#revision()}: a star, a saved sticker or the server's list
         * since the last look. Refills (keeping the scroll) only when the shown list really changed.
         * The panel polls on every draw; a host may call it before placing the panel, too. True when
         * something changed.
         */
        public boolean poll() {
            int now = EmojiFont.revision();
            if (now == revision) {
                return false;
            }
            revision = now;
            boolean tabsChanged = EmojiTabs.count() != tabCount;
            if (tabsChanged) {
                // The server's groups changed: other tabs (and maybe another tab row).
                state.tab = Math.max(0, Math.min(state.tab, EmojiTabs.count() - 1));
            }
            // The tab icons too: a server tab shows "?" until its emoji's image has arrived (revisions are rare).
            fillTabs();
            if (EmojiTabs.stickers(state.tab, state.query)) {
                List<ChatLinks.Sticker> stickers = EmojiFont.stickers();
                if (tabsChanged || !showingStickers || !stickers.equals(shownStickers)) {
                    show(null, stickers, false);
                    return true;
                }
            } else {
                List<EmojiFont.Entry> entries = EmojiTabs.entries(state.tab, state.query);
                if (tabsChanged || showingStickers || !entries.equals(shownEntries)) {
                    show(entries, null, false);
                    return true;
                }
            }
            return false;
        }

        private void fillTabs() {
            tabs.clear();
            tabCount = EmojiTabs.count();
            for (int tab = 0; tab < tabCount; tab++) {
                tabs.item(new TabCell(look, state, tab, () -> {
                    state.query = "";
                    search.text("");
                    refill(true);
                }));
            }
        }

        /** One of the two lists, the other hidden; exactly one argument is non-null. */
        private void show(@Nullable List<EmojiFont.Entry> entries, @Nullable List<ChatLinks.Sticker> stickers,
                          boolean resetScroll) {
            revision = EmojiFont.revision();
            showingStickers = stickers != null;
            grid.visible(!showingStickers);
            stickerGrid.visible(showingStickers);
            if (stickers != null) {
                shownStickers = stickers;
                stickerGrid.items(stickerRows(stickers, stickerColumns));
                if (resetScroll) {
                    stickerGrid.scrollTo(0);
                }
            } else {
                shownEntries = entries == null ? List.of() : entries;
                grid.emptyText(Component.translatable(state.query.isBlank() && state.tab == EmojiTabs.FAVORITES
                        ? "gui.eden_ld.hub.emoji.no_favorites" : "gui.eden_ld.hub.emoji.none"));
                grid.items(rows(shownEntries, columns));
                if (resetScroll) {
                    grid.scrollTo(0);
                }
            }
            if (built) {
                host.resized();
            }
        }

        /** Saved stickers: click puts one at the caret (a FakeNitro link), right-click drops it. */
        private Flex stickerRow(List<ChatLinks.Sticker> stickers) {
            Flex row = Flex.row();
            for (ChatLinks.Sticker sticker : stickers) {
                // Saving or dropping bumps EmojiFont's revision: the next draw refills.
                row.item(new StickerNode(sticker, stickerCell, look, () -> pick(EmojiFont.stickerCharacter(sticker)),
                        () -> {
                        }, Component.translatable("chat.eden_ld.sticker.insert"), host::notice));
            }
            return row;
        }

        private Flex emojiRow(EmojiFont.Entry[] entries) {
            Flex row = Flex.row();
            for (EmojiFont.Entry entry : entries) {
                if (entry != null) {
                    row.item(new EmojiCell(look, entry, () -> pick(Character.toString(entry.codepoint())),
                            () -> favorite(entry)));
                }
            }
            return row;
        }

        /** Into the chat line at its caret; the panel closes unless Shift is held. */
        private void pick(String text) {
            host.insert(text);
            host.focusInput();
            if (!Screen.hasShiftDown()) {
                state.open = false;
                state.searchFocusPending = false;
                host.close();
            }
        }

        private void favorite(EmojiFont.Entry entry) {
            // A star bumps EmojiFont's revision: the next draw refills the Favorites tab.
            if (!EmojiFont.toggleFavorite(entry.id())) {
                host.notice(Component.translatable("chat.eden_ld.emoji.favorites_full", EmojiFont.favoritesLimit())
                        .withStyle(ChatFormatting.RED));
            }
        }

        @Override
        protected void draw(CanvasRenderContext ctx) {
            poll();
            if (focusSearch) {
                VectorCanvas canvas = canvas();
                if (canvas != null) {
                    focusSearch = false;
                    canvas.focus(search);
                    state.searchFocusPending = false;
                }
            }
            super.draw(ctx);
        }
    }

    // ---------------------------------------------------------------- cells

    /** A highlight box behind a centered emoji glyph. */
    private abstract static class Cell extends CanvasGroup implements Interactive {
        private static final Rect BOUNDS = new Rect(0, 0, CELL, CELL);
        private final Look look;
        private final RoundedBox back;
        private boolean hovered;

        Cell(Look look, Component glyph) {
            this.look = look;
            back = add(RoundedBox.of(CELL, CELL));
            int width = Minecraft.getInstance().font.width(glyph);
            add(TextNode.of(glyph).shadow(false).at((CELL - width) / 2f + 1, 2));
        }

        abstract boolean selected();

        @Override
        public Rect bounds() {
            return BOUNDS;
        }

        @Override
        protected void draw(CanvasRenderContext ctx) {
            // The look's prebuilt styles: switched when the state changes, nothing allocated per frame.
            BoxStyle style = selected() ? look.cellLit() : hovered ? look.cellHover() : BoxStyle.NONE;
            if (back.style() != style) {
                back.style(style);
            }
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

        TabCell(Look look, State state, int tab, Runnable changed) {
            super(look, EmojiTabs.icon(tab));
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

        EmojiCell(Look look, EmojiFont.Entry entry, Runnable pick, Runnable favorite) {
            super(look, EmojiTabs.glyph(entry));
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
