package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasRenderContext;
import net.ledok.vectorlib.client.canvas.ImageNode;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * A Minestar reward on a profile: its image in a frame of its rarity's color (a star until the
 * image is there, or when it can't be shown); hover for the title, description and rarity.
 */
final class RewardIcon extends CanvasGroup {

    /** Common, rare, epic, legendary. */
    static final int[] RARITY_COLORS = {0xFFB0B0B8, 0xFF4DA3FF, 0xFFB45CFF, 0xFFFFB32E};
    private static final String[] RARITY_KEYS = {"common", "rare", "epic", "legendary"};

    private final float size;
    private final RemoteImages.Image image;
    private final ImageNode picture;
    private final TextNode star;

    RewardIcon(HubPayloads.ProfileReward reward, float size) {
        this.size = size;
        int rarity = Math.floorMod(reward.rarity(), RARITY_COLORS.length);
        int color = RARITY_COLORS[rarity];
        add(Shapes.roundRect(0, 0, size, size, 2).fill(0x70000000).stroke(color, 1));
        picture = add(new ImageNode(ResourceLocation.withDefaultNamespace("missingno")));
        picture.visible(false);
        star = add(TextNode.of(Component.literal("★")).color(color).align(TextNode.Align.CENTER));
        star.at(size / 2, size / 2 - 4);
        image = reward.imageUrl().isEmpty() ? null : RemoteImages.get(reward.imageUrl(), null);

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(reward.title()).withColor(color & 0xFFFFFF));
        lines.addAll(wrap(reward.description(), 40));
        lines.add(Component.translatable("gui.eden_ld.hub.profile.rarity." + RARITY_KEYS[rarity])
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip(lines.toArray(Component[]::new));
    }

    /** The text in lines of about {@code width} characters (tooltips don't wrap on their own). */
    private static List<Component> wrap(String text, int width) {
        List<Component> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(Component.literal(line.toString()).withStyle(ChatFormatting.GRAY));
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(Component.literal(line.toString()).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    @Override
    public Rect bounds() {
        return new Rect(0, 0, size, size);
    }

    @Override
    protected void draw(CanvasRenderContext ctx) {
        ResourceLocation texture = image == null ? null : image.texture();
        if (texture != null && image.width() > 0) {
            float inner = size - 4;
            float fit = inner / Math.max(image.width(), image.height());
            float w = image.width() * fit;
            float h = image.height() * fit;
            picture.texture(texture).textureSize(image.width(), image.height())
                    .region(0, 0, image.width(), image.height())
                    .bounds((size - w) / 2, (size - h) / 2, w, h);
            picture.visible(true);
            star.visible(false);
        } else {
            picture.visible(false);
            star.visible(true);
        }
        super.draw(ctx);
    }
}
