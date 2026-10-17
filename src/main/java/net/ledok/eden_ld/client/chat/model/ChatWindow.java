package net.ledok.eden_ld.client.chat.model;

import org.jetbrains.annotations.Nullable;

/**
 * A chat window: the main chat, later moved and resized (phase 9) and perhaps detached chats (phase 12). Each has its
 * own view (ChatView). For now there is exactly one, where vanilla puts the chat.
 *
 * @param layout where it was moved to, or null for vanilla's place and size (Options → Chat Settings)
 */
public record ChatWindow(String id, @Nullable ChatLayout layout) {

    public static final ChatWindow MAIN = new ChatWindow("main", null);
}
