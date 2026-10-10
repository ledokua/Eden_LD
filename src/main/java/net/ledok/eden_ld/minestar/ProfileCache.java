package net.ledok.eden_ld.minestar;

import ua.com.minestar.model.UserFriend;
import ua.com.minestar.model.UserGroup;
import ua.com.minestar.model.UserProfile;
import ua.com.minestar.model.UserReward;
import ua.com.minestar.model.UserXp;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minestar profiles of online players, fetched once on join.
 * This is the data source for Hub features: friends list, platform
 * group (role), and prime status. Callers must go through
 * {@link MinestarHook#LOADED} before touching this class.
 */
public final class ProfileCache {

    private static final Map<UUID, UserProfile> PROFILES = new ConcurrentHashMap<>();

    private ProfileCache() {
    }

    static void put(UserProfile profile) {
        PROFILES.put(profile.uuid(), profile);
    }

    static void evict(UUID uuid) {
        PROFILES.remove(uuid);
    }

    public static Optional<UserProfile> get(UUID uuid) {
        return Optional.ofNullable(PROFILES.get(uuid));
    }

    public static List<UserFriend> friends(UUID uuid) {
        return get(uuid).map(UserProfile::friends).orElse(List.of());
    }

    /** Uuids of the player's accepted friends (empty until the join fetch lands). */
    public static java.util.Set<UUID> friendUuids(UUID uuid) {
        java.util.Set<UUID> uuids = new java.util.HashSet<>();
        for (UserFriend friend : friends(uuid)) {
            uuids.add(friend.uuid());
        }
        return uuids;
    }

    public static Optional<UserGroup> group(UUID uuid) {
        return get(uuid).flatMap(UserProfile::group);
    }

    /** Platform group name, if the profile is cached and has one. */
    public static Optional<String> groupName(UUID uuid) {
        return group(uuid).map(UserGroup::name);
    }

    /** True while the cached profile has an unexpired prime status. */
    public static boolean prime(UUID uuid) {
        return get(uuid).map(ProfileCache::prime).orElse(false);
    }

    private static boolean prime(UserProfile profile) {
        return profile.primeStatus()
                .map(status -> status.expiresAt().isAfter(Instant.now()))
                .orElse(false);
    }

    /** The profile in Eden's own types (level, rewards, playtime…) for the hub. */
    static ProfileSnapshot snapshot(UserProfile profile) {
        String group = profile.group().map(UserGroup::name).orElse("");
        int groupColor = profile.group().map(g -> color(g.color())).orElse(-1);
        List<ProfileSnapshot.Reward> rewards = new java.util.ArrayList<>();
        for (UserReward reward : profile.rewards() == null ? List.<UserReward>of() : profile.rewards()) {
            rewards.add(new ProfileSnapshot.Reward(reward.id(), nonNull(reward.title()), nonNull(reward.description()),
                    nonNull(reward.imageUrl()), reward.rarity() == null ? 0 : reward.rarity().ordinal()));
        }
        java.util.Set<UUID> friends = new java.util.HashSet<>();
        for (UserFriend friend : profile.friends() == null ? List.<UserFriend>of() : profile.friends()) {
            friends.add(friend.uuid());
        }
        UserXp xp = profile.xp();
        return new ProfileSnapshot(profile.nickname(), group, groupColor, prime(profile),
                profile.registeredAt() == null ? -1 : profile.registeredAt().toEpochMilli(),
                profile.playtime() == null ? 0 : profile.playtime().toSeconds(),
                xp == null ? 0 : xp.level(), xp == null ? 0 : xp.totalXp(),
                xp == null ? 0 : xp.currentLevelXp(), xp == null ? 0 : xp.xpForCurrentLevel(),
                xp == null ? 0 : xp.xpToNextLevel(),
                List.copyOf(rewards), java.util.Set.copyOf(friends));
    }

    /** "#rrggbb" (or "rrggbb") as RGB; -1 when it isn't a color. */
    private static int color(String hex) {
        if (hex == null) {
            return -1;
        }
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return digits.length() == 6 ? Integer.parseInt(digits, 16) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
