package net.ledok.eden_ld.chat;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.util.regex.Pattern;

/**
 * Links in chat: clickable, underlined; GIF links (Tenor, Giphy, Klipy, .gif files — what
 * Discord's GIF picker posts) show as a short "[GIF]", FakeNitro sticker links as
 * "[Sticker: name]" — both open the chat's viewer.
 */
public final class ChatLinks {

    public static final Pattern URL = Pattern.compile("(https?://[^\\s<>\"]+)");
    private static final Pattern GIF = Pattern.compile(
            "https?://(?:[a-z0-9-]+\\.)?(?:tenor\\.com|giphy\\.com|klipy\\.com)/.*|https?://\\S+\\.gif(?:[?#]\\S*)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * A Discord sticker posted as its image (FakeNitro style): {@code [name](https://media.discordapp.net/stickers/<id>.png?...)}
     * or the bare link. Groups: 1 the link text, 2 / 3 the link.
     */
    public static final Pattern STICKER = Pattern.compile(
            "\\[([^\\]\\n]{0,64})]\\(<?(https://(?:media|cdn)\\.discordapp\\.(?:net|com)/stickers/\\d+\\.\\w+[^)\\s]*)>?\\)"
                    + "|(https://(?:media|cdn)\\.discordapp\\.(?:net|com)/stickers/\\d+\\.\\w+\\S*)");
    private static final Pattern STICKER_LINK = Pattern.compile(
            "https://(?:media|cdn)\\.discordapp\\.(?:net|com)/stickers/\\d+\\.\\w+\\S*");

    private ChatLinks() {
    }

    public static boolean hasLink(String text) {
        return URL.matcher(text).find();
    }

    public static boolean isGif(@org.jetbrains.annotations.Nullable String url) {
        if (url == null) {
            return false;
        }
        return GIF.matcher(url).matches();
    }

    public static boolean isSticker(@org.jetbrains.annotations.Nullable String url) {
        return url != null && STICKER_LINK.matcher(url).matches();
    }

    /** What the chat's viewer can show: GIFs and stickers. */
    public static boolean isViewable(@org.jetbrains.annotations.Nullable String url) {
        return isGif(url) || isSticker(url);
    }

    /**
     * A Discord sticker a player can keep: its id, the image type Eden fetches ({@code png} or
     * {@code gif}), and a name. Favorites store it as {@code s:<id>.<ext>:<name>}; it goes back
     * into chat as a FakeNitro link.
     */
    public record Sticker(String id, String ext, String name) {
        public static final String PREFIX = "s:";

        public String favoriteId() {
            return PREFIX + id + "." + ext + ":" + name;
        }

        public String url() {
            return "https://media.discordapp.net/stickers/" + id + "." + ext + "?size=160&name=" + name;
        }

        /** As typed in chat: {@code [name](link)} (Discord shows the image, Eden "[Sticker: name]"). */
        public String chatText() {
            return "[" + name + "](" + url() + ")";
        }
    }

    private static final Pattern STICKER_PARTS = Pattern.compile("/stickers/(\\d{5,25})\\.(\\w+)");
    private static final Pattern STICKER_FAVORITE = Pattern.compile("s:(\\d{5,25})\\.(png|gif):([A-Za-z0-9_\\-]{1,32})");

    /** The sticker a link shows, or null (not a sticker, or a Lottie one nothing here can draw). */
    @org.jetbrains.annotations.Nullable
    public static Sticker stickerOf(@org.jetbrains.annotations.Nullable String url) {
        if (!isSticker(url)) {
            return null;
        }
        java.util.regex.Matcher parts = STICKER_PARTS.matcher(url);
        if (!parts.find() || parts.group(2).equalsIgnoreCase("json")) {
            return null;
        }
        // Discord's media proxy converts on request: PNG for stills (WebP/APNG don't decode here), GIF stays.
        String ext = parts.group(2).equalsIgnoreCase("gif") ? "gif" : "png";
        return new Sticker(parts.group(1), ext, stickerSafeName(stickerName(url)));
    }

    /** A sticker favorite's id back to the sticker, or null when it isn't one. */
    @org.jetbrains.annotations.Nullable
    public static Sticker stickerFavorite(String favoriteId) {
        java.util.regex.Matcher matcher = STICKER_FAVORITE.matcher(favoriteId);
        return matcher.matches() ? new Sticker(matcher.group(1), matcher.group(2), matcher.group(3)) : null;
    }

    private static String stickerSafeName(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return clean.isEmpty() ? "sticker" : clean.length() > 32 ? clean.substring(0, 32) : clean;
    }

    /**
     * A sticker as chat text: "[Sticker: name]", clicked opens it in the viewer. The link carries
     * the name ({@code &name=}), so a right-click can save the sticker with it.
     */
    public static MutableComponent sticker(String name, String url) {
        TextColor color = LegacyText.color(EdenLD.CONFIG.chat.link_color);
        String shown = name == null || name.isBlank() ? stickerName(url) : name;
        String link = url.contains("name=") ? url
                : url + (url.contains("?") ? "&" : "?") + "name=" + stickerSafeName(shown);
        return Component.translatable("chat.eden_ld.sticker", shown).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("chat.eden_ld.sticker.open", shown))));
    }

    private static String stickerName(String url) {
        java.util.regex.Matcher matcher = Pattern.compile("[?&]name=([^&\\s]+)").matcher(url);
        return matcher.find() ? matcher.group(1) : "sticker";
    }

    /** The link as chat text: "[GIF]" for GIFs, else the address; either way clickable. */
    public static MutableComponent link(String url) {
        TextColor color = LegacyText.color(EdenLD.CONFIG.chat.link_color);
        boolean gif = isGif(url);
        Component label = gif ? Component.translatable("chat.eden_ld.gif") : Component.literal(url);
        return Component.empty().append(label).withStyle(style -> style.withUnderlined(true)
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, gif
                        ? Component.translatable("chat.eden_ld.gif.open", url)
                        : Component.translatable("chat.link.open"))));
    }
}
