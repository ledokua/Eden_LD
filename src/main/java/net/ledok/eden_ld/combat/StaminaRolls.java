package net.ledok.eden_ld.combat;

import net.combat_roll.api.RollInvulnerable;
import net.combat_roll.api.event.ServerSideRollEvents;

/** The Combat Roll side of {@link Stamina}; only loaded with Combat Roll installed. */
final class StaminaRolls {

    private StaminaRolls() {
    }

    static void register() {
        // Runs right after Combat Roll gave the roll its configured invulnerability: replace it with the dodge type's, or
        // none when the server's stamina refuses the roll (the client's move already happened).
        ServerSideRollEvents.PLAYER_START_ROLLING.register((player, velocity) -> {
            if (Stamina.enabled() && player instanceof RollInvulnerable invulnerable) {
                invulnerable.setRollInvulnerableTicks(Stamina.roll(player));
            }
        });
    }
}
