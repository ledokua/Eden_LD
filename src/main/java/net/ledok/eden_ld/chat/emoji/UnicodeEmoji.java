package net.ledok.eden_ld.chat.emoji;

import net.ledok.eden_ld.EdenLD;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The standard (Unicode) emojis, as Discord offers them: names as on Discord ({@code :joy:},
 * {@code :thumbsup:}, {@code :flag_ua:}), Discord's picker categories, Twemoji images (Discord's
 * own set). The list and the images ship in the mod ({@code eden_ld_emoji/}, built by
 * {@code tools/emoji/build_emoji.py} from emojibase-data and Twemoji), so server and client agree
 * without sending anything.
 * <p>
 * Minecraft draws text one character at a time, so an emoji — often several characters (skin
 * tones, flags, families) — travels as one private-use character of its own (U+F0000 on, by list
 * position); {@link #toCharacters} and {@link #toUnicode} convert both ways. Text-style symbols
 * (™ © ❤ …) become images too, with or without the emoji variation selector (U+FE0F).
 */
public final class UnicodeEmoji {

    public static final int FIRST_CODEPOINT = 0xF0000;
    /** Room for 16,384; the server's own emojis follow (EmojiRegistry). */
    public static final int LAST_CODEPOINT = 0xF3FFF;
    private static final int VS16 = 0xFE0F;

    /** Discord's picker categories, in its order (emojibase groups mapped onto them). */
    public enum Category { PEOPLE, NATURE, FOOD, ACTIVITIES, TRAVEL, OBJECTS, SYMBOLS, FLAGS }

    /**
     * One emoji. {@code id} ("u:" + hexcode) is stable across dataset updates (favorites use it);
     * {@code codepoint} follows the list order.
     */
    public record Entry(String id, String unicode, String hexcode, int codepoint, List<String> names,
                        Category category, boolean textStyle, boolean skinTone) {
        public String name() {
            return names.get(0);
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, Entry> BY_NAME = new HashMap<>();
    private static final Map<String, Entry> BY_ID = new HashMap<>();
    /** By the emoji's text with every FE0F removed (the form matching looks up). */
    private static final Map<String, Entry> BY_SEQUENCE = new HashMap<>();
    private static int longestSequence;

    static {
        load();
    }

    private UnicodeEmoji() {
    }

    private static void load() {
        try (InputStream in = UnicodeEmoji.class.getResourceAsStream("/eden_ld_emoji/unicode_emoji.tsv")) {
            if (in == null) {
                EdenLD.LOGGER.error("[Emoji] Standard emoji list missing from the mod jar.");
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 4 || FIRST_CODEPOINT + ENTRIES.size() > LAST_CODEPOINT) {
                    continue;
                }
                String hexcode = parts[0];
                StringBuilder unicode = new StringBuilder();
                for (String hex : hexcode.split("-")) {
                    unicode.appendCodePoint(Integer.parseInt(hex, 16));
                }
                Entry entry = new Entry("u:" + hexcode, unicode.toString(), hexcode, FIRST_CODEPOINT + ENTRIES.size(),
                        List.of(parts[3].split(",")), category(Integer.parseInt(parts[1])),
                        parts[2].contains("t"), parts[2].contains("s"));
                ENTRIES.add(entry);
                BY_ID.put(entry.id(), entry);
                for (String name : entry.names()) {
                    BY_NAME.putIfAbsent(name.toLowerCase(Locale.ROOT), entry);
                }
                String sequence = withoutVs16(entry.unicode());
                BY_SEQUENCE.putIfAbsent(sequence, entry);
                longestSequence = Math.max(longestSequence, sequence.length());
            }
        } catch (IOException | RuntimeException e) {
            EdenLD.LOGGER.error("[Emoji] Can't read the standard emoji list.", e);
        }
    }

    /** emojibase groups → Discord's categories (0 smileys + 1 people → People, …). */
    private static Category category(int group) {
        return switch (group) {
            case 0, 1 -> Category.PEOPLE;
            case 3 -> Category.NATURE;
            case 4 -> Category.FOOD;
            case 5 -> Category.TRAVEL;
            case 6 -> Category.ACTIVITIES;
            case 7 -> Category.OBJECTS;
            case 9 -> Category.FLAGS;
            default -> Category.SYMBOLS;
        };
    }

    private static String withoutVs16(String text) {
        return text.indexOf(VS16) < 0 ? text : text.replace(Character.toString(VS16), "");
    }

    public static List<Entry> all() {
        return ENTRIES;
    }

    @Nullable
    public static Entry byName(String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    @Nullable
    public static Entry byId(String id) {
        return BY_ID.get(id);
    }

    @Nullable
    public static Entry byCodepoint(int codepoint) {
        int index = codepoint - FIRST_CODEPOINT;
        return index >= 0 && index < ENTRIES.size() ? ENTRIES.get(index) : null;
    }

    public static boolean isCharacter(int codepoint) {
        return codepoint >= FIRST_CODEPOINT && codepoint < FIRST_CODEPOINT + ENTRIES.size();
    }

    /**
     * Every standard emoji in the text replaced by its own character (longest match first, so
     * 👍🏽 stays one emoji and 🇺🇦 is a flag, not two letters). Text without emojis comes back as is.
     */
    public static String toCharacters(String text) {
        StringBuilder out = null;
        int i = 0;
        while (i < text.length()) {
            int codepoint = text.codePointAt(i);
            int width = Character.charCount(codepoint);
            Match match = codepoint < 0x80 && codepoint != '#' && codepoint != '*' && (codepoint < '0' || codepoint > '9')
                    ? null : matchAt(text, i);
            if (match == null) {
                if (out != null) {
                    out.appendCodePoint(codepoint);
                }
                i += width;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length()).append(text, 0, i);
            }
            out.appendCodePoint(match.entry().codepoint());
            i = match.end();
        }
        return out == null ? text : out.toString();
    }

    private record Match(Entry entry, int end) {
    }

    /** The longest emoji starting at {@code start}, FE0F ignored (and swallowed after a match). */
    @Nullable
    private static Match matchAt(String text, int start) {
        StringBuilder key = new StringBuilder();
        Match best = null;
        int i = start;
        while (i < text.length() && key.length() <= longestSequence) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (codepoint == VS16) {
                if (best != null && best.end() == i - 1) {
                    best = new Match(best.entry(), i);
                }
                continue;
            }
            key.appendCodePoint(codepoint);
            Entry entry = BY_SEQUENCE.get(key.toString());
            if (entry != null) {
                best = new Match(entry, i);
            }
        }
        return best;
    }

    /** Emoji characters (standard ones) back to real Unicode — for Discord. */
    public static String toUnicode(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            Entry entry = isCharacter(codepoint) ? byCodepoint(codepoint) : null;
            if (entry != null && out == null) {
                out = new StringBuilder(text.length()).append(text, 0, i);
            }
            if (out != null) {
                if (entry != null) {
                    out.append(entry.unicode());
                } else {
                    out.appendCodePoint(codepoint);
                }
            }
            i += Character.charCount(codepoint);
        }
        return out == null ? text : out.toString();
    }
}
