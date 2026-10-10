package net.ledok.eden_ld.compat.waystones;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.waystones.api.requirement.WarpRequirement;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystoneGroups;
import net.blay09.mods.waystones.api.WaystoneVisibility;
import net.blay09.mods.waystones.api.WaystonesAPI;
import net.blay09.mods.waystones.api.TeleportFlags;
import net.blay09.mods.waystones.api.WaystoneTypes;
import net.blay09.mods.waystones.api.event.BuildWaystoneSelectionMenuEvent;
import net.blay09.mods.waystones.api.event.WaystoneActivatedEvent;
import net.blay09.mods.waystones.api.event.WaystoneRemovedEvent;
import net.blay09.mods.waystones.api.event.WaystoneUpdatedEvent;
import net.blay09.mods.waystones.core.PlayerWaystoneManager;
import net.blay09.mods.waystones.core.WaystoneSyncManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * All Waystones-typed code. Only reached through {@link WaystonesHook} when the mod is loaded.
 * <p>
 * Guild waystones: on this server Waystones' "Visible to Team" means <b>visible to the owner's
 * guild</b>. Waystones itself matches scoreboard teams (which Duels_LD uses for matches), so
 * Eden adds guildmates' TEAM stones to the hub and to every Waystones selection menu, and drops
 * TEAM stones Waystones offered through a shared scoreboard team instead.
 */
final class WaystonesIntegration {

    private static volatile MinecraftServer server;

    /** No cost, no cooldown. Waystones reports any unmet requirement as "not enough XP". */
    private static final WarpRequirement FREE = new WarpRequirement() {
        @Override
        public boolean canAfford(Player player) {
            return true;
        }

        @Override
        public void consume(Player player) {
        }

        @Override
        public void rollback(Player player) {
        }

        @Override
        public void appendHoverText(Player player, List<Component> tooltip) {
        }

        @Override
        public boolean isEmpty() {
            return true;
        }
    };

    private WaystonesIntegration() {
    }

