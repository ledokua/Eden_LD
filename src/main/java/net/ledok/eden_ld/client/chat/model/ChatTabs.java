package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The new look's chat tabs ({@code docs/PLAN-chat-v2.md}, phase 5): which tabs the strip shows, in which order, and
 * which one the view follows. The state is a view mode, not a tab: in {@link Mode#CHANNEL} the active tab is the
 * channel the player types into, so every way that channel changes — a tab, {@code /ch}, a [tag], Reply, a mention
 * jump, the server's fallback when a party ends — moves the view with it; All and System keep the typing channel (the
 * input's glyph shows it). The mode lasts for the game run (it starts on All) and is written nowhere.
 * <p>
 * The strip is {@code [All] Global Local [Party] [Guild] System [Spy]}, by slot ({@link #slot}): All while the player
 * keeps its tab ({@code chat_all_tab}); a channel while it is open to them (Party and Guild only as a member) and not
 * hidden in Settings → Chat ({@code CHAT_HIDE_*}) — a hidden one still shows while it is the active tab; System always;
 * Spy while spying (phase 10; never yet). With no typing channel (not an Eden server) there are no tabs at all.
 * <p>
 * Plain code: the client hands in its state ({@link #refresh}), and the order is laid out again only when that state
 * or the mode changed. Nothing here allocates after construction but {@link #tagChannel}, which runs on a click.
 */
public final class ChatTabs {

    public enum Mode { CHANNEL, ALL, SYSTEM, SPY }

    /** Slots: 0 All, 1..4 Global/Local/Party/Guild (1 + ordinal), 5 System, 6 Spy (PM tabs grow it in phase 5b). */
    public static final int SLOTS = 7;
    public static final int ALL_SLOT = 0;
    public static final int SYSTEM_SLOT = 5;
    public static final int SPY_SLOT = 6;
    private static final int CHANNELS = Channel.values().length;
    private static final ChatTabSpec[] SPECS = {ChatTabSpec.ALL, ChatTabSpec.of(Channel.GLOBAL),
            ChatTabSpec.of(Channel.LOCAL), ChatTabSpec.of(Channel.PARTY), ChatTabSpec.of(Channel.GUILD),
            ChatTabSpec.SYSTEM, ChatTabSpec.SPY};
    /** Where each input sits in the packed key ({@link #key}). */
    private static final int HIDDEN_SHIFT = CHANNELS;
    private static final int TYPING_SHIFT = 2 * CHANNELS;
    private static final int ALL_TAB_BIT = 1 << TYPING_SHIFT + 3;
    private static final int SPY_BIT = ALL_TAB_BIT << 1;
    private static final int MODE_SHIFT = TYPING_SHIFT + 5;

    private Mode mode = Mode.ALL;
    /** The visible tabs' slots, in strip order. */
    private final int[] order = new int[SLOTS];
    private int count;
    /** The inputs (and the mode) the order was laid out for; -1 before the first {@link #refresh}. */
    private int key = -1;
    private int open;
    private int hidden;
    private int typing = -1;
    private boolean allTab = true;
    private boolean spy;

    // ---------------------------------------------------------------- slots

    /** A tab's slot; -1 for one the strip has no place for (PM, phase 5b). */
    public static int slot(ChatTabSpec tab) {
        return switch (tab.kind()) {
            case ALL -> ALL_SLOT;
            case CHANNEL -> 1 + java.util.Objects.requireNonNull(tab.channel()).ordinal();
            case SYSTEM -> SYSTEM_SLOT;
            case SPY -> SPY_SLOT;
            case PM -> -1;
        };
    }

    /** The tab in a slot. */
    public static ChatTabSpec spec(int slot) {
        return SPECS[slot];
    }

    /** The mode that shows the slot's tab. */
    public static Mode modeOf(int slot) {
        return switch (slot) {
            case ALL_SLOT -> Mode.ALL;
            case SYSTEM_SLOT -> Mode.SYSTEM;
            case SPY_SLOT -> Mode.SPY;
            default -> Mode.CHANNEL;
        };
    }

    /** The channel a slot's tab belongs to, or null (All, System, Spy). */
    @Nullable
    public static Channel channelOf(int slot) {
        return slot > 0 && slot <= CHANNELS ? Channel.of(slot - 1) : null;
    }

    // ---------------------------------------------------------------- the mode

    public Mode mode() {
        return mode;
    }

    /**
     * The view switches (a tab clicked, Ctrl+Tab, a mention jump…). Setting {@link Mode#CHANNEL} goes together with
     * selecting the channel: the active tab is always the typing channel's. The next {@link #refresh} lays the strip
     * out for it (a hidden channel's tab comes or goes with it).
     */
    public void mode(Mode mode) {
        if (this.mode != mode) {
            this.mode = mode;
            key = -1;
        }
    }

    /** The mode as it reads with these inputs: All without its tab is the typing channel's, Spy without spying All. */
    private static Mode shown(Mode mode, boolean allTab, boolean spy) {
        if (mode == Mode.SPY && !spy) {
            mode = allTab ? Mode.ALL : Mode.CHANNEL;
        }
        if (mode == Mode.ALL && !allTab) {
            mode = Mode.CHANNEL;
        }
        return mode;
    }

    /**
     * The tab the view shows now, without changing anything — from the live state, so the closed chat (which lays out
     * no strip) agrees with the open one: the typing channel's in {@link Mode#CHANNEL} (All with none), All without
     * the All tab is the typing channel's, Spy without spying All (or the typing channel's).
     */
    public ChatTabSpec active(@Nullable Channel typing, boolean allTab, boolean spy) {
        return switch (shown(mode, allTab, spy)) {
            case ALL -> ChatTabSpec.ALL;
            case SYSTEM -> ChatTabSpec.SYSTEM;
            case SPY -> ChatTabSpec.SPY;
            case CHANNEL -> typing != null ? ChatTabSpec.of(typing) : ChatTabSpec.ALL;
        };
    }

    /** The active tab's slot, by the state of the last {@link #refresh}. */
    public int activeSlot() {
        return slot(active(typing >= 0 ? Channel.of(typing) : null, allTab, spy));
    }

    // ---------------------------------------------------------------- the strip

    /**
     * The client's state now: {@code openBits} the channels open to the player and {@code hiddenBits} those hidden in
     * Settings → Chat (a bit per {@link Channel} ordinal), {@code typing} the channel typed into (ordinal; -1 none: not
     * an Eden server, no tabs), whether the player keeps the All tab, whether they spy. The stored mode is set right
     * first (as {@link #active} reads it), so it always names a tab the strip shows: turning the All tab back on later
     * never jumps the view back to All. The order is laid out again only when an input or the mode changed.
     *
     * @return whether the order was laid out again
     */
    public boolean refresh(int openBits, int hiddenBits, int typing, boolean allTab, boolean spy) {
        mode = shown(mode, allTab, spy);
        int mask = (1 << CHANNELS) - 1;
        openBits &= mask;
        hiddenBits &= mask;
        if (typing < 0 || typing >= CHANNELS) {
            typing = -1;
        }
        int packed = openBits | hiddenBits << HIDDEN_SHIFT | typing + 1 << TYPING_SHIFT | (allTab ? ALL_TAB_BIT : 0)
                | (spy ? SPY_BIT : 0) | mode.ordinal() << MODE_SHIFT;
        if (packed == key) {
            return false;
        }
        key = packed;
        open = openBits;
        hidden = hiddenBits;
        this.typing = typing;
        this.allTab = allTab;
        this.spy = spy;
        count = 0;
        if (typing < 0) {
            return true;
        }
        int active = activeSlot();
        for (int slot = 0; slot < SLOTS; slot++) {
            if (visible(slot, active)) {
                order[count++] = slot;
            }
        }
        return true;
    }

    private boolean visible(int slot, int active) {
        return switch (slot) {
            case ALL_SLOT -> allTab;
            case SYSTEM_SLOT -> true;
            case SPY_SLOT -> spy;
            default -> {
                int bit = 1 << slot - 1;
                yield (open & bit) != 0 && ((hidden & bit) == 0 || slot == active);
            }
        };
    }

    /** What the order was laid out for (the inputs and the mode, packed): a strip lays itself out again when it changes. */
    public int key() {
        return key;
    }

    /** How many tabs the strip shows (0: not an Eden server). */
    public int count() {
        return count;
    }

    /** The slot of the strip's {@code index}-th tab. */
    public int slotAt(int index) {
        return order[index];
    }

    /** Where the slot's tab is in the strip, or -1 when it isn't shown. */
    public int indexOf(int slot) {
        for (int i = 0; i < count; i++) {
            if (order[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The tab after {@code fromIndex} round the strip (before it with {@code back}), as Ctrl+Tab steps; from no tab
     * (-1) the first, or the last going back. -1 when there are no tabs.
     */
    public int step(int fromIndex, boolean back) {
        if (count == 0) {
            return -1;
        }
        if (fromIndex < 0 || fromIndex >= count) {
            return back ? count - 1 : 0;
        }
        return Math.floorMod(fromIndex + (back ? -1 : 1), count);
    }

    // ---------------------------------------------------------------- hiding

    /** Whether the player hid the slot's tab: a channel in Settings → Chat, All by turning its tab off. */
    public boolean hidden(int slot) {
        if (slot == ALL_SLOT) {
            return !allTab;
        }
        return slot > 0 && slot <= CHANNELS && (hidden & 1 << slot - 1) != 0;
    }

    /** All or a channel, shown and not hidden: what "Hide tab" may take away. */
    private boolean hideable(int slot) {
        return slot >= ALL_SLOT && slot <= CHANNELS && !hidden(slot) && indexOf(slot) >= 0;
    }

    /**
     * Whether "Hide tab" may hide the slot's tab: All or a channel the strip shows that isn't hidden already (a hidden
     * channel showing while it's active offers "Show tab" instead), and never the last of them — a strip of System
     * alone would leave nothing to switch to.
     */
    public boolean canHide(int slot) {
        if (!hideable(slot)) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (order[i] != slot && hideable(order[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * The mode once the slot's tab is hidden: unchanged unless it is the active tab — then All, or the typing channel's
     * tab when All is the one going or the player has no All tab (hiding a channel then moves the typing channel off
     * it, which the view follows).
     */
    public Mode afterHiding(int slot) {
        if (slot != activeSlot()) {
            return mode;
        }
        return slot == ALL_SLOT || !allTab ? Mode.CHANNEL : Mode.ALL;
    }

    /**
     * Whether "Show hidden tabs" offers the slot: All while its tab is off, a hidden channel while it is open to the
     * player (Party and Guild only as a member: unhiding another would show nothing).
     */
    public boolean showable(int slot) {
        if (slot == ALL_SLOT) {
            return !allTab;
        }
        return slot > 0 && slot <= CHANNELS && (hidden & open & 1 << slot - 1) != 0;
    }

    // ---------------------------------------------------------------- mention jumps

    /**
     * Where a jump to a mention goes when the view doesn't show it, its channel picked first: All when the player keeps
     * the All tab and All shows the line ({@code allShows}; as 1.2's default view after a pick), else the channel's own
     * tab when it is the one typed into ({@code typedInto}); null when no tab can show it — its channel closed under
     * it (the pick was refused), or hidden with no All tab — and the mention is done with.
     */
    @Nullable
    public static Mode revealMode(boolean allShows, boolean typedInto) {
        return allShows ? Mode.ALL : typedInto ? Mode.CHANNEL : null;
    }

    // ---------------------------------------------------------------- [tag] clicks

    /**
     * The channel a chat line's {@code [Local]} tag switches to — its click runs {@code "/ch local"}
     * ({@code ChatStyle.channelTag}) — or null for any other command.
     */
    @Nullable
    public static Channel tagChannel(String command) {
        if (!command.startsWith("/ch ")) {
            return null;
        }
        String id = command.substring(4);
        for (Channel channel : Channel.values()) {
            if (channel.name().toLowerCase(Locale.ROOT).equals(id)) {
                return channel;
            }
        }
        return null;
    }
}
