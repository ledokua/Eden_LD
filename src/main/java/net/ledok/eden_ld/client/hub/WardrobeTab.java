package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.HubGlyphs;
import net.ledok.eden_ld.client.profile.ProfileBackgrounds;
import net.ledok.eden_ld.client.theme.HubTheme;
import net.ledok.eden_ld.client.theme.HubThemes;
import net.ledok.eden_ld.client.theme.ThemePreview;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.WardrobeItem;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Grid;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Wardrobe tab: every look a player can own, by category (profile backgrounds, hub themes —
 * room for character cosmetics later), filtered All / Owned / Shop. A card shows the picture and
 * whether it's worn, owned, its price, or locked; clicking one opens it big, with Wear, Buy and
 * Gift. A gift is paid by the giver, unlocked for the friend and announced to them by mail.
 */
final class WardrobeTab {

    private static final float LANE = 6;
    private static final float GAP = 5;
    /** A card's narrowest width: three a row at the smallest hub, more as it grows. */
    private static final float CARD_MIN = 130;
    /** Set as the tab builds, from its width. */
    private static int columns = 3;
    private static float cardWidth = CARD_MIN;
    private static final float PICTURE_H = 62;
    private static final float CARD_H = PICTURE_H + 22;

    enum Category { BACKGROUNDS, THEMES }

    enum Filter { ALL, OWNED, SHOP }

    /** Kept while the game runs (like the other tabs' choices). */
    private static Category category = Category.BACKGROUNDS;
    private static Filter filter = Filter.ALL;

