package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.chat.ChatLinks;
import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.ledok.eden_ld.client.chat.EmojiFont;
import net.ledok.eden_ld.client.chat.StickerImages;
import net.ledok.vectorlib.client.canvas.BoxStyle;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasRenderContext;
import net.ledok.vectorlib.client.canvas.ImageNode;
import net.ledok.vectorlib.client.canvas.Interactive;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.RoundedBox;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.Consumer;

/**
 * A sticker drawn as its image in a square box (animated ones play): under a hub party or guild
 * chat message, and on the emoji picker's Stickers tab. "…" stands in while it has no image
 * (loading, or failed). Left click runs {@code onClick}; right-click saves or drops the sticker
 * (its own list and limit; players with the emoji picker), and a full list goes to {@code notice}.
 */
public final class StickerNode extends CanvasGroup implements Interactive {

    private final ChatLinks.Sticker sticker;
    private final Rect bounds;
    private final float side;
    private final EmojiPicker.Look look;
    private final Runnable onClick;
    private final Runnable onSavedChanged;
    private final Consumer<Component> notice;
    private final StickerImages.Image image;
    private final RoundedBox back;
    private final ImageNode picture;
    private final TextNode loading;
    private boolean hovered;

    public StickerNode(ChatLinks.Sticker sticker, float side, EmojiPicker.Look look, Runnable onClick,
                       Runnable onSavedChanged, Component clickHint, Consumer<Component> notice) {
        this.sticker = sticker;
        this.bounds = new Rect(0, 0, side, side);
        this.side = side;
        this.look = look;
        this.onClick = onClick;
        this.onSavedChanged = onSavedChanged;
        this.notice = notice;
        this.image = StickerImages.get(sticker, null);
        back = add(RoundedBox.of(side, side));
        picture = add(new ImageNode(ResourceLocation.withDefaultNamespace("missingno")));
        picture.visible(false);
        loading = add(TextNode.of(Component.literal("…")).color(look.inkDim()).shadow(look.textShadow()));
        loading.at(side / 2 - 3, side / 2 - 5);
        tooltip(() -> ClientChatChannel.emojiPicker()
                ? List.of(Component.literal(sticker.name()), clickHint.copy().withStyle(ChatFormatting.GRAY),
                        Component.translatable(EmojiFont.isSaved(sticker) ? "chat.eden_ld.sticker.drop"
                                : "chat.eden_ld.sticker.save").withStyle(ChatFormatting.GRAY))
                : List.of(Component.literal(sticker.name()), clickHint.copy().withStyle(ChatFormatting.GRAY)));
    }

    @Override
    public Rect bounds() {
        return bounds;
    }

    @Override
    protected void draw(CanvasRenderContext ctx) {
        // The look's prebuilt style: switched when the hover changes, nothing allocated per frame.
        BoxStyle style = hovered ? look.cellHover() : BoxStyle.NONE;
        if (back.style() != style) {
            back.style(style);
        }
        ResourceLocation texture = StickerImages.texture(image);
        if (texture != null && image.width() > 0) {
            float fit = (side - 2) / Math.max(image.width(), image.height());
            float w = image.width() * fit;
            float h = image.height() * fit;
            picture.texture(texture).textureSize(image.width(), image.height())
                    .region(0, 0, image.width(), image.height())
                    .bounds((side - w) / 2, (side - h) / 2, w, h);
            picture.visible(true);
            loading.visible(false);
        } else {
            // Loading, or it failed: "…" either way, as the chat's hand-drawn picker shows it.
            picture.visible(false);
            loading.visible(true);
        }
        super.draw(ctx);
    }

    @Override
    public boolean onMouseDown(float x, float y, int button) {
        if (button == 0) {
            onClick.run();
            return true;
        }
        if (button == 1 && ClientChatChannel.emojiPicker()) {
            if (!EmojiFont.toggleSticker(sticker)) {
                notice.accept(Component.translatable("chat.eden_ld.sticker.full", EmojiFont.stickersLimit())
                        .withStyle(ChatFormatting.RED));
            } else {
                onSavedChanged.run();
            }
            return true;
        }
        return false;
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
