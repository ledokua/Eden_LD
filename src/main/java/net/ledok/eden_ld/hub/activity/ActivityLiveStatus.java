package net.ledok.eden_ld.hub.activity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What an activity is doing right now, as its source reports it ({@link ActivitySource#status}) — the
 * Moderation window's live block. Eden's own shape: a source translates into it.
 *
 * @param instances   every place the activity runs at (an Arenas_LD controller each)
 * @param runs        the runs going on now
 * @param openLobbies lobbies waiting to start
 * @param queued      players waiting in those lobbies or a queue
 * @param warnings    setup problems worth a staff member's look
 */
public record ActivityLiveStatus(List<Instance> instances, List<Run> runs, int openLobbies, int queued,
                                 Set<Warning> warnings) {

    public ActivityLiveStatus {
        instances = List.copyOf(instances);
        runs = List.copyOf(runs);
        warnings = warnings.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(warnings));
    }

    public enum State { FREE, RUNNING, COOLDOWN }

    /** A place the activity runs at; {@code cooldownTicksLeft} only counts while {@link State#COOLDOWN}. */
    public record Instance(ResourceLocation dimension, BlockPos pos, State state, int cooldownTicksLeft) {
        public Instance {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * A run going on: where ({@code dimension}, {@code instance} — what {@code forceEnd} takes), its
     * difficulty (null for arenas), the players, a short phase line ("Wave 3/10", "Boss"), ticks since
     * it started and ticks left on its timer (-1 without one). A null phase reads as none.
     */
    public record Run(ResourceLocation dimension, BlockPos instance, @Nullable String difficulty, List<UUID> players,
                      Component phase, long ticksElapsed, long ticksLeft) {
        public Run {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(instance, "instance");
            players = List.copyOf(players);
            phase = phase == null ? Component.empty() : phase;
        }
    }

    public enum Warning {
        /** The id was made up from the controller's position, not given by staff. */
        GENERATED_ID,
        /** No display name set. */
        UNNAMED,
        /** No difficulty enabled (dungeons and raids). */
        NO_DIFFICULTIES,
        /** No place to run it at. */
        NO_INSTANCES,
        /** Instances marked for removal once their runs end. */
        PENDING_REMOVALS,
        /** The controller block is gone: Forget drops the activity. */
        CONTROLLER_MISSING;

        public int bit() {
            return 1 << ordinal();
        }

        public static int mask(Set<Warning> warnings) {
            int mask = 0;
            for (Warning warning : warnings) {
                mask |= warning.bit();
            }
            return mask;
        }
    }

    /** Runs going on now. */
    public int running() {
        return runs.size();
    }

    /** Instances cooling down. */
    public int coolingDown() {
        return (int) instances.stream().filter(instance -> instance.state() == State.COOLDOWN).count();
    }
}