    private WardrobeTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.wardrobe." + key, args);
    }

    static void build(HubScreen screen, Flex content) {
        HubPayloads.WardrobeData data = HubState.wardrobeData;
        float width = HubScreen.innerWidth();
        columns = Math.max(3, (int) ((width - LANE + GAP) / (CARD_MIN + GAP)));
        cardWidth = (width - LANE - GAP * (columns - 1)) / columns;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
            content.item(center);
            return;
        }
        Flex header = Flex.row().gap(3).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        header.item(EdenParchment.tabs(List.of(Category.values()),
                candidate -> tr("category." + candidate.name().toLowerCase(Locale.ROOT)), category, candidate -> {
                    category = candidate;
                    screen.onWardrobeData();
                }, 50, "wardrobe/category"));
        header.spacer();
        header.item(EdenParchment.tabs(List.of(Filter.values()),
                candidate -> tr("filter." + candidate.name().toLowerCase(Locale.ROOT)), filter, candidate -> {
                    filter = candidate;
                    screen.onWardrobeData();
                }, 36, "wardrobe/filter"));
        content.item(header);

        List<WardrobeItem> shown = new ArrayList<>();
        for (WardrobeItem item : category == Category.THEMES ? data.themes() : data.backgrounds()) {
            boolean owned = item.state() == WardrobeItem.OWNED || item.state() == WardrobeItem.WORN;
            // Only looks this client can draw (a resource pack may lack one).
            if (!known(item) || filter == Filter.OWNED && !owned || filter == Filter.SHOP && item.state() != WardrobeItem.FOR_SALE) {
                continue;
            }
            shown.add(item);
        }
        // Worn first, then owned, then for sale (cheapest first), then locked.
        shown.sort(Comparator.<WardrobeItem>comparingInt(item -> switch (item.state()) {
            case WardrobeItem.WORN -> 0;
            case WardrobeItem.OWNED -> 1;
            case WardrobeItem.FOR_SALE -> 2;
            default -> 3;
        }).thenComparingLong(WardrobeItem::price).thenComparing(item -> name(item).getString()));
        if (shown.isEmpty()) {
            content.item(EdenParchment.text(tr("empty." + filter.name().toLowerCase(Locale.ROOT)), EdenParchment.INK_DIM));
            return;
        }
        Grid grid = Grid.of(columns).gap(GAP, GAP).cell(cardWidth, CARD_H);
        for (WardrobeItem item : shown) {
            grid.item(new Card(screen, data, item));
        }
        ScrollPanel panel = HubScroll.panel("wardrobe/" + category, HubScreen.innerWidth(), 100, grid);
        panel.sizing(Sizing.fill(), Sizing.expand());
        content.item(panel);
    }

    // ---------------------------------------------------------------- looks

    private static boolean themes() {
        return category == Category.THEMES;
    }

    @Nullable
    private static HubTheme theme(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : HubThemes.get(location);
    }

    private static boolean known(WardrobeItem item) {
        return themes() ? theme(item.id()) != null : ProfileBackgrounds.get(item.id()) != null;
    }

    private static Component name(WardrobeItem item) {
        if (themes()) {
            HubTheme theme = theme(item.id());
            return theme == null ? Component.literal(item.id()) : theme.name();
        }
        ProfileBackgrounds.Background background = ProfileBackgrounds.get(item.id());
        return background == null ? Component.literal(item.id()) : background.name();
    }

    private static CanvasNode picture(WardrobeItem item, float w, float h, float dim) {
        if (themes()) {
            CanvasGroup group = new CanvasGroup();
            HubTheme theme = theme(item.id());
            if (theme != null) {
                group.add(ThemePreview.node(theme, w, h));
            }
            if (dim > 0) {
                group.add(Shapes.rect(0, 0, w, h).fill((int) (dim * 255) << 24));
            }
            return group;
        }
        return ProfileBackgrounds.thumbnail(item.id(), w, h, dim);
    }

    private static Component price(long price) {
        return Component.literal(price + " " + HubGlyphs.LC_TEXT);
    }

    /** "Worn ✔", "Owned", "500 🪙", "🔒". */
    private static Component state(WardrobeItem item) {
        return switch (item.state()) {
            case WardrobeItem.WORN -> tr("worn");
            case WardrobeItem.OWNED -> tr("owned");
            case WardrobeItem.FOR_SALE -> price(item.price());
            default -> tr("locked");
        };
    }

    // ---------------------------------------------------------------- dialogs

    /** The look big, with what can be done with it: wear, buy, gift. */
    private static void open(HubScreen screen, HubPayloads.WardrobeData data, WardrobeItem item) {
        Modal[] modal = new Modal[1];
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(300), Sizing.content());
        dialog.background(EdenParchment.panel());
        dialog.item(EdenParchment.text(name(item), EdenParchment.INK));
        float pictureH = themes() ? 110 : 140;
        Flex framed = Flex.column();
        framed.sizing(Sizing.fixed(280), Sizing.fixed(pictureH));
        CanvasGroup preview = new CanvasGroup();
        preview.add(picture(item, 280, pictureH, 0));
        preview.add(Shapes.rect(0, 0, 280, pictureH).stroke(Stroke.of(EdenParchment.HAIRLINE, 1)));
        framed.item(preview);
        dialog.item(framed);
        dialog.item(EdenParchment.text(switch (item.state()) {
            case WardrobeItem.WORN -> tr("detail.worn");
            case WardrobeItem.OWNED -> tr("detail.owned");
            case WardrobeItem.FOR_SALE -> tr("detail.for_sale", item.price());
            default -> tr("detail.locked");
        }, EdenParchment.INK_MID, 280));
        HubPayloads.HomeData home = HubState.homeData;
        if (data.economy() && home != null && home.hasEconomy() && item.price() > 0) {
            dialog.item(EdenParchment.text(tr("balance", home.balance()), EdenParchment.INK_DIM, 280));
        }

        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        String id = item.id();
        if (item.state() == WardrobeItem.OWNED) {
            buttons.item(EdenParchment.fitButton(tr("wear"), 50, 14, () -> {
                ClientPlayNetworking.send(themes() ? new HubPayloads.ThemeSelect(id) : new HubPayloads.BackgroundSelect(id));
                modal[0].close();
            }));
        } else if (item.state() == WardrobeItem.WORN && !themes()) {
            buttons.item(EdenParchment.fitButton(tr("take_off"), 50, 14, () -> {
                ClientPlayNetworking.send(new HubPayloads.BackgroundSelect(""));
                modal[0].close();
            }));
        }
        if (item.state() == WardrobeItem.FOR_SALE && data.economy()) {
            boolean affordable = home == null || !home.hasEconomy() || home.balance() >= item.price();
            Button buy = EdenParchment.fitButton(tr("buy", item.price()), 60, 14, () -> {
                ClientPlayNetworking.send(new HubPayloads.LookBuy(themes(), id));
                ClientPlayNetworking.send(HubPayloads.HomeDataRequest.INSTANCE);
                modal[0].close();
            });
            buy.enabled(affordable);
            buttons.item(buy);
        }
        if (item.price() > 0 && data.economy() && item.state() != WardrobeItem.LOCKED) {
            buttons.item(EdenParchment.fitButton(tr("gift"), 50, 14, () -> {
                modal[0].close();
                gift(screen, item);
            }));
        }
        buttons.item(EdenParchment.fitButton(Component.translatable("gui.eden_ld.hub.profile.close"), 50, 14,
                () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    /** Gift it: to whom, an optional note, the price; the server checks, charges and sends. */
    private static void gift(HubScreen screen, WardrobeItem item) {
        Modal[] modal = new Modal[1];
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(260), Sizing.content());
        dialog.background(EdenParchment.panel());
        dialog.item(EdenParchment.text(tr("gift_title", name(item)), EdenParchment.INK, 240));
        dialog.item(EdenParchment.text(tr("gift_to"), EdenParchment.INK_MID));
        TextField recipient = new TextField(240, "", text -> { });
        recipient.hint(tr("gift_to_hint").getString());
        recipient.maxLength(16);
        dialog.item(recipient);
        dialog.item(EdenParchment.text(tr("gift_note"), EdenParchment.INK_MID));
        TextField note = new TextField(240, "", text -> { });
        note.hint(tr("gift_note_hint").getString());
        note.maxLength(120);
        dialog.item(note);
        dialog.item(EdenParchment.text(tr("gift_cost", item.price()), EdenParchment.INK_DIM, 240));
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        boolean theme = themes();
        Runnable send = () -> {
            String to = recipient.text().strip();
            if (to.isEmpty()) {
                return;
            }
            ClientPlayNetworking.send(new HubPayloads.LookGift(theme, item.id(), to, note.text()));
            ClientPlayNetworking.send(HubPayloads.HomeDataRequest.INSTANCE);
            modal[0].close();
        };
        recipient.onSubmit(send);
        buttons.item(EdenParchment.fitButton(tr("gift_send"), 60, 14, send));
        buttons.item(EdenParchment.fitButton(Component.translatable("gui.eden_ld.hub.profile.close"), 50, 14,
                () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    /** One look: picture, name, and its state; click opens it. */
    private static final class Card extends Widget {
        private final HubScreen screen;
        private final HubPayloads.WardrobeData data;
        private final WardrobeItem item;

        Card(HubScreen screen, HubPayloads.WardrobeData data, WardrobeItem item) {
            super(cardWidth, CARD_H);
            this.screen = screen;
            this.data = data;
            this.item = item;
            sizing(Sizing.fixed(cardWidth), Sizing.fixed(CARD_H));
            tooltip(() -> List.of(name(item), state(item)));
        }

        @Override
        protected void rebuild() {
            boolean worn = item.state() == WardrobeItem.WORN;
            boolean locked = item.state() == WardrobeItem.FOR_SALE || item.state() == WardrobeItem.LOCKED;
            add(Shapes.rect(0, 0, width, height).fill(EdenParchment.ROW_BG));
            add(picture(item, width - 4, PICTURE_H, locked ? 0.35f : 0).at(2, 2));
            int frame = worn ? EdenParchment.GOOD : hovered ? EdenParchment.ACCENT : EdenParchment.HAIRLINE;
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(frame, worn ? 2 : 1)));
            // The state keeps what it needs (up to 40%); the name gets the rest of the line.
            Component stateText = state(item);
            int stateWidth = Math.min((int) (width * 0.4f), net.minecraft.client.Minecraft.getInstance().font.width(stateText));
            add(TextNode.of(name(item)).at(4, PICTURE_H + 6).maxWidth((int) (width - 8 - stateWidth - 4)).ellipsis(true)
                    .color(EdenParchment.INK).shadow(EdenParchment.TEXT_SHADOW));
            int stateColor = worn ? EdenParchment.GOOD : item.state() == WardrobeItem.FOR_SALE ? EdenParchment.ACCENT
                    : EdenParchment.INK_DIM;
            add(TextNode.of(stateText).at(width - 4, PICTURE_H + 6).align(TextNode.Align.RIGHT)
                    .maxWidth(stateWidth).ellipsis(true).color(stateColor).shadow(EdenParchment.TEXT_SHADOW));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            open(screen, data, item);
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }
}
