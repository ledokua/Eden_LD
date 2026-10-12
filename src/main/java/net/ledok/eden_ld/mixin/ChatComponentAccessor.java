package net.ledok.eden_ld.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The chat's history and its window lines (ChatActivity: pruning, jumping to a mention). */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("allMessages")
    List<GuiMessage> eden_ld$allMessages();

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> eden_ld$trimmedMessages();
}
