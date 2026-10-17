package net.ledok.eden_ld.client.chat;

import com.mojang.blaze3d.platform.NativeImage;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.chat.emoji.GifStrip;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The chat's GIF viewer: clicking a [GIF] (or a [Sticker]) link opens a small animated preview over the chat,
 * with "open in browser" and close. The player's own game fetches the GIF, only on that click
 * (a Tenor/Giphy page link is resolved to its .gif through the page's og:image); Klipy and other
 * sites that refuse it show a note and keep the browser button.
 */
public final class GifViewer {

    /** Frames kept at most (longer GIFs stop there) and their largest side in pixels. */
    private static final int MAX_FRAMES = 80;
    private static final int MAX_SIDE = 320;
    /** The preview's largest side on screen (GUI pixels). */
    private static final int VIEW_SIDE = 160;
    private static final int TITLE = 12;
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "gif_viewer");

    private enum Status { LOADING, READY, FAILED }

    @Nullable
    private static String url;
    /** A sticker (still image, or GIF) rather than a GIF link: only the title differs. */
    private static boolean sticker;
    private static Status status = Status.LOADING;
    private static List<NativeImage> frames = List.of();
    private static List<Integer> delays = List.of();
    private static int loop;
    private static long started;
    private static int shownFrame = -1;
    @Nullable
    private static DynamicTexture texture;
    /** Bumped on every open/close: a download finishing late for an older GIF is dropped. */
    private static int generation;

    private GifViewer() {
    }

    public static boolean isOpen() {
        return url != null;
    }

    /** Opens the viewer for a GIF link (replacing any open one) and starts loading it. */
    public static void open(String link) {
        close();
        url = link;
        sticker = net.ledok.eden_ld.chat.ChatLinks.isSticker(link);
        status = Status.LOADING;
        int ticket = ++generation;
        Util.backgroundExecutor().execute(() -> {
            GifStrip.Frames decoded = null;
            try {
                decoded = MediaLoader.load(link, MAX_FRAMES, MAX_SIDE);
            } catch (IOException | RuntimeException e) {
                EdenLD.LOGGER.info("[GIF] Can't preview {}: {}", link, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            GifStrip.Frames result = decoded;
            Minecraft.getInstance().execute(() -> {
                if (ticket == generation) {
                    ready(result);
                }
            });
        });
    }

    /** Closes the viewer and frees its images. */
    public static void close() {
        generation++;
        url = null;
        frames.forEach(NativeImage::close);
        frames = List.of();
        delays = List.of();
        shownFrame = -1;
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
            texture = null;
        }
    }

    // ---------------------------------------------------------------- render thread

    private static void ready(@Nullable GifStrip.Frames decoded) {
        if (decoded == null || decoded.images().isEmpty()) {
            status = Status.FAILED;
            return;
        }
        List<NativeImage> images = new ArrayList<>();
        for (BufferedImage frame : decoded.images()) {
            images.add(MediaLoader.toNative(frame));
        }
        frames = images;
        delays = decoded.delays();
        loop = Math.max(1, delays.stream().mapToInt(Integer::intValue).sum());
        started = Util.getMillis();
        NativeImage first = images.get(0);
        texture = new DynamicTexture(first.getWidth(), first.getHeight(), true);
        Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        status = Status.READY;
    }

    /** Panel {x0, y0, x1, y1}: above the chat input on the right. */
    private static int[] panel(int screenWidth, int screenHeight) {
        int width = VIEW_SIDE;
        int height = VIEW_SIDE;
        if (status == Status.READY) {
            NativeImage first = frames.get(0);
            double scale = (double) VIEW_SIDE / Math.max(first.getWidth(), first.getHeight());
            width = Math.max(60, (int) Math.round(first.getWidth() * scale));
            height = (int) Math.round(first.getHeight() * scale);
        } else {
            height = 40;
        }
        int x1 = screenWidth - 4;
        int y1 = screenHeight - 18;
        return new int[]{x1 - width - 4, y1 - height - 4 - TITLE, x1, y1};
    }

    public static void render(GuiGraphics graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        if (url == null) {
            return;
        }
        int[] p = panel(screenWidth, screenHeight);
        graphics.fill(p[0], p[1], p[2], p[3], 0xE0101010);
        graphics.renderOutline(p[0], p[1], p[2] - p[0], p[3] - p[1], 0xFF505050);
        graphics.drawString(font, Component.translatable(sticker ? "chat.eden_ld.sticker.title" : "chat.eden_ld.gif"),
                p[0] + 3, p[1] + 2, 0xFF7878FF, false);
        int openX = p[2] - 34;
        int closeX = p[2] - 12;
        boolean overOpen = over(mouseX, mouseY, openX, p[1], closeX - 2, p[1] + TITLE);
        boolean overClose = over(mouseX, mouseY, closeX, p[1], p[2], p[1] + TITLE);
        net.minecraft.resources.ResourceLocation open = net.ledok.eden_ld.client.hub.HubIcons.texture("open_external");
        if (open != null) {
            // Dimmed until hovered, like the text symbol.
            float shade = overOpen ? 1.0F : 0.7F;
            graphics.setColor(shade, shade, shade, 1.0F);
            graphics.blit(open, openX + 6, p[1] + 1, 8, 8, 0, 0, 16, 16, 16, 16);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        } else {
            graphics.drawString(font, "↗", openX + 6, p[1] + 2, overOpen ? 0xFFFFFFFF : 0xFFA0A0A0, false);
        }
        graphics.drawString(font, "✕", closeX + 2, p[1] + 2, overClose ? 0xFFFF6060 : 0xFFA0A0A0, false);
        int top = p[1] + TITLE;
        if (status == Status.READY && texture != null) {
            int frame = MediaLoader.frameAt(delays, loop, Util.getMillis() - started);
            if (frame != shownFrame) {
                texture.getPixels().copyFrom(frames.get(frame));
                texture.upload();
                shownFrame = frame;
            }
            NativeImage image = frames.get(0);
            int width = p[2] - p[0] - 4;
            int height = p[3] - top - 2;
            graphics.blit(TEXTURE, p[0] + 2, top, width, height, 0, 0, image.getWidth(), image.getHeight(),
                    image.getWidth(), image.getHeight());
        } else {
            Component note = status == Status.LOADING ? Component.translatable("chat.eden_ld.gif.loading")
                    : Component.translatable("chat.eden_ld.gif.failed").withStyle(ChatFormatting.GRAY);
            graphics.drawString(font, note, p[0] + 4, top + 6, 0xFFA0A0A0, false);
        }
        if (overOpen) {
            graphics.renderTooltip(font, Component.translatable("chat.eden_ld.gif.browser"), mouseX, mouseY);
        }
    }

    /** A click while open: the title buttons act; any click inside is kept; outside closes it. */
    public static boolean click(Screen screen, double mouseX, double mouseY) {
        if (url == null) {
            return false;
        }
        int[] p = panel(screen.width, screen.height);
        if (over(mouseX, mouseY, p[2] - 34, p[1], p[2] - 14, p[1] + TITLE)) {
            String link = url;
            close();
            ConfirmLinkScreen.confirmLinkNow(screen, link);
            return true;
        }
        if (over(mouseX, mouseY, p[2] - 12, p[1], p[2], p[1] + TITLE)) {
            close();
            return true;
        }
        if (over(mouseX, mouseY, p[0], p[1], p[2], p[3])) {
            return true;
        }
        close();
        return false;
    }

    private static boolean over(double mouseX, double mouseY, int x0, int y0, int x1, int y1) {
        return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
    }
}
