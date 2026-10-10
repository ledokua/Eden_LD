package net.ledok.eden_ld.hub.guild;

/**
 * Fixed guild ranks, lowest first; {@code level} orders them. The vice-leader has every leader
 * power except transferring leadership and disbanding.
 */
public enum GuildRank {
    RECRUIT(0), MEMBER(1), OFFICER(2), VICE_LEADER(3), LEADER(4);

    public final int level;

    GuildRank(int level) {
        this.level = level;
    }

    public static GuildRank of(int level) {
        for (GuildRank rank : values()) {
            if (rank.level == level) {
                return rank;
            }
        }
        return RECRUIT;
    }

    public boolean atLeast(GuildRank other) {
        return level >= other.level;
    }

    /** Leader or vice-leader: guild settings, officer management, the bank's leader controls. */
    public boolean manages() {
        return atLeast(VICE_LEADER);
    }
}
