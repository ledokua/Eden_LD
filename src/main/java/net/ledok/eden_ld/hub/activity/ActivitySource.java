package net.ledok.eden_ld.hub.activity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where activities come from (Arenas_LD, a datapack). Server thread.
 * <p>
 * Besides listing and opening, a source may offer the Moderation window's controller actions
 * ({@link #adminActions()}); every one defaults to unsupported, and the window only shows the
 * buttons a source names. A {@code @Nullable Component} answer is null when done, else the refusal
 * shown to the admin.
 */
public interface ActivitySource {

    List<Activity> activities(MinecraftServer server);

    /** Shown in the Moderation window ("Arenas_LD", "datapack"). */
    String name();

    /** Opens the activity's lobby screen (or whatever "go there" means) for the player; false if unknown. */
    boolean open(ServerPlayer player, ResourceLocation activity);

    /** The lobby the player is in: its activity's name, members of the most it takes, and whether its run started. */
    record OwnLobby(Component name, int members, int maxMembers, boolean inRun) {
    }

    /** The player's own lobby in this source, if they're in one (cheap: asked every 2 s while the tab shows). */
    default Optional<OwnLobby> ownLobby(ServerPlayer player) {
        return Optional.empty();
    }

    /** Opens the player's own lobby screen from anywhere; false when they have none here. */
    default boolean openOwnLobby(ServerPlayer player) {
        return false;
    }

    // ---------------------------------------------------------------- admin (Moderation → Activities)

    /** What the Moderation window may do with this source's activities. */
    enum AdminAction {
        /** Teleport the admin to the activity's controller. */
        TELEPORT,
        /** Rename the activity (op only: sources accept op edits only). */
        RENAME,
        /** Open the source's own admin screen for it (op only). */
        OPEN_ADMIN,
        /** {@link #status}: instances, runs, lobbies and warnings. */
        STATUS,
        /** End a run (or every run) of the activity. */
        FORCE_END,
        /** Drop an activity whose controller is gone. */
        FORGET,
        /** Make an instance on cooldown (or every one) free again. Appended: the bits of the others stay. */
        RESET_COOLDOWN,
        /** Open the source's details screen of a run in progress (players, stats). */
        RUN_DETAILS;

        public int bit() {
            return 1 << ordinal();
        }

        public static int mask(Set<AdminAction> actions) {
            int mask = 0;
            for (AdminAction action : actions) {
                mask |= action.bit();
            }
            return mask;
        }

        public static Set<AdminAction> of(int mask) {
            EnumSet<AdminAction> actions = EnumSet.noneOf(AdminAction.class);
            for (AdminAction action : values()) {
                if ((mask & action.bit()) != 0) {
                    actions.add(action);
                }
            }
            return actions;
        }
    }

    /** One running place of an activity: the controller's dimension and position. */
    record InstanceRef(ResourceLocation dimension, BlockPos pos) {
    }

    /** The actions this source supports (none by default). */
    default Set<AdminAction> adminActions() {
        return Set.of();
    }

    /** Teleports the admin to the activity's controller. */
    @Nullable
    default Component teleport(ServerPlayer admin, ResourceLocation id) {
        return unsupported();
    }

    /** Renames the activity ({@code name} plain text, 1–48 characters). */
    @Nullable
    default Component rename(ServerPlayer admin, ResourceLocation id, String name) {
        return unsupported();
    }

    /** Opens the source's own admin screen for the activity. */
    @Nullable
    default Component openAdmin(ServerPlayer admin, ResourceLocation id) {
        return unsupported();
    }

    /** Ends the run at {@code instance}, or every run of the activity (null). Players leave as on a loss. */
    @Nullable
    default Component forceEnd(MinecraftServer server, ResourceLocation id, @Nullable InstanceRef instance) {
        return unsupported();
    }

    /** Makes the instance at {@code instance} (or every instance, null) on cooldown free again; {@code actor} is logged. */
    @Nullable
    default Component resetCooldowns(MinecraftServer server, ResourceLocation id, @Nullable InstanceRef instance, String actor) {
        return unsupported();
    }

    /** Opens the source's details screen of the run at {@code instance} for the staff member. */
    @Nullable
    default Component openRunDetails(ServerPlayer staff, ResourceLocation id, InstanceRef instance) {
        return unsupported();
    }

    /** Drops an activity whose controller is gone ({@link ActivityLiveStatus.Warning#CONTROLLER_MISSING}). */
    @Nullable
    default Component forget(MinecraftServer server, ResourceLocation id) {
        return unsupported();
    }

    /**
     * What's going on now; empty when the source can't tell. Polled for <em>every</em> activity about
     * every 2 s while a staff member has the Moderation window's Activities section open, so it must
     * answer from state the source already holds in memory: never load chunks or read block entities
     * of unloaded controllers (report such an instance as unknown / leave it out instead).
     */
    default Optional<ActivityLiveStatus> status(MinecraftServer server, ResourceLocation id) {
        return Optional.empty();
    }

    static Component unsupported() {
        return Component.translatable("message.eden_ld.admin.activity_unsupported");
    }
}
