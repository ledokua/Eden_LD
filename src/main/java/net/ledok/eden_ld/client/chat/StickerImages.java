package net.ledok.eden_ld.client.chat;

import com.mojang.blaze3d.platform.NativeImage;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.chat.ChatLinks;
import net.ledok.eden_ld.chat.emoji.GifStrip;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sticker images for the pickers' Stickers tab and the hub's chat (shown under the message).
 * Each loads once, on first sight, and stays while recently used (animated ones play); the
 * least recently used go first past {@link #MAX_KEPT}. Cleared on disconnect.
 */
public final class StickerImages {

    private static final int MAX_KEPT = 32;
    private static final int MAX_FRAMES = 60;
    /** Decoded size: plenty for the hub's 64 px preview and the pickers' thumbnails. */
    private static final int MAX_SIDE = 96;

    public enum Status { LOADING, READY, FAILED }

    /** One sticker's image: frames and delays once loaded, and the texture showing the current frame. */
    public static final class Image {
        private Status status = Status.LOADING;
        private List<NativeImage> frames = List.of();
        private List<Integer> delays = List.of();
        private int loop;
        private long started;
        private int shown = -1;
        @Nullable
        private DynamicTexture texture;
        private ResourceLocation location;
        private final List<Runnable> waiting = new ArrayList<>();

        public Status status() {
            return status;
        }

        /** The decoded frames (owned here; copy before keeping) and their delays in ms. */
        public List<NativeImage> frames() {
            return frames;
        }

        public List<Integer> delays() {
            return delays;
        }

        public int width() {
            return frames.isEmpty() ? 0 : frames.get(0).getWidth();
        }

        public int height() {
            return frames.isEmpty() ? 0 : frames.get(0).getHeight();
        }

        private void free() {
            frames.forEach(NativeImage::close);
            frames = List.of();
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(location);
                texture = null;
            }
        }
    }

    /** By sticker id + type; access order, so the eldest is the least recently used. */
    private static final Map<String, Image> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static int generation;

    private StickerImages() {
    }

    /** The sticker's image, loading it on first ask ({@code onReady} runs on the render thread once it's done). */
    public static Image get(ChatLinks.Sticker sticker, @Nullable Runnable onReady) {
        String key = sticker.id() + "." + sticker.ext();
        Image image = CACHE.get(key);
        if (image == null) {
            image = new Image();
            CACHE.put(key, image);
            load(image, sticker);
            evict();
        }
        if (onReady != null && image.status == Status.LOADING) {
            image.waiting.add(onReady);
        }
        return image;
    }

    /** The texture to draw now (animated stickers advance to the current frame), or null until loaded. */
    @Nullable
    public static ResourceLocation texture(Image image) {
        if (image.status != Status.READY || image.texture == null) {
            return null;
        }
        int frame = image.frames.size() == 1 ? 0 : MediaLoader.frameAt(image.delays, image.loop, Util.getMillis() - image.started);
        if (frame != image.shown) {
            NativeImage pixels = image.texture.getPixels();
            if (pixels != null) {
                pixels.copyFrom(image.frames.get(frame));
                image.texture.upload();
            }
            image.shown = frame;
        }
        return image.location;
    }

    public static void clear() {
        generation++;
        CACHE.values().forEach(Image::free);
        CACHE.clear();
    }

    private static void evict() {
        Iterator<Image> it = CACHE.values().iterator();
        while (CACHE.size() > MAX_KEPT && it.hasNext()) {
            Image eldest = it.next();
            eldest.free();
            it.remove();
        }
    }

    private static void load(Image image, ChatLinks.Sticker sticker) {
        int ticket = generation;
        image.location = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "sticker/" + sticker.id() + "_" + sticker.ext());
        Util.backgroundExecutor().execute(() -> {
            GifStrip.Frames decoded = null;
            try {
                decoded = MediaLoader.load(sticker.url(), MAX_FRAMES, MAX_SIDE);
            } catch (IOException | RuntimeException e) {
                EdenLD.LOGGER.info("[Sticker] Can't show {}: {}", sticker.name(), e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            GifStrip.Frames result = decoded;
            Minecraft.getInstance().execute(() -> {
                if (ticket != generation || !CACHE.containsValue(image)) {
                    return;
                }
                ready(image, result);
                List<Runnable> waiting = List.copyOf(image.waiting);
                image.waiting.clear();
                waiting.forEach(Runnable::run);
            });
        });
    }

    private static void ready(Image image, @Nullable GifStrip.Frames decoded) {
        if (decoded == null || decoded.images().isEmpty()) {
            image.status = Status.FAILED;
            return;
        }
        List<NativeImage> frames = new ArrayList<>();
        for (BufferedImage frame : decoded.images()) {
            frames.add(MediaLoader.toNative(frame));
        }
        image.frames = frames;
        image.delays = decoded.delays();
        image.loop = Math.max(1, image.delays.stream().mapToInt(Integer::intValue).sum());
        image.started = Util.getMillis();
        NativeImage first = frames.get(0);
        image.texture = new DynamicTexture(first.getWidth(), first.getHeight(), true);
        Minecraft.getInstance().getTextureManager().register(image.location, image.texture);
        image.status = Status.READY;
    }
}
