package net.ledok.eden_ld.hub.daily;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What a quest may ask beyond its objective: where its progress counts ({@code dimension}, and {@code station} — the
 * block things are made at; dailies and guild quests) and who may draw it (dailies: dimensions unlocked in Realm Keys,
 * a {@code loot_stage} range). The loot stage is DD Loot Stage's {@code loot_stage:loot_stage} attribute, read by id so
 * neither mod needs the other.
 */
public final class QuestConditions {

    public static final ResourceLocation LOOT_STAGE = ResourceLocation.fromNamespaceAndPath("loot_stage", "loot_stage");
    public static final ResourceLocation CRAFTING_TABLE = ResourceLocation.withDefaultNamespace("crafting_table");
    private static final ResourceLocation CRAFTING_MENU = ResourceLocation.withDefaultNamespace("crafting");

    /** {@code "station"}: one id or a list. */
    public static final Codec<List<ResourceLocation>> STATIONS = Codec.either(ResourceLocation.CODEC,
            ResourceLocation.CODEC.listOf()).xmap(either -> either.map(List::of, list -> list),
            list -> list.size() == 1 ? Either.left(list.get(0)) : Either.right(list));

    /** The station of things made with no menu open (Farmer's Delight serves meals by right-click). */
    private static final ThreadLocal<ResourceLocation> MADE_AT = new ThreadLocal<>();

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

    /** Counts what {@code make} awards as made at {@code station}, whatever menu is open. */
    public static void madeAt(ResourceLocation station, Runnable make) {
        MADE_AT.set(station);
        try {
            make.run();
        } finally {
            MADE_AT.remove();
        }
    }

    /** Whether what the player makes now counts for a quest bound to {@code stations} (empty = anywhere). */
    public static boolean atStation(ServerPlayer player, List<ResourceLocation> stations) {
        return stations.isEmpty() || stations.contains(station(player));
    }

    /**
     * Where the player is making things: the open menu's type, its block's id — the crafting menu and the inventory's
     * own grid are the crafting table, the others share their block's id (furnace, smoker, Farmer's Delight's
     * cooking pot). Null for menus without a type.
     */
    @Nullable
    static ResourceLocation station(ServerPlayer player) {
        ResourceLocation made = MADE_AT.get();
        if (made != null) {
            return made;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof InventoryMenu) {
            return CRAFTING_TABLE;
        }
        try {
            return stationOf(BuiltInRegistries.MENU.getKey(menu.getType()));
        } catch (UnsupportedOperationException noType) {
            return null;
        }
    }

    /** The station a menu type stands for. */
    @Nullable
    static ResourceLocation stationOf(@Nullable ResourceLocation menu) {
        return CRAFTING_MENU.equals(menu) ? CRAFTING_TABLE : menu;
    }

    /** The objective line with the stations it counts at: "Craft Meals ×10 (Cooking Pot)". */
    public static Component withStation(Component line, List<ResourceLocation> stations) {
        if (stations.isEmpty()) {
            return line;
        }
        MutableComponent names = Component.empty();
        for (int i = 0; i < stations.size(); i++) {
            if (i > 0) {
                names.append(" / ");
            }
            names.append(stationName(stations.get(i)));
        }
        return Component.translatableWithFallback("gui.eden_ld.hub.dailies.at_station", "%s (%s)", line, names);
    }

    /** The station's item name, else its path made readable. */
    public static Component stationName(ResourceLocation station) {
        return BuiltInRegistries.ITEM.getOptional(station)
                .filter(item -> item != Items.AIR)
                .<Component>map(Item::getDescription)
                .orElseGet(() -> Component.literal(DailyObjectives.pretty(station.getPath())));
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
