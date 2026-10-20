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
        if (!ADMIN) {
            EdenLD.LOGGER.info("[Arenas] This Arenas_LD has no admin API (4.9.0+): Moderation's controller actions stay hidden.");
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

    // ---------------------------------------------------------------- admin (4.9.0+)

    @Override
    public Set<AdminAction> adminActions() {
        return ADMIN ? EnumSet.allOf(AdminAction.class) : Set.of();
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
    public Component forget(MinecraftServer server, ResourceLocation id) {
        return ADMIN ? ArenasAdmin.forget(server, id) : ActivitySource.unsupported();
    }

    @Override
    public Optional<ActivityLiveStatus> status(MinecraftServer server, ResourceLocation id) {
        return ADMIN ? ArenasAdmin.status(server, id) : Optional.empty();
    }
}
