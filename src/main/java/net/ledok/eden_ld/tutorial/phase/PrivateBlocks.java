package net.ledok.eden_ld.tutorial.phase;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.tutorial.Tutorial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private containers in the tutorial ({@code docs/PLAN-tutorial.md} section 3, ported from tutorial-mod-for-yggdrasil):
 * a tutorial player who uses a listed block ({@code tutorial.private_blocks}: the alchemy table, chests, furnaces…)
 * gets their own copy of its block entity — its own items, progress and lid — kept in memory, ticked on its own and
 * sent only to them. While a player acts on such a block (using it, clicking in its menu, the copy ticking) a
 * <em>scope</em> is open: {@code Level.getBlockEntity} answers with their copy and a same-block state change (a lit
 * furnace) stays theirs ({@code PhaseLevelBlockMixin}). Copies go when their player has left the tutorial or been away
 * five minutes. After 20 errors the whole feature switches itself off (the server keeps running).
 */
public final class PrivateBlocks {

    private record Key(ResourceKey<Level> dimension, long pos) {
    }

    /** An open scope: whose copy, of which block; {@code create} lets a first use make the copy. */
    record Frame(UUID phase, BlockPos pos, boolean create, boolean setBlock) {
    }

    /** A private block's menu the player has open (its clicks and ticks reopen the scope). */
    private record OpenMenu(AbstractContainerMenu menu, ResourceKey<Level> dimension, BlockPos pos, UUID phase) {
    }

    private static final Map<Key, Map<UUID, BlockEntity>> COPIES = new ConcurrentHashMap<>();
    private static final Map<Key, Map<UUID, BlockState>> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SEEN = new ConcurrentHashMap<>();
    private static final Map<UUID, OpenMenu> MENUS = new ConcurrentHashMap<>();
    private static final ThreadLocal<ArrayDeque<Frame>> SCOPE = ThreadLocal.withInitial(ArrayDeque::new);
    /** Open scopes on any thread — a cheap first check for the hot Level hooks. */
    private static int open;
    private static int failures;
    private static boolean tripped;
    private static long lastTick = Long.MIN_VALUE;

    private static final long AWAY_TICKS = 6000;
    private static final int RESYNC_TICKS = 40;
    private static final int FAILURE_LIMIT = 20;

    private PrivateBlocks() {
    }

    // ---------------------------------------------------------------- which blocks

    /** Whether the block here gets private copies: the tutorial dimension and a listed block. */
    public static boolean isPrivate(Level level, BlockPos pos) {
        if (tripped || level.isClientSide() || !Tutorial.isTutorialDimension(level.dimension()) || !level.isLoaded(pos)) {
            return false;
        }
        return listed(realState(level, pos));
    }

