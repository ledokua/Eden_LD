package net.ledok.eden_ld.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Partymates' overhead nameplates render in cyan, from the synced party data
 * (each client only knows its own party, so the tint is per-viewer).
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {

    @ModifyVariable(
            method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;"
                    + "Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
            at = @At("HEAD"), argsOnly = true)
    private Component eden_ld$tintPartymateName(Component name, AbstractClientPlayer player,
            Component displayName, PoseStack poseStack, MultiBufferSource bufferSource,
            int packedLight, float partialTick) {
        // A guild tag the player chose to show goes first ("[TAG] Name").
        Component tag = net.ledok.eden_ld.client.hub.PlayerNames.guildTag(player.getUUID());
        Component tagged = tag.getString().isEmpty() ? name : Component.empty().append(tag).append(name);
        HubPayloads.PartyData data = HubState.partyData;
        var local = Minecraft.getInstance().player;
        if (data == null || !data.inParty() || local == null
                || player.getUUID().equals(local.getUUID())) {
            return tagged;
        }
        for (HubPayloads.PartyMember member : data.members()) {
            if (member.uuid().equals(player.getUUID())) {
                Component cyan = name.copy().withStyle(style -> style.withColor(ChatFormatting.AQUA));
                return tag.getString().isEmpty() ? cyan : Component.empty().append(tag).append(cyan);
            }
        }
        return tagged;
    }
}
