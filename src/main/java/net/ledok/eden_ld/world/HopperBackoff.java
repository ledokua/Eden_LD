package net.ledok.eden_ld.world;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hopper retry back-off for big storages of other mods ({@code hoppers.json} {@code back_off_on_large_storages}).
 * A hopper reaches such a storage through Fabric's transfer API, which offers each of its 5 slots to every slot of the
 * storage; a storage network that can't take the item (an Extended Drawers access point: ~2800 slots) cost 328 µs
 * per hopper, every tick. After a failed push or pull there, the hopper waits 2, 4, 8, 16, then 20 ticks before the
 * next try. Moving something, the hopper's contents changing or the facing block changing ends the wait.
 */
public final class HopperBackoff {

    private static final int LARGE_STORAGE_SLOTS = 128;
    private static final int MAX_BACKOFF_TICKS = 20;
    /** How long "this block is no big storage" is trusted for the same block state. */
    private static final int NOT_LARGE_RECHECK_TICKS = 100;

    /** Added to {@link HopperBlockEntity} by {@code HopperBackoffMixin}: one state for pushing, one for pulling. */
    public interface Holder {
        State eden_ld$pushBackoff();

        State eden_ld$pullBackoff();
    }

    public static final class State {
        private long waitUntil;
        private long lastFailure;
        private int backoff;
        private int contents;
        private BlockState target;
        private BlockState notLargeState;
        private long notLargeUntil;

        private void reset() {
            waitUntil = 0;
            backoff = 0;
            target = null;
        }
    }

    private HopperBackoff() {
    }

    /** True while the hopper should skip this transfer (nothing has changed since it last failed). */
    public static boolean waiting(State state, Level level, BlockPos targetPos, HopperBlockEntity hopper) {
        if (state.waitUntil == 0 || level.getGameTime() >= state.waitUntil) {
            return false;
        }
        if (level.getBlockState(targetPos) != state.target || contents(hopper) != state.contents) {
            state.reset();
            return false;
        }
        return true;
    }

    /** Records the outcome of a transfer the hopper did try; failures against a big foreign storage start a wait. */
    public static void record(State state, boolean moved, Level level, BlockPos targetPos, Direction side,
                              HopperBlockEntity hopper) {
        if (moved) {
            state.reset();
            return;
        }
        // Idle hoppers fail every tick (an empty pull), so a "no big storage here" answer is kept for a while.
        BlockState targetState = level.getBlockState(targetPos);
        long time = level.getGameTime();
        if (targetState == state.notLargeState && time < state.notLargeUntil) {
            state.reset();
            return;
        }
        if (!isLargeForeignStorage(level, targetPos, targetState, side)) {
            state.reset();
            state.notLargeState = targetState;
            state.notLargeUntil = time + NOT_LARGE_RECHECK_TICKS;
            return;
        }
        long now = level.getGameTime();
        boolean consecutive = state.backoff > 0 && now - state.lastFailure <= state.backoff + 2;
        state.backoff = consecutive ? Math.min(state.backoff * 2, MAX_BACKOFF_TICKS) : 2;
        state.lastFailure = now;
        state.waitUntil = now + state.backoff;
        state.target = level.getBlockState(targetPos);
        state.contents = contents(hopper);
    }

    /** Not a vanilla container (those keep vanilla timing), but a transfer-API storage with many slots. */
    private static boolean isLargeForeignStorage(Level level, BlockPos pos, BlockState state, Direction side) {
        if (state.isAir() || HopperBlockEntity.getContainerAt(level, pos) != null) {
            return false;
        }
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, pos, side);
        if (storage == null) {
            return false;
        }
        int slots = 0;
        for (StorageView<ItemVariant> ignored : storage) {
            if (++slots >= LARGE_STORAGE_SLOTS) {
                return true;
            }
        }
        return false;
    }

    private static int contents(HopperBlockEntity hopper) {
        int hash = 1;
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            ItemStack stack = hopper.getItem(slot);
            hash = 31 * hash + System.identityHashCode(stack.getItem());
            hash = 31 * hash + stack.getCount();
        }
        return hash;
    }
}
