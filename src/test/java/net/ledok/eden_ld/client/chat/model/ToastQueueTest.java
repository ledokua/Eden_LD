package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The queue only keeps the entries it's given: these tests hand it none (null). */
class ToastQueueTest {

    private static final long HOLD = 4000;

    @Test
    void notificationsDecideWhatRaisesAToast() {
        ToastQueue queue = new ToastQueue();
        assertFalse(queue.offer(null, Channel.GLOBAL, ChannelNotify.NOTHING, false, true, 0));
        assertFalse(queue.offer(null, Channel.GLOBAL, ChannelNotify.ONLY_MENTIONS, false, false, 0));
        assertFalse(queue.offer(null, Channel.GLOBAL, ChannelNotify.ALL_MESSAGES, true, false, 0));
        assertTrue(queue.isEmpty());
        assertTrue(queue.offer(null, Channel.GLOBAL, ChannelNotify.ONLY_MENTIONS, false, true, 0));
        assertTrue(queue.offer(null, Channel.LOCAL, ChannelNotify.ALL_MESSAGES, false, false, 0));
        assertTrue(queue.offer(null, Channel.PARTY, ChannelNotify.ALL_MESSAGES, true, true, 0));
        assertEquals(3, queue.toasts().size());
    }

    @Test
    void oneToastPerChannelCountingItsLines() {
        ToastQueue queue = new ToastQueue();
        queue.offer(null, Channel.GUILD, ChannelNotify.ALL_MESSAGES, false, false, 0);
        queue.offer(null, Channel.GUILD, ChannelNotify.ALL_MESSAGES, false, true, 10);
        queue.offer(null, Channel.GUILD, ChannelNotify.ALL_MESSAGES, false, false, 20);
        assertEquals(1, queue.toasts().size());
        ToastQueue.Toast toast = queue.toasts().get(0);
        assertEquals(3, toast.lines());
        assertEquals(1, toast.mentions());
        assertEquals(0, toast.shownAt());
    }

    @Test
    void aFifthChannelPushesOutTheOldest() {
        ToastQueue queue = new ToastQueue();
        Channel[] channels = Channel.values();
        for (int i = 0; i < channels.length; i++) {
            queue.offer(null, channels[i], ChannelNotify.ALL_MESSAGES, false, false, i);
        }
        assertEquals(Math.min(channels.length, ToastQueue.MAX), queue.toasts().size());
        // Four channels fit; a channel already shown never pushes anything out.
        queue.offer(null, channels[0], ChannelNotify.ALL_MESSAGES, false, false, 100);
        assertEquals(Math.min(channels.length, ToastQueue.MAX), queue.toasts().size());
    }

    @Test
    void aCardFoldsIntoAPillAndANewLineMakesItACardAgain() {
        ToastQueue queue = new ToastQueue();
        queue.offer(null, Channel.LOCAL, ChannelNotify.ALL_MESSAGES, false, false, 1000);
        ToastQueue.Toast toast = queue.toasts().get(0);
        assertEquals(ToastQueue.Stage.CARD, toast.stage(1000, HOLD));
        assertEquals(ToastQueue.Stage.CARD, toast.stage(1000 + HOLD - 1, HOLD));
        assertEquals(ToastQueue.Stage.FOLDING, toast.stage(1000 + HOLD, HOLD));
        assertEquals(ToastQueue.Stage.FOLDING, toast.stage(1000 + HOLD + ToastQueue.FOLD_MS - 1, HOLD));
        assertEquals(ToastQueue.Stage.PILL, toast.stage(1000 + HOLD + ToastQueue.FOLD_MS, HOLD));
        assertEquals(ToastQueue.Stage.PILL, toast.stage(1000 + 60_000, HOLD));
        queue.offer(null, Channel.LOCAL, ChannelNotify.ALL_MESSAGES, false, false, 70_000);
        assertEquals(ToastQueue.Stage.CARD, toast.stage(70_001, HOLD));
    }

    @Test
    void readingAChannelDropsItsToast() {
        ToastQueue queue = new ToastQueue();
        queue.offer(null, Channel.LOCAL, ChannelNotify.ALL_MESSAGES, false, false, 0);
        queue.offer(null, Channel.GUILD, ChannelNotify.ALL_MESSAGES, false, false, 0);
        queue.read(Channel.LOCAL);
        assertEquals(1, queue.toasts().size());
        assertEquals(Channel.GUILD, queue.toasts().get(0).channel());
        queue.retain(channel -> channel != Channel.GUILD);
        assertTrue(queue.isEmpty());
        queue.offer(null, Channel.LOCAL, ChannelNotify.ALL_MESSAGES, false, false, 0);
        queue.clear();
        assertTrue(queue.isEmpty());
    }
}
