package net.ledok.eden_ld.combat;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * More Relics' Liandry's Torment scales with its wearer (owner, 2026-11-06): each second it deals
 * {@code combat.liandry_power_per_second} (default 10%) of the caster's arcane spell power per stack, as their magic
 * damage, instead of 3% of the target's max health from nobody. Spell Engine adds the torment with the caster as its
 * source, so Eden remembers, per tormented target, who it was and their spell power right then (the last caster to add
 * or stack it). Unknown caster (another way of getting it, or a restart in between): no damage. Its damage-taken bonus
 * (More Relics' effects config) is untouched.
 */
public final class LiandrysTorment {

    public static final ResourceLocation EFFECT = ResourceLocation.fromNamespaceAndPath("more_relics", "greater_liandrys_torment");

    private record Source(UUID caster, double power) {
    }

    /** Tormented target → who tormented them and with what power (server thread only). */
    private static final Map<LivingEntity, Source> SOURCES = new WeakHashMap<>();
    private static boolean logged;

    private LiandrysTorment() {
    }

    /** {@code LivingEntity.onEffectAdded/onEffectUpdated} on the server: remembers the caster of a new or stacked torment. */
    public static void applied(LivingEntity target, MobEffectInstance effect, @Nullable Entity source, boolean added) {
        if (!effect.getEffect().is(EFFECT)) {
            return;
        }
        if (source instanceof LivingEntity caster) {
            SOURCES.put(target, new Source(caster.getUUID(), spellPower(caster)));
        } else if (added) {
            SOURCES.remove(target);
        }
    }

    /** The torment's tick (every second): true keeps the effect, as More Relics does. */
    public static boolean tick(LivingEntity target, int amplifier) {
        if (!(target.level() instanceof ServerLevel level)) {
            return true;
        }
        Source source = SOURCES.get(target);
        double perSecond = EdenLD.CONFIG.combat.liandry_power_per_second;
        if (source == null || perSecond <= 0 || source.power() <= 0) {
            return true;
        }
        if (!logged) {
            logged = true;
            EdenLD.LOGGER.info("[Relics] Liandry's Torment deals {}% of the caster's {} per stack per second.",
                    Math.round(perSecond * 100), EdenLD.CONFIG.combat.liandry_spell_school);
        }
        float damage = (float) (source.power() * perSecond * (amplifier + 1));
        Entity caster = level.getEntity(source.caster());
        DamageSource damageSource = caster != null
                ? level.damageSources().indirectMagic(caster, caster)
                : level.damageSources().magic();
        target.hurt(damageSource, damage);
        return true;
    }

    private static double spellPower(LivingEntity caster) {
        ResourceLocation id = ResourceLocation.tryParse(EdenLD.CONFIG.combat.liandry_spell_school);
        Optional<Holder.Reference<Attribute>> attribute = id == null ? Optional.empty() : BuiltInRegistries.ATTRIBUTE.getHolder(id);
        if (attribute.isEmpty() || caster.getAttributes().getInstance(attribute.get()) == null) {
            return 0;
        }
        return caster.getAttributeValue(attribute.get());
    }
}
