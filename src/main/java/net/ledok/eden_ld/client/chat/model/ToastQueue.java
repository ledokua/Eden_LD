package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * The closed chat's toasts ({@code docs/PLAN-chat-v2.md}, drawn from phase 6): one per channel with lines the player
 * hasn't seen and isn't looking at. A toast is a card while lines keep coming ({@code hold} after the last one), folds
 * into a pill, and stays a pill until the channel is read. What raises one follows the channel's notifications: All
 * messages — every line (only mentions while muted), Only mentions — mentions, Nothing — nothing. Times are
 * {@code System.currentTimeMillis()}, handed in.
 */
public final class ToastQueue {

    /** At most this many toasts; a new channel pushes out the oldest. */
    public static final int MAX = 4;
    /** How long a card takes to fold into its pill. */
    public static final long FOLD_MS = 200;

    public enum Stage { CARD, FOLDING, PILL }

    /** One channel's toast: its newest line, how many came and how many mention the player. */
    public static final class Toast {

        private final Channel channel;
        private final long shownAt;
        private ChatEntry last;
        private int lines;
        private int mentions;
        private long lastAt;

        Toast(Channel channel, long now) {
            this.channel = channel;
            this.shownAt = now;
        }

        public Channel channel() {
            return channel;
        }

        /** When it first showed (its slide-in starts here). */
        public long shownAt() {
            return shownAt;
        }

        public ChatEntry last() {
            return last;
        }

        public int lines() {
            return lines;
        }

        public int mentions() {
            return mentions;
        }

        /** A card for {@code holdMs} after its last line, then folding, then a pill. */
        public Stage stage(long now, long holdMs) {
            long since = now - lastAt;
            return since < holdMs ? Stage.CARD : since < holdMs + FOLD_MS ? Stage.FOLDING : Stage.PILL;
        }
    }

    /** Oldest first. */
    private final List<Toast> toasts = new ArrayList<>();

    /**
     * A line came on a channel the player isn't looking at: a toast for it, or the channel's toast updated (a card
     * again). False when its notifications don't call for one.
     */
    public boolean offer(ChatEntry entry, Channel channel, ChannelNotify notify, boolean muted, boolean mention,
                         long now) {
        boolean wanted = notify != ChannelNotify.NOTHING
                && (mention || !muted && notify == ChannelNotify.ALL_MESSAGES);
        if (!wanted) {
            return false;
        }
        Toast toast = find(channel);
        if (toast == null) {
            toast = new Toast(channel, now);
            toasts.add(toast);
            if (toasts.size() > MAX) {
                toasts.remove(0);
            }
        }
        toast.last = entry;
        toast.lines++;
        if (mention) {
            toast.mentions++;
        }
        toast.lastAt = now;
        return true;
    }

    /** The channel was read: its toast goes. */
    public void read(Channel channel) {
        toasts.removeIf(toast -> toast.channel == channel);
    }

    /** Keeps only the toasts of channels {@code wanted} still says yes to (unread, notifications not Nothing). */
    public void retain(Predicate<Channel> wanted) {
        toasts.removeIf(toast -> !wanted.test(toast.channel));
    }

    public void clear() {
        toasts.clear();
    }

    public boolean isEmpty() {
        return toasts.isEmpty();
    }

    /** Oldest first. */
    public List<Toast> toasts() {
        return Collections.unmodifiableList(toasts);
    }

    private Toast find(Channel channel) {
        for (Toast toast : toasts) {
            if (toast.channel == channel) {
                return toast;
            }
        }
        return null;
    }
}
