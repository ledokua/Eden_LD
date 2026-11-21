package net.ledok.eden_ld.compat;

/**
 * Whether a projectile is Spell Engine's {@code net.spell_engine.entity.SpellProjectile} (or a subclass), by name, so
 * Eden needs no Spell Engine classes at runtime. Answered once per class.
 * <p>
 * Review finding 98: Eden's projectile mixin made every projectile fly through friends, so a bard's Magical Ballad, a
 * priest's Penance or a water wizard's waterball never reached the partymate it was meant to buff or heal, while Spell
 * Engine highlighted that friend as a target. Spell projectiles don't override {@code canHitEntity}, and Spell Engine
 * judges friend or foe itself once they collide (its intent gate, with {@code projectiles_pass_thru_irrelevant_targets}
 * on by default and on live); so for them Eden only keeps ignored pairs apart.
 */
public final class SpellProjectiles {

    private static final String SPELL_PROJECTILE = "net.spell_engine.entity.SpellProjectile";

    private static final ClassValue<Boolean> IS_SPELL = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                if (SPELL_PROJECTILE.equals(c.getName())) {
                    return true;
                }
            }
            return false;
        }
    };

    private SpellProjectiles() {
    }

    public static boolean is(Object projectile) {
        return projectile != null && IS_SPELL.get(projectile.getClass());
    }
}
