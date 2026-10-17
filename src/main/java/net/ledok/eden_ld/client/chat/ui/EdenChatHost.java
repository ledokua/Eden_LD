package net.ledok.eden_ld.client.chat.ui;

import org.jetbrains.annotations.Nullable;

/**
 * A chat screen's new look, for code outside ChatScreenMixin (which implements this on every {@code ChatScreen}):
 * InBedChatScreenMixin hands the bed chat's keys to its canvas first.
 */
public interface EdenChatHost {

    /** The new look's host while this screen is the one showing, else null (the old look, no frame drawn yet). */
    @Nullable
    EdenChatUi eden_ld$host();
}
