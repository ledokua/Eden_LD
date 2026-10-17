package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.chat.ChatLinks;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.ledok.eden_ld.client.chat.EmojiFont;
import net.ledok.eden_ld.client.chat.ui.StickerNode;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.Interactive;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A chat line in the hub (party and guild chat) that works like the game's chat: hover shows
 * what the text carries — an item's tooltip for {@code :item:}, a spoiler's text, an emoji's
 * name — and clicks act (links, the GIF viewer, {@code :pos:} copying). Right-click on an emoji
 * stars or unstars it, as in chat.
 */
final class ChatText extends CanvasGroup implements Interactive {

    /** TextNode's line pitch: font line plus its default spacing. */
    private static final int LINE_SPACING = 1;

    private final Component text;
    private final int wrap;
    private final TextNode node;
    @Nullable
    private Style hovered;

    /** Stickers shown under a message: their size, and at most this many per message. */
    private static final float STICKER_SIDE = 64;
    private static final int MAX_STICKERS = 3;

    /**
     * A whole hub chat message: the line, then the stickers it links as their images (only
     * stickers — GIFs stay a "[GIF]" to click). A sticker opens the viewer on click.
     */
    static net.ledok.vectorlib.client.canvas.CanvasNode message(Component line, int wrap) {
        List<ChatLinks.Sticker> stickers = stickers(line);
        ChatText text = new ChatText(line, EdenParchment.INK, wrap);
        if (stickers.isEmpty()) {
            return text;
        }
        net.ledok.vectorlib.client.canvas.layout.Flex column = net.ledok.vectorlib.client.canvas.layout.Flex.column().gap(2);
        column.item(text);
        net.ledok.vectorlib.client.canvas.layout.Flex row = net.ledok.vectorlib.client.canvas.layout.Flex.row().gap(3);
        for (ChatLinks.Sticker sticker : stickers) {
            row.item(new StickerNode(sticker, STICKER_SIDE, HubPicker.look(),
                    () -> net.ledok.eden_ld.client.chat.GifViewer.open(sticker.url()), () -> {
                    }, Component.translatable("chat.eden_ld.sticker.enlarge"), HubPicker::notice));
        }
        column.item(row);
        return column;
    }

    /** The stickers a message links ([Sticker: name]), in order, without repeats. */
    private static List<ChatLinks.Sticker> stickers(Component line) {
        List<ChatLinks.Sticker> found = new java.util.ArrayList<>();
        line.visit((style, part) -> {
            net.minecraft.network.chat.ClickEvent click = style.getClickEvent();
            if (click != null && click.getAction() == net.minecraft.network.chat.ClickEvent.Action.OPEN_URL) {
                ChatLinks.Sticker sticker = ChatLinks.stickerOf(click.getValue());
                if (sticker != null && !found.contains(sticker) && found.size() < MAX_STICKERS) {
                    found.add(sticker);
                }
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return found;
    }

    ChatText(Component text, int color, int wrap) {
        this.text = text;
        this.wrap = wrap;
        this.node = add(EdenParchment.text(text, color, wrap));
        tooltip(this::hoverLines);
    }

    @Override
    public Rect bounds() {
        return node.bounds();
    }

    /** The style of the character under a point (local units), or null between lines/after the end. */
    @Nullable
    private Style styleAt(float x, float y) {
        Font font = Minecraft.getInstance().font;
        if (x < 0 || y < 0) {
            return null;
        }
        List<FormattedText> lines = font.getSplitter().splitLines(text, wrap, Style.EMPTY);
        int index = (int) (y / (font.lineHeight + LINE_SPACING));
        if (index >= lines.size()) {
            return null;
        }
        return font.getSplitter().componentStyleAtWidth(lines.get(index), (int) x);
    }

    @Nullable
    private List<Component> hoverLines() {
        Style style = hovered;
        HoverEvent hover = style == null ? null : style.getHoverEvent();
        if (hover == null) {
            return null;
        }
        Component shown = hover.getValue(HoverEvent.Action.SHOW_TEXT);
        if (shown != null) {
            return List.of(shown);
        }
        HoverEvent.ItemStackInfo item = hover.getValue(HoverEvent.Action.SHOW_ITEM);
        if (item != null) {
            return Screen.getTooltipFromItem(Minecraft.getInstance(), item.getItemStack());
        }
        HoverEvent.EntityTooltipInfo entity = hover.getValue(HoverEvent.Action.SHOW_ENTITY);
        return entity != null ? entity.getTooltipLines() : null;
    }

    @Override
    public boolean onMouseMove(float x, float y) {
        hovered = styleAt(x, y);
        return false;
    }

    @Override
    public void onHoverChanged(boolean hovered) {
        if (!hovered) {
            this.hovered = null;
        }
    }

    @Override
    public boolean onMouseDown(float x, float y, int button) {
        Style style = styleAt(x, y);
        if (style == null) {
            return false;
        }
        if (button == 0 && style.getClickEvent() != null
                && style.getClickEvent().getAction() == net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND
                && style.getClickEvent().getValue().startsWith("@")) {
            // A sender's name: mention them in this chat's field.
            ChatMentions.mention(style.getClickEvent().getValue());
            return true;
        }
        if (button == 0 && style.getClickEvent() != null
                && Minecraft.getInstance().screen instanceof HubScreen screen) {
            // As chat does: links (and the GIF viewer), copy, commands.
            return screen.handleComponentClicked(style);
        }
        net.minecraft.network.chat.ClickEvent click = style.getClickEvent();
        ChatLinks.Sticker sticker = click != null && click.getAction() == net.minecraft.network.chat.ClickEvent.Action.OPEN_URL
                ? ChatLinks.stickerOf(click.getValue()) : null;
        if (button == 1 && sticker != null && ClientChatChannel.emojiPicker()) {
            // Right-click a [Sticker]: save or drop it, as in chat.
            if (!EmojiFont.toggleSticker(sticker)) {
                HubPicker.notice(Component.translatable("chat.eden_ld.sticker.full", EmojiFont.stickersLimit())
                        .withStyle(ChatFormatting.RED));
            }
            return true;
        }
        String insertion = style.getInsertion();
        if (button == 1 && insertion != null && insertion.codePointCount(0, insertion.length()) == 1
                && ClientChatChannel.emojiPicker()) {
            EmojiFont.Entry entry = EmojiFont.byCodepoint(insertion.codePointAt(0));
            if (entry != null) {
                if (!EmojiFont.toggleFavorite(entry.id())) {
                    HubPicker.notice(Component.translatable("chat.eden_ld.emoji.favorites_full",
                            EmojiFont.favoritesLimit()).withStyle(ChatFormatting.RED));
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public Cursor cursor() {
        return hovered != null && hovered.getClickEvent() != null ? Cursor.HAND : Cursor.DEFAULT;
    }
}
