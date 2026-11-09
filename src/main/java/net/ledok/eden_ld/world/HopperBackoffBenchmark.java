package net.ledok.eden_ld.world;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dev only ({@code EDEN_HOPPER_BENCH=1} in a dev environment, needs Extended Drawers in the run's mods): a hopper
 * holding a diamond on top of an Extended Drawers access point whose network is a 35×20 wall of quad drawers
 * (2800 slots, each holding stone, so the diamond fits nowhere). Times the hopper's tick with
 * {@code back_off_on_large_storages} off (vanilla) and on, then the same over plain stone (the floor), and logs them.
 */
public final class HopperBackoffBenchmark {

    private static final int WIDTH = 35;
    private static final int HEIGHT = 20;

    private static int tick;
    private static long nanos;
    private static int samples;
    private static BlockPos access;

    private HopperBackoffBenchmark() {
    }

    public static void registerIfRequested() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment() || !"1".equals(System.getenv("EDEN_HOPPER_BENCH"))) {
            return;
        }
        ServerTickEvents.END_WORLD_TICK.register(level -> {
            if (level != level.getServer().overworld()) {
                return;
            }
            tick++;
            // phases of 600 ticks, 40 ticks apart: network off, network on, stone off, stone on
            if (tick == 40) {
                build(level);
            } else if (tick == 100) {
                fill(level);
            }
            int phase = (tick - 140) / 640;
            int inPhase = (tick - 140) % 640;
            if (tick < 140 || phase > 3) {
                return;
            }
            if (inPhase == 0) {
                EdenLD.CONFIG.hoppers.back_off_on_large_storages = phase % 2 == 1;
                if (phase == 2) {
                    level.setBlockAndUpdate(access, Blocks.STONE.defaultBlockState());
                }
            } else if (inPhase > 40) {
                time(level);
            }
            if (inPhase == 639) {
                report((phase < 2 ? "network" : "plain stone (floor)") + ", back-off " + (phase % 2 == 1 ? "on" : "off"));
                if (phase == 3) {
                    level.getServer().halt(false);
                }
            }
        });
    }

    private static void build(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        // a fresh spot each run: a network left over from an earlier (halted) run may not reconnect
        int offset = (int) (System.currentTimeMillis() / 1000 % 64) * 48;
        access = new BlockPos(spawn.getX() + 8 + offset, level.getMaxBuildHeight() - 30, spawn.getZ() + 8);
        level.setBlockAndUpdate(access, block("extended_drawers:access_point"));
        BlockState drawer = block("extended_drawers:quad_drawer");
        for (int x = 1; x <= WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                level.setBlockAndUpdate(access.offset(x, y, 0), drawer);
            }
        }
        level.setBlockAndUpdate(access.above(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
    }

    private static void fill(ServerLevel level) {
        int slots = 0;
        try (Transaction tx = Transaction.openOuter()) {
            for (int x = 1; x <= WIDTH; x++) {
                for (int y = 0; y < HEIGHT; y++) {
                    var storage = ItemStorage.SIDED.find(level, access.offset(x, y, 0), Direction.SOUTH);
                    if (storage == null) {
                        continue;
                    }
                    for (var view : storage) {
                        if (view instanceof SingleSlotStorage<ItemVariant> slot) {
                            slots++;
                            if (slot.isResourceBlank()) {
                                slot.insert(ItemVariant.of(Items.STONE), 1, tx);
                            }
                        }
                    }
                }
            }
            tx.commit();
        }
        if (level.getBlockEntity(access.above()) instanceof HopperBlockEntity hopper) {
            hopper.setItem(0, new ItemStack(Items.DIAMOND));
        }
        int networkSlots = 0;
        var network = ItemStorage.SIDED.find(level, access, Direction.UP);
        if (network != null) {
            for (var ignored : network) {
                networkSlots++;
            }
        }
        EdenLD.LOGGER.info("[HopperBench] filled {} drawer slots; the access point sees {}", slots, networkSlots);
    }

    private static void time(ServerLevel level) {
        BlockPos pos = access.above();
        if (!(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) {
            return;
        }
        long start = System.nanoTime();
        HopperBlockEntity.pushItemsTick(level, pos, level.getBlockState(pos), hopper);
        nanos += System.nanoTime() - start;
        samples++;
    }

    private static void report(String mode) {
        EdenLD.LOGGER.info("[HopperBench] back-off {}: hopper tick {} us avg over {} ticks", mode,
                String.format("%.1f", nanos / 1000.0 / Math.max(1, samples)), samples);
        nanos = 0;
        samples = 0;
    }

    private static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }
}
