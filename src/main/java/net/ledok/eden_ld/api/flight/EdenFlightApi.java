package net.ledok.eden_ld.api.flight;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Flight control for the Fallen Eden server. Other LeDok mods register
 * {@link FlightRule}s here to allow or deny elytra/wing flight and
 * firework boosting; Eden_LD enforces the combined result.
 *
 * Thread contract: register during mod init; evaluation happens on the
 * game threads only.
 */
public final class EdenFlightApi {

    private record Entry(ResourceLocation id, int priority, FlightRule rule) {
    }

    private static final List<Entry> RULES = new ArrayList<>();

    private EdenFlightApi() {
    }

    /**
     * Registers a rule. Lower priority runs first; Eden_LD's own
     * config-driven rule runs at priority 1000, so use a lower value to
     * override it (e.g. force-ALLOW flight inside an arena).
     */
    public static void register(ResourceLocation id, int priority, FlightRule rule) {
        RULES.removeIf(entry -> entry.id().equals(id));
        RULES.add(new Entry(id, priority, rule));
        RULES.sort(Comparator.comparingInt(Entry::priority));
    }

    public static void unregister(ResourceLocation id) {
        RULES.removeIf(entry -> entry.id().equals(id));
    }

    /** Combined verdict for flight; PASS means no rule objected. */
    public static Result testFlight(LivingEntity entity) {
        for (Entry entry : RULES) {
            FlightVerdict verdict = entry.rule().testFlight(entity);
            if (verdict != FlightVerdict.PASS) {
                return new Result(verdict, entry.rule().flightDenyMessage());
            }
        }
        return Result.PASS;
    }

    /** Combined verdict for firework boosting; PASS means no rule objected. */
    public static Result testBoost(Player player) {
        for (Entry entry : RULES) {
            FlightVerdict verdict = entry.rule().testBoost(player);
            if (verdict != FlightVerdict.PASS) {
                return new Result(verdict, entry.rule().boostDenyMessage());
            }
        }
        return Result.PASS;
    }

    public static boolean isFlightAllowed(LivingEntity entity) {
        return testFlight(entity).allowed();
    }

    public static boolean isBoostAllowed(Player player) {
        return testBoost(player).allowed();
    }

    public record Result(FlightVerdict verdict, @Nullable Component message) {
        public static final Result PASS = new Result(FlightVerdict.PASS, null);

        public boolean allowed() {
            return verdict != FlightVerdict.DENY;
        }
    }
}
