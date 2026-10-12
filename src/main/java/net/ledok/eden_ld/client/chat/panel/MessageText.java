package net.ledok.eden_ld.client.chat.panel;

import net.ledok.eden_ld.client.chat.EmojiFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Reading a chat message for the message actions: who wrote it (for Reply's @name) and what they wrote (for Copy) —
 * the text after the sender part ({@code [Global] [TAG] Name » }), with the server's emojis back as {@code :name:},
 * [GIF] and [Sticker] links back as their addresses, and spoilers as their text.
 */
public final class MessageText {

    private static final String LINE_MARKER = "eden_ld:line:";
    private static final String SPOILER_BAR = "▌";

    private MessageText() {
    }

    /** The writer's name: from the tab list while they're online, else from their name in the line. */
    @Nullable
    public static String senderName(ChatEntry entry) {
        UUID sender = entry.sender();
        if (sender == null) {
            return null;
        }
        PlayerInfo info = Minecraft.getInstance().getConnection() == null ? null
                : Minecraft.getInstance().getConnection().getPlayerInfo(sender);
        if (info != null) {
            return info.getProfile().getName();
        }
        // The vanilla display name in the line: its hover card names the player, its insertion is the name.
        String[] found = new String[1];
        entry.message().content().visit((style, text) -> {
            HoverEvent hover = style.getHoverEvent();
            HoverEvent.EntityTooltipInfo entity = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_ENTITY);
            if (entity != null && sender.equals(entity.id) && style.getInsertion() != null) {
                found[0] = style.getInsertion();
                return Optional.of(Boolean.TRUE);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    /** What the message says, for the clipboard (everything after the sender part; the whole line without one). */
    public static String copyText(ChatEntry entry) {
        Component content = entry.message().content();
        Component senderPart = senderPart(content, 0);
        int skip = senderPart == null ? 0 : senderPart.getString().length();
        StringBuilder out = new StringBuilder();
        int[] offset = {0};
        String[] lastUrl = {null};
        boolean[] inSpoiler = {false};
        content.visit((style, text) -> {
            int start = offset[0];
            offset[0] += text.length();
            if (offset[0] <= skip) {
                return Optional.empty();
            }
            String part = start < skip ? text.substring(skip - start) : text;
            if (part.isEmpty()) {
                return Optional.empty();
            }
            ClickEvent click = style.getClickEvent();
            if (click != null && click.getAction() == ClickEvent.Action.OPEN_URL && !part.contains(click.getValue())) {
                // A short label ([GIF], [Sticker: name], a named link): its address instead, once.
                if (!click.getValue().equals(lastUrl[0])) {
                    out.append(click.getValue());
                    lastUrl[0] = click.getValue();
                }
                return Optional.empty();
            }
            lastUrl[0] = null;
            HoverEvent hover = style.getHoverEvent();
            Component hidden = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_TEXT);
            if (hidden != null && part.replace(SPOILER_BAR, "").isEmpty()) {
                // A spoiler's bars: its text, once.
                if (!inSpoiler[0]) {
                    out.append(hidden.getString());
                    inSpoiler[0] = true;
                }
                return Optional.empty();
            }
            inSpoiler[0] = false;
            part.codePoints().forEach(codepoint -> {
                EmojiFont.Entry emoji = codepoint >= 0xF0000 ? EmojiFont.byCodepoint(codepoint) : null;
                if (emoji != null) {
                    out.append(':').append(emoji.name()).append(':');
                } else {
                    out.appendCodePoint(codepoint);
                }
            });
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString().strip();
    }

    /** The part of the line before the message: the piece that starts with Eden's line marker. */
    @Nullable
    private static Component senderPart(Component component, int depth) {
        if (depth > 8) {
            return null;
        }
        if (!component.getSiblings().isEmpty()) {
            Component first = component.getSiblings().get(0);
            String insertion = first.getStyle().getInsertion();
            if (insertion != null && insertion.startsWith(LINE_MARKER) && first.getString().isEmpty()) {
                return component;
            }
        }
        if (component.getContents() instanceof TranslatableContents translatable) {
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component part) {
                    Component found = senderPart(part, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            Component found = senderPart(sibling, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
