package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.ledok.eden_ld.combat.PvpIgnore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Remembers who is using a bed or a respawn anchor while the use runs: one that blows up (a bed outside the Overworld,
 * an anchor outside the Nether) deals damage with no attacker, so without this the player who set it off could kill
 * players they can't hurt, in any PvP mode (review finding 58). {@link PvpIgnore} then refuses that blast's damage and
 * knockback for those players; the damage itself isn't re-attributed, so the user still takes their own blast.
 */
@Mixin({BedBlock.class, RespawnAnchorBlock.class})
public abstract class RespawnBlockPvpMixin {

    @WrapMethod(method = "useWithoutItem")
    private InteractionResult eden_ld$rememberUser(BlockState state, Level level, BlockPos pos, Player player,
                                                   BlockHitResult hit, Operation<InteractionResult> original) {
        if (level.isClientSide()) {
            return original.call(state, level, pos, player, hit);
        }
        return PvpIgnore.asBlockUser(player, () -> original.call(state, level, pos, player, hit));
    }
}
