package net.ledok.eden_ld.client.chat;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.chat.emoji.EmojiRegistry;
import net.ledok.eden_ld.chat.emoji.UnicodeEmoji;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The {@code eden_ld:emoji} font, built at runtime from the server's Discord emoji images — no
 * resource pack. Each emoji is one private-use character, drawn 9 px tall on the text baseline
 * in its own colors. The client also keeps the name list for the chat screen's emoji picker.
 * <p>
 * The server's own emoji images are cached on disk by Discord emoji id ({@code <game
 * dir>/eden_ld/emoji_cache/}; an id never changes its image): the server sends only the list, and
 * the client asks for the images it doesn't have yet — after the first join, nothing is
 * downloaded until emojis change. Standard emojis ship with the mod (sheets, loaded on first use).
 */
public final class EmojiFont {

    /** On-screen glyph height; the text line is 9 px. */
    private static final float HEIGHT = 9f;
    /** Emojis are 32 px (animated: a strip of up to 64 frames); anything far bigger isn't one. */
    private static final int MAX_WIDTH = 256;
    private static final int MAX_HEIGHT = 256 * 64;
    /** An animated emoji not drawn for this long (ms) is off screen: its animation pauses. */
    private static final long PAUSE_AFTER = 1000;
    /** GL_TEXTURE_BINDING_2D: which texture the font atlas bound when an emoji was placed in it. */
    private static final int TEXTURE_BINDING_2D = 0x8069;
    private static final Path CACHE = FabricLoader.getInstance().getGameDir().resolve(EdenLD.MOD_ID).resolve("emoji_cache");

    /** FontManager's font sets (FontManagerMixin). */
    public interface FontSets {
        Map<ResourceLocation, FontSet> eden_ld$fontSets();
    }

    public record Entry(String name, int codepoint, String id, boolean guest) {
    }

    /** The server's list for this player (listed emojis + favorited guests), in its order. */
    private static List<HubPayloads.EmojiRef> base = List.of();
    /** Guest emojis that arrived from Discord this session (on top of {@link #base}). */
    private static final Map<String, HubPayloads.EmojiRef> GUESTS = new LinkedHashMap<>();
    /** Both, as the font sees them. */
    private static List<HubPayloads.EmojiRef> list = List.of();
    private static List<String> favorites = List.of();
    private static int favoritesLimit;
    private static List<Entry> favoriteEntries = List.of();
    /** Saved stickers (favorite ids {@code s:…}), their own list and limit. */
    private static List<String> stickers = List.of();
    private static int stickersLimit;
    /** The server's emoji groups this player may use (picker tabs), and emojis locked to them. */
    private static List<HubPayloads.EmojiGroup> groups = List.of();
    private static Set<String> locked = Set.of();
    /** Every server emoji the player may use (grouped or not): what {@link #byName} looks in. */
    private static List<Entry> usable = List.of();
    /** Joined an Eden server (its emoji list arrived): the font exists, standard emojis load on demand. */
    private static boolean connected;
    /** Standard emoji sheets opened so far (256 emojis each; kept while on the server). */
    private static final Map<Integer, NativeImage> SHEETS = new HashMap<>();
    private static final int SHEET_GRID = 16;
    private static final int SHEET_CELL = 32;
    /** Standard emojis drawn but not loaded yet, and those already looked up. */
    private static final Set<String> PENDING = new HashSet<>();
    private static final Set<String> REQUESTED = new HashSet<>();
    private static long lastFlush;
    /** Stands in for a standard emoji until its image arrives (a blank space, not a "missing" box). */
    @Nullable
    private static Glyph placeholder;
    private static final Map<UnicodeEmoji.Category, List<Entry>> CATEGORY_ENTRIES = new java.util.EnumMap<>(UnicodeEmoji.Category.class);
    /** Loaded images by emoji id; owned here (providers only borrow them). */
    private static final Map<String, NativeImage> IMAGES = new HashMap<>();
    @Nullable
    private static Provider provider;
    @Nullable
    private static FontSet fontSet;
    private static List<Entry> entries = List.of();
    /** Bumped when what a picker lists may have changed (see {@link #revision()}). */
    private static int revision;

