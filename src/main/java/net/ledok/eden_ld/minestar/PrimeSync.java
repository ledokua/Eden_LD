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
import java.util.List;

/**
 * Keeps the LuckPerms prime group in sync with the platform's prime status, on join. The group is given with the
 * Prime's expiry, so it also ends while the player is offline.
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
        // Second precision, as LuckPerms stores expiries; null: no (current) Prime.
        Instant expiry = profile.primeStatus()
                .map(status -> status.expiresAt())
                .filter(expiresAt -> expiresAt.isAfter(Instant.now()))
                .map(expiresAt -> Instant.ofEpochSecond(expiresAt.getEpochSecond()))
                .orElse(null);
        String playerName = player.getGameProfile().getName();

        luckPerms.getUserManager().loadUser(player.getUUID()).thenAccept(user -> {
            List<InheritanceNode> current = user.getNodes(NodeType.INHERITANCE).stream()
                    .filter(node -> node.getGroupName().equalsIgnoreCase(group))
                    .toList();
            if (expiry == null) {
                if (current.isEmpty()) {
                    return;
                }
                current.forEach(node -> user.data().remove(node));
                EdenLD.LOGGER.info("[Prime Sync] Removed '{}' group from {}.", group, playerName);
            } else {
                // The group expires with the Prime, so LuckPerms drops it even while they're offline (their guild's
                // Prime claims go with it). A permanent node from older versions is replaced; a renewal moves it.
                if (current.size() == 1 && expiry.equals(current.get(0).getExpiry())) {
                    return;
                }
                current.forEach(node -> user.data().remove(node));
                user.data().add(InheritanceNode.builder(group).expiry(expiry).build());
                EdenLD.LOGGER.info("[Prime Sync] '{}' group for {} until {}{}.", group, playerName, expiry,
                        current.isEmpty() ? "" : " (updated)");
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
