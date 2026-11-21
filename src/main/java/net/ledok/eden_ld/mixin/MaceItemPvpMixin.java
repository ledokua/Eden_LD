package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.combat.PvpIgnore;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.MaceItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Predicate;

/**
 * A mace smash doesn't push the players (and pets) its wielder ignores (docs/PLAN-pvp-toggle.md: no knockback for
 * ignored pairs; review finding 57): the area push around the smashed target skips them, as it already skips the
 * wielder's own team and tamed animals. Friends are still pushed; only their damage is refused.
 */
@Mixin(MaceItem.class)
public abstract class MaceItemPvpMixin {

    @ModifyReturnValue(method = "knockbackPredicate", at = @At("RETURN"))
    private static Predicate<LivingEntity> eden_ld$skipIgnored(Predicate<LivingEntity> original,
                                                               @Local(argsOnly = true) Player player) {
        return original.and(target -> !PvpIgnore.ignores(player, target));
    }
}
