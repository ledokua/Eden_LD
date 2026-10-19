package net.ledok.eden_ld.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The player's own switch: a minute's cooldown both ways, none while combat-tagged (PvpSwitch). */
class PvpSwitchTest {

    private static final long NOW = 1_800_000_000L;

    @Test
    void neverSwitchedHasNoCooldown() {
        assertEquals(0, PvpSwitch.cooldownLeft(NOW, 0));
        assertEquals(PvpSwitch.Result.SWITCHED, PvpSwitch.check(false, true, NOW, 0, 0));
    }

    @Test
    void theCooldownRunsAMinuteAfterAnySwitch() {
        assertEquals(60, PvpSwitch.cooldownLeft(NOW, NOW));
        assertEquals(45, PvpSwitch.cooldownLeft(NOW, NOW - 15));
        assertEquals(1, PvpSwitch.cooldownLeft(NOW, NOW - 59));
        assertEquals(0, PvpSwitch.cooldownLeft(NOW, NOW - 60));
        assertEquals(0, PvpSwitch.cooldownLeft(NOW, NOW - 3600));
    }

    @Test
    void theCooldownHoldsBothWays() {
        assertEquals(PvpSwitch.Result.COOLDOWN, PvpSwitch.check(false, true, NOW, NOW - 10, 0), "off → on");
        assertEquals(PvpSwitch.Result.COOLDOWN, PvpSwitch.check(true, false, NOW, NOW - 10, 0), "on → off");
        assertEquals(PvpSwitch.Result.SWITCHED, PvpSwitch.check(true, false, NOW, NOW - 60, 0));
    }

    @Test
    void aClockThatWentBackNeverWaitsLongerThanTheCooldown() {
        assertEquals(PvpSwitch.COOLDOWN_SECONDS, PvpSwitch.cooldownLeft(NOW, NOW + 3600));
    }

    @Test
    void aCombatTagBlocksSwitchingEitherWay() {
        assertEquals(PvpSwitch.Result.IN_COMBAT, PvpSwitch.check(true, false, NOW, 0, 12_000));
        assertEquals(PvpSwitch.Result.IN_COMBAT, PvpSwitch.check(false, true, NOW, 0, 1));
        // In combat and in the cooldown: the combat tag is named (it's what the player can see running).
        assertEquals(PvpSwitch.Result.IN_COMBAT, PvpSwitch.check(true, false, NOW, NOW - 5, 12_000));
    }

    @Test
    void askingForTheCurrentStateChangesNothing() {
        assertEquals(PvpSwitch.Result.ALREADY, PvpSwitch.check(true, true, NOW, NOW, 30_000));
        assertEquals(PvpSwitch.Result.ALREADY, PvpSwitch.check(false, false, NOW, 0, 0));
    }
}
