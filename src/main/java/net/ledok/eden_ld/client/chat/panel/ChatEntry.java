package net.ledok.eden_ld.client.chat.panel;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * One message in Eden's chat ({@code docs/PLAN-custom-chat.md}): vanilla's message as it arrived, what its marker
 * says (channel, sender, kind — read once), whether it mentions the player, whether the open chat has shown it, and
 * its lines wrapped for the width the chat had when last drawn.
 */
public final class ChatEntry {

    GuiMessage message;
    @Nullable
    final ChatMarker.Mark mark;
    @Nullable
    final Channel channel;
    /** The player wrote it (their own lines always show and never count as unseen). */
    final boolean own;
    final boolean mention;
    /** The open chat has drawn it (or it never needed seeing: own, ignored, muted). */
    boolean seen;
    /** How many times it came in a row (spam protection: one line, "×N"). */
    int count = 1;
    /** When it came (the last time, for a repeat), on this computer's clock: the hover shows it. */
    long time = System.currentTimeMillis();

    private List<FormattedCharSequence> lines = List.of();
    private int wrappedWidth = -1;

    ChatEntry(GuiMessage message, @Nullable ChatMarker.Mark mark, @Nullable Channel channel, boolean own,
              boolean mention) {
        this.message = message;
        this.mark = mark;
        this.channel = channel;
        this.own = own;
        this.mention = mention;
    }

    public GuiMessage message() {
        return message;
    }

    @Nullable
    public Channel channel() {
        return channel;
    }

    @Nullable
    public UUID sender() {
        return mark == null ? null : mark.sender();
    }

    public boolean mention() {
        return mention;
    }

    public int count() {
        return count;
    }

    public long time() {
        return time;
    }

    /** The line's own player (null for the server's lines and other mods'). */
    public boolean own() {
        return own;
    }

    public boolean seen() {
        return seen;
    }

    /** Mark as read, a mute, or nothing worth catching up on. */
    public void markSeen() {
        seen = true;
    }

    /** The message's lines at this width (wrapped again only when the width or the count changes). */
    List<FormattedCharSequence> lines(Font font, int width) {
        if (width != wrappedWidth) {
            net.minecraft.network.chat.Component text = count < 2 ? message.content()
                    : net.minecraft.network.chat.Component.empty().append(message.content())
                            .append(net.minecraft.network.chat.Component.literal(" ×" + count)
                                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            lines = ComponentRenderUtils.wrapComponents(text, Math.max(1, width), font);
            wrappedWidth = width;
        }
        return lines;
    }

    /** The same message again: one more on the count, and it's new again (the HUD shows it, unseen). */
    void repeat(GuiMessage again) {
        message = again;
        count++;
        time = System.currentTimeMillis();
        wrappedWidth = -1;
        if (!own) {
            seen = false;
        }
    }

    /** Vanilla replaced the message (a deletion by the server): new text, wrapped again. */
    void replace(GuiMessage replacement) {
        message = replacement;
        wrappedWidth = -1;
    }
}
