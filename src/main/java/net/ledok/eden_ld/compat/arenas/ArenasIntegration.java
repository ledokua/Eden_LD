package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.activity.Activities;
import net.ledok.eden_ld.hub.activity.Activity;
import net.ledok.eden_ld.hub.activity.ActivityGate;
import net.ledok.eden_ld.hub.activity.ActivitySource;
import net.ledok.eden_ld.hub.activity.ActivityServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Arenas_LD behind {@link ArenasHook}: its activities as an {@link ActivitySource} (the hub card
 * opens the lobby screen), Eden's level check as its entry gate, and finished runs into the
 * activity stats and dailies. Only class-loaded when Arenas_LD is present.
 * <p>
 * No Moderation controller actions yet (the {@link ActivitySource} admin defaults: unsupported) — the
 * 4.6.0 jar has no calls for them. With ArenasApi 4.7.0 this overrides {@code adminActions()} and
 * delegates: teleport → {@code location}/{@code teleportToController}, rename → {@code rename},
 * openAdmin → {@code openAdmin}, status → {@code status} (into {@code ActivityLiveStatus}), forceEnd →
 * {@code forceEnd}, forget → {@code forget}; and its forced-end run reports go to
 * {@code ActivityServer.recordRun(..., forced = true)}. See docs/PLAN-activity-admin.md.
 */
final class ArenasIntegration implements ActivitySource {

    private static final ArenasIntegration INSTANCE = new ArenasIntegration();

    private ArenasIntegration() {
    }

    static void register() {
        Activities.register(INSTANCE);
        ArenasApi.addActivitiesListener(Activities::changed);
        ArenasApi.registerEntryGate(ActivityGate::check);
        ArenasApi.addRunListener(run -> {
            MinecraftServer server = EdenLD.server();
            if (server != null) {
                ActivityServer.recordRun(server, run.activity(), run.difficulty(), run.outcome() == ArenasApi.Outcome.WIN,
                        run.durationTicks() * 50L, run.wave(), run.players());
            }
        });
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
}
