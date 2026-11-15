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
 * Private conversations (1.3.94) follow, a tab each ({@link Mode#PM}: the active one is {@link #pm()}): up to
 * {@link #PM_SLOTS} of them, in the order the client hands them in ({@link #refresh(int, int, int, boolean, boolean,
 * ChatTabSpec[], int, int)}), from slot {@link #SLOTS} on.
 * <p>
 * Plain code: the client hands in its state ({@link #refresh}), and the order is laid out again only when that state
 * or the mode changed. Nothing here allocates after construction but {@link #tagChannel}, which runs on a click.
 */
public final class ChatTabs {

    public enum Mode { CHANNEL, ALL, SYSTEM, SPY, PM }

    /** The fixed slots: 0 All, 1..4 Global/Local/Party/Guild (1 + ordinal), 5 System, 6 Spy. */
    public static final int SLOTS = 7;
    /** Private conversations' tabs at most, in the slots after the fixed ones. */
    public static final int PM_SLOTS = 8;
    /** Every slot, the conversations' included. */
    public static final int TOTAL = SLOTS + PM_SLOTS;
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
    /** The conversation shown in {@link Mode#PM}. */
    @Nullable
    private String pm;
    /** The conversations' tabs, in strip order, and the client's count of their changes. */
    private final ChatTabSpec[] pms = new ChatTabSpec[PM_SLOTS];
    private int pmCount;
    private int pmEpoch = -1;
    /** The visible tabs' slots, in strip order. */
    private final int[] order = new int[TOTAL];
    private int count;
    /** The inputs (and the mode) the order was laid out for; -1 before the first {@link #refresh}. */
    private int key = -1;
    /** Bumped on every new layout: what {@link #key()} gives the strip. */
    private int layouts;
    private int open;
    private int hidden;
    private int typing = -1;
    private boolean allTab = true;
    private boolean spy;

    // ---------------------------------------------------------------- slots

    /** A fixed tab's slot; -1 for a conversation's (their slots are the strip's own: {@link #slotOf}). */
    public static int slot(ChatTabSpec tab) {
        return switch (tab.kind()) {
            case ALL -> ALL_SLOT;
            case CHANNEL -> 1 + java.util.Objects.requireNonNull(tab.channel()).ordinal();
            case SYSTEM -> SYSTEM_SLOT;
            case SPY -> SPY_SLOT;
            case PM -> -1;
        };
    }

    /** The tab in a fixed slot. */
    public static ChatTabSpec spec(int slot) {
        return SPECS[slot];
    }

    /** Any tab's slot, a conversation's included; -1 for a conversation the strip has no tab for. */
    public int slotOf(ChatTabSpec tab) {
        if (tab.kind() != ChatTabSpec.Kind.PM) {
            return slot(tab);
        }
        for (int i = 0; i < pmCount; i++) {
            if (pms[i].conversation() != null && pms[i].conversation().equals(tab.conversation())) {
                return SLOTS + i;
            }
        }
        return -1;
    }

    /** The tab in any slot, a conversation's included (as of the last {@link #refresh}). */
    public ChatTabSpec specAt(int slot) {
        return slot < SLOTS ? SPECS[slot] : pms[slot - SLOTS];
    }

    /** The mode that shows the slot's tab. */
    public static Mode modeOf(int slot) {
        if (slot >= SLOTS) {
            return Mode.PM;
        }
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

    /** The view switches to a conversation's tab ({@link Mode#PM}). */
    public void pm(String conversation) {
        if (mode != Mode.PM || !conversation.equals(pm)) {
            mode = Mode.PM;
            pm = conversation;
            key = -1;
        }
    }

    /** The conversation the view shows in {@link Mode#PM} (null in the other modes). */
    @Nullable
    public String pm() {
        return mode == Mode.PM ? pm : null;
    }

    /** The active conversation's tab among those handed in, or null. */
    @Nullable
    private ChatTabSpec pmTab() {
        if (pm == null) {
            return null;
        }
        for (int i = 0; i < pmCount; i++) {
            if (pm.equals(pms[i].conversation())) {
                return pms[i];
            }
        }
        return null;
    }

    /**
     * The mode as it reads with these inputs: All without its tab is the typing channel's, Spy without spying All, a
     * conversation without its tab (closed, left) All.
     */
    private Mode shown(Mode mode, boolean allTab, boolean spy) {
        if (mode == Mode.PM && pmTab() == null) {
            mode = allTab ? Mode.ALL : Mode.CHANNEL;
        }
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
            case PM -> java.util.Objects.requireNonNull(pmTab());
            case CHANNEL -> typing != null ? ChatTabSpec.of(typing) : ChatTabSpec.ALL;
        };
    }

    /** The active tab's slot, by the state of the last {@link #refresh}. */
    public int activeSlot() {
        return slotOf(active(typing >= 0 ? Channel.of(typing) : null, allTab, spy));
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
        return refresh(openBits, hiddenBits, typing, allTab, spy, pms, pmCount, pmEpoch);
    }

    /**
     * As {@link #refresh(int, int, int, boolean, boolean)}, with the conversations' tabs: {@code pmCount} of
     * {@code pmTabs} (at most {@link #PM_SLOTS} are kept), in strip order; {@code pmEpoch} changes whenever they do.
     */
    public boolean refresh(int openBits, int hiddenBits, int typing, boolean allTab, boolean spy,
                           ChatTabSpec[] pmTabs, int pmCount, int pmEpoch) {
        if (pmEpoch != this.pmEpoch) {
            int n = Math.min(Math.max(0, pmCount), PM_SLOTS);
            if (pmTabs != pms) {
                System.arraycopy(pmTabs, 0, pms, 0, n);
            }
            java.util.Arrays.fill(pms, n, PM_SLOTS, null);
            this.pmCount = n;
            this.pmEpoch = pmEpoch;
            key = -1;
        }
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
        layouts++;
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
        for (int slot = 0; slot < SLOTS + this.pmCount; slot++) {
            if (visible(slot, active)) {
                order[count++] = slot;
            }
        }
        return true;
    }

    private boolean visible(int slot, int active) {
        if (slot >= SLOTS) {
            return true;
        }
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

    /** Changes whenever the order is laid out again (the inputs, the mode or the conversations changed): a strip lays itself out again then. */
    public int key() {
        return layouts;
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
