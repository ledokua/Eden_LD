package net.ledok.eden_ld.mana;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Regenerating fluid: heals living entities standing in it.
 * Heal amount and interval come from the {@code liquid_mana} config section.
 */
public class LiquidManaBlock extends LiquidBlock {
    // Weak keys: entries disappear with the entity, so this never leaks
    // (the old implementation kept UUIDs forever). Server thread only.
    private final Map<LivingEntity, Long> lastHealTick = new WeakHashMap<>();

    public LiquidManaBlock(FlowingFluid fluid, Properties properties) {
        super(fluid, properties);
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!level.isClientSide && entity instanceof LivingEntity livingEntity && livingEntity.isAlive()) {
            long now = level.getGameTime();
            int interval = Math.max(1, EdenLD.CONFIG.liquid_mana.heal_interval_ticks);
            Long last = lastHealTick.get(livingEntity);
            if (last == null || now - last >= interval) {
                lastHealTick.put(livingEntity, now);
                float healAmount = (float) (livingEntity.getMaxHealth() * EdenLD.CONFIG.liquid_mana.heal_percent / 100.0);
                if (healAmount > 0) {
                    livingEntity.heal(healAmount);
                }
            }
        }
        super.entityInside(state, level, pos, entity);
    }
}
