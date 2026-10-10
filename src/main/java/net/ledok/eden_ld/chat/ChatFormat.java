package net.ledok.eden_ld.chat;

import net.ledok.eden_ld.chat.emoji.EmojiRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text formatting players type in messages (it replaces Styled Chat's):
 * <ul>
 *   <li><b>Markdown</b> as on Discord — {@code **bold**}, {@code *italic*} / {@code _italic_},
 *   {@code __underline__}, {@code ~~strikethrough~~}, {@code ||spoiler||}, {@code `code`}
 *   (nothing parsed inside); {@code \*} keeps a character as it is;</li>
 *   <li><b>Tags</b> — colors {@code <red>}, {@code <#ff8800>}, {@code <color:gold>}; {@code <b>},
 *   {@code <i>}, {@code <u>}, {@code <st>}, {@code <spoiler>}; closed by {@code </red>} (or
 *   {@code </>} for the last one), else they run to the end.</li>
 * </ul>
 * Links, emojis ({@code :name:}, their characters, Discord's {@code <:name:id>}, image links)
 * and {@code @names} are never cut: markers inside them are plain text. A spoiler shows as a
 * gray bar; hovering reads it. Discord gets the same markdown (tags turned into it).
 * <p>
 * Shared by server chat (the hub's chat panels get the same styled lines) and Discord lines.
 */
public final class ChatFormat {

    /** One run of text with one look. */
    public record Span(String text, Style style, boolean spoiler) {
    }

    /** Which formatting is on (server config). */
    public record Options(boolean markdown, boolean tags) {
        public static final Options ALL = new Options(true, true);
        public static final Options NONE = new Options(false, false);

        public boolean any() {
            return markdown || tags;
        }
    }

    private static final String SPOILER_BAR = "▌";
    private static final int SPOILER_MAX_BARS = 40;

    /** Tokens whose characters are never markers: emoji and sticker links, links, Discord emojis, :name:, emoji characters, @names. */
    private static final Pattern PROTECTED = Pattern.compile(EmojiRegistry.cdnPattern().pattern()
            + "|" + ChatLinks.STICKER.pattern()
            + "|https?://[^\\s<>\"]*[^\\s<>\"*_~|`]"
            + "|<a?:[A-Za-z0-9_~\\-]{1,32}:\\d+>"
            + "|" + EmojiRegistry.SHORTCODE.pattern()
            + "|" + EmojiRegistry.CHARACTER.pattern()
            + "|@[A-Za-z0-9_]{3,16}");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-z_]+(?::[#A-Za-z0-9_]+)?|#[0-9a-fA-F]{6})?>");
    private static final String[] MARKERS = {"||", "**", "__", "~~", "*", "_", "`"};
    private static final Map<String, TextColor> EXTRA_COLORS = Map.of(
            "orange", TextColor.fromRgb(0xFFA500),
            "grey", TextColor.fromLegacyFormat(ChatFormatting.GRAY),
            "dark_grey", TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY),
            "pink", TextColor.fromRgb(0xFF69B4));

    private enum Effect { BOLD, ITALIC, UNDERLINE, STRIKE, SPOILER, CODE, COLOR }

    private ChatFormat() {
    }

    // ---------------------------------------------------------------- parsing

    /** The text cut into runs by its formatting; one plain run when there is none (or it's off). */
    public static List<Span> parse(String text, Options options) {
        if (!options.any() || !mayHaveFormatting(text)) {
            return List.of(new Span(text, Style.EMPTY, false));
        }
        int[] protectedEnd = protectedRanges(text);
        List<Span> spans = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        State state = new State();
        Style runStyle = Style.EMPTY;
        boolean runSpoiler = false;
        int i = 0;
        while (i < text.length()) {
            if (protectedEnd[i] > i) {
                // A link, emoji or mention: as is, in the current look.
                Style now = state.style();
                if (!run.isEmpty() && (!now.equals(runStyle) || state.spoiler() != runSpoiler)) {
                    spans.add(new Span(run.toString(), runStyle, runSpoiler));
                    run.setLength(0);
                }
                runStyle = now;
                runSpoiler = state.spoiler();
                run.append(text, i, protectedEnd[i]);
                i = protectedEnd[i];
                continue;
            }
            int consumed = 0;
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && isEscapable(text.charAt(i + 1))) {
                // "\*" → "*": the next character is plain text.
                i++;
            } else if (options.markdown()) {
                consumed = markdown(text, i, protectedEnd, state);
            }
            if (consumed == 0 && options.tags() && c == '<' && !state.code) {
                consumed = tag(text, i, state);
            }
            if (consumed > 0) {
                i += consumed;
                continue;
            }
            Style now = state.style();
            if (!run.isEmpty() && (!now.equals(runStyle) || state.spoiler() != runSpoiler)) {
                spans.add(new Span(run.toString(), runStyle, runSpoiler));
                run.setLength(0);
            }
            runStyle = now;
            runSpoiler = state.spoiler();
            run.append(text.charAt(i));
            i++;
        }
        if (!run.isEmpty()) {
            spans.add(new Span(run.toString(), runStyle, runSpoiler));
        }
        return spans.isEmpty() ? List.of(new Span("", Style.EMPTY, false)) : spans;
    }

    /** Whether parsing changed anything (a marker or tag was used). */
    public static boolean changed(List<Span> spans, String text) {
        if (spans.size() != 1) {
            return true;
        }
        Span only = spans.get(0);
        return only.spoiler() || !only.style().isEmpty() || !only.text().equals(text);
    }

    /** Whether the text uses any formatting (Discord lines go out untouched otherwise). */
    public static boolean hasFormatting(String text, Options options) {
        return changed(parse(text, options), text);
    }

    private static boolean mayHaveFormatting(String text) {
        for (int i = 0; i < text.length(); i++) {
            switch (text.charAt(i)) {
                case '*', '_', '~', '|', '`', '<', '\\' -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    private static boolean isEscapable(char c) {
        return c == '*' || c == '_' || c == '~' || c == '|' || c == '`' || c == '<' || c == '\\';
    }

    /** For each index where a protected token starts, where it ends (else 0). */
    private static int[] protectedRanges(String text) {
        int[] end = new int[text.length() + 1];
        Matcher matcher = PROTECTED.matcher(text);
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) {
                end[matcher.start()] = matcher.end();
            }
        }
        return end;
    }

    /** The markdown marker at {@code i} applied to the state; its length, or 0 when it's plain text. */
    private static int markdown(String text, int i, int[] protectedEnd, State state) {
        for (String marker : MARKERS) {
            if (!text.startsWith(marker, i)) {
                continue;
            }
            Effect effect = effect(marker);
            if (state.code && effect != Effect.CODE) {
                return 0;
            }
            boolean open = state.markdown.contains(effect);
            if (open) {
                if (!canClose(text, i, marker)) {
                    continue;
                }
                state.markdown.remove(effect);
                state.code = state.code && effect != Effect.CODE;
                return marker.length();
            }
            if (canOpen(text, i, marker) && closingAhead(text, i + marker.length(), marker, protectedEnd)) {
                state.markdown.add(effect);
                state.code = state.code || effect == Effect.CODE;
                return marker.length();
            }
        }
        return 0;
    }

    private static Effect effect(String marker) {
        return switch (marker) {
            case "||" -> Effect.SPOILER;
            case "**" -> Effect.BOLD;
            case "__" -> Effect.UNDERLINE;
            case "~~" -> Effect.STRIKE;
            case "`" -> Effect.CODE;
            default -> Effect.ITALIC;
        };
    }

    /** Opening: something follows; "*"/"_" need a non-space next, "_" also no letter before (snake_case stays). */
    private static boolean canOpen(String text, int i, String marker) {
        int after = i + marker.length();
        if (after >= text.length()) {
            return false;
        }
        if (marker.equals("*") || marker.equals("_")) {
            if (Character.isWhitespace(text.charAt(after))) {
                return false;
            }
            return !marker.equals("_") || i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
        }
        return true;
    }

    /** Closing: "*"/"_" need a non-space before, "_" also no letter after. */
    private static boolean canClose(String text, int i, String marker) {
        if (marker.equals("*") || marker.equals("_")) {
            if (i == 0 || Character.isWhitespace(text.charAt(i - 1))) {
                return false;
            }
            int after = i + 1;
            return !marker.equals("_") || after >= text.length() || !Character.isLetterOrDigit(text.charAt(after));
        }
        return true;
    }

    /** Whether the marker closes somewhere after {@code from} (outside links and emojis): a lone "*" stays a "*". */
    private static boolean closingAhead(String text, int from, String marker, int[] protectedEnd) {
        int j = from;
        while (j < text.length()) {
            if (protectedEnd[j] > j) {
                j = protectedEnd[j];
                continue;
            }
            if (text.charAt(j) == '\\') {
                j += 2;
                continue;
            }
            if (text.startsWith(marker, j) && j > from && canClose(text, j, marker)) {
                return true;
            }
            j++;
        }
        return false;
    }

    /** The tag at {@code i} applied to the state; its length, or 0 when it isn't one. */
    private static int tag(String text, int i, State state) {
        Matcher matcher = TAG.matcher(text).region(i, text.length());
        if (!matcher.lookingAt() || matcher.group(2) == null && matcher.group(1).isEmpty()) {
            return 0;
        }
        boolean closing = !matcher.group(1).isEmpty();
        String name = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase(Locale.ROOT);
        if (closing) {
            if (name.isEmpty()) {
                // "</>" closes the last tag.
                if (state.tags.isEmpty()) {
                    return 0;
                }
                state.tags.pop();
                return matcher.end() - i;
            }
            Opened target = null;
            for (Opened opened : state.tags) {
                if (opened.effect == tagEffect(name) && (opened.effect != Effect.COLOR || matchesColor(opened, name))) {
                    target = opened;
                    break;
                }
            }
            if (target == null && tagEffect(name) == null) {
                return 0;
            }
            if (target != null) {
                state.tags.remove(target);
            }
            return matcher.end() - i;
        }
        if (name.equals("reset")) {
            state.tags.clear();
            return matcher.end() - i;
        }
        Effect effect = tagEffect(name);
        if (effect == null) {
            return 0;
        }
        TextColor color = null;
        if (effect == Effect.COLOR) {
            color = color(name);
            if (color == null) {
                return 0;
            }
        }
        state.tags.push(new Opened(effect, name, color));
        return matcher.end() - i;
    }

    @Nullable
    private static Effect tagEffect(String name) {
        return switch (name) {
            case "b", "bold" -> Effect.BOLD;
            case "i", "italic", "em" -> Effect.ITALIC;
            case "u", "underline", "underlined" -> Effect.UNDERLINE;
            case "st", "strikethrough", "strike" -> Effect.STRIKE;
            case "spoiler" -> Effect.SPOILER;
            case "color", "c" -> Effect.COLOR;
            default -> color(name) != null ? Effect.COLOR : null;
        };
    }

    /** "</red>" closes "<red>"; "</color>" / "</c>" close any color. */
    private static boolean matchesColor(Opened opened, String closing) {
        return closing.equals("color") || closing.equals("c") || opened.name.equals(closing)
                || opened.color != null && opened.color.equals(color(closing));
    }

    /** A color tag's color: a name ({@code gold}), {@code #rrggbb}, {@code color:x} / {@code c:x}. */
    @Nullable
    private static TextColor color(String name) {
        String value = name;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            String head = name.substring(0, colon);
            if (!head.equals("color") && !head.equals("c")) {
                return null;
            }
            value = name.substring(colon + 1);
        }
        if (value.startsWith("#")) {
            return value.length() == 7 ? TextColor.parseColor(value).result().orElse(null) : null;
        }
        TextColor extra = EXTRA_COLORS.get(value);
        if (extra != null) {
            return extra;
        }
        ChatFormatting format = ChatFormatting.getByName(value);
        return format != null && format.isColor() ? TextColor.fromLegacyFormat(format) : null;
    }

    private record Opened(Effect effect, String name, @Nullable TextColor color) {
    }

    /** What's on at a point of the text: markdown toggles plus a stack of open tags. */
    private static final class State {
        final java.util.EnumSet<Effect> markdown = java.util.EnumSet.noneOf(Effect.class);
        final Deque<Opened> tags = new ArrayDeque<>();
        boolean code;

        boolean on(Effect effect) {
            if (markdown.contains(effect)) {
                return true;
            }
            for (Opened opened : tags) {
                if (opened.effect == effect) {
                    return true;
                }
            }
            return false;
        }

        boolean spoiler() {
            return on(Effect.SPOILER);
        }

        Style style() {
            Style style = Style.EMPTY;
            if (code) {
                return style.withColor(ChatFormatting.GRAY);
            }
            for (Opened opened : tags) {
                if (opened.color != null) {
                    style = style.withColor(opened.color);
                    break;
                }
            }
            if (on(Effect.BOLD)) {
                style = style.withBold(true);
            }
            if (on(Effect.ITALIC)) {
                style = style.withItalic(true);
            }
            if (on(Effect.UNDERLINE)) {
                style = style.withUnderlined(true);
            }
            if (on(Effect.STRIKE)) {
                style = style.withStrikethrough(true);
            }
            return style;
        }
    }

    // ---------------------------------------------------------------- building

    /**
     * The runs as one component, into {@code out}: each run's text goes through {@code tokens}
     * (links, emojis, mentions — whatever the caller renders) inside the run's look; spoiler runs
     * become one bar each with their content on hover.
     */
    public static void build(MutableComponent out, List<Span> spans, BiConsumer<MutableComponent, String> tokens) {
        MutableComponent hidden = null;
        int hiddenLength = 0;
        for (Span span : spans) {
            MutableComponent part = Component.empty().withStyle(span.style());
            tokens.accept(part, span.text());
            if (span.spoiler()) {
                if (hidden == null) {
                    hidden = Component.empty();
                }
                hidden.append(part);
                hiddenLength += span.text().codePointCount(0, span.text().length());
                continue;
            }
            if (hidden != null) {
                out.append(spoiler(hidden, hiddenLength));
                hidden = null;
                hiddenLength = 0;
            }
            out.append(part);
        }
        if (hidden != null) {
            out.append(spoiler(hidden, hiddenLength));
        }
    }

    /** A spoiler: a gray bar as long as the text (capped); hovering shows it. */
    public static Component spoiler(Component hidden, int length) {
        String bars = SPOILER_BAR.repeat(Math.max(1, Math.min(SPOILER_MAX_BARS, length)));
        return Component.literal(bars).withStyle(style -> style
                .withColor(ChatFormatting.DARK_GRAY)
                .withBold(false).withItalic(false).withUnderlined(false).withStrikethrough(false)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hidden)));
    }

    // ---------------------------------------------------------------- Discord

    /**
     * The text as Discord should read it: markdown stays (Discord's own); tags become markdown
     * ({@code <b>} → {@code **}, {@code <spoiler>} → {@code ||}…) or go (colors — Discord has none).
     */
    public static String toDiscord(String text, Options options) {
        if (!options.tags() || text.indexOf('<') < 0) {
            return text;
        }
        int[] protectedEnd = protectedRanges(text);
        StringBuilder out = new StringBuilder();
        Deque<String> open = new ArrayDeque<>();
        int i = 0;
        while (i < text.length()) {
            if (protectedEnd[i] > i) {
                out.append(text, i, protectedEnd[i]);
                i = protectedEnd[i];
                continue;
            }
            if (text.charAt(i) == '\\' && i + 1 < text.length() && isEscapable(text.charAt(i + 1))) {
                out.append(text, i, i + 2);
                i += 2;
                continue;
            }
            Matcher matcher = text.charAt(i) == '<' ? TAG.matcher(text).region(i, text.length()) : null;
            if (matcher != null && matcher.lookingAt() && (matcher.group(2) != null || !matcher.group(1).isEmpty())) {
                boolean closing = !matcher.group(1).isEmpty();
                String name = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase(Locale.ROOT);
                Effect effect = name.isEmpty() ? null : name.equals("reset") ? null : tagEffect(name);
                if (closing && name.isEmpty()) {
                    if (!open.isEmpty()) {
                        out.append(open.pop());
                        i = matcher.end();
                        continue;
                    }
                } else if (name.equals("reset") && !closing) {
                    while (!open.isEmpty()) {
                        out.append(open.pop());
                    }
                    i = matcher.end();
                    continue;
                } else if (effect != null) {
                    String markdown = switch (effect) {
                        case BOLD -> "**";
                        case ITALIC -> "*";
                        case UNDERLINE -> "__";
                        case STRIKE -> "~~";
                        case SPOILER -> "||";
                        default -> "";
                    };
                    if (closing) {
                        open.remove(markdown);
                    } else {
                        open.push(markdown);
                    }
                    out.append(markdown);
                    i = matcher.end();
                    continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        while (!open.isEmpty()) {
            out.append(open.pop());
        }
        return out.toString();
    }
}
