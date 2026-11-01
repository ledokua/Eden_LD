package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.compat.EmiReloadFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EMI Loot (0.7.9) gets the server's loot tables as a stream of packets after joining; when they keep coming while EMI
 * bakes, its chest loot fails ("Recipes completely skipped!") and nothing bakes them again. Each packet is noted for
 * {@link EmiReloadFix}. Nothing happens without EMI Loot ({@code @Pseudo}, {@code require = 0}).
 */
@Pseudo
@Mixin(targets = "fzzyhmstrs.emi_loot.client.ClientLootTablesReceiver", remap = false)
public abstract class EmiLootReceiverMixin {

    @Inject(method = {"receiveChestSender", "receiveBlockSender", "receiveMobSender", "receiveGameplaySender",
            "receiveArchaeologySender"}, at = @At("HEAD"), remap = false, require = 0)
    private static void eden_ld$lootArrived(CallbackInfo ci) {
        EmiReloadFix.lootArrived();
    }
}