    private EmojiFont() {
    }

    /**
     * A counter an open picker polls: it moves when a favorite or a saved sticker is toggled, and when
     * the server's list (or its images, which put server emojis on the lists) arrives. Image loads for
     * drawing (the standard emoji sheets, sticker glyphs) don't move it.
     */
    public static int revision() {
        return revision;
    }

    /**
     * For FontSetMixin: the emoji font set when {@code other} (any other font, with no glyph of
     * its own there) is asked for an emoji's character, else null.
     */
    @Nullable
    public static FontSet borrowingFrom(FontSet other, int codepoint) {
        if (!ours(codepoint)) {
            return null;
        }
        FontSet emoji = fontSet;
        return emoji != null && other != emoji && provider != null && provider.getGlyph(codepoint) != null ? emoji : null;
    }

    /** Whether the character is an emoji Eden draws (listed, guest or standard). */
    public static boolean isEmoji(int codepoint) {
        if (!ours(codepoint)) {
            return false;
        }
        Provider emoji = provider;
        return emoji != null && emoji.getGlyph(codepoint) != null;
    }

    /** One of Eden's characters: the server's emojis (listed, guests), standard ones, saved stickers. */
    private static boolean ours(int codepoint) {
        return EmojiRegistry.isServerCharacter(codepoint) || UnicodeEmoji.isCharacter(codepoint)
                || isStickerCharacter(codepoint);
    }

    // ---------------------------------------------------------------- stickers in the chat field

    /**
     * Saved stickers go into a chat field as one character each (plane 16, U+100000 on, handed
     * out per session), drawn as the sticker like an emoji; {@link #expandStickers} turns them
     * into their links when the message is sent.
     */
    public static final int FIRST_STICKER = 0x100000;
    private static final Map<String, Integer> STICKER_CODEPOINTS = new HashMap<>();
    private static final Int2ObjectMap<net.ledok.eden_ld.chat.ChatLinks.Sticker> STICKER_BY_CODEPOINT = new Int2ObjectOpenHashMap<>();
    /** Loaded sticker glyph images (a strip of frames, owned here). */
    private static final Int2ObjectMap<Glyph> STICKER_GLYPHS = new Int2ObjectOpenHashMap<>();

    /** The sticker's character for a chat field. */
    public static String stickerCharacter(net.ledok.eden_ld.chat.ChatLinks.Sticker sticker) {
        int codepoint = STICKER_CODEPOINTS.computeIfAbsent(sticker.favoriteId(), id -> {
            int next = FIRST_STICKER + STICKER_BY_CODEPOINT.size();
            STICKER_BY_CODEPOINT.put(next, sticker);
            return next;
        });
        return Character.toString(codepoint);
    }

    public static boolean isStickerCharacter(int codepoint) {
        return codepoint >= FIRST_STICKER && codepoint < FIRST_STICKER + STICKER_BY_CODEPOINT.size();
    }