    static void register(Consumer<ServerPlayer> onPlayerChanged, Runnable onAllChanged) {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> server = started);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);

        Balm.getEvents().onEvent(WaystoneActivatedEvent.class, event -> {
            if (event.getPlayer() instanceof ServerPlayer player) {
                runOnServer(() -> onPlayerChanged.accept(player));
            }
        });
        Balm.getEvents().onEvent(WaystoneUpdatedEvent.class, event -> runOnServer(onAllChanged));
        Balm.getEvents().onEvent(WaystoneRemovedEvent.class, event -> runOnServer(onAllChanged));
        Balm.getEvents().onEvent(BuildWaystoneSelectionMenuEvent.class, WaystonesIntegration::onBuildMenu);
    }

    /** A TEAM stone shared with the player through their guild (their own included). */
    private static boolean guildShared(@Nullable Guild guild, Waystone waystone) {
        return guild != null && waystone.getVisibility() == WaystoneVisibility.TEAM
                && waystone.hasOwner() && guild.isMember(waystone.getOwnerUid());
    }

    /** Guildmates' TEAM stones the player may travel to (listable ones only). */
    private static List<Waystone> guildStones(MinecraftServer server, ServerPlayer player) {
        Guild guild = GuildManager.get(server).guildOf(player.getUUID());
        if (guild == null) {
            return List.of();
        }
        return WaystonesAPI.getAllWaystones(server)
                .filter(waystone -> guildShared(guild, waystone))
                .filter(WaystonesIntegration::listable)
                .toList();
    }

    /**
     * Waystones' own menus (waystone blocks, warp stones and scrolls, the inventory button,
     * portstones): guild stones join the list, scoreboard-team ones that aren't the guild's
     * leave it. Sharestone networks and the admin inspection menu stay as Waystones built them.
     */
    private static void onBuildMenu(BuildWaystoneSelectionMenuEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || player.getServer() == null
                || event.getFlags().contains(TeleportFlags.ADMIN)) {
            return;
        }
        Waystone source = event.getSourceWaystone();
        ResourceLocation kind = event.getTargetKind();
        if ((kind != null && WaystoneTypes.isSharestone(kind))
                || (source != null && WaystoneTypes.isSharestone(source.getWaystoneType()))) {
            return;
        }
        Guild guild = GuildManager.get(player.getServer()).guildOf(player.getUUID());
        event.getWaystones().removeIf(waystone -> waystone.getVisibility() == WaystoneVisibility.TEAM
                && !guildShared(guild, waystone) && !PlayerWaystoneManager.isWaystoneActivated(player, waystone));
        Set<UUID> listed = new HashSet<>();
        event.getWaystones().forEach(waystone -> listed.add(waystone.getWaystoneUid()));
        if (source != null) {
            listed.add(source.getWaystoneUid());
        }
        for (Waystone waystone : guildStones(player.getServer(), player)) {
            if (listed.add(waystone.getWaystoneUid())) {
                event.addWaystone(PlayerWaystoneManager.getPlayerDecoratedWaystone(player, waystone));
            }
        }
    }

    /** Events fire mid-operation; answer next tick, once Waystones' own state has settled. */
    private static void runOnServer(Runnable task) {
        MinecraftServer current = server;
        if (current != null) {
            current.execute(task);
        }
    }

    static List<WaystonesHook.Stone> targets(ServerPlayer player) {
        MinecraftServer current = player.getServer();
        if (current == null) {
            return List.of();
        }
        Map<UUID, WaystonesHook.Stone> stones = new LinkedHashMap<>();
        WaystonesAPI.getAllWaystones(current)
                .filter(waystone -> waystone.getVisibility() == WaystoneVisibility.GLOBAL)
                .filter(WaystonesIntegration::listable)
                .forEach(waystone -> stones.put(waystone.getWaystoneUid(), stone(player, waystone, true)));
        for (Waystone waystone : guildStones(current, player)) {
            stones.putIfAbsent(waystone.getWaystoneUid(), stone(player, waystone, false));
        }
        for (Waystone waystone : WaystonesAPI.getActivatedWaystones(player)) {
            if (listable(waystone)) {
                stones.putIfAbsent(waystone.getWaystoneUid(), stone(player, waystone, false));
            }
        }
        return new ArrayList<>(stones.values());
    }

    private static boolean listable(Waystone waystone) {
        return waystone.isValid() && !waystone.isTransient() && waystone.hasName();
    }

    private static WaystonesHook.Stone stone(ServerPlayer player, Waystone waystone, boolean global) {
        UUID owner = waystone.hasOwner() ? waystone.getOwnerUid() : null;
        String ownerName = waystone.hasOwner() && waystone.getOwnerUsername() != null
                ? waystone.getOwnerUsername() : "";
        UUID id = waystone.getWaystoneUid();
        Component alias = PlayerWaystoneManager.getWaystoneAlias(player, id).orElse(null);
        boolean favorite = PlayerWaystoneManager.getConfiguredWaystoneGroups(player, id)
                .contains(WaystoneGroups.FAVORITES.identifier());
        return new WaystonesHook.Stone(id, waystone.getName(), waystone.getDimension().location(),
                waystone.getPos(), global, owner, ownerName, alias, favorite);
    }

    // Per-player settings go through the same (internal) calls Waystones' own settings
    // screen makes server side, then re-sync the player's Waystones client data so the
    // mod's menus show the change too.

    static void setFavorite(ServerPlayer player, UUID id, boolean favorite) {
        if (!reachable(player, id)) {
            return;
        }
        Set<ResourceLocation> groups = new LinkedHashSet<>(PlayerWaystoneManager.getConfiguredWaystoneGroups(player, id));
        boolean changed = favorite
                ? groups.add(WaystoneGroups.FAVORITES.identifier())
                : groups.remove(WaystoneGroups.FAVORITES.identifier());
        if (changed) {
            PlayerWaystoneManager.setConfiguredWaystoneGroups(player, id, groups);
            WaystoneSyncManager.sendActivatedWaystones(player);
        }
    }

    static void setAlias(ServerPlayer player, UUID id, String alias) {
        if (!reachable(player, id)) {
            return;
        }
        String trimmed = alias.trim();
        PlayerWaystoneManager.setWaystoneAlias(player, id, trimmed.isEmpty() ? null : Component.literal(trimmed));
        WaystoneSyncManager.sendActivatedWaystones(player);
    }

    static void forget(ServerPlayer player, UUID id) {
        MinecraftServer current = player.getServer();
        if (current == null || !reachable(player, id)) {
            return;
        }
        WaystonesAPI.getWaystone(current, id).ifPresent(waystone -> {
            if (waystone.getVisibility() != WaystoneVisibility.GLOBAL) {
                WaystonesAPI.deactivateWaystone(player, waystone);
                WaystoneSyncManager.sendActivatedWaystones(player);
            }
        });
    }

    /** Only stones the tab lists for this player — never trust a client-sent id alone. */
    private static boolean reachable(ServerPlayer player, UUID id) {
        return targets(player).stream().anyMatch(stone -> stone.id().equals(id));
    }

    static void teleport(ServerPlayer player, UUID id, Consumer<Component> onDone) {
        MinecraftServer current = player.getServer();
        if (current == null) {
            return;
        }
        boolean allowed = reachable(player, id);
        Waystone waystone = allowed ? WaystonesAPI.getWaystone(current, id).orElse(null) : null;
        if (waystone == null) {
            onDone.accept(Component.translatable("message.eden_ld.waystones.unknown"));
            return;
        }
        // Eden enforces its own cooldown and PvP rules before getting here; replace whatever
        // Waystones' config would charge (XP, items, its cooldowns) with nothing.
        WaystonesAPI.createDefaultTeleportContext(player, waystone, context -> { })
                .ifLeft(context -> WaystonesAPI.tryTeleportAsync(context.setRequirements(FREE))
                        .thenAccept(result -> current.execute(() -> onDone.accept(
                                result.map(entities -> null, error -> error.getComponent()))))
                        .exceptionally(error -> {
                            EdenLD.LOGGER.error("[Waystones] Hub teleport failed.", error);
                            current.execute(() -> onDone.accept(
                                    Component.translatable("message.eden_ld.waystones.failed")));
                            return null;
                        }))
                .ifRight(error -> onDone.accept(error.getComponent()));
    }
}
