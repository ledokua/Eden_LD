package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.chat.ChatLinks;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.ledok.eden_ld.client.chat.EmojiFont;
import net.ledok.eden_ld.client.chat.StickerImages;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasRenderContext;
import net.ledok.vectorlib.client.canvas.ImageNode;
import net.ledok.vectorlib.client.canvas.Interactive;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.ShapeNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.widget.Toast;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * A sticker drawn as its image in the hub, in a square box (animated ones play): under a party
 * or guild chat message, and on the emoji picker's Stickers tab. Left click runs
 * {@code onClick}; right-click saves or drops the sticker (its own list and limit; players with
 * the emoji picker).
 */
final class StickerNode extends CanvasGroup implements Interactive {

    private final ChatLinks.Sticker sticker;
    private final float side;
    private final Runnable onClick;
    private final Runnable onSavedChanged;
    private final StickerImages.Image image;
    private final ShapeNode back;
    private final ImageNode picture;
    private final TextNode loading;
    private boolean hovered;

    StickerNode(ChatLinks.Sticker sticker, float side, Runnable onClick, Runnable onSavedChanged, Component clickHint) {
        this.sticker = sticker;
        this.side = side;
        this.onClick = onClick;
        this.onSavedChanged = onSavedChanged;
        this.image = StickerImages.get(sticker, null);
        back = add(Shapes.rect(0, 0, side, side));
        picture = add(new ImageNode(ResourceLocation.withDefaultNamespace("missingno")));
        picture.visible(false);
        loading = add(EdenParchment.text(Component.literal("…"), EdenParchment.INK_DIM));
        loading.at(side / 2 - 3, side / 2 - 5);
        tooltip(() -> ClientChatChannel.emojiPicker()
                ? List.of(Component.literal(sticker.name()), clickHint.copy().withStyle(ChatFormatting.GRAY),
                        Component.translatable(EmojiFont.isSaved(sticker) ? "chat.eden_ld.sticker.drop"
                                : "chat.eden_ld.sticker.save").withStyle(ChatFormatting.GRAY))
                : List.of(Component.literal(sticker.name()), clickHint.copy().withStyle(ChatFormatting.GRAY)));
    }

    @Override
    public Rect bounds() {
        return new Rect(0, 0, side, side);
    }

    @Override
    protected void draw(CanvasRenderContext ctx) {
        int accent = EdenParchment.ACCENT & 0xFFFFFF;
        back.fill(hovered ? 0x40000000 | accent : 0);
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
            picture.visible(false);
            loading.visible(image.status() == StickerImages.Status.LOADING);
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
                if (Minecraft.getInstance().screen instanceof HubScreen screen) {
                    Toast.text(screen.canvas(), Component.translatable("chat.eden_ld.sticker.full",
                            EmojiFont.stickersLimit()).withStyle(ChatFormatting.RED), EdenParchment.DANGER);
                }
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
