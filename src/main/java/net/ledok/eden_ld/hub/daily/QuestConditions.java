package net.ledok.eden_ld.hub.daily;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What a quest may ask beyond its objective: where its progress counts ({@code dimension}, dailies and guild quests)
 * and who may draw it (dailies: dimensions unlocked in Realm Keys, a {@code loot_stage} range). The loot stage is DD
 * Loot Stage's {@code loot_stage:loot_stage} attribute, read by id so neither mod needs the other.
 */
public final class QuestConditions {

    public static final ResourceLocation LOOT_STAGE = ResourceLocation.fromNamespaceAndPath("loot_stage", "loot_stage");

    private QuestConditions() {
    }

    /** A loot stage range a daily is drawn for: {@code min}..{@code max}, max 0 = no top. */
    public record StageRange(int min, int max) {
        public static final StageRange ANY = new StageRange(0, 0);
        public static final Codec<StageRange> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, 100_000).optionalFieldOf("min", 0).forGetter(StageRange::min),
                Codec.intRange(0, 100_000).optionalFieldOf("max", 0).forGetter(StageRange::max)
        ).apply(instance, StageRange::new));

        public boolean atLeastMin(int stage) {
            return stage >= min;
        }

        public boolean belowMax(int stage) {
            return max <= 0 || stage <= max;
        }
    }

    /**
     * The quests a player may draw: what {@code strict} allows, or — when that leaves fewer than {@code needed} —
     * what {@code relaxed} allows (a stage top dropped), so a high stage never runs the pool dry.
     */
    public static <T> List<T> pool(List<T> all, Predicate<T> strict, Predicate<T> relaxed, int needed) {
        List<T> pool = all.stream().filter(strict).toList();
        return pool.size() >= needed ? pool : all.stream().filter(relaxed).toList();
    }

    /** The player's loot stage; 1 when DD Loot Stage isn't installed or the player is offline. */
    public static int lootStage(@Nullable ServerPlayer player) {
        if (player == null) {
            return 1;
        }
        AttributeInstance stage = BuiltInRegistries.ATTRIBUTE.getHolder(LOOT_STAGE)
                .map(player::getAttribute).orElse(null);
        return stage == null ? 1 : (int) Math.floor(stage.getValue());
    }

    /** Whether progress the player makes now counts for a quest bound to {@code dimension}. */
    public static boolean inDimension(ServerPlayer player, Optional<ResourceLocation> dimension) {
        return dimension.isEmpty() || player.level().dimension().location().equals(dimension.get());
    }

    /** The objective line with the dimension it counts in: "Kill mobs ×100 in the End". */
    public static Component withDimension(Component line, Optional<ResourceLocation> dimension) {
        return dimension.<Component>map(dim -> Component.translatable("gui.eden_ld.hub.dailies.in_dimension", line,
                dimensionName(dim))).orElse(line);
    }

    /** {@code dimension.<ns>.<path>} from the lang tables, else the path made readable. */
    public static Component dimensionName(ResourceLocation dimension) {
        return Component.translatableWithFallback("dimension." + dimension.getNamespace() + "."
                + dimension.getPath().replace('/', '.'), DailyObjectives.pretty(dimension.getPath()));
    }
}
