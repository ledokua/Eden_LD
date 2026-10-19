package net.ledok.eden_ld.combat;

import org.junit.jupiter.api.Test;

import static net.ledok.eden_ld.combat.PvpRelation.ENEMIES;
import static net.ledok.eden_ld.combat.PvpRelation.FRIENDS;
import static net.ledok.eden_ld.combat.PvpRelation.IGNORE;
import static net.ledok.eden_ld.combat.PvpRelation.NO_MATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The relation matrix of docs/PLAN-pvp-toggle.md: teams, run teams, duels, effective states and world modes. */
class PvpRelationTest {

    private static final boolean[] BOTH = {false, true};

    /** Two players outside any duel: party or guild ({@code group}), run team, their flags, the world's mode. */
    private static PvpRelation in(PvpMode mode, boolean group, boolean runTeam, boolean flagA, boolean flagB) {
        return PvpRelation.of(group, runTeam, NO_MATCH, NO_MATCH, mode.effective(flagA), mode.effective(flagB));
    }

    @Test
    void sameTeamBothOnOrBothOffAreFriends() {
        assertEquals(FRIENDS, PvpRelation.of(true, false, NO_MATCH, NO_MATCH, true, true));
        assertEquals(FRIENDS, PvpRelation.of(true, false, NO_MATCH, NO_MATCH, false, false));
    }

    @Test
    void sameTeamOneOnOneOffIgnoreEachOther() {
        // A PvP-off guildmate can't heal or buff a PvP-on fighter risk-free.
        assertEquals(IGNORE, PvpRelation.of(true, false, NO_MATCH, NO_MATCH, true, false));
        assertEquals(IGNORE, PvpRelation.of(true, false, NO_MATCH, NO_MATCH, false, true));
    }

    @Test
    void noTeamBothOnAreEnemiesElseIgnore() {
        assertEquals(ENEMIES, PvpRelation.of(false, false, NO_MATCH, NO_MATCH, true, true));
        assertEquals(IGNORE, PvpRelation.of(false, false, NO_MATCH, NO_MATCH, true, false));
        assertEquals(IGNORE, PvpRelation.of(false, false, NO_MATCH, NO_MATCH, false, true));
        assertEquals(IGNORE, PvpRelation.of(false, false, NO_MATCH, NO_MATCH, false, false));
    }

    @Test
    void aRunTeamStaysFriendsWhateverTheFlags() {
        for (boolean group : BOTH) {
            for (boolean a : BOTH) {
                for (boolean b : BOTH) {
                    assertEquals(FRIENDS, PvpRelation.of(group, true, NO_MATCH, NO_MATCH, a, b),
                            "group " + group + ", " + a + "/" + b);
                }
            }
        }
    }

    @Test
    void theSameDuelFollowsItsTeamsWhateverTheFlags() {
        for (boolean group : BOTH) {
            for (boolean a : BOTH) {
                for (boolean b : BOTH) {
                    // Opponents (different sides) fight; partners (one side, a scoreboard team without friendly fire) don't.
                    assertEquals(ENEMIES, PvpRelation.of(group, false, 7, 7, a, b));
                    assertEquals(FRIENDS, PvpRelation.of(group, true, 7, 7, a, b));
                }
            }
        }
    }

    @Test
    void aDuelistAndAnyoneOutsideTheMatchIgnoreEachOther() {
        for (boolean group : BOTH) {
            for (boolean runTeam : BOTH) {
                assertEquals(IGNORE, PvpRelation.of(group, runTeam, 7, NO_MATCH, true, true), "duelist hits an outsider");
                assertEquals(IGNORE, PvpRelation.of(group, runTeam, NO_MATCH, 7, true, true), "outsider hits a duelist");
                assertEquals(IGNORE, PvpRelation.of(group, runTeam, 7, 8, true, true), "two different matches");
            }
        }
    }

    @Test
    void anOffWorldMakesStrangersIgnoreAndKeepsTeamsFriends() {
        for (boolean a : BOTH) {
            for (boolean b : BOTH) {
                assertEquals(IGNORE, in(PvpMode.OFF, false, false, a, b));
                assertEquals(FRIENDS, in(PvpMode.OFF, true, false, a, b), "party and guild heal each other at spawn");
            }
        }
    }

    @Test
    void anOnWorldMakesStrangersEnemiesAndKeepsTeamsFriends() {
        for (boolean a : BOTH) {
            for (boolean b : BOTH) {
                assertEquals(ENEMIES, in(PvpMode.ON, false, false, a, b));
                assertEquals(FRIENDS, in(PvpMode.ON, true, false, a, b));
            }
        }
    }

    @Test
    void aToggleWorldFollowsEachPlayersFlag() {
        assertEquals(ENEMIES, in(PvpMode.TOGGLE, false, false, true, true));
        assertEquals(IGNORE, in(PvpMode.TOGGLE, false, false, true, false));
        assertEquals(IGNORE, in(PvpMode.TOGGLE, false, false, false, false));
        assertEquals(FRIENDS, in(PvpMode.TOGGLE, true, false, true, true));
        assertEquals(FRIENDS, in(PvpMode.TOGGLE, true, false, false, false));
        assertEquals(IGNORE, in(PvpMode.TOGGLE, true, false, false, true));
    }

    @Test
    void theRelationIsSymmetric() {
        int[] matches = {NO_MATCH, 7, 8};
        for (boolean group : BOTH) {
            for (boolean runTeam : BOTH) {
                for (int ma : matches) {
                    for (int mb : matches) {
                        for (boolean a : BOTH) {
                            for (boolean b : BOTH) {
                                assertEquals(PvpRelation.of(group, runTeam, ma, mb, a, b),
                                        PvpRelation.of(group, runTeam, mb, ma, b, a));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void theIgnoreReasonNamesTheDuelFirstThenTheWorldThenWhoIsOff() {
        assertEquals(PvpRelation.Reason.TARGET_DUEL, PvpRelation.reason(NO_MATCH, 7, PvpMode.OFF, false, false));
        assertEquals(PvpRelation.Reason.TARGET_DUEL, PvpRelation.reason(8, 7, PvpMode.TOGGLE, true, true));
        assertEquals(PvpRelation.Reason.OWN_DUEL, PvpRelation.reason(7, NO_MATCH, PvpMode.TOGGLE, true, false));
        assertEquals(PvpRelation.Reason.WORLD_OFF, PvpRelation.reason(NO_MATCH, NO_MATCH, PvpMode.OFF, false, false));
        assertEquals(PvpRelation.Reason.TARGET_OFF, PvpRelation.reason(NO_MATCH, NO_MATCH, PvpMode.TOGGLE, true, false));
        assertEquals(PvpRelation.Reason.TARGET_OFF, PvpRelation.reason(NO_MATCH, NO_MATCH, PvpMode.TOGGLE, false, false),
                "both off: \"Bob has PvP off\"");
        assertEquals(PvpRelation.Reason.OWN_OFF, PvpRelation.reason(NO_MATCH, NO_MATCH, PvpMode.TOGGLE, false, true));
    }
}
