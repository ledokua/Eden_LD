package net.ledok.eden_ld.tutorial;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.busylib.BusyState;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.config.EdenConfig;
import net.ledok.eden_ld.hub.BusyLocks;
import net.ledok.eden_ld.hub.HubData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The tutorial ({@code docs/PLAN-tutorial.md}, config {@code tutorial.json}). A first-time player is put into the
 * tutorial's room: their inventory and trinkets are put aside, they get ADVENTURE mode, their own phase (other
 * tutorial players are out of sight) and the busy reason {@link BusyLocks#TUTORIAL} (no teleports out, guilds, rewards
 * or spending). Dying there is cancelled. The NPCs_LD guide runs the stages and, last, {@code /eden tutorial finish}:
 * everything comes back and they go to the finish spot. The state lives in the player's hub data ({@code tutorial}).
 */
public final class Tutorial {

    public enum State { NONE, ACTIVE, DONE }

    private static final String TAG = "tutorial";
    private static final boolean TRINKETS = FabricLoader.getInstance().isModLoaded("trinkets");

    private Tutorial() {
    }

    public static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            // Decided now: a tick later the play-time stat has started counting.
            boolean firstTime = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0;
            server.execute(() -> onJoin(player, firstTime));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            BusyState.clearBusy(handler.getPlayer().getUUID(), BusyLocks.TUTORIAL);
            TutorialPhases.loggedOut(handler.getPlayer());
        });
        net.ledok.eden_ld.tutorial.phase.Phases.initialize();
        // No dying in the tutorial: back to its spawn, healed.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player && isActive(player)) {
                spare(player);
                return false;
            }
            return true;
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> EdenLD.CONFIG.reloadTutorial());
    }

    // ---------------------------------------------------------------- state

    public static State state(ServerPlayer player) {
        CompoundTag tag = HubData.get(player.server).peek(player.getUUID()).getCompound(TAG);
        return switch (tag.getString("state")) {
            case "active" -> State.ACTIVE;
            case "done" -> State.DONE;
            default -> State.NONE;
        };
    }

    public static boolean isActive(ServerPlayer player) {
        return state(player) == State.ACTIVE;
    }

    private static CompoundTag data(ServerPlayer player) {
        CompoundTag hub = HubData.get(player.server).player(player.getUUID());
        if (!hub.contains(TAG, Tag.TAG_COMPOUND)) {
            hub.put(TAG, new CompoundTag());
        }
        return hub.getCompound(TAG);
    }

    private static void onJoin(ServerPlayer player, boolean firstTime) {
        if (player.hasDisconnected()) {
            return;
        }
        State state = state(player);
        if (state == State.ACTIVE) {
            // Busy is kept in memory only; the phase is saved with the player.
            BusyState.setBusy(player, BusyLocks.TUTORIAL);
        } else if (state == State.NONE && firstTime && EdenLD.CONFIG.tutorial.enabled) {
            start(player);
        }
    }

    // ---------------------------------------------------------------- start and finish

    /** Puts the player into the tutorial (again, for staff); false when its dimension is missing. */
    public static boolean start(ServerPlayer player) {
        EdenConfig.Tutorial config = EdenLD.CONFIG.tutorial;
        ServerLevel level = level(player.server, config.dimension);
        if (level == null) {
            EdenLD.LOGGER.error("[Tutorial] Dimension {} doesn't exist; {} isn't put into the tutorial.",
                    config.dimension, player.getGameProfile().getName());
            return false;
        }
        CompoundTag data = data(player);
        if (!"active".equals(data.getString("state"))) {
            // Put aside what they carry (not again when already in: that would overwrite it with tutorial items).
            ListTag inventory = new ListTag();
            player.getInventory().save(inventory);
            data.put("inventory", inventory);
            player.getInventory().clearContent();
            if (TRINKETS) {
                data.put("trinkets", TutorialTrinkets.takeAll(player));
            }
        }
        data.putString("state", "active");
        BusyState.setBusy(player, BusyLocks.TUTORIAL);
        TutorialPhases.enter(player);
        player.setGameMode(GameType.ADVENTURE);
        teleport(player, level, config.spawn, true);
        run(player, config.commands_on_start);
        player.inventoryMenu.broadcastChanges();
        EdenLD.LOGGER.info("[Tutorial] {} started the tutorial.", player.getGameProfile().getName());
        return true;
    }

    /** Ends the tutorial (finished or skipped): everything back, the finish spot, never again on join. */
    public static void finish(ServerPlayer player) {
        EdenConfig.Tutorial config = EdenLD.CONFIG.tutorial;
        CompoundTag data = data(player);
        boolean wasActive = "active".equals(data.getString("state"));
        data.putString("state", "done");
        BusyState.clearBusy(player, BusyLocks.TUTORIAL);
        TutorialPhases.leave(player);
        if (wasActive) {
            giveBack(player, data);
            if (player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
                player.setGameMode(player.server.getDefaultGameType());
            }
        }
        data.remove("inventory");
        data.remove("trinkets");
        run(player, config.commands_on_finish);
        ServerLevel level = level(player.server, config.finish_dimension);
        if (level != null) {
            teleport(player, level, config.finish_spot, true);
        } else {
            EdenLD.LOGGER.error("[Tutorial] Finish dimension {} doesn't exist; {} stays where they are.",
                    config.finish_dimension, player.getGameProfile().getName());
        }
        player.inventoryMenu.broadcastChanges();
        EdenLD.LOGGER.info("[Tutorial] {} finished the tutorial.", player.getGameProfile().getName());
    }

    /** The saved inventory back in its slots; whatever they hold now is kept too (added, or dropped at their feet). */
    private static void giveBack(ServerPlayer player, CompoundTag data) {
        List<ItemStack> holding = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                holding.add(stack.copy());
            }
        }
        player.getInventory().load(data.getList("inventory", Tag.TAG_COMPOUND));
        for (ItemStack stack : holding) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        if (TRINKETS && data.contains("trinkets", Tag.TAG_LIST)) {
            TutorialTrinkets.giveBack(player, data.getList("trinkets", Tag.TAG_COMPOUND));
        }
    }

    /** A death in the tutorial: healed, back at its spawn. */
    private static void spare(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.removeAllEffects();
        player.clearFire();
        player.setRemainingFireTicks(0);
        player.getFoodData().setFoodLevel(20);
        player.fallDistance = 0;
        ServerLevel level = level(player.server, EdenLD.CONFIG.tutorial.dimension);
        if (level != null) {
            teleport(player, level, EdenLD.CONFIG.tutorial.spawn, false);
        }
    }

    // ---------------------------------------------------------------- helpers

    @Nullable
    private static ServerLevel level(MinecraftServer server, String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    /** Whether a dimension is the tutorial's (waystones there stay usable). */
    public static boolean isTutorialDimension(ResourceKey<Level> dimension) {
        return dimension.location().toString().equals(EdenLD.CONFIG.tutorial.dimension);
    }

    /**
     * To "x y z [yaw pitch]" in the level, or to its spawn point when the spot is empty or unreadable; with
     * {@code respawn}, that spot becomes their respawn point too (start: the tutorial; finish: the finish spot).
     */
    private static void teleport(ServerPlayer player, ServerLevel level, String spot, boolean respawn) {
        String[] parts = spot == null ? new String[0] : spot.trim().split("\\s+");
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        try {
            if (parts.length < 3) {
                throw new NumberFormatException("no spot");
            }
            x = Double.parseDouble(parts[0]);
            y = Double.parseDouble(parts[1]);
            z = Double.parseDouble(parts[2]);
            yaw = parts.length >= 5 ? Float.parseFloat(parts[3]) : player.getYRot();
            pitch = parts.length >= 5 ? Float.parseFloat(parts[4]) : player.getXRot();
        } catch (NumberFormatException e) {
            if (parts.length >= 3) {
                EdenLD.LOGGER.warn("[Tutorial] Can't read the spot \"{}\"; using {}'s spawn point.", spot, level.dimension().location());
            }
            BlockPos pos = level.getSharedSpawnPos();
            x = pos.getX() + 0.5;
            y = pos.getY();
            z = pos.getZ() + 0.5;
            yaw = level.getSharedSpawnAngle();
            pitch = 0;
        }
        player.teleportTo(level, x, y, z, yaw, pitch);
        if (respawn) {
            player.setRespawnPosition(level.dimension(), BlockPos.containing(x, y, z), yaw, true, false);
        }
    }

    private static void run(ServerPlayer player, List<String> commands) {
        MinecraftServer server = player.server;
        String name = player.getGameProfile().getName();
        for (String command : commands) {
            if (command == null || command.isBlank()) {
                continue;
            }
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                        command.replace("{player}", name));
            } catch (Exception e) {
                EdenLD.LOGGER.warn("[Tutorial] Command failed for {}: {}", name, command, e);
            }
        }
    }
}
