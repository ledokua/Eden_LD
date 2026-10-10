package net.ledok.eden_ld.hub.daily;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * One quest of the daily pool, from {@code data/<ns>/daily_quests/<path>.json} (the path is
 * the id). {@code name} is optional — without it the card shows the generated objective line
 * ("Kill Zombie ×20", every player in their own language); with {@code {"translate": key}}
 * it comes from the server lang tables. {@code objective} is a progress type — built-in {@code kill_mobs},
 * {@code craft_items}, {@code cast_spells}, {@code complete_dungeon} (bare names mean
 * {@code eden_ld:}), or any id another mod reports through {@code EdenDailyApi}.
 * {@code target} narrows it: an id, or {@code #tag} for kills (entity types) and crafts
 * (items); absent = anything counts. {@code guild_xp} overrides what claiming it gives the
 * player's guild (config {@code guilds.daily_quest_xp}).
 */
public record DailyQuestDefinition(ResourceLocation id, Optional<Component> name, ResourceLocation objective,
                                   Optional<String> target, int count, ResourceLocation icon, int weight,
                                   DailyReward rewards, Optional<Integer> guildXp) {

    /** Bare objective names ("kill_mobs") belong to Eden. */
    private static final Codec<ResourceLocation> OBJECTIVE = Codec.STRING.xmap(
            text -> text.contains(":") ? ResourceLocation.parse(text)
                    : ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, text),
            ResourceLocation::toString);

    public record Body(Optional<Component> name, ResourceLocation objective, Optional<String> target, int count,
                       ResourceLocation icon, int weight, DailyReward rewards, Optional<Integer> guildXp) {
        public static final Codec<Body> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ComponentSerialization.CODEC.optionalFieldOf("name").forGetter(Body::name),
                OBJECTIVE.fieldOf("objective").forGetter(Body::objective),
                Codec.STRING.optionalFieldOf("target").forGetter(Body::target),
                Codec.intRange(1, 1_000_000).optionalFieldOf("count", 1).forGetter(Body::count),
                ResourceLocation.CODEC.optionalFieldOf("icon", ResourceLocation.withDefaultNamespace("paper"))
                        .forGetter(Body::icon),
                Codec.intRange(0, 10_000).optionalFieldOf("weight", 10).forGetter(Body::weight),
                DailyReward.CODEC.optionalFieldOf("rewards", DailyReward.NONE).forGetter(Body::rewards),
                Codec.intRange(0, 1_000_000).optionalFieldOf("guild_xp").forGetter(Body::guildXp)
        ).apply(instance, Body::new));

        DailyQuestDefinition withId(ResourceLocation id) {
            return new DailyQuestDefinition(id, name, objective, target, count, icon, weight, rewards, guildXp);
        }
    }

    /** Guild XP a member's claim gives their guild: the quest's own {@code guild_xp}, else config. */
    public int guildXpOrDefault() {
        return Math.max(0, guildXp.orElse(net.ledok.eden_ld.EdenLD.CONFIG.guilds.daily_quest_xp));
    }

    /** The card title: the authored name, else the objective without the amount (the bar shows it). */
    public Component cardTitle() {
        return name.orElseGet(() -> DailyObjectives.goal(objective, target));
    }

    /** The chat title: the authored name, else the generated objective line. */
    public Component title() {
        return name.orElseGet(() -> DailyObjectives.describe(this));
    }
}
