package net.ledok.eden_ld.client.hub;

/** The Guild tab's sub-tabs (in a guild). */
enum GuildView {
    MEMBERS, RECRUITING, QUESTS, LEVEL, BANK;

    /** Recruiting is for officers and up. */
    boolean visibleTo(int rank) {
        return this != RECRUITING || rank >= net.ledok.eden_ld.hub.guild.GuildRank.OFFICER.level;
    }
}
