package net.ledok.eden_ld.client.chat;

import com.mojang.blaze3d.platform.NativeImage;
import net.ledok.eden_ld.chat.ChatLinks;
import net.ledok.eden_ld.chat.emoji.GifStrip;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and decodes a chat GIF or sticker for the viewer and the hub's inline previews: a
 * Tenor/Giphy page link is resolved to its .gif through the page's og:image; image files (a
 * .gif, a Discord sticker) are fetched as they are. Blocking — call off the render thread.
 */
public final class MediaLoader {

    private static final long MAX_BYTES = 12L * 1024 * 1024;
    /** A Tenor/Giphy page is read only this far looking for its og:image (review finding 62). */
    private static final long MAX_PAGE_BYTES = 1024L * 1024;
    /**
     * A download's whole time, body included (review finding 62): the request's own timeout ends when the headers
     * arrive, so a host trickling the body could hold a worker forever.
     */
    private static final long TOTAL_TIMEOUT_SECONDS = 45;
    /** A still image's header may claim at most this much; it's then read subsampled toward {@code maxSide}. */
    private static final int MAX_STILL_SIDE = 16384;
    private static final long MAX_STILL_PIXELS = 64L * 1024 * 1024;
    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta[^>]+(?:property|name)=\"(?:og:image|twitter:image)\"[^>]+content=\"([^\"]+)\"");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private MediaLoader() {
    }

    /**
     * A download the caller can stop (review finding 62: closing the GIF viewer used to leave the download running):
     * {@link #cancel} aborts the request, or closes the body being read, which ends the read at once. Never blocks.
     */
    public static final class Ticket {
        private volatile boolean cancelled;
        @Nullable
        private volatile CompletableFuture<?> sending;
        @Nullable
        private volatile InputStream body;

        public void cancel() {
            cancelled = true;
            CompletableFuture<?> request = sending;
            if (request != null) {
                request.cancel(true);
            }
            closeQuietly(body);
        }

        public boolean cancelled() {
            return cancelled;
        }

        private void sending(CompletableFuture<?> request) {
            sending = request;
            if (cancelled) {
                request.cancel(true);
            }
        }

        private void body(InputStream stream) {
            body = stream;
            if (cancelled) {
                closeQuietly(stream);
            }
        }
    }

    /** The link's frames (up to {@code maxFrames}, shrunk to {@code maxSide}); a still image is one frame. */
    public static GifStrip.Frames load(String link, int maxFrames, int maxSide) throws IOException, InterruptedException {
        return load(link, maxFrames, maxSide, new Ticket());
    }

    /** {@link #load(String, int, int)}, stopped early by {@code ticket} (an {@link IOException} then). */
    public static GifStrip.Frames load(String link, int maxFrames, int maxSide, Ticket ticket)
            throws IOException, InterruptedException {
        byte[] bytes = download(resolve(link, ticket), MAX_BYTES, false, ticket);
        if (ticket.cancelled()) {
            throw new IOException("cancelled");
        }
        return decode(bytes, maxFrames, maxSide);
    }

    /** An image file's frames as they are, without looking it up as a page (reward icons). */
    public static GifStrip.Frames loadImage(String link, int maxFrames, int maxSide) throws IOException, InterruptedException {
        return decode(download(link, MAX_BYTES, false, new Ticket()), maxFrames, maxSide);
    }

    /**
     * A GIF's frames, or a still image (a sticker PNG; an animated PNG's first frame) as one frame. Sizes are checked
     * against the header before pixels are allocated (review finding 63), and a decode that still runs out of memory
     * fails like a bad file, so the viewer shows "failed" instead of hanging on "Loading…".
     */
    private static GifStrip.Frames decode(byte[] bytes, int maxFrames, int maxSide) throws IOException {
        try {
            if (bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
                return GifStrip.frames(bytes, maxFrames, maxSide);
            }
            BufferedImage image = readStill(bytes, maxSide);
            double scale = Math.min(1.0, (double) maxSide / Math.max(image.getWidth(), image.getHeight()));
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            BufferedImage fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = fitted.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
            g.dispose();
            return new GifStrip.Frames(List.of(fitted), List.of(1000));
        } catch (OutOfMemoryError e) {
            throw new IOException("image too large to decode");
        }
    }

    /**
     * A still image, its size read from the header first: refused past {@link #MAX_STILL_SIDE} / {@link
     * #MAX_STILL_PIXELS}, and read subsampled when it's far larger than {@code maxSide} (review finding 63).
     */
    private static BufferedImage readStill(byte[] bytes, int maxSide) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw new IOException("not an image this viewer can show (Lottie sticker?)");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_STILL_SIDE || height > MAX_STILL_SIDE
                        || width * height > MAX_STILL_PIXELS) {
                    throw new IOException("image too large (" + width + "x" + height + ")");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                // Read at least twice maxSide, so the smooth shrink after still has pixels to work with.
                long step = Math.max(1, Math.max(width, height) / Math.max(1, 2L * maxSide));
                if (step > 1) {
                    param.setSourceSubsampling((int) step, (int) step, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new IOException("not an image this viewer can show (Lottie sticker?)");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /** A page link (Tenor, Giphy, …) → its GIF, through the page's og:image; image files stay as they are. */
    private static String resolve(String link, Ticket ticket) throws IOException, InterruptedException {
        if (link.toLowerCase(Locale.ROOT).matches("https?://\\S+\\.gif(?:[?#]\\S*)?") || ChatLinks.isSticker(link)) {
            return link;
        }
        // Only the page's start: og:image sits in <head>, and Giphy pages can run past the cap.
        String page = new String(download(link, MAX_PAGE_BYTES, true, ticket), StandardCharsets.UTF_8);
        Matcher matcher = OG_IMAGE.matcher(page);
        while (matcher.find()) {
            String image = matcher.group(1).replace("&amp;", "&");
            if (image.toLowerCase(Locale.ROOT).contains(".gif")) {
                return image;
            }
        }
        throw new IOException("no GIF on the page");
    }

    /**
     * The body, streamed (review finding 62): refused past {@code max} bytes (a Content-Length over it, before
     * reading) — or with {@code prefix}, its first {@code max} bytes, the rest never read — cut off
     * {@value #TOTAL_TIMEOUT_SECONDS} s after the request started however slowly the body comes, and stopped by
     * {@code ticket}. Closing the body cancels the exchange.
     */
    private static byte[] download(String link, long max, boolean prefix, Ticket ticket)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TOTAL_TIMEOUT_SECONDS);
        CompletableFuture<HttpResponse<InputStream>> sending =
                HTTP.sendAsync(request(link), HttpResponse.BodyHandlers.ofInputStream());
        ticket.sending(sending);
        HttpResponse<InputStream> response;
        try {
            response = sending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            sending.cancel(true);
            throw new IOException("timed out");
        } catch (CancellationException e) {
            throw new IOException("cancelled");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException io ? io : new IOException(String.valueOf(cause), cause);
        } catch (InterruptedException e) {
            sending.cancel(true);
            throw e;
        }
        try (InputStream body = response.body()) {
            ticket.body(body);
            if (response.statusCode() != 200) {
                throw new IOException("answered " + response.statusCode());
            }
            if (!prefix && response.headers().firstValueAsLong("Content-Length").orElse(-1) > max) {
                throw new IOException("too large");
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new IOException("timed out");
            }
            // Closing the body at the deadline ends a read that's waiting (it reads as the end of the body).
            AtomicBoolean late = new AtomicBoolean();
            CompletableFuture<Void> watchdog = CompletableFuture.runAsync(() -> {
                late.set(true);
                closeQuietly(body);
            }, CompletableFuture.delayedExecutor(left, TimeUnit.NANOSECONDS));
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = body.read(buffer)) != -1) {
                    if (out.size() + (long) read > max) {
                        if (!prefix) {
                            throw new IOException("too large");
                        }
                        // Enough of the page: closing the body (below) drops the rest.
                        out.write(buffer, 0, (int) (max - out.size()));
                        break;
                    }
                    out.write(buffer, 0, read);
                }
                if (ticket.cancelled()) {
                    throw new IOException("cancelled");
                }
                if (late.get()) {
                    throw new IOException("timed out");
                }
                return out.toByteArray();
            } catch (IOException e) {
                throw ticket.cancelled() ? new IOException("cancelled") : late.get() ? new IOException("timed out") : e;
            } finally {
                watchdog.cancel(false);
            }
        }
    }

    private static void closeQuietly(@Nullable InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing only stops the download.
        }
    }

    private static HttpRequest request(String link) {
        return HttpRequest.newBuilder(URI.create(link)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Minecraft; Eden_LD GIF preview)").GET().build();
    }

    /** A decoded frame as a texture image (render thread). */
    public static NativeImage toNative(BufferedImage image) {
        NativeImage out = new NativeImage(image.getWidth(), image.getHeight(), true);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                // NativeImage stores ABGR.
                out.setPixelRGBA(x, y, (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16));
            }
        }
        return out;
    }

    /** The frame showing {@code elapsed} ms into a loop of these delays. */
    public static int frameAt(List<Integer> delays, int loop, long elapsed) {
        long t = elapsed % Math.max(1, loop);
        for (int i = 0; i < delays.size(); i++) {
            t -= delays.get(i);
            if (t < 0) {
                return i;
            }
        }
        return 0;
    }
}
