package net.ledok.eden_ld.tutorial;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
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
 * everything comes back and they go to the finish spot. While they're in it, the stash and the active state live with
 * the player ({@link #STASH}); the done/skipped history lives in their hub data ({@code tutorial}).
 */
public final class Tutorial {

    public enum State { NONE, ACTIVE, DONE, SKIPPED }

    private static final String TAG = "tutorial";
    private static final boolean TRINKETS = FabricLoader.getInstance().isModLoaded("trinkets");

    /**
     * Review finding 17: the put-aside inventory and trinkets ({@code inventory}, {@code trinkets}, {@code skipping}),
     * present exactly while the player is in the tutorial. It is saved in the player file with the cleared or restored
     * inventory and the phase, so a hard stop rolls all of them back together (in hub.dat, written only at autosave,
     * a stop between the two lost the first-join gifts or gave a stale stash back on top of the inventory).
     */
    private static final AttachmentType<CompoundTag> STASH = AttachmentRegistry.<CompoundTag>builder()
            .persistent(CompoundTag.CODEC)
            .copyOnDeath()
            .buildAndRegister(ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "tutorial_stash"));

    private Tutorial() {
    }

    public static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            // Decided now: a tick later the play-time stat has started counting.
            boolean firstTime = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0;
            // Not execute (review finding 33): once the server has started stopping, that runs on the calling thread.
            net.ledok.eden_ld.util.OnServerThread.run(server, () -> onJoin(player, firstTime));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
            BusyState.clearBusy(handler.getPlayer().getUUID(), BusyLocks.TUTORIAL);
            TutorialPhases.loggedOut(handler.getPlayer());
        }));
        net.ledok.eden_ld.tutorial.phase.Phases.initialize();
        TutorialLockedBlocks.initialize();
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
        if (player.hasAttached(STASH)) {
            return State.ACTIVE;
        }
        CompoundTag tag = HubData.get(player.server).peek(player.getUUID()).getCompound(TAG);
        return switch (tag.getString("state")) {
            case "done" -> State.DONE;
            case "skipped" -> State.SKIPPED;
            default -> State.NONE;
        };
    }

    public static boolean isActive(ServerPlayer player) {
        return player.hasAttached(STASH);
    }

    /** The player's tutorial history in hub data (done or skipped; "active" is {@link #STASH}). */
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
        boolean phased = net.ledok.eden_ld.tutorial.phase.Phases.of(player) != null;
        if (state == State.ACTIVE) {
            // Busy is kept in memory only; the stash and the phase are saved with the player (review finding 17: both
            // in the player file, so they agree; a stash without its phase gets it back).
            BusyState.setBusy(player, BusyLocks.TUTORIAL);
            if (!phased) {
                TutorialPhases.enter(player);
            }
        } else if (phased) {
            // Review finding 17: a phase with no stash (no tutorial to finish): out of the phase and adventure mode, to
            // the finish spot, keeping what they hold.
            EdenLD.LOGGER.warn("[Tutorial] {} joined in a tutorial phase without being in the tutorial; letting them out.",
                    player.getGameProfile().getName());
            TutorialPhases.leave(player);
            leaveAdventure(player);
            // As finish() does, so the realms the tutorial unlocks get unlocked (review of 1.3.133).
            run(player, EdenLD.CONFIG.tutorial.commands_on_finish);
            toFinishSpot(player);
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
        CompoundTag stash = player.getAttached(STASH);
        if (stash == null) {
            // Put aside what they carry (not again when already in: that would overwrite it with tutorial items).
            stash = new CompoundTag();
            ListTag inventory = new ListTag();
            player.getInventory().save(inventory);
            stash.put("inventory", inventory);
            player.getInventory().clearContent();
            if (TRINKETS) {
                stash.put("trinkets", TutorialTrinkets.takeAll(player));
            }
        }
        stash.remove("skipping");
        player.setAttached(STASH, stash);
        BusyState.setBusy(player, BusyLocks.TUTORIAL);
        TutorialPhases.enter(player);
        player.setGameMode(GameType.ADVENTURE);
        teleport(player, level, config.spawn, true);
        run(player, config.commands_on_start);
        player.inventoryMenu.broadcastChanges();
        EdenLD.LOGGER.info("[Tutorial] {} started the tutorial.", player.getGameProfile().getName());
        return true;
    }

    /**
     * Marks the player as having skipped the tutorial rather than completed it. The guide's skip branch runs it, before
     * or after its {@code /eden tutorial finish}: while they're still in the tutorial it only notes it and
     * {@link #finish} then saves "skipped"; once they're out, "done" becomes "skipped". False when they never started.
     */
    public static boolean markSkipped(ServerPlayer player) {
        CompoundTag stash = player.getAttached(STASH);
        if (stash != null) {
            stash.putBoolean("skipping", true);
            player.setAttached(STASH, stash);
        } else {
            CompoundTag data = data(player);
            switch (data.getString("state")) {
                case "done", "skipped" -> data.putString("state", "skipped");
                default -> {
                    return false;
                }
            }
        }
        EdenLD.LOGGER.info("[Tutorial] {} skipped the tutorial.", player.getGameProfile().getName());
        return true;
    }

    /**
     * Ends the tutorial (finished or skipped): everything back, the finish spot, never again on join. Review finding
     * 18: the stash is given back after the teleport, so anything that overflows lands at the player's feet at the
     * finish spot (out of the phase, where they can see it) rather than in the tutorial room they just left.
     * <p>
     * The stash leaves the attachment first but is given back in a {@code finally} (review of 1.3.133): a step that
     * throws (a phase, a command, the teleport) used to lose the player's whole inventory with the attachment gone.
     * It is given back exactly once, even then; the error still surfaces after.
     */
    public static void finish(ServerPlayer player) {
        EdenConfig.Tutorial config = EdenLD.CONFIG.tutorial;
        CompoundTag stash = player.getAttached(STASH);
        CompoundTag data = data(player);
        boolean skipped = stash != null && stash.getBoolean("skipping") || "skipped".equals(data.getString("state"));
        data.putString("state", skipped ? "skipped" : "done");
        player.removeAttached(STASH);
        try {
            BusyState.clearBusy(player, BusyLocks.TUTORIAL);
            TutorialPhases.leave(player);
            // Review finding 17: out of adventure mode even when there was no stash to give back.
            leaveAdventure(player);
            run(player, config.commands_on_finish);
            toFinishSpot(player);
        } finally {
            if (stash != null) {
                giveBack(player, stash);
            }
            player.inventoryMenu.broadcastChanges();
        }
        EdenLD.LOGGER.info("[Tutorial] {} {} the tutorial.", player.getGameProfile().getName(),
                skipped ? "left (skipped)" : "finished");
    }

    private static void leaveAdventure(ServerPlayer player) {
        if (player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
            player.setGameMode(player.server.getDefaultGameType());
        }
    }

    private static void toFinishSpot(ServerPlayer player) {
        EdenConfig.Tutorial config = EdenLD.CONFIG.tutorial;
        ServerLevel level = level(player.server, config.finish_dimension);
        if (level != null) {
            teleport(player, level, config.finish_spot, true);
        } else {
            EdenLD.LOGGER.error("[Tutorial] Finish dimension {} doesn't exist; {} stays where they are.",
                    config.finish_dimension, player.getGameProfile().getName());
        }
    }

    /**
     * The saved inventory back in its slots, then the saved trinkets, and last whatever they hold now (tutorial
     * leftovers), so when something must overflow it is the leftovers rather than their own things (review finding
     * 18). Overflow drops at their feet: called once they are out of the phase and at the finish spot.
     */
    private static void giveBack(ServerPlayer player, CompoundTag stash) {
        List<ItemStack> holding = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                holding.add(stack.copy());
            }
        }
        player.getInventory().load(stash.getList("inventory", Tag.TAG_COMPOUND));
        if (TRINKETS && stash.contains("trinkets", Tag.TAG_LIST)) {
            TutorialTrinkets.giveBack(player, stash.getList("trinkets", Tag.TAG_COMPOUND));
        }
        for (ItemStack stack : holding) {
            if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                player.drop(stack, false);
            }
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
