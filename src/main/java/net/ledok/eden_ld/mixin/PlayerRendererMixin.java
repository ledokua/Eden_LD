package net.ledok.eden_ld.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.ledok.eden_ld.client.HubGlyphs;
import net.ledok.eden_ld.client.hub.ClientPvp;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Overhead nameplates: the guild tag a player chose to show goes first, partymates' names render in cyan from the
 * synced party data (each client only knows its own party, so the tint is per-viewer), and players whose PvP is on
 * where they stand (docs/PLAN-pvp-toggle.md) get the Duels tab's crossed swords after the name — a font glyph
 * ({@link HubGlyphs#PVP}), so it's drawn with the name in world space, see-through and all.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {

    @Unique
    private static final Component PVP_ICON = Component.literal(" " + HubGlyphs.PVP_TEXT);

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
        boolean tagged = !tag.getString().isEmpty();
        Component shown = eden_ld$partymate(player) ? name.copy().withStyle(style -> style.withColor(ChatFormatting.AQUA))
                : name;
        boolean pvp = ClientPvp.showsIcon(player.getUUID());
        if (!tagged && !pvp) {
            return shown;
        }
        MutableComponent line = Component.empty();
        if (tagged) {
            line.append(tag);
        }
        line.append(shown);
        if (pvp) {
            line.append(PVP_ICON);
        }
        return line;
    }

    /** In the viewer's own party (never the viewer). */
    @Unique
    private static boolean eden_ld$partymate(AbstractClientPlayer player) {
        HubPayloads.PartyData data = HubState.partyData;
        var local = Minecraft.getInstance().player;
        if (data == null || !data.inParty() || local == null || player.getUUID().equals(local.getUUID())) {
            return false;
        }
        for (HubPayloads.PartyMember member : data.members()) {
            if (member.uuid().equals(player.getUUID())) {
                return true;
            }
        }
        return false;
    }
}
