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

    public boolean seen() {
        return seen;
    }

    /** Mark as read, a mute, or nothing worth catching up on. */
    public void markSeen() {
        seen = true;
    }

    /** The message's lines at this width (wrapped again only when the width changes). */
    List<FormattedCharSequence> lines(Font font, int width) {
        if (width != wrappedWidth) {
            lines = ComponentRenderUtils.wrapComponents(message.content(), Math.max(1, width), font);
            wrappedWidth = width;
        }
        return lines;
    }

    /** Vanilla replaced the message (a deletion by the server): new text, wrapped again. */
    void replace(GuiMessage replacement) {
        message = replacement;
        wrappedWidth = -1;
    }
}
