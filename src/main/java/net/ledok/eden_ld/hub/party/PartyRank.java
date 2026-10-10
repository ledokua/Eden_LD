package net.ledok.eden_ld.hub.party;

/** Party member ranks. Higher {@link #level} outranks lower. */
public enum PartyRank {
    MEMBER(0),
    VICE(1),
    LEADER(2);

    public final int level;

    PartyRank(int level) {
        this.level = level;
    }

    public static PartyRank byLevel(int level) {
        for (PartyRank rank : values()) {
            if (rank.level == level) {
                return rank;
            }
        }
        return MEMBER;
    }
}