    /**
     * The text with every sticker character as its link ({@code [name](link)} — Discord shows the
     * image, Eden "[Sticker: name]"). Links that would pass {@code maxLength} are left out (a cut
     * link would break), as are characters of an older session.
     */
    public static String expandStickers(String text, int maxLength) {
        if (text.codePoints().noneMatch(cp -> cp >= FIRST_STICKER)) {
            return text;
        }
        StringBuilder plain = new StringBuilder();
        text.codePoints().filter(cp -> !isStickerCharacter(cp) && cp < FIRST_STICKER).forEach(plain::appendCodePoint);
        int room = maxLength - plain.length();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            net.ledok.eden_ld.chat.ChatLinks.Sticker sticker = STICKER_BY_CODEPOINT.get(codepoint);
            if (sticker == null) {
                if (codepoint < FIRST_STICKER) {
                    out.appendCodePoint(codepoint);
                }
                continue;
            }
            String link = sticker.chatText();
            if (link.length() <= room) {
                out.append(link);
                room -= link.length();
            }
        }
        return out.toString();
    }

    /** A sticker's image arrived: it becomes its character's glyph (animated ones animate). */
    private static void stickerLoaded(int codepoint, StickerImages.Image image) {
        List<NativeImage> frames = image.frames();
        if (frames.isEmpty() || STICKER_GLYPHS.containsKey(codepoint)) {
            return;
        }
        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        NativeImage strip = new NativeImage(width, height * frames.size(), true);
        for (int i = 0; i < frames.size(); i++) {
            NativeImage frame = frames.get(i);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    strip.setPixelRGBA(x, i * height + y, frame.getPixelRGBA(x, y));
                }
            }
        }
        STICKER_GLYPHS.put(codepoint, new Glyph(strip, height, frames.size() > 1 ? image.delays() : List.of()));
        rebuild();
    }

    /** The server emoji a name stands for (case-insensitive) that the player may use, or null. */
    @Nullable
    public static Entry byName(String name) {
        for (Entry entry : usable) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    /** Any shown emoji (listed, guest or standard) by its character, or null. */
    @Nullable
    public static Entry byCodepoint(int codepoint) {
        UnicodeEmoji.Entry standard = UnicodeEmoji.byCodepoint(codepoint);
        if (standard != null) {
            return entry(standard);
        }
        for (HubPayloads.EmojiRef ref : list) {
            if (ref.codepoint() == codepoint && IMAGES.containsKey(ref.id())) {
                return new Entry(ref.name(), ref.codepoint(), ref.id(), ref.guest());
            }
        }
        return null;
    }

    private static Entry entry(UnicodeEmoji.Entry standard) {
        return new Entry(standard.name(), standard.codepoint(), standard.id(), false);
    }

    /** A standard emoji by name ({@code joy}), or null. */
    @Nullable
    public static Entry standardByName(String name) {
        UnicodeEmoji.Entry standard = UnicodeEmoji.byName(name);
        return standard == null ? null : entry(standard);
    }

    /** The picker's tab for one of Discord's categories (skin tone variants left out). */
    public static List<Entry> category(UnicodeEmoji.Category category) {
        return CATEGORY_ENTRIES.computeIfAbsent(category, c -> UnicodeEmoji.all().stream()
                .filter(standard -> standard.category() == c && !standard.skinTone())
                .map(EmojiFont::entry).toList());
    }

    /**
     * The picker's search: the server's emojis whose name contains the query, then standard ones
     * matching any of their names (skin tones only when asked for "tone").
     */
    public static List<Entry> search(String query) {
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT).replace(":", "");
        if (needle.isEmpty()) {
            return List.of();
        }
        List<Entry> found = new ArrayList<>();
        for (Entry entry : usable) {
            if (entry.name().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                found.add(entry);
            }
        }
        boolean tones = needle.contains("tone");
        for (UnicodeEmoji.Entry standard : UnicodeEmoji.all()) {
            if (found.size() >= 300) {
                break;
            }
            if (standard.skinTone() && !tones) {
                continue;
            }
            for (String name : standard.names()) {
                if (name.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    found.add(entry(standard));
                    break;
                }
            }
        }
        return found;
    }

    /** The server's emojis outside any group (the Minestar tab), in the server's order. */
    public static List<Entry> entries() {
        return entries;
    }

    /** The server's emoji groups this player may use (picker tabs, after the Minestar one). */
    public static List<HubPayloads.EmojiGroup> groups() {
        return groups;
    }

    /** One group's emojis (those with an image), in the group's order. */
    public static List<Entry> groupEntries(HubPayloads.EmojiGroup group) {
        List<Entry> out = new ArrayList<>();
        for (String id : group.emojis()) {
            for (Entry entry : usable) {
                if (entry.id().equals(id)) {
                    out.add(entry);
                    break;
                }
            }
        }
        return out;
    }

    /** The player's favorites (listed or guests), in the order they were added. */
    public static List<Entry> favorites() {
        return favoriteEntries;
    }

    public static int favoritesLimit() {
        return favoritesLimit;
    }

    public static boolean isFavorite(String id) {
        return favorites.contains(id);
    }

    /**
     * Stars or unstars an emoji; the server keeps favorites per player and answers with the list.
     * False when adding would pass the player's limit.
     */
    public static boolean toggleFavorite(String id) {
        boolean add = !favorites.contains(id);
        if (add && favorites.size() >= favoritesLimit) {
            return false;
        }
        List<String> changed = new ArrayList<>(favorites);
        if (add) {
            changed.add(id);
        } else {
            changed.remove(id);
        }
        favorites = List.copyOf(changed);
        rebuild();
        revision++;
        ClientPlayNetworking.send(new HubPayloads.EmojiFavorite(id, add));
        return true;
    }

    /** The player's saved stickers, in the order they were saved. */
    public static List<net.ledok.eden_ld.chat.ChatLinks.Sticker> stickers() {
        List<net.ledok.eden_ld.chat.ChatLinks.Sticker> out = new ArrayList<>(stickers.size());
        for (String id : stickers) {
            net.ledok.eden_ld.chat.ChatLinks.Sticker sticker = net.ledok.eden_ld.chat.ChatLinks.stickerFavorite(id);
            if (sticker != null) {
                out.add(sticker);
            }
        }
        return out;
    }

    public static int stickersLimit() {
        return stickersLimit;
    }

    public static boolean isSaved(net.ledok.eden_ld.chat.ChatLinks.Sticker sticker) {
        return stickers.contains(sticker.favoriteId());
    }

    /**
     * Saves or drops a sticker (the server keeps them per player, apart from emoji favorites);
     * false when saving would pass the player's sticker limit.
     */
    public static boolean toggleSticker(net.ledok.eden_ld.chat.ChatLinks.Sticker sticker) {
        String id = sticker.favoriteId();
        boolean add = !stickers.contains(id);
        if (add && stickers.size() >= stickersLimit) {
            return false;
        }
        List<String> changed = new ArrayList<>(stickers);
        if (add) {
            changed.add(id);
        } else {
            changed.remove(id);
        }
        stickers = List.copyOf(changed);
        revision++;
        ClientPlayNetworking.send(new HubPayloads.EmojiFavorite(id, add));
        return true;
    }

    /** The server's emoji list (render thread): cached images load now, the rest are requested. */
    public static void onList(HubPayloads.EmojiList received) {
        connected = true;
        base = List.copyOf(received.emojis());
        favorites = List.copyOf(received.favorites());
        favoritesLimit = received.favoritesLimit();
        stickers = List.copyOf(received.stickers());
        stickersLimit = received.stickersLimit();
        groups = List.copyOf(received.groups());
        locked = Set.copyOf(received.locked());
        refreshList();
        Set<String> wanted = new HashSet<>();
        List<String> missing = new ArrayList<>();
        for (HubPayloads.EmojiRef ref : list) {
            if (!validId(ref.id()) || !wanted.add(ref.id()) || IMAGES.containsKey(ref.id())) {
                continue;
            }
            NativeImage cached = readCached(ref);
            if (cached != null) {
                IMAGES.put(ref.id(), cached);
            } else {
                missing.add(ref.id());
            }
        }
        List<NativeImage> dropped = new ArrayList<>();
        IMAGES.keySet().removeIf(id -> {
            if (!wanted.contains(id) && !id.startsWith("u:")) {
                dropped.add(IMAGES.get(id));
                return true;
            }
            return false;
        });
        rebuild();
        revision++;
        // Only after the new font stopped using them.
        dropped.forEach(NativeImage::close);
        if (!missing.isEmpty() && ClientPlayNetworking.canSend(HubPayloads.EmojiRequest.TYPE)) {
            ClientPlayNetworking.send(new HubPayloads.EmojiRequest(missing));
        }
    }

    /** Guests that just arrived from Discord: into the font (images from the cache or requested). */
    public static void onGuests(HubPayloads.EmojiGuests received) {
        received.emojis().forEach(ref -> GUESTS.put(ref.id(), ref));
        refreshList();
        List<String> missing = new ArrayList<>();
        for (HubPayloads.EmojiRef ref : received.emojis()) {
            if (validId(ref.id()) && !IMAGES.containsKey(ref.id())) {
                NativeImage cached = readCached(ref);
                if (cached != null) {
                    IMAGES.put(ref.id(), cached);
                } else {
                    missing.add(ref.id());
                }
            }
        }
        rebuild();
        if (!missing.isEmpty() && ClientPlayNetworking.canSend(HubPayloads.EmojiRequest.TYPE)) {
            ClientPlayNetworking.send(new HubPayloads.EmojiRequest(missing));
        }
    }

    /** {@link #list} = the server's list, then this session's guests not in it. */
    private static void refreshList() {
        Set<String> ids = new HashSet<>();
        List<HubPayloads.EmojiRef> merged = new ArrayList<>();
        for (HubPayloads.EmojiRef ref : base) {
            if (ids.add(ref.id())) {
                merged.add(ref);
            }
        }
        for (HubPayloads.EmojiRef ref : GUESTS.values()) {
            if (ids.add(ref.id())) {
                merged.add(ref);
            }
        }
        list = List.copyOf(merged);
    }

    /** Images the client asked for: cached to disk and added to the font. */
    public static void onImages(HubPayloads.EmojiImages received) {
        Map<String, HubPayloads.EmojiRef> listed = new HashMap<>();
        list.forEach(ref -> listed.put(ref.id(), ref));
        boolean added = false;
        for (HubPayloads.EmojiImage image : received.images()) {
            HubPayloads.EmojiRef ref = listed.get(image.id());
            if (ref == null || !validId(image.id()) || IMAGES.containsKey(image.id())) {
                continue;
            }
            NativeImage decoded = decode(image.png(), ref);
            if (decoded == null) {
                continue;
            }
            IMAGES.put(image.id(), decoded);
            added = true;
            try {
                Files.createDirectories(CACHE);
                Files.write(cacheFile(ref), image.png());
            } catch (IOException e) {
                EdenLD.LOGGER.warn("[Emoji] Can't cache emoji {}: {}", image.id(), e.toString());
            }
        }
        if (added) {
            rebuild();
            // Server emojis join the lists with their images: the rest of the list arrived.
            revision++;
        }
    }

    /** Left the server: its emojis go with it (the disk cache stays). */
    public static void reset() {
        connected = false;
        STICKER_GLYPHS.values().forEach(glyph -> glyph.image.close());
        STICKER_GLYPHS.clear();
        groups = List.of();
        locked = Set.of();
        SHEETS.values().forEach(sheet -> {
            if (sheet != null) {
                sheet.close();
            }
        });
        SHEETS.clear();
        PENDING.clear();
        REQUESTED.clear();
        base = List.of();
        GUESTS.clear();
        favorites = List.of();
        stickers = List.of();
        list = List.of();
        rebuild();
        StickerImages.clear();
        IMAGES.values().forEach(NativeImage::close);
        IMAGES.clear();
    }

    /** Discord ids are digits — never trust a server-sent id as a file name otherwise. */
    private static boolean validId(String id) {
        return !id.isEmpty() && id.length() <= 24 && id.chars().allMatch(Character::isDigit);
    }



    /** Still images as {@code <id>.png}; animated ones as their frame strip, {@code <id>.anim.png}. */
    private static Path cacheFile(HubPayloads.EmojiRef ref) {
        return CACHE.resolve(ref.id() + (ref.animated() ? ".anim.png" : ".png"));
    }

    @Nullable
    private static NativeImage readCached(HubPayloads.EmojiRef ref) {
        Path file = cacheFile(ref);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            NativeImage image = decode(Files.readAllBytes(file), ref);
            if (image == null) {
                Files.deleteIfExists(file);
            }
            return image;
        } catch (IOException e) {
            return null;
        }
    }

    /** The PNG as an image fit for this emoji (animated: exactly one square frame per delay), else null. */
    @Nullable
    private static NativeImage decode(byte[] png, HubPayloads.EmojiRef ref) {
        return decode(png, ref.animated() ? ref.delays().size() : 1);
    }

    @Nullable
    private static NativeImage decode(byte[] png, int frames) {
        try {
            NativeImage image = NativeImage.read(png);
            boolean fits = image.getWidth() <= MAX_WIDTH && image.getHeight() <= MAX_HEIGHT
                    && (frames == 1 || image.getHeight() == image.getWidth() * frames);
            if (!fits) {
                image.close();
                return null;
            }
            return image;
        } catch (IOException e) {
            return null;
        }
    }

    /** Entries and font from the list and the images loaded so far. */
    private static void rebuild() {
        Int2ObjectMap<Glyph> byCodepoint = new Int2ObjectOpenHashMap<>();
        List<Entry> shown = new ArrayList<>();
        List<Entry> allowed = new ArrayList<>();
        Set<String> grouped = new HashSet<>();
        groups.forEach(group -> grouped.addAll(group.emojis()));
        Map<String, Entry> byId = new HashMap<>();
        for (HubPayloads.EmojiRef ref : list) {
            NativeImage image = IMAGES.get(ref.id());
            if (image != null && !byCodepoint.containsKey(ref.codepoint())) {
                byCodepoint.put(ref.codepoint(), new Glyph(image, ref.animated() ? image.getWidth() : image.getHeight(),
                        ref.animated() ? ref.delays() : List.of()));
                Entry entry = new Entry(ref.name(), ref.codepoint(), ref.id(), ref.guest());
                byId.put(ref.id(), entry);
                if (!ref.guest() && !locked.contains(ref.id())) {
                    allowed.add(entry);
                    if (!grouped.contains(ref.id())) {
                        shown.add(entry);
                    }
                }
            }
        }
        entries = List.copyOf(shown);
        usable = List.copyOf(allowed);
        // Standard emojis loaded so far.
        for (Map.Entry<String, NativeImage> image : IMAGES.entrySet()) {
            UnicodeEmoji.Entry standard = UnicodeEmoji.byId(image.getKey());
            if (standard != null) {
                byCodepoint.put(standard.codepoint(), new Glyph(image.getValue(), image.getValue().getHeight(), List.of()));
            }
        }
        List<Entry> starred = new ArrayList<>();
        for (String id : favorites) {
            Entry entry = byId.get(id);
            UnicodeEmoji.Entry standard = entry == null ? UnicodeEmoji.byId(id) : null;
            if (entry != null && !locked.contains(id)) {
                starred.add(entry);
            } else if (standard != null) {
                starred.add(entry(standard));
            }
        }
        favoriteEntries = List.copyOf(starred);
        byCodepoint.putAll(STICKER_GLYPHS);
        provider = connected ? new Provider(byCodepoint) : null;
        install();
    }

    /** Text is drawing this character now (FontSetMixin): an animated emoji keeps playing. */
    public static void markDrawn(int codepoint) {
        if (isStickerCharacter(codepoint) && !STICKER_GLYPHS.containsKey(codepoint)) {
            // First time a sticker character shows: load its image, then it becomes the glyph.
            net.ledok.eden_ld.chat.ChatLinks.Sticker sticker = STICKER_BY_CODEPOINT.get(codepoint);
            StickerImages.Image[] image = new StickerImages.Image[1];
            image[0] = StickerImages.get(sticker, () -> stickerLoaded(codepoint, image[0]));
            if (image[0].status() == StickerImages.Status.READY) {
                stickerLoaded(codepoint, image[0]);
            }
            return;
        }
        if (UnicodeEmoji.isCharacter(codepoint)) {
            // First time a standard emoji shows up: load it (disk cache, else the server).
            Provider current = provider;
            if (current != null && !current.glyphs.containsKey(codepoint)) {
                UnicodeEmoji.Entry standard = UnicodeEmoji.byCodepoint(codepoint);
                if (standard != null && !REQUESTED.contains(standard.id())) {
                    PENDING.add(standard.id());
                }
            }
            return;
        }
        if (!EmojiRegistry.isServerCharacter(codepoint) && !isStickerCharacter(codepoint)) {
            return;
        }
        Provider current = provider;
        Glyph glyph = current == null ? null : current.glyphs.get(codepoint);
        if (glyph != null && glyph.animated()) {
            glyph.lastDrawn = net.minecraft.Util.getMillis();
        }
    }

    /**
     * Every rendered frame (GameRendererMixin): animated emojis whose frame changed get it drawn
     * over their spot in the font texture — every text showing them animates, at no extra cost.
     * Emojis nothing has drawn for a second (scrolled away, chat faded) are skipped until seen again.
     */
    public static void animate() {
        long now = net.minecraft.Util.getMillis();
        if (!PENDING.isEmpty() && now - lastFlush > 100) {
            lastFlush = now;
            loadPending();
        }
        Provider current = provider;
        if (current == null || current.animated.isEmpty()) {
            return;
        }
        int previousUnit = GlStateManager._getActiveTexture();
        boolean switched = false;
        for (Glyph glyph : current.animated) {
            if (glyph.texture < 0 || now - glyph.lastDrawn > PAUSE_AFTER) {
                continue;
            }
            int frame = glyph.frameAt(now);
            if (frame == glyph.shownFrame) {
                continue;
            }
            if (!switched) {
                GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                switched = true;
            }
            GlStateManager._bindTexture(glyph.texture);
            glyph.uploadFrame(glyph.x, glyph.y, frame);
        }
        if (switched) {
            GlStateManager._activeTexture(previousUnit);
        }
    }

    /**
     * Standard emojis drawn since the last look, cut from the sheets shipped in the mod (Twemoji
     * at 32 px, 256 per sheet, in list order) — no download.
     */
    private static void loadPending() {
        boolean loaded = false;
        for (String id : PENDING) {
            if (!IMAGES.containsKey(id)) {
                NativeImage image = bundled(id);
                if (image != null) {
                    IMAGES.put(id, image);
                    loaded = true;
                }
                // Not in the sheets (can't happen with a matching list): stays a blank space.
                REQUESTED.add(id);
            }
        }
        PENDING.clear();
        if (loaded) {
            rebuild();
        }
    }

    /** A standard emoji's image from its sheet ({@code eden_ld_emoji/sheets/NN.png}), or null. */
    @Nullable
    private static NativeImage bundled(String id) {
        UnicodeEmoji.Entry standard = UnicodeEmoji.byId(id);
        if (standard == null) {
            return null;
        }
        int index = standard.codepoint() - UnicodeEmoji.FIRST_CODEPOINT;
        NativeImage sheet = SHEETS.computeIfAbsent(index / (SHEET_GRID * SHEET_GRID), EmojiFont::readSheet);
        int cell = index % (SHEET_GRID * SHEET_GRID);
        int x = (cell % SHEET_GRID) * SHEET_CELL;
        int y = (cell / SHEET_GRID) * SHEET_CELL;
        if (sheet == null || y + SHEET_CELL > sheet.getHeight()) {
            return null;
        }
        NativeImage image = new NativeImage(SHEET_CELL, SHEET_CELL, true);
        sheet.copyRect(image, x, y, 0, 0, SHEET_CELL, SHEET_CELL, false, false);
        return image;
    }

    @Nullable
    private static NativeImage readSheet(int sheet) {
        try (java.io.InputStream in = EmojiFont.class.getResourceAsStream(
                String.format("/eden_ld_emoji/sheets/%02d.png", sheet))) {
            return in == null ? null : NativeImage.read(in);
        } catch (IOException e) {
            EdenLD.LOGGER.warn("[Emoji] Can't read emoji sheet {}: {}", sheet, e.toString());
            return null;
        }
    }

    /** (Re)registers the emoji font set with the game's FontManager (also after resource reloads). */
    public static void install() {
        Minecraft minecraft = Minecraft.getInstance();
        FontManager manager = ((MinecraftFonts) minecraft).eden_ld$fontManager();
        if (manager == null) {
            return;
        }
        Map<ResourceLocation, FontSet> sets = ((FontSets) manager).eden_ld$fontSets();
        // Still registered: ours to close. Gone: a resource reload already closed it.
        if (fontSet != null && sets.get(EmojiRegistry.FONT) == fontSet) {
            sets.remove(EmojiRegistry.FONT);
            fontSet.close();
        }
        fontSet = null;
        if (provider == null) {
            return;
        }
        // A fresh font set places every glyph again; old spots belong to closed textures.
        provider.glyphs.values().forEach(Glyph::forgetPlacement);
        fontSet = new FontSet(minecraft.getTextureManager(), EmojiRegistry.FONT);
        fontSet.reload(List.of(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)), Set.of());
        sets.put(EmojiRegistry.FONT, fontSet);
    }

    /** Minecraft's font manager (MinecraftMixin). */
    public interface MinecraftFonts {
        FontManager eden_ld$fontManager();
    }

    private static final class Provider implements GlyphProvider {
        private final Int2ObjectMap<Glyph> glyphs;
        private final List<Glyph> animated = new ArrayList<>();

        Provider(Int2ObjectMap<Glyph> glyphs) {
            this.glyphs = glyphs;
            glyphs.values().forEach(glyph -> {
                if (glyph.animated()) {
                    animated.add(glyph);
                }
            });
        }

        @Nullable
        @Override
        public GlyphInfo getGlyph(int codepoint) {
            Glyph glyph = glyphs.get(codepoint);
            if (glyph == null && (UnicodeEmoji.isCharacter(codepoint) || isStickerCharacter(codepoint))) {
                // Not loaded yet: blank space of the right width (markDrawn starts the loading).
                if (placeholder == null) {
                    placeholder = new Glyph(new NativeImage(32, 32, true), 32, List.of());
                }
                return placeholder;
            }
            return glyph;
        }

        @Override
        public IntSet getSupportedGlyphs() {
            return glyphs.keySet();
        }

        @Override
        public void close() {
            // The images belong to EmojiFont (reused across rebuilds).
        }
    }

    /**
     * One emoji: a frame of the image scaled to {@link #HEIGHT}, 1 px above the cap line to 1 px
     * below the baseline. Animated ones remember where the font texture placed them, so
     * {@link #animate()} can draw later frames there.
     */
    private static final class Glyph implements GlyphInfo {
        private final NativeImage image;
        private final int frameHeight;
        private final List<Integer> delays;
        private final int loop;
        private int texture = -1;
        private int x;
        private int y;
        private int shownFrame;
        /** When text last drew this emoji (ms). */
        private long lastDrawn;

        Glyph(NativeImage image, int frameHeight, List<Integer> delays) {
            this.image = image;
            this.frameHeight = frameHeight;
            this.delays = delays;
            this.loop = delays.stream().mapToInt(Integer::intValue).sum();
        }

        boolean animated() {
            return delays.size() > 1 && loop > 0;
        }

        int frameAt(long millis) {
            long t = millis % loop;
            for (int i = 0; i < delays.size(); i++) {
                t -= delays.get(i);
                if (t < 0) {
                    return i;
                }
            }
            return 0;
        }

        void uploadFrame(int atX, int atY, int frame) {
            image.upload(0, atX, atY, 0, frame * frameHeight, image.getWidth(), frameHeight, false, false);
            shownFrame = frame;
        }

        void forgetPlacement() {
            texture = -1;
        }

        private float scale() {
            return HEIGHT / frameHeight;
        }

        @Override
        public float getAdvance() {
            return Math.round(image.getWidth() * scale()) + 1;
        }

        @Override
        public BakedGlyph bake(Function<SheetGlyphInfo, BakedGlyph> baker) {
            return baker.apply(new SheetGlyphInfo() {
                @Override
                public int getPixelWidth() {
                    return image.getWidth();
                }

                @Override
                public int getPixelHeight() {
                    return frameHeight;
                }

                @Override
                public void upload(int atX, int atY) {
                    // The font texture is bound right now: remember the spot for animation.
                    if (animated()) {
                        texture = GlStateManager._getInteger(TEXTURE_BINDING_2D);
                        x = atX;
                        y = atY;
                    }
                    uploadFrame(atX, atY, 0);
                }

                @Override
                public boolean isColored() {
                    return true;
                }

                @Override
                public float getOversample() {
                    return 1f / scale();
                }

                @Override
                public float getBearingTop() {
                    return 8f;
                }
            });
        }
    }
}
