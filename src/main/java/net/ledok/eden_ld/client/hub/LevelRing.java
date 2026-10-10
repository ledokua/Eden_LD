package net.ledok.eden_ld.client.hub;

import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The Minestar activity level as a ring, like on the Minestar site: the level in the middle, the
 * ring filled as far as the player got towards the next level.
 */
final class LevelRing extends CanvasGroup {

    /** Minestar's purple. */
    static final int COLOR = 0xFF8B5CF6;

    private final float size;

    /**
     * @param progress 0..1 towards the next level
     * @param caption  a small "LVL" under the number (big rings)
     */
    LevelRing(float size, float thickness, int level, float progress, int textColor, int trackColor, boolean caption) {
        this.size = size;
        float center = size / 2;
        float radius = (size - thickness) / 2;
        add(Shapes.circle(center, center, radius).stroke(Stroke.of(trackColor, thickness)));
        float filled = Mth.clamp(progress, 0f, 1f);
        if (filled > 0.001f) {
            add(Shapes.arc(center, center, radius, (float) (-Math.PI / 2), (float) (Math.PI * 2 * filled))
                    .stroke(Stroke.of(COLOR, thickness).cap(Stroke.Cap.ROUND)));
        }
        float scale = size >= 40 ? 2f : 1f;
        // Light text (over a picture) gets a shadow; theme ink doesn't.
        int r = (textColor >> 16) & 0xFF, g = (textColor >> 8) & 0xFF, b = textColor & 0xFF;
        boolean light = r * 299 + g * 587 + b * 114 > 128_000;
        float captionHeight = caption ? 5f : 0f;
        TextNode number = TextNode.of(Component.literal(String.valueOf(level)))
                .color(textColor).shadow(light).scale(scale).align(TextNode.Align.CENTER);
        number.at(center, center - (8 * scale + captionHeight) / 2 + 1);
        add(number);
        if (caption) {
            TextNode label = TextNode.of(Component.translatable("gui.eden_ld.hub.profile.lvl"))
                    .color(textColor).scale(0.5f).align(TextNode.Align.CENTER);
            label.at(center, center + (8 * scale - captionHeight) / 2 + 2);
            add(label);
        }
    }

    @Override
    public Rect bounds() {
        return new Rect(0, 0, size, size);
    }
}
