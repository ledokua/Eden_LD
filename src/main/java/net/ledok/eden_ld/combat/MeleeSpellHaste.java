package net.ledok.eden_ld.combat;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.config.EdenConfig;

/** The melee schools' spell haste from attack speed, capped ({@code combat.json} {@code melee_spell_haste_*}). */
public final class MeleeSpellHaste {

    private MeleeSpellHaste() {
    }

    /** {@code haste} (attack speed multipliers − 1) after the soft and hard caps; slowing (below 0) is left alone. */
    public static double cap(double haste) {
        EdenConfig.Combat config = EdenLD.CONFIG == null ? null : EdenLD.CONFIG.combat;
        if (config == null || haste <= config.melee_spell_haste_soft_cap) {
            return haste;
        }
        double capped = config.melee_spell_haste_soft_cap
                + (haste - config.melee_spell_haste_soft_cap) * config.melee_spell_haste_share_above;
        return Math.min(capped, config.melee_spell_haste_hard_cap);
    }
}
