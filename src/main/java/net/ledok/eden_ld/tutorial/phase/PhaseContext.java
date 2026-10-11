package net.ledok.eden_ld.tutorial.phase;

import net.ledok.eden_ld.EdenLD;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The phase the server thread is acting for right now — a phased entity ticking, a tutorial player attacking, using a
 * block or dropping something. New entities take it ({@code PhaseServerLevelMixin}), and sounds and particles sent
 * while it is set reach only that phase ({@code PhasePacketMixin}). Pushed and popped in pairs around a call.
 */
public final class PhaseContext {

    private static final ThreadLocal<ArrayDeque<UUID>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private PhaseContext() {
    }

    @Nullable
    public static UUID current() {
        return STACK.get().peek();
    }

    /** Runs {@code body} acting for {@code phase} (as is when null). */
    public static <T> T call(@Nullable UUID phase, Supplier<T> body) {
        if (phase == null) {
            return body.get();
        }
        ArrayDeque<UUID> stack = STACK.get();
        stack.push(phase);
        try {
            return body.get();
        } finally {
            stack.pop();
        }
    }

    public static void run(@Nullable UUID phase, Runnable body) {
        call(phase, () -> {
            body.run();
            return null;
        });
    }

    /** End of every server tick: nothing should be left (a missed pop would leak a phase into the next tick). */
    public static void clear() {
        ArrayDeque<UUID> stack = STACK.get();
        if (!stack.isEmpty()) {
            EdenLD.LOGGER.warn("[Tutorial] {} phase frame(s) left at the end of a tick; cleared.", stack.size());
            stack.clear();
        }
    }
}
