package net.ledok.eden_ld.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formatting as LuckPerms prefixes and Eden's config use it, turned into real component styles.
 * Shared by server chat and the hub. Both kinds mix freely:
 * <ul>
 *   <li><b>Legacy codes</b> — {@code &c}, {@code §l}, hex {@code &#ff8800}; a color code resets
 *   bold and the like, as in vanilla;</li>
 *   <li><b>Tags</b> (MiniMessage style, as the TAB plugin writes them) — colors {@code <#ff8800>},
 *   {@code <red>}, {@code <color:gold>}; {@code <b>}/{@code <bold>}, {@code <i>}/{@code <italic>},
 *   {@code <u>}/{@code <underlined>}, {@code <st>}/{@code <strikethrough>},
 *   {@code <obf>}/{@code <obfuscated>}, {@code <reset>}. A tag is closed by its own name
 *   ({@code </#ff8800>}, {@code </b>}) or {@code </>}, else it runs to the end. A hex tag closed by
 *   another color, {@code <#ffffff>text</#ffff00>}, is a gradient (TAB's form).</li>
 * </ul>
 * Anything that isn't a known tag stays as text.
 */
public final class LegacyText {

    private static final Pattern TAG = Pattern.compile("<(/?)(#[0-9a-fA-F]{6}|[a-z_]+(?::[#A-Za-z0-9_]+)?)?>");
    private static final Map<String, TextColor> EXTRA_COLORS = Map.of(
            "orange", TextColor.fromRgb(0xFFA500),
            "grey", TextColor.fromLegacyFormat(ChatFormatting.GRAY),
            "dark_grey", TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY),
            "pink", TextColor.fromRgb(0xFF69B4));

    private LegacyText() {
    }

    /** Legacy codes ({@code &}/{@code §}, {@code &#rrggbb}) and tags to a component. */
    public static MutableComponent parse(@Nullable String text) {
        return parse(text, true);
    }

    /**
     * As {@link #parse(String)}; with {@code ampersand} false only {@code §} codes count
     * (text that already went through a {@code &} → {@code §} pass, so a remaining {@code &} is text).
     */
    public static MutableComponent parse(@Nullable String text, boolean ampersand) {
        MutableComponent out = Component.empty();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Builder builder = new Builder();
        List<Frame> open = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '§' || (ampersand && c == '&')) && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == '#' && i + 8 <= text.length()) {
                    TextColor hex = hex(text.substring(i + 2, i + 8));
                    if (hex != null) {
                        builder.style = Style.EMPTY.withColor(hex);
                        i += 7;
                        continue;
                    }
                }
                ChatFormatting format = ChatFormatting.getByCode(code);
                if (format != null) {
                    builder.style = format == ChatFormatting.RESET ? Style.EMPTY
                            : format.isColor() ? Style.EMPTY.withColor(format)
                            : builder.style.applyFormat(format);
                    i++;
                    continue;
                }
            }
            if (c == '<') {
                Matcher tag = TAG.matcher(text).region(i, text.length());
                if (tag.lookingAt() && applyTag(tag.group(1).equals("/"), tag.group(2), builder, open)) {
                    i = tag.end() - 1;
                    continue;
                }
            }
            builder.append(c);
        }
        for (Builder.Piece piece : builder.pieces) {
            out.append(Component.literal(piece.text.toString()).withStyle(piece.style));
        }
        return out;
    }

    /** An open tag: its name, the look before it, and where its text starts. */
    private record Frame(String name, Style before, int firstPiece, @Nullable TextColor color) {
    }

    /** Applies one tag; false when it isn't one (it stays as text). */
    private static boolean applyTag(boolean closing, @Nullable String name, Builder builder, List<Frame> open) {
        if (closing) {
            if (open.isEmpty()) {
                return name != null && known(name);
            }
            int index = open.size() - 1;
            if (name != null) {
                while (index >= 0 && !matches(open.get(index).name(), name)) {
                    index--;
                }
                if (index < 0) {
                    return known(name);
                }
            }
            Frame frame = open.get(index);
            TextColor end = name != null && name.startsWith("#") ? hex(name.substring(1)) : null;
            if (frame.color() != null && end != null && !end.equals(frame.color())) {
                builder.gradient(frame.firstPiece(), frame.color(), end);
            }
            builder.style = frame.before();
            open.subList(index, open.size()).clear();
            return true;
        }
        if (name == null) {
            return false;
        }
        Style before = builder.style;
        Style after;
        TextColor color = null;
        switch (name) {
            case "b", "bold" -> after = before.withBold(true);
            case "i", "em", "italic" -> after = before.withItalic(true);
            case "u", "underline", "underlined" -> after = before.withUnderlined(true);
            case "st", "strikethrough" -> after = before.withStrikethrough(true);
            case "obf", "obfuscated" -> after = before.withObfuscated(true);
            case "reset" -> {
                builder.style = Style.EMPTY;
                open.clear();
                return true;
            }
            default -> {
                color = color(name.startsWith("color:") ? name.substring(6) : name);
                if (color == null) {
                    return false;
                }
                after = before.withColor(color);
            }
        }
        open.add(new Frame(name, before, builder.pieces.size(), color));
        builder.style = after;
        return true;
    }

    /** Whether a closing tag's name closes this open tag ("</#…>" closes any hex color: TAB's gradients). */
    private static boolean matches(String opened, String closing) {
        return opened.equals(closing) || (opened.startsWith("#") && closing.startsWith("#"))
                || (opened.startsWith("color:") && closing.equals("color"));
    }

    private static boolean known(String name) {
        return switch (name) {
            case "b", "bold", "i", "em", "italic", "u", "underline", "underlined", "st", "strikethrough",
                 "obf", "obfuscated", "reset", "color" -> true;
            default -> color(name.startsWith("color:") ? name.substring(6) : name) != null;
        };
    }

    /**
     * One color as config and LuckPerms meta write it: {@code #69118f}, {@code &#69118f},
     * {@code &6}, or a name ({@code gold}). Null when blank or unreadable.
     */
    @Nullable
    public static TextColor color(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("&#")) {
            v = v.substring(1);
        }
        if ((v.startsWith("&") || v.startsWith("§")) && v.length() == 2) {
            ChatFormatting format = ChatFormatting.getByCode(v.charAt(1));
            return format != null && format.isColor() ? TextColor.fromLegacyFormat(format) : null;
        }
        TextColor extra = EXTRA_COLORS.get(v);
        return extra != null ? extra : TextColor.parseColor(v).result().orElse(null);
    }

    @Nullable
    private static TextColor hex(String digits) {
        try {
            return TextColor.fromRgb(Integer.parseInt(digits, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Text runs with their looks, built one character at a time. */
    private static final class Builder {
        private static final class Piece {
            final StringBuilder text = new StringBuilder();
            final Style style;

            Piece(Style style) {
                this.style = style;
            }
        }

        final List<Piece> pieces = new ArrayList<>();
        Style style = Style.EMPTY;

        void append(char c) {
            Piece last = pieces.isEmpty() ? null : pieces.get(pieces.size() - 1);
            if (last == null || !last.style.equals(style)) {
                last = new Piece(style);
                pieces.add(last);
            }
            last.text.append(c);
        }

        /** Recolors the text since piece {@code from}, one character at a time, from {@code start} to {@code end}. */
        void gradient(int from, TextColor start, TextColor end) {
            if (from >= pieces.size()) {
                return;
            }
            List<Piece> tail = new ArrayList<>(pieces.subList(from, pieces.size()));
            int total = 0;
            for (Piece piece : tail) {
                total += piece.text.codePointCount(0, piece.text.length());
            }
            pieces.subList(from, pieces.size()).clear();
            int a = start.getValue();
            int b = end.getValue();
            int n = 0;
            for (Piece piece : tail) {
                String text = piece.text.toString();
                for (int offset = 0; offset < text.length(); ) {
                    int codePoint = text.codePointAt(offset);
                    float t = total <= 1 ? 0 : (float) n++ / (total - 1);
                    Piece one = new Piece(piece.style.withColor(TextColor.fromRgb(mix(a, b, t))));
                    one.text.appendCodePoint(codePoint);
                    pieces.add(one);
                    offset += Character.charCount(codePoint);
                }
            }
        }

        private static int mix(int a, int b, float t) {
            int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
            int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
            int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
            return (r << 16) | (g << 8) | bl;
        }
    }
}
