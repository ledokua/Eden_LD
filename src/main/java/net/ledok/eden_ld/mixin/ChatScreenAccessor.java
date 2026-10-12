package net.ledok.eden_ld.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Typing into the chat input from outside it (Reply puts "@Name " in). */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {

    @Invoker("insertText")
    void eden_ld$insertText(String text, boolean override);
}