    private static boolean listed(BlockState state) {
        var list = EdenLD.CONFIG.tutorial.private_blocks;
        if (list.isEmpty() || state.isAir()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return list.contains(id.toString()) || list.contains(id.getNamespace() + ":");
    }

    // ---------------------------------------------------------------- scope

    public static boolean scopeOpen() {
        return open > 0;
    }

    @Nullable
    static Frame frame() {
        return open <= 0 ? null : SCOPE.get().peek();
    }

    /** Opens a scope (false = nothing opened, don't close). */
    static boolean push(@Nullable UUID phase, BlockPos pos, boolean create, boolean setBlock) {
        if (phase == null || tripped) {
            return false;
        }
        SCOPE.get().push(new Frame(phase, pos.immutable(), create, setBlock));
        open++;
        return true;
    }

    public static void popScope() {
        pop();
    }

    static void pop() {
        ArrayDeque<Frame> stack = SCOPE.get();
        if (!stack.isEmpty()) {
            stack.pop();
            open--;
        }
    }

    /** A player using a block: the scope opens when it is a private block and they are phased. */
    public static boolean pushUse(ServerPlayer player, Level level, BlockPos pos) {
        UUID phase = Phases.of(player);
        return phase != null && isPrivate(level, pos) && push(phase, pos, true, true);
    }

    /** A click, button or tick of an open private menu: its scope again (false = no private menu open). */
    public static boolean pushMenu(ServerPlayer player) {
        OpenMenu menu = MENUS.get(player.getUUID());
        if (menu == null) {
            return false;
        }
        if (menu.menu() != player.containerMenu) {
            MENUS.remove(player.getUUID());
            return false;
        }
        return push(menu.phase(), menu.pos(), true, true);
    }

    @Nullable
    public static UUID menuPhase(ServerPlayer player) {
        OpenMenu menu = MENUS.get(player.getUUID());
        return menu != null && menu.menu() == player.containerMenu ? menu.phase() : null;
    }

    /** Right after {@code openMenu} inside a use scope: remember the menu, send the copy. */
    public static void menuOpened(ServerPlayer player) {
        Frame frame = frame();
        UUID phase = Phases.of(player);
        if (frame == null || phase == null || !phase.equals(frame.phase()) || player.containerMenu == player.inventoryMenu) {
            return;
        }
        if (copy(player.level(), frame.pos(), phase) != null) {
            MENUS.put(player.getUUID(), new OpenMenu(player.containerMenu, player.level().dimension(), frame.pos(), phase));
            sync((ServerLevel) player.level(), frame.pos(), phase);
        }
    }

    public static void menuClosed(ServerPlayer player) {
        MENUS.remove(player.getUUID());
    }

    // ---------------------------------------------------------------- the Level hooks (inside a scope)

    /** {@code Level.getBlockEntity} in a scope: the player's copy of a private block (made on first use). */
    @Nullable
    public static BlockEntity scopedBlockEntity(Level level, BlockPos pos) {
        try {
            Frame frame = frame();
            if (frame == null || level.isClientSide() || !isPrivate(level, pos)) {
                return null;
            }
            return frame.create() ? copyOrCreate(level, pos, frame.phase()) : copy(level, pos, frame.phase());
        } catch (Throwable error) {
            failed("in getBlockEntity", error);
            return null;
        }
    }

    /** {@code Level.getBlockState} in a scope: the player's own state of a private block, when it has one. */
    @Nullable
    public static BlockState scopedState(Level level, BlockPos pos) {
        try {
            Frame frame = frame();
            return frame == null || level.isClientSide() ? null : state(level, pos, frame.phase());
        } catch (Throwable error) {
            failed("in getBlockState", error);
            return null;
        }
    }

    /** {@code Level.setBlock} in a scope: a same-block change to a private block they have a copy of stays theirs. */
    public static boolean scopedSetBlock(Level level, BlockPos pos, BlockState state) {
        try {
            Frame frame = frame();
            if (frame == null || !frame.setBlock() || !(level instanceof ServerLevel server)
                    || copy(level, pos, frame.phase()) == null || !state.is(realState(level, pos).getBlock())) {
                return false;
            }
            setState(server, pos, frame.phase(), state);
            return true;
        } catch (Throwable error) {
            failed("in setBlock", error);
            return false;
        }
    }

    // ---------------------------------------------------------------- copies

    @Nullable
    static BlockEntity copy(Level level, BlockPos pos, UUID phase) {
        Map<UUID, BlockEntity> byPhase = COPIES.get(key(level, pos));
        return byPhase == null ? null : byPhase.get(phase);
    }

    /** The player's copy of the block entity here, made from the real one on first use. */
    @Nullable
    static BlockEntity copyOrCreate(Level level, BlockPos pos, UUID phase) {
        BlockEntity existing = copy(level, pos, phase);
        if (existing != null || !(level instanceof ServerLevel server)) {
            return existing;
        }
        BlockEntity real = realBlockEntity(level, pos);
        if (real == null) {
            return null;
        }
        BlockState state = realState(level, pos);
        BlockEntity copy;
        try {
            CompoundTag tag = real.saveWithFullMetadata(server.registryAccess());
            copy = BlockEntity.loadStatic(pos, state, tag, server.registryAccess());
        } catch (Exception e) {
            EdenLD.LOGGER.warn("[Tutorial] Can't copy the block entity at {}: {}", pos, e.toString());
            return null;
        }
        if (copy == null) {
            return null;
        }
        copy.setLevel(level);
        COPIES.computeIfAbsent(key(level, pos), k -> new ConcurrentHashMap<>()).put(phase, copy);
        sync(server, pos, phase);
        return copy;
    }

    @Nullable
    static BlockState state(Level level, BlockPos pos, UUID phase) {
        Map<UUID, BlockState> byPhase = STATES.get(key(level, pos));
        return byPhase == null ? null : byPhase.get(phase);
    }

    /** A same-block state change made through the copy (a lit furnace, an open barrel): theirs only. */
    static void setState(ServerLevel level, BlockPos pos, UUID phase, BlockState state) {
        STATES.computeIfAbsent(key(level, pos), k -> new ConcurrentHashMap<>()).put(phase, state);
        BlockEntity copy = copy(level, pos, phase);
        if (copy != null) {
            copy.setBlockState(state);
        }
        send(level, phase, new ClientboundBlockUpdatePacket(pos.immutable(), state));
    }

    /** The real block was broken: every copy of it goes. */
    public static void cleared(Level level, BlockPos pos) {
        Key key = key(level, pos);
        COPIES.remove(key);
        STATES.remove(key);
    }

    /** The player's copies go (they finished the tutorial). */
    public static void clearPhase(UUID phase) {
        COPIES.values().forEach(map -> map.remove(phase));
        STATES.values().forEach(map -> map.remove(phase));
        COPIES.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        STATES.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        LAST_SEEN.remove(phase);
        MENUS.values().removeIf(menu -> menu.phase().equals(phase));
    }

    // ---------------------------------------------------------------- ticking

    /** End of a server tick: tick the copies, resend them now and then, drop the ones left behind. */
    public static void tick(MinecraftServer server) {
        if (COPIES.isEmpty() || tripped) {
            return;
        }
        long now = server.overworld().getGameTime();
        if (now == lastTick) {
            return;
        }
        lastTick = now;
        if (now % 20 == 0) {
            dropLeftBehind(server, now);
        }
        boolean resync = now % RESYNC_TICKS == 0;
        for (Map.Entry<Key, Map<UUID, BlockEntity>> entry : COPIES.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey().dimension());
            BlockPos pos = BlockPos.of(entry.getKey().pos());
            if (level == null || !level.isLoaded(pos)) {
                continue;
            }
            for (Map.Entry<UUID, BlockEntity> copy : entry.getValue().entrySet()) {
                tickCopy(level, pos, copy.getKey(), copy.getValue());
                if (resync) {
                    sync(level, pos, copy.getKey());
                }
            }
        }
    }

