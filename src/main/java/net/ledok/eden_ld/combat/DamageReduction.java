package net.ledok.eden_ld.combat;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.config.EdenConfig;
import net.ledok.eden_ld.world.Hazards;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;

/**
 * Damage reduction ({@code Balance/COMBAT-REWORK.md}): {@code eden_ld:damage_reduction}, in percent, lowers every hit a
 * player takes, spells included. The RPG Series armor sets carry it in their equipment configs instead of armor points;
 * any armor points a player still has work as before on top (physical only). Capped ({@code combat.json}
 * {@code damage_reduction_cap}), only part of it against other players ({@code damage_reduction_pvp_share}), and none
 * against hazards (they scale with health, {@link Hazards}) or the types in {@code damage_reduction_exempt}.
 */
public final class DamageReduction {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "damage_reduction");
    /** Registered with vanilla's attributes ({@code AttributesRegisterMixin}), before the class mods read their configs. */
    public static Holder<Attribute> ATTRIBUTE;

    private DamageReduction() {
    }

    /** From vanilla's {@code Attributes} static init, so it exists before any mod builds its armor. */
    public static void register() {
        if (ATTRIBUTE == null) {
            ATTRIBUTE = Registry.registerForHolder(BuiltInRegistries.ATTRIBUTE, ID,
                    new RangedAttribute("attribute.name.eden_ld.damage_reduction", 0.0, 0.0, 100.0).setSyncable(true));
        }
    }

    /** What reaches {@code player} of {@code amount} (after armor, effects and enchantments). */
    public static float apply(Player player, DamageSource source, float amount) {
        if (ATTRIBUTE == null || amount <= 0 || !player.getAttributes().hasAttribute(ATTRIBUTE)) {
            return amount;
        }
        double percent = player.getAttributeValue(ATTRIBUTE);
        if (percent <= 0 || exempt(source)) {
            return amount;
        }
        EdenConfig.Combat config = EdenLD.CONFIG.combat;
        percent = Math.min(percent, config.damage_reduction_cap);
        if (source.getEntity() instanceof Player attacker && attacker != player) {
            percent *= config.damage_reduction_pvp_share;
        }
        return (float) (amount * (1.0 - percent / 100.0));
    }

    private static boolean exempt(DamageSource source) {
        if (Hazards.ignoresArmor(source)) {
            return true;
        }
        ResourceKey<DamageType> type = source.typeHolder().unwrapKey().orElse(null);
        return type != null && EdenLD.CONFIG.combat.damage_reduction_exempt.contains(type.location().toString());
    }
}
