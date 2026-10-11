package net.ledok.eden_ld.tutorial.phase;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Mixed into every entity ({@code PhaseEntityMixin}): the phase it belongs to — a tutorial player's id — or null for
 * the shared world. Saved with the entity, so it survives chunk unloads, relogs and restarts.
 */
public interface Phased {

    @Nullable
    UUID eden_ld$phase();

    void eden_ld$setPhase(@Nullable UUID phase);
}
