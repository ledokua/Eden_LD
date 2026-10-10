package net.ledok.eden_ld.minestar;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A player's Minestar profile in Eden's own types, so hub code never touches
 * {@code ua.com.minestar} classes. {@code groupColor} is RGB (-1 without a group);
 * {@code registeredAtMillis} is epoch millis; {@code playtimeSeconds} is summed over every
 * Minestar server. The level fields are the platform's activity level: {@code levelXp} out of
 * {@code levelXpNeeded} into the current level, {@code xpToNextLevel} left.
 */
public record ProfileSnapshot(String nickname, String group, int groupColor, boolean prime,
                              long registeredAtMillis, long playtimeSeconds,
                              int level, long totalXp, long levelXp, long levelXpNeeded, long xpToNextLevel,
                              List<Reward> rewards, Set<UUID> friends) {

    /** An earned platform reward (achievement). {@code rarity}: 0 common, 1 rare, 2 epic, 3 legendary. */
    public record Reward(long id, String title, String description, String imageUrl, int rarity) {
    }
}
