package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.activity.Activities;
import net.ledok.eden_ld.hub.activity.Activity;
import net.ledok.eden_ld.hub.activity.ActivityGate;
import net.ledok.eden_ld.hub.activity.ActivityLiveStatus;
import net.ledok.eden_ld.hub.activity.ActivitySource;
import net.ledok.eden_ld.hub.activity.ActivityServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Arenas_LD behind {@link ArenasHook}: its activities as an {@link ActivitySource} (the hub card
 * opens the lobby screen), Eden's level check as its entry gate, and finished runs into the
 * activity stats and dailies. Only class-loaded when Arenas_LD is present.
 * <p>
 * The Moderation window's controller actions (teleport, rename, admin screen, live status, end run, forget)
 * need ArenasApi 4.9.0+. Whether the running Arenas_LD has them is looked up once by reflection
 * ({@link #ADMIN}); the calls themselves live in {@link ArenasAdmin}, the only class touching the 4.9.0 types,
 * so an older Arenas_LD just leaves the buttons hidden.
 */
final class ArenasIntegration implements ActivitySource {

    private static final ArenasIntegration INSTANCE = new ArenasIntegration();

    /** The running Arenas_LD has the admin calls (4.9.0+). */
    private static final boolean ADMIN = adminApi();
    /** The running Arenas_LD opens a run's details screen (4.22.0+). */
    private static final boolean RUN_DETAILS = runDetailsApi();
    /** The running Arenas_LD resets instance cooldowns (4.21.1+). */
    private static final boolean RESET_COOLDOWNS = resetCooldownsApi();
    /** The running Arenas_LD tells a player's own lobby and opens it (4.21.0+). */
    private static final boolean OWN_LOBBY = ownLobbyApi();
    /** The running Arenas_LD takes rental builds for runs, by preset (4.26.1+). */
    private static final boolean RENTAL = rentalApi();
    /** The running Arenas_LD asks before each invite (4.28.0+). */
    private static final boolean INVITE_FILTER = inviteFilterApi();
    /** The running Arenas_LD lists every open lobby on the server (4.31.0+). */
    private static final boolean OPEN_LOBBIES = openLobbiesApi();
    /** The running Arenas_LD creates and changes lobbies for a hub, with minimum levels (4.33.0+). */
    private static final boolean CREATE = createApi();
    /** The running Arenas_LD shows a party's HP in its ally sidebar outside runs (4.34.0+). */
    private static final boolean PARTY = partyApi();

    private ArenasIntegration() {
    }

    static void register() {
        Activities.register(INSTANCE);
        ArenasApi.addActivitiesListener(Activities::changed);
        ArenasApi.registerEntryGate(ActivityGate::check);
        ArenasApi.addRunListener(run -> {
            MinecraftServer server = EdenLD.server();
            if (server != null) {
                // FORCED (4.9.0+, a staff end) by name: an older Arenas_LD has no such constant.
                boolean forced = "FORCED".equals(run.outcome().name());
                ActivityServer.recordRun(server, run.activity(), run.difficulty(), run.outcome() == ArenasApi.Outcome.WIN,
                        run.durationTicks() * 50L, run.wave(), run.players(), forced);
            }
        });
        if (requirementApi()) {
            ArenasRequirement.register();
        }
        if (RENTAL) {
            ArenasRental.register();
        }
        if (INVITE_FILTER) {
            ArenasInvites.register();
        }
        if (CREATE) {
            ArenasLobbyCreation.register();
        }
        if (PARTY) {
            ArenasParty.register();
        }
        EdenLD.LOGGER.info("[Arenas] Arenas_LD APIs found: admin {}, cooldown resets {}, run details {}, own lobby {}, "
                + "rental builds {}, invite filter {}, open lobbies {}, lobby creation {}, party sidebar {}.", ADMIN,
                RESET_COOLDOWNS, RUN_DETAILS, OWN_LOBBY, RENTAL, INVITE_FILTER, OPEN_LOBBIES, CREATE, PARTY);
        if (!ADMIN) {
            EdenLD.LOGGER.info("[Arenas] This Arenas_LD has no admin API (4.9.0+): Moderation's controller actions stay hidden.");
        }
    }

    /** The running Arenas_LD shows a met requirement (4.16.0+); an older one only shows the gate's refusal. */
    private static boolean requirementApi() {
        try {
            ArenasApi.class.getMethod("registerEntryRequirement",
                    Class.forName("net.ledok.arenas_ld.api.ArenasApi$EntryRequirement"));
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean runDetailsApi() {
        try {
            ArenasApi.class.getMethod("openRunDetails", ServerPlayer.class, ResourceLocation.class,
                    Class.forName("net.ledok.arenas_ld.api.ArenasApi$InstanceRef"));
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean resetCooldownsApi() {
        try {
            ArenasApi.class.getMethod("resetCooldowns", MinecraftServer.class, ResourceLocation.class,
                    Class.forName("net.ledok.arenas_ld.api.ArenasApi$InstanceRef"), String.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean rentalApi() {
        try {
            Class<?> provider = Class.forName("net.ledok.arenas_ld.api.ArenasApi$RentalProvider");
            ArenasApi.class.getMethod("setRentalProvider", provider);
            // 4.26.1's presets (4.26.0 picked kits by class and band: not served).
            provider.getMethod("presets", int.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean inviteFilterApi() {
        try {
            ArenasApi.class.getMethod("setInviteFilter",
                    Class.forName("net.ledok.arenas_ld.api.ArenasApi$InviteFilter"));
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean partyApi() {
        try {
            ArenasApi.class.getMethod("setPartyProvider", java.util.function.Function.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /** 4.33.0's createLobby, updateLobby, activityInfo, possibleDrops and setLevelProvider (with the open lobbies). */
    static boolean createApi() {
        try {
            Class<?> settings = Class.forName("net.ledok.arenas_ld.api.ArenasApi$LobbySettings");
            ArenasApi.class.getMethod("createLobby", ServerPlayer.class, ResourceLocation.class, settings, boolean.class);
            ArenasApi.class.getMethod("updateLobby", ServerPlayer.class, settings);
            ArenasApi.class.getMethod("activityInfo", MinecraftServer.class, ResourceLocation.class);
            ArenasApi.class.getMethod("possibleDrops", MinecraftServer.class, ResourceLocation.class, String.class);
            ArenasApi.class.getMethod("setLevelProvider", java.util.function.ToIntFunction.class);
            return openLobbiesApi();
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean openLobbiesApi() {
        try {
            ArenasApi.class.getMethod("listOpenLobbies", MinecraftServer.class);
            ArenasApi.class.getMethod("joinOpenLobby", ServerPlayer.class, ResourceLocation.class, java.util.UUID.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean ownLobbyApi() {
        try {
            ArenasApi.class.getMethod("ownLobby", ServerPlayer.class);
            ArenasApi.class.getMethod("openOwnLobby", ServerPlayer.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean adminApi() {
        try {
            Class<?> api = ArenasApi.class;
            api.getMethod("teleportToController", ServerPlayer.class, ResourceLocation.class);
            api.getMethod("rename", MinecraftServer.class, ResourceLocation.class, String.class);
            api.getMethod("openAdmin", ServerPlayer.class, ResourceLocation.class);
            api.getMethod("status", MinecraftServer.class, ResourceLocation.class);
            api.getMethod("forceEnd", MinecraftServer.class, ResourceLocation.class,
                    Class.forName("net.ledok.arenas_ld.api.ArenasApi$InstanceRef"));
            api.getMethod("forget", MinecraftServer.class, ResourceLocation.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    @Override
    public List<Activity> activities(MinecraftServer server) {
        List<Activity> out = new ArrayList<>();
        for (ArenasApi.Activity activity : ArenasApi.activities(server)) {
            List<Activity.Difficulty> difficulties = new ArrayList<>();
            // Only what exists: levels and card art are set in Eden's Moderation window, not in Arenas.
            for (ArenasApi.Difficulty difficulty : activity.difficulties()) {
                difficulties.add(new Activity.Difficulty(difficulty.id(), 0));
            }
            out.add(new Activity(activity.id(), Activity.Type.valueOf(activity.type().name()), activity.name(),
                    List.copyOf(difficulties), 0, null));
        }
        return out;
    }

    @Override
    public String name() {
        return "Arenas_LD";
    }

    @Override
    public boolean open(ServerPlayer player, ResourceLocation activity) {
        return ArenasApi.openLobbies(player, activity);
    }

    @Override
    public Optional<OwnLobby> ownLobby(ServerPlayer player) {
        return OWN_LOBBY ? ArenasOwnLobby.of(player) : Optional.empty();
    }

    @Override
    public boolean openOwnLobby(ServerPlayer player) {
        return OWN_LOBBY && ArenasOwnLobby.open(player);
    }

    @Override
    public java.util.Map<ResourceLocation, List<OpenLobby>> openLobbies(ServerPlayer viewer) {
        return OPEN_LOBBIES ? ArenasOpenLobbies.of(viewer) : java.util.Map.of();
    }

    @Override
    public boolean listsLobbies() {
        return OPEN_LOBBIES;
    }

    @Override
    public boolean createsLobbies() {
        return CREATE;
    }

    @Override
    public Optional<CreateInfo> createInfo(MinecraftServer server, ResourceLocation activity) {
        return CREATE ? ArenasLobbyCreation.info(server, activity) : Optional.empty();
    }

    @Override
    public List<net.minecraft.world.item.ItemStack> possibleDrops(MinecraftServer server, ResourceLocation activity,
                                                                   @Nullable String difficulty) {
        return CREATE ? ArenasLobbyCreation.drops(server, activity, difficulty) : List.of();
    }

    @Override
    public Optional<LobbySettings> ownLobbySettings(ServerPlayer owner) {
        return CREATE ? ArenasLobbyCreation.ownSettings(owner) : Optional.empty();
    }

    @Override
    public @Nullable Component createLobby(ServerPlayer owner, ResourceLocation activity, LobbySettings settings,
                                           boolean edit) {
        return CREATE ? ArenasLobbyCreation.create(owner, activity, settings, edit)
                : Component.translatable("message.eden_ld.activities.unknown");
    }

    @Override
    public @Nullable Component joinLobby(ServerPlayer player, ResourceLocation activity, java.util.UUID lobby) {
        return OPEN_LOBBIES ? ArenasOpenLobbies.join(player, activity, lobby)
                : Component.translatable("message.eden_ld.activities.unknown");
    }

    // ---------------------------------------------------------------- admin (4.9.0+)

    @Override
    public Set<AdminAction> adminActions() {
        if (!ADMIN) {
            return Set.of();
        }
        EnumSet<AdminAction> actions = EnumSet.allOf(AdminAction.class);
        if (!RESET_COOLDOWNS) {
            actions.remove(AdminAction.RESET_COOLDOWN);
        }
        if (!RUN_DETAILS) {
            actions.remove(AdminAction.RUN_DETAILS);
        }
        return actions;
    }

    @Override
    @Nullable
    public Component teleport(ServerPlayer admin, ResourceLocation id) {
        return ADMIN ? ArenasAdmin.teleport(admin, id) : ActivitySource.unsupported();
    }

    @Override
    @Nullable
    public Component rename(ServerPlayer admin, ResourceLocation id, String name) {
        return ADMIN ? ArenasAdmin.rename(admin.server, id, name) : ActivitySource.unsupported();
    }

    @Override
    @Nullable
    public Component openAdmin(ServerPlayer admin, ResourceLocation id) {
        return ADMIN ? ArenasAdmin.openAdmin(admin, id) : ActivitySource.unsupported();
    }

    @Override
    @Nullable
    public Component forceEnd(MinecraftServer server, ResourceLocation id, @Nullable InstanceRef instance) {
        return ADMIN ? ArenasAdmin.forceEnd(server, id, instance) : ActivitySource.unsupported();
    }
    @Override
    @Nullable
    public Component resetCooldowns(MinecraftServer server, ResourceLocation id, @Nullable InstanceRef instance, String actor) {
        return RESET_COOLDOWNS ? ArenasAdmin.resetCooldowns(server, id, instance, actor) : ActivitySource.unsupported();
    }
    @Override
    @Nullable
    public Component openRunDetails(ServerPlayer staff, ResourceLocation id, InstanceRef instance) {
        if (!RUN_DETAILS) {
            return ActivitySource.unsupported();
        }
        return ArenasAdmin.openRunDetails(staff, id, instance) ? null
                : Component.translatable("message.eden_ld.admin.activity_no_run_details");
    }



    @Override
    @Nullable
    public Component forget(MinecraftServer server, ResourceLocation id) {
        return ADMIN ? ArenasAdmin.forget(server, id) : ActivitySource.unsupported();
    }

    @Override
    public Optional<ActivityLiveStatus> status(MinecraftServer server, ResourceLocation id) {
        return ADMIN ? ArenasAdmin.status(server, id) : Optional.empty();
    }
}