    /** The scope stack can't outlive a tick. */
    static void endTick() {
        ArrayDeque<Frame> stack = SCOPE.get();
        if (!stack.isEmpty()) {
            open -= stack.size();
            if (open < 0) {
                open = 0;
            }
            stack.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static void tickCopy(ServerLevel level, BlockPos pos, UUID phase, BlockEntity copy) {
        BlockState state = effectiveState(level, pos, phase);
        if (!(state.getBlock() instanceof EntityBlock block) || !copy.getType().isValid(state)) {
            return;
        }
        BlockEntityTicker<BlockEntity> ticker;
        try {
            ticker = (BlockEntityTicker<BlockEntity>) block.getTicker(level, state, copy.getType());
        } catch (Exception e) {
            return;
        }
        if (ticker == null) {
            return;
        }
        boolean pushed = push(phase, pos, false, true);
        try {
            PhaseContext.run(phase, () -> ticker.tick(level, pos, state, copy));
        } catch (Exception e) {
            failed("ticking a copy", e);
        } finally {
            if (pushed) {
                pop();
            }
        }
    }

    private static void dropLeftBehind(MinecraftServer server, long now) {
        Set<UUID> phases = new HashSet<>();
        COPIES.values().forEach(map -> phases.addAll(map.keySet()));
        for (UUID phase : phases) {
            ServerPlayer owner = server.getPlayerList().getPlayer(phase);
            if (owner != null && phase.equals(Phases.of(owner))) {
                LAST_SEEN.put(phase, now);
            } else if (owner != null || now - LAST_SEEN.computeIfAbsent(phase, k -> now) > AWAY_TICKS) {
                // Online but out of the tutorial, or away too long.
                clearPhase(phase);
            }
        }
    }

    // ---------------------------------------------------------------- sending

    /** The copy (and its state) to its player — under their phase, so the packet filter lets it through. */
    static void sync(ServerLevel level, BlockPos pos, UUID phase) {
        BlockEntity copy = copy(level, pos, phase);
        if (copy == null) {
            return;
        }
        BlockState state = state(level, pos, phase);
        if (state != null) {
            send(level, phase, new ClientboundBlockUpdatePacket(pos.immutable(), state));
        }
        send(level, phase, ClientboundBlockEntityDataPacket.create(copy));
    }

    private static void send(ServerLevel level, UUID phase, Packet<?> packet) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(phase);
        if (player != null && player.level() == level && phase.equals(Phases.of(player))) {
            PhaseContext.run(phase, () -> player.connection.send(packet));
        }
    }

    // ---------------------------------------------------------------- helpers

    static BlockState effectiveState(Level level, BlockPos pos, UUID phase) {
        BlockState state = state(level, pos, phase);
        return state != null ? state : realState(level, pos);
    }

    /** The world's own block entity, past the hooks. */
    @Nullable
    static BlockEntity realBlockEntity(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return null;
        }
        LevelChunk chunk = level.getChunkAt(pos);
        return chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
    }

    /** The world's own block state, past the hooks. */
    static BlockState realState(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        return level.getChunkAt(pos).getBlockState(pos);
    }

    private static Key key(Level level, BlockPos pos) {
        return new Key(level.dimension(), pos.asLong());
    }

    /** An error in a hook: logged; the 20th switches private blocks off for good (until a restart). */
    static void failed(String where, Throwable error) {
        if (tripped) {
            return;
        }
        failures++;
        EdenLD.LOGGER.error("[Tutorial] Private block error {} ({}/{})", where, failures, FAILURE_LIMIT, error);
        if (failures >= FAILURE_LIMIT) {
            tripped = true;
            COPIES.clear();
            STATES.clear();
            MENUS.clear();
            EdenLD.LOGGER.error("[Tutorial] Private blocks are OFF until a restart: too many errors. Everyone shares containers now.");
        }
    }
}
