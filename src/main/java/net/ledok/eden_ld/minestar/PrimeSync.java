package net.ledok.eden_ld.minestar;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.minecraft.server.level.ServerPlayer;
import ua.com.minestar.model.UserProfile;

import java.time.Instant;

/**
 * Keeps the LuckPerms prime group in sync with the platform's prime status.
 * Fully async: LuckPerms user load/save run on LuckPerms' own executor,
 * nothing blocks the server thread.
 */
final class PrimeSync {

    private PrimeSync() {
    }

    static void sync(ServerPlayer player, UserProfile profile) {
        LuckPerms luckPerms = luckPerms();
        if (luckPerms == null) {
            return;
        }

        String group = EdenLD.CONFIG.minestar.prime_group;
        boolean apiHasPrime = profile.primeStatus()
                .map(status -> status.expiresAt().isAfter(Instant.now()))
                .orElse(false);
        String playerName = player.getGameProfile().getName();

        luckPerms.getUserManager().loadUser(player.getUUID()).thenAccept(user -> {
            boolean lpHasPrime = user.getNodes(NodeType.INHERITANCE).stream()
                    .anyMatch(node -> node.getGroupName().equalsIgnoreCase(group));
            if (apiHasPrime == lpHasPrime) {
                return;
            }

            InheritanceNode node = InheritanceNode.builder(group).build();
            if (apiHasPrime) {
                user.data().add(node);
                EdenLD.LOGGER.info("[Prime Sync] Added '{}' group to {}.", group, playerName);
            } else {
                user.data().remove(node);
                EdenLD.LOGGER.info("[Prime Sync] Removed '{}' group from {}.", group, playerName);
            }
            luckPerms.getUserManager().saveUser(user);
        }).exceptionally(cause -> {
            EdenLD.LOGGER.error("[Prime Sync] Failed to sync prime group for {}.", playerName, cause);
            return null;
        });
    }

    private static LuckPerms luckPerms() {
        if (!FabricLoader.getInstance().isModLoaded("luckperms")) {
            return null;
        }
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            EdenLD.LOGGER.warn("[Prime Sync] LuckPerms API not available.");
            return null;
        }
    }
}
