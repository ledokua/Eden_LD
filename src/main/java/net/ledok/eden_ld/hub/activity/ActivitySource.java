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

    /**
     * The lobby the player is in: its activity's name, members of the most it takes, and whether its run started; its
     * activity's id and whether the player owns it (1.3.103: the browser's «Змінити лобі»).
     */
    record OwnLobby(Component name, int members, int maxMembers, boolean inRun, @Nullable ResourceLocation activity,
                    boolean owner) {
        public OwnLobby(Component name, int members, int maxMembers, boolean inRun) {
            this(name, members, maxMembers, inRun, null, false);
        }
    }

    /** The player's own lobby in this source, if they're in one (cheap: asked every 2 s while the tab shows). */
    default Optional<OwnLobby> ownLobby(ServerPlayer player) {
        return Optional.empty();
    }

    /** Opens the player's own lobby screen from anywhere; false when they have none here. */
    default boolean openOwnLobby(ServerPlayer player) {
        return false;
    }

    /** Opens the activity's leaderboard (1.3.126); by default its lobby screen, as {@link #open}. */
    default boolean openLeaderboard(ServerPlayer player, ResourceLocation activity) {
        return open(player, activity);
    }

    /**
     * A lobby forming now that the viewer may see on an activity card and in the «Лобі» list (1.3.98): its id, its
     * owner's name, members of the most it takes, the picked difficulty's id ("easy", "hard"…), hardcore, whether it's
     * joined by request (friends-only) and whether the viewer is in it; 1.3.103: members marked ready, the minimum join
     * level (0 = none), the other run modifiers' names (hardcore is its own flag) and when it was made (newest first).
     */
    record OpenLobby(java.util.UUID lobbyId, String owner, int members, int maxMembers, String difficulty,
                     boolean hardcore, boolean request, boolean member, int ready, int minLevel,
                     List<Component> modifiers, long createdAt) {
        public boolean full() {
            return members >= maxMembers;
        }
    }

    /**
     * The lobbies forming now that {@code viewer} may see, by activity (cheap: asked every 2 s while the Activities tab
     * shows). Friends-only lobbies only for the owner's friends and the lobby's members.
     */
    default java.util.Map<ResourceLocation, List<OpenLobby>> openLobbies(ServerPlayer viewer) {
        return java.util.Map.of();
    }

    /** Whether this source lists its open lobbies at all (the hub shows the «Лобі» list only then). */
    default boolean listsLobbies() {
        return false;
    }

    /**
     * Joins a public lobby or asks to join a friends-only one from the hub's «Лобі» list (1.3.99), through the
     * source's usual checks; its lobby screen opens. Null when it went through, otherwise why not.
     */
    @Nullable
    default Component joinLobby(ServerPlayer player, ResourceLocation activity, java.util.UUID lobby) {
        return Component.translatable("message.eden_ld.activities.unknown");
    }

    // ---------------------------------------------------------------- creating lobbies (1.3.103)

    /** A lobby's settings, as the hub's «Створити лобі» panel sets them; visibility 0 public, 1 friends, 2 private. */
    record LobbySettings(String difficulty, boolean hardcore, List<String> modifiers, int visibility, int minLevel) {
    }

    /** A run modifier the panel offers: its id, name, what it does, its reward effect ("+2 loot"), what it rules out. */
    record ModifierView(String id, Component name, Component description, Component rewards, List<String> conflicts) {
    }

    /**
     * What the panel offers for an activity: the enabled difficulties (empty: none to pick, e.g. arenas), the run
     * modifiers (hardcore among them as {@link #HARDCORE}), the most members, and whether minimum levels are checked.
     */
    record CreateInfo(List<String> difficulties, List<ModifierView> modifiers, int maxMembers, boolean levelsActive) {
    }

    /** The hardcore modifier's id: the panel shows it as its own switch. */
    String HARDCORE = "hardcore_run";

    /** Whether the hub may create lobbies of this source's activities (else the panel only opens them). */
    default boolean createsLobbies() {
        return false;
    }

    default Optional<CreateInfo> createInfo(MinecraftServer server, ResourceLocation activity) {
        return Optional.empty();
    }

    /** What a win can give at that difficulty (null: any), for the panel's «Можлива здобич». */
    default List<net.minecraft.world.item.ItemStack> possibleDrops(MinecraftServer server, ResourceLocation activity,
                                                                    @Nullable String difficulty) {
        return List.of();
    }

    /** How many items a win can give at that difficulty, before any cap of {@link #possibleDrops}; -1 unknown. */
    default int possibleDropsCount(MinecraftServer server, ResourceLocation activity, @Nullable String difficulty) {
        return -1;
    }

    /** A win's base loot rolls before difficulty and modifiers, and whether that's the server's default (1.3.118). */
    record LootRolls(double rolls, boolean serverDefault) {
    }

    /** The activity's base loot rolls; empty when unknown (an arena, an older Arenas_LD). */
    default Optional<LootRolls> baseLootRolls(MinecraftServer server, ResourceLocation activity) {
        return Optional.empty();
    }

    /** The settings of the forming lobby the player owns, for editing (empty when unknown). */
    default Optional<LobbySettings> ownLobbySettings(ServerPlayer owner) {
        return Optional.empty();
    }

    /**
     * Creates a lobby with these settings (its screen opens), or with {@code edit} changes the one the player owns.
     * Null when done, otherwise why not.
     */
    @Nullable
    default Component createLobby(ServerPlayer owner, ResourceLocation activity, LobbySettings settings, boolean edit) {
        return Component.translatable("message.eden_ld.activities.unknown");
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
