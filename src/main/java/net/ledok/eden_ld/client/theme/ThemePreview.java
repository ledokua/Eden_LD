package net.ledok.eden_ld.client.theme;

import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.ImageNode;
import net.ledok.vectorlib.client.canvas.NineSlicePanel;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A small picture of a hub theme (themes have no image of their own): its panel with the
 * background pattern, a row, a button and text in its colors. Drawn without applying the theme,
 * for the theme picker's buy dialog and the Moderation window.
 */
public final class ThemePreview {

    private ThemePreview() {
    }

    public static CanvasNode node(HubTheme theme, float w, float h) {
        CanvasGroup group = new CanvasGroup();
        group.pickable(false);
        HubTheme.Palette p = theme.palette();
        String folder = theme.textures().getPath().endsWith("/") ? theme.textures().getPath() : theme.textures().getPath() + "/";
        String ns = theme.textures().getNamespace();
        NineSlicePanel.Mode edges = theme.tileEdges() ? NineSlicePanel.Mode.TILE : NineSlicePanel.Mode.STRETCH;
        group.add(NineSlicePanel.of(ResourceLocation.fromNamespaceAndPath(ns, folder + "panel.png"), 48, 48)
                .insets(8).centerMode(NineSlicePanel.Mode.TILE).edgeMode(edges).tint(p.panelTint())
                .bounds(0, 0, w, h));
        HubTheme.Background background = theme.background();
        if (background != null) {
            HubTheme.Image image = background.image();
            int tint = (Math.round(background.opacity() * 255) << 24) | 0xFFFFFF;
            float tw = background.tileWidth();
            float th = background.tileHeight();
            for (float y = 6; y < h - 6; y += th) {
                float ch = Math.min(th, h - 6 - y);
                for (float x = 6; x < w - 6; x += tw) {
                    float cw = Math.min(tw, w - 6 - x);
                    group.add(new ImageNode(image.texture()).textureSize(image.width(), image.height())
                            .region(0, 0, image.width() * cw / tw, image.height() * ch / th)
                            .bounds(x, y, cw, ch).tint(tint));
                }
            }
        }
        float pad = 8;
        float inner = w - 2 * pad;
        // A title, a row with dim text, a button and an accent line.
        group.add(TextNode.of(Component.literal("Aa")).at(pad, pad).color(p.ink()).shadow(theme.textShadow()));
        float rowY = pad + 11;
        float rowH = Math.min(14, (h - rowY - pad - 16));
        if (rowH > 6) {
            HubTheme.Rows rows = theme.rows();
            if (rows != null) {
                group.add(NineSlicePanel.of(rows.image().texture(), rows.image().width(), rows.image().height())
                        .insets(rows.border()).centerMode(NineSlicePanel.Mode.TILE).edgeMode(edges)
                        .bounds(pad, rowY, inner, rowH));
            } else {
                group.add(Shapes.rect(pad + 0.5f, rowY + 0.5f, inner - 1, rowH - 1).fill(p.rowBg()).stroke(p.hairline(), 1));
            }
            group.add(TextNode.of(Component.literal("···")).at(pad + 4, rowY + (rowH - 8) / 2 + 1)
                    .color(p.inkDim()).shadow(theme.textShadow()));
        }
        float buttonW = Math.min(36, inner / 2);
        float buttonY = h - pad - 12;
        group.add(NineSlicePanel.of(ResourceLocation.fromNamespaceAndPath(ns, folder + "button_normal.png"), 32, 16)
                .insets(4).bounds(w - pad - buttonW, buttonY, buttonW, 12));
        group.add(Shapes.rect(pad, buttonY + 5, Math.max(4, inner - buttonW - 6), 2).fill(p.accent()));
        return group;
    }
}
