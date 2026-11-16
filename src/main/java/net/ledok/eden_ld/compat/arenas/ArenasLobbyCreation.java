package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.hub.activity.ActivityLevels;
import net.ledok.eden_ld.hub.activity.ActivitySource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The only class using ArenasApi 4.33.0's lobby creation (the hub's lobby browser and «Створити лобі» panel, 1.3.103):
 * creating and changing lobbies, what an activity offers, its possible drops, and Eden's level for minimum join levels.
 * {@link ArenasIntegration} calls in here only after checking the running Arenas_LD has it.
 */
final class ArenasLobbyCreation {

    /** {@code ArenasApi.lobbySettings(ServerPlayer)} (asked for after 4.33.0), looked up once; null when missing. */
    @Nullable
    private static final Method LOBBY_SETTINGS = lobbySettingsMethod();

    private ArenasLobbyCreation() {
    }

    /** Minimum join levels are Eden's activity level (the same number as the «Рівень N+» unlocks). */
    static void register() {
        // Without a level category nothing is locked by level: then no lobby's minimum stops anyone either.
        ArenasApi.setLevelProvider(player -> ActivityLevels.gated() ? ActivityLevels.level(player) : Integer.MAX_VALUE);
    }

    static int minLevel(ArenasApi.OpenLobby lobby) {
        return lobby.minLevel();
    }

    /** The lobby's run modifiers by name, hardcore left out (it's its own flag). */
    static List<Component> modifierNames(MinecraftServer server, ArenasApi.OpenLobby lobby) {
        if (lobby.modifiers().isEmpty()) {
            return List.of();
        }
        List<ArenasApi.ModifierInfo> known = ArenasApi.activityInfo(server, lobby.activity())
                .map(ArenasApi.ActivityInfo::modifiers).orElse(List.of());
        List<Component> names = new ArrayList<>();
        for (String id : lobby.modifiers()) {
            if (ActivitySource.HARDCORE.equals(id)) {
                continue;
            }
            Component name = Component.literal(id);
            for (ArenasApi.ModifierInfo info : known) {
                if (info.id().equals(id)) {
                    name = info.name();
                    break;
                }
            }
            names.add(name);
        }
        return names;
    }

    static Optional<ActivitySource.CreateInfo> info(MinecraftServer server, ResourceLocation activity) {
        return ArenasApi.activityInfo(server, activity).map(info -> {
            List<ActivitySource.ModifierView> modifiers = new ArrayList<>();
            for (ArenasApi.ModifierInfo modifier : info.modifiers()) {
                modifiers.add(new ActivitySource.ModifierView(modifier.id(), modifier.name(), modifier.description(),
                        modifier.rewards(), List.copyOf(modifier.conflicts())));
            }
            return new ActivitySource.CreateInfo(List.copyOf(info.difficulties()), modifiers, info.maxMembers(),
                    info.levelsActive());
        });
    }

    static List<ItemStack> drops(MinecraftServer server, ResourceLocation activity, @Nullable String difficulty) {
        return ArenasApi.possibleDrops(server, activity, difficulty);
    }

    @Nullable
    static Component create(ServerPlayer owner, ResourceLocation activity, ActivitySource.LobbySettings settings,
                            boolean edit) {
        ArenasApi.LobbySettings arenas = new ArenasApi.LobbySettings(settings.difficulty(), settings.hardcore(),
                List.copyOf(settings.modifiers()), visibility(settings.visibility()), Math.max(0, settings.minLevel()));
        return edit ? ArenasApi.updateLobby(owner, arenas) : ArenasApi.createLobby(owner, activity, arenas, true);
    }

    static Optional<ActivitySource.LobbySettings> ownSettings(ServerPlayer owner) {
        if (LOBBY_SETTINGS == null) {
            return Optional.empty();
        }
        try {
            Object result = LOBBY_SETTINGS.invoke(null, owner);
            if (result instanceof Optional<?> optional && optional.isPresent()
                    && optional.get() instanceof ArenasApi.LobbySettings settings) {
                return Optional.of(new ActivitySource.LobbySettings(settings.difficulty(), settings.hardcore(),
                        List.copyOf(settings.modifiers()), settings.visibility().ordinal(), settings.minLevel()));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A changed signature: editing a private lobby just isn't offered.
        }
        return Optional.empty();
    }

    private static ArenasApi.LobbyVisibility visibility(int ordinal) {
        ArenasApi.LobbyVisibility[] values = ArenasApi.LobbyVisibility.values();
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }

    @Nullable
    private static Method lobbySettingsMethod() {
        try {
            return ArenasApi.class.getMethod("lobbySettings", ServerPlayer.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
