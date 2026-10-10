package net.ledok.eden_ld.client.hub;

import com.mojang.blaze3d.platform.NativeImage;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.chat.emoji.GifStrip;
import net.ledok.eden_ld.client.chat.MediaLoader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small still images from the web for the hub (Minestar reward icons): each link loads once, on
 * first sight, and stays while recently used; the least recently used go past {@link #MAX_KEPT}.
 * Only https links; the first frame of a GIF. Cleared on disconnect.
 */
public final class RemoteImages {

    private static final int MAX_KEPT = 64;
    private static final int MAX_SIDE = 64;

    enum Status { LOADING, READY, FAILED }

    static final class Image {
        private Status status = Status.LOADING;
        private int width;
        private int height;
        @Nullable
        private DynamicTexture texture;
        private ResourceLocation location;
        private final List<Runnable> waiting = new ArrayList<>();

        Status status() {
            return status;
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        /** The texture once loaded, else null. */
        @Nullable
        ResourceLocation texture() {
            return status == Status.READY ? location : null;
        }

        private void free() {
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(location);
                texture = null;
            }
        }
    }

    /** By link; access order, so the eldest is the least recently used. */
    private static final Map<String, Image> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static int generation;
    private static int next;

    private RemoteImages() {
    }

    /** The link's image, loading it on first ask ({@code onReady} runs on the render thread once done). */
    static Image get(String url, @Nullable Runnable onReady) {
        Image image = CACHE.get(url);
        if (image == null) {
            image = new Image();
            CACHE.put(url, image);
            load(image, url);
            evict();
        }
        if (onReady != null && image.status == Status.LOADING) {
            image.waiting.add(onReady);
        }
        return image;
    }

    public static void clear() {
        generation++;
        CACHE.values().forEach(Image::free);
        CACHE.clear();
    }

    private static void evict() {
        Iterator<Image> it = CACHE.values().iterator();
        while (CACHE.size() > MAX_KEPT && it.hasNext()) {
            it.next().free();
            it.remove();
        }
    }

    private static void load(Image image, String url) {
        if (!url.startsWith("https://")) {
            image.status = Status.FAILED;
            return;
        }
        int ticket = generation;
        image.location = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "remote/" + next++);
        Util.backgroundExecutor().execute(() -> {
            GifStrip.Frames decoded = null;
            try {
                decoded = MediaLoader.loadImage(url, 1, MAX_SIDE);
            } catch (IOException | RuntimeException e) {
                EdenLD.LOGGER.info("[Profile] Can't show {}: {}", url, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            GifStrip.Frames result = decoded;
            Minecraft.getInstance().execute(() -> {
                if (ticket != generation || !CACHE.containsValue(image)) {
                    return;
                }
                if (result == null || result.images().isEmpty()) {
                    image.status = Status.FAILED;
                } else {
                    NativeImage first = MediaLoader.toNative(result.images().get(0));
                    image.width = first.getWidth();
                    image.height = first.getHeight();
                    image.texture = new DynamicTexture(first);
                    Minecraft.getInstance().getTextureManager().register(image.location, image.texture);
                    image.status = Status.READY;
                }
                List<Runnable> waiting = List.copyOf(image.waiting);
                image.waiting.clear();
                waiting.forEach(Runnable::run);
            });
        });
    }
}
