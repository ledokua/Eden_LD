package net.ledok.eden_ld.mixin;

import net.minecraft.client.gui.components.CommandSuggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Tab after "@" in chat completes a player's name as a mention ("@Le" → "@LeDok"): chat's name
 * completion also offers every name with an "@" in front (plain names still complete as before).
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {

    @ModifyVariable(method = "updateCommandInfo", at = @At(value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/client/multiplayer/ClientSuggestionProvider;getCustomTabSugggestions()Ljava/util/Collection;"))
    private Collection<String> eden_ld$mentions(Collection<String> names) {
        List<String> withMentions = new ArrayList<>(names.size() * 2);
        withMentions.addAll(names);
        for (String name : names) {
            withMentions.add("@" + name);
        }
        return withMentions;
    }
}
