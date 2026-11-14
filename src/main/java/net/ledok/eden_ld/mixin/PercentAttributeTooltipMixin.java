package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.combat.DamageReduction;
import net.ledok.eden_ld.combat.GearStats;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Eden's percent-point attributes ({@code eden_ld:damage_reduction}, {@code eden_ld:max_health_percent}) are flat
 * ADD_VALUE numbers whose unit is a percent, so vanilla's tooltip line read "+8 Damage Reduction". They now use the
 * percent line ("+8% Damage Reduction", like "+13% Ranged Damage"): the same translation keys vanilla uses for
 * multiplied modifiers, with the value as it is (not times 100), so the color and every language follow.
 */
@Mixin(ItemStack.class)
abstract class PercentAttributeTooltipMixin {

    @WrapOperation(method = "addModifierTooltip", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent eden_ld$percentLine(String key, Object[] args, Operation<MutableComponent> original,
                                                 @Local(argsOnly = true) Holder<Attribute> attribute,
                                                 @Local(argsOnly = true) AttributeModifier modifier) {
        if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE && isPercentPoints(attribute)
                && (key.equals("attribute.modifier.plus.0") || key.equals("attribute.modifier.take.0"))) {
            key = key.substring(0, key.length() - 1) + "1";
        }
        return original.call(key, args);
    }

    private static boolean isPercentPoints(Holder<Attribute> attribute) {
        return attribute == DamageReduction.ATTRIBUTE || attribute == GearStats.MAX_HEALTH_PERCENT
                || attribute.is(DamageReduction.ID) || attribute.is(GearStats.MAX_HEALTH_PERCENT_ID);
    }
}
