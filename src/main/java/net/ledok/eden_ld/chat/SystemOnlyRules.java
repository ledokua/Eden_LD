package net.ledok.eden_ld.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Which unmarked chat lines show in the System tab alone (1.3.2, the new look): other admins', command blocks' and the
 * console's command echoes ({@code chat.type.admin}) and routine game and mod notices — "Respawn point set", F3
 * feedback, login tips, update lists, claim refusals. Eden's own list ({@link #KEYS}, {@link #TEXT}, {@link #KEEP})
 * plus the server's {@code config/eden_ld/chat_system_only.json}, which it sends in {@code HubPayloads.ChatSystemOnly}.
 * <p>
 * A line matches by translation key — its own, or a part's inside an empty wrapper (F3, Simple Voice Chat) — or by how
 * its text starts (mods that send plain text). A keep rule wins over every other rule: it can switch off one of Eden's,
 * and, tried on the echoed command's key too, keep one kind of admin echo. Plain code: the client reads each line once,
 * when it arrives ({@code ChatStore.add}); the server only cleans its lists ({@link #clean}).
 */
public final class SystemOnlyRules {

    /** The most entries a list keeps (the rest are dropped). */
    public static final int MAX_ENTRIES = 256;
    /** The longest entry. */
    public static final int MAX_LENGTH = 256;
    /** How deep the classifier looks through empty wrappers: the root, its parts and theirs. */
    public static final int MAX_DEPTH = 2;
    /** The shortest text start (and key prefix, before its {@code *}): shorter ones would catch too much. */
    private static final int MIN_LENGTH = 3;

    /** The vanilla wrapper of a command echo to the other admins: "[Name: feedback]". */
    private static final String ADMIN = "chat.type.admin";

    /** Translation keys; a trailing {@code *} makes a prefix. */
    public static final List<String> KEYS = List.of(
            // Other admins', command blocks' ("@") and the console's ("Server") command echoes.
            ADMIN,
            "block.minecraft.set_spawn",
            "screenshot.success",
            // F3 shortcuts: "[Debug]: Hitboxes: shown" (the prefix is a part of an empty line).
            "debug.prefix",
            // Iris's shader key, the same kind of feedback as F3.
            "iris.shaders.toggled",
            "iris.shaders.disabled",
            "text.observable.announce",
            "chat.waystones.waystone_activated",
            "text.yigd.message.grave_location",
            "text.yigd.message.grave_relocated",
            // Open Parties and Claims' refusals: "(Main Hand) You are not allowed to interact with this block (…)!".
            "gui.xaero_claims_protection_*",
            // CraftTweaker tells every player about each /reload.
            "crafttweaker.reload.start",
            "crafttweaker.reload.complete");

    /** How lines of mods that send plain text start (after leading spaces and § codes); {@code #} is a number. */
    public static final List<String> TEXT = List.of(
            // Axiom's tips only: its warnings and errors stay.
            "[Axiom] Tip:",
            // Its update notice only ("[ModBeacon] 8 updates available:"): its restart countdown and /modbeacon replies stay.
            "[ModBeacon] # update",
            // ModBeacon's rows ("  ▸ [MR] Collective  8.47 -> …"): ▸ is U+25B8, not the test kit's ▶.
            "▸ [MR]",
            "▸ [CF]",
            "▸ [PIN]",
            // Every Simple Voice Chat line ("[%s]" around its name, chat.square_brackets).
            "[Simple Voice Chat]");

    /** Keys and text starts that always stay where they are, over every rule. */
    public static final List<String> KEEP = List.of(
            // F3 + Q's "[Debug]: Key bindings:" stays with its list (which has no prefix).
            "debug.help.message",
            // "F3 + C is held down. This will crash the game…" and "Crashing in N…".
            "debug.crash.message",
            "debug.crash.warning");

    /** Eden's own rules, without the server's. */
    public static final SystemOnlyRules BUILT_IN = new SystemOnlyRules(List.of(), List.of(), List.of());

    /** Which list an entry is in: each is cleaned by its own rules ({@link #clean}). */
    public enum Part {
        /** Translation keys; a trailing {@code *} makes a prefix. */
        KEYS,
        /** Text starts. */
        TEXT,
        /** Kept lines: each entry is tried as a key and as a text start. */
        KEEP
    }

    private static final int HIT = 1;
    private static final int KEPT = 2;
    private static final BiConsumer<String, String> SILENT = (entry, why) -> {
    };

    /** The server's lists, cleaned: what makes two rule sets equal (the built-ins are the same on both sides). */
    private final List<String> extraKeys;
    private final List<String> extraText;
    private final List<String> extraKeep;

    private final Set<String> keys = new HashSet<>();
    private final String[] keyPrefixes;
    private final String[] text;
    private final Set<String> keepKeys = new HashSet<>();
    private final String[] keepPrefixes;
    private final String[] keepText;

    private SystemOnlyRules(List<String> extraKeys, List<String> extraText, List<String> extraKeep) {
        this.extraKeys = extraKeys;
        this.extraText = extraText;
        this.extraKeep = extraKeep;
        List<String> prefixes = new ArrayList<>();
        addKeys(KEYS, keys, prefixes);
        addKeys(extraKeys, keys, prefixes);
        keyPrefixes = prefixes.toArray(String[]::new);
        List<String> starts = new ArrayList<>(TEXT);
        starts.addAll(extraText);
        text = starts.toArray(String[]::new);
        List<String> keptPrefixes = new ArrayList<>();
        List<String> keptText = new ArrayList<>();
        for (List<String> list : List.of(KEEP, extraKeep)) {
            for (String entry : list) {
                if (keyProblem(entry) == null) {
                    addKey(entry, keepKeys, keptPrefixes);
                }
                String start = textStart(entry);
                if (textProblem(start) == null) {
                    keptText.add(start);
                }
            }
        }
        keepPrefixes = keptPrefixes.toArray(String[]::new);
        keepText = keptText.toArray(String[]::new);
    }

    /** Eden's rules plus the server's lists (cleaned here too: entries no rule allows are dropped). */
    public static SystemOnlyRules of(@Nullable List<String> keys, @Nullable List<String> text,
                                     @Nullable List<String> keep) {
        return new SystemOnlyRules(clean(keys, Part.KEYS, SILENT), clean(text, Part.TEXT, SILENT),
                clean(keep, Part.KEEP, SILENT));
    }

    /** As {@link #clean(List, Part, BiConsumer)}, dropping silently. */
    public static List<String> clean(@Nullable List<String> list, Part part) {
        return clean(list, part, SILENT);
    }

    /**
     * The entries of a list that a rule allows, each once, in their order, at most {@link #MAX_ENTRIES} of them and
     * {@link #MAX_LENGTH} characters each: keys without whitespace, a prefix ({@code *} at the end) with at least 3
     * characters before it; text starts without their leading spaces and § codes or trailing spaces, at least 3
     * characters long; keep entries that are either. {@code dropped} hears each entry left out and why.
     */
    public static List<String> clean(@Nullable List<String> list, Part part, BiConsumer<String, String> dropped) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String entry : list) {
            if (entry == null) {
                dropped.accept("null", "empty");
                continue;
            }
            String clean = switch (part) {
                case KEYS, KEEP -> entry.strip();
                case TEXT -> textStart(entry);
            };
            String problem = clean.length() > MAX_LENGTH ? "longer than " + MAX_LENGTH + " characters"
                    : switch (part) {
                        case KEYS -> keyProblem(clean);
                        case TEXT -> textProblem(clean);
                        // Kept when it works as either.
                        case KEEP -> {
                            String asKey = keyProblem(clean);
                            yield asKey == null || textProblem(textStart(clean)) == null ? null : asKey;
                        }
                    };
            if (problem != null) {
                dropped.accept(entry, problem);
            } else if (kept.size() >= MAX_ENTRIES && !kept.contains(clean)) {
                dropped.accept(entry, "more than " + MAX_ENTRIES + " entries");
            } else {
                kept.add(clean);
            }
        }
        return List.copyOf(kept);
    }

    /** Why a key can't be a rule, or null when it can. */
    @Nullable
    private static String keyProblem(String key) {
        if (key.isEmpty()) {
            return "empty";
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isWhitespace(key.charAt(i))) {
                return "a key has no spaces";
            }
        }
        int star = key.indexOf('*');
        if (star >= 0 && star != key.length() - 1) {
            return "'*' only works at the end";
        }
        if (star >= 0 && star < MIN_LENGTH) {
            return "a prefix needs at least " + MIN_LENGTH + " characters before its '*'";
        }
        return null;
    }

    /** Why a text start (as {@link #textStart} leaves it) can't be a rule, or null when it can. */
    @Nullable
    private static String textProblem(String start) {
        if (start.length() < MIN_LENGTH) {
            return start.isEmpty() ? "empty" : "shorter than " + MIN_LENGTH + " characters";
        }
        return null;
    }

    /** A text rule as lines are read: without leading spaces and § codes, nor trailing spaces. */
    private static String textStart(String entry) {
        return entry.substring(start(entry)).stripTrailing();
    }

    private static void addKeys(List<String> list, Set<String> exact, List<String> prefixes) {
        for (String key : list) {
            addKey(key, exact, prefixes);
        }
    }

    private static void addKey(String key, Set<String> exact, List<String> prefixes) {
        if (key.endsWith("*")) {
            prefixes.add(key.substring(0, key.length() - 1));
        } else {
            exact.add(key);
        }
    }

    // ---------------------------------------------------------------- classifying

    /**
     * Whether the line shows in the System tab alone. {@code text}: its {@link Component#getString()} (the caller has
     * it already). Call it for unmarked lines only ({@link ChatMarker#find} is null): a channel's, Discord's, a death
     * or an advancement never is. Allocates nothing.
     */
    public boolean matches(Component line, String text) {
        int hits = visit(line, 0);
        int from = start(text);
        if ((hits & KEPT) != 0 || textStartsWithAny(text, from, keepText)) {
            return false;
        }
        return (hits & HIT) != 0 || textStartsWithAny(text, from, this.text);
    }

    /**
     * The rules a node's keys meet: its own key (and, in an admin echo, the echoed command's — which can only keep the
     * line), or its parts' when it is an empty wrapper. Never inside a part with text of its own: a key nested in
     * someone's message isn't the line's.
     */
    private int visit(Component node, int depth) {
        ComponentContents contents = node.getContents();
        if (contents instanceof TranslatableContents translatable) {
            String key = translatable.getKey();
            int hits = test(key);
            Object[] args = translatable.getArgs();
            if (ADMIN.equals(key) && args.length > 1 && args[1] instanceof Component echoed
                    && echoed.getContents() instanceof TranslatableContents command) {
                hits |= test(command.getKey()) & KEPT;
            }
            return hits;
        }
        if (depth >= MAX_DEPTH || !(contents instanceof PlainTextContents plain) || !plain.text().isEmpty()) {
            return 0;
        }
        int hits = 0;
        List<Component> parts = node.getSiblings();
        for (int i = 0; i < parts.size(); i++) {
            hits |= visit(parts.get(i), depth + 1);
        }
        return hits;
    }

    private int test(String key) {
        int hits = 0;
        if (keys.contains(key) || startsWithAny(key, 0, keyPrefixes)) {
            hits |= HIT;
        }
        if (keepKeys.contains(key) || startsWithAny(key, 0, keepPrefixes)) {
            hits |= KEPT;
        }
        return hits;
    }

    /** {@link #startsWithAny} for text starts, where {@code #} stands for a number (one or more digits). */
    private static boolean textStartsWithAny(String text, int from, String[] starts) {
        for (String start : starts) {
            if (textStartsWith(text, from, start)) {
                return true;
            }
        }
        return false;
    }

    static boolean textStartsWith(String text, int from, String start) {
        int t = from;
        for (int i = 0; i < start.length(); i++) {
            char c = start.charAt(i);
            if (c == '#') {
                int digits = t;
                while (t < text.length() && Character.isDigit(text.charAt(t))) {
                    t++;
                }
                if (t == digits) {
                    return false;
                }
            } else if (t >= text.length() || text.charAt(t++) != c) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAny(String text, int from, String[] starts) {
        for (String start : starts) {
            if (text.startsWith(start, from)) {
                return true;
            }
        }
        return false;
    }

    /** Where a line's text starts: past leading spaces and § codes. */
    static int start(String text) {
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '§' && i + 1 < length) {
                i += 2;
            } else {
                break;
            }
        }
        return i;
    }

    // ---------------------------------------------------------------- equality

    @Override
    public boolean equals(Object other) {
        return other instanceof SystemOnlyRules rules && extraKeys.equals(rules.extraKeys)
                && extraText.equals(rules.extraText) && extraKeep.equals(rules.extraKeep);
    }

    @Override
    public int hashCode() {
        return (extraKeys.hashCode() * 31 + extraText.hashCode()) * 31 + extraKeep.hashCode();
    }

    @Override
    public String toString() {
        return "SystemOnlyRules[keys=" + extraKeys + ", text=" + extraText + ", keep=" + extraKeep + "]";
    }
}
