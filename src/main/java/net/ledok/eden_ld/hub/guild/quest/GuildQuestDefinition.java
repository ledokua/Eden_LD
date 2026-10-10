package net.ledok.eden_ld.hub.guild.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.daily.DailyObjectives;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * One guild quest of the pool, from {@code data/<ns>/guild_quests/<path>.json} or
 * {@code config/eden_ld/guild_quests/*.json}. Same objectives as dailies ({@code objective},
 * {@code target}, {@code count}), shared by the whole guild. {@code period}: on the daily or the
 * weekly board. Drawn only for guilds whose level is within {@code min_level}..{@code max_level}
 * (0 = no top). {@code count_per_member} adds that much to the goal for each member the guild has
 * when the quest is drawn. Rewards: guild XP, LC into the guild bank, and items mailed to every
 * member when it's done.
 */
public record GuildQuestDefinition(ResourceLocation id, Optional<Component> name, Period period,
                                   ResourceLocation objective, Optional<String> target, int count,
                                   int countPerMember, ResourceLocation icon, int weight, int minLevel,
                                   int maxLevel, Rewards rewards) {

    public enum Period implements StringRepresentable {
        DAILY, WEEKLY;

        public static final Codec<Period> CODEC = StringRepresentable.fromEnum(Period::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** {@code guild_xp} to the guild, {@code lc} into its bank, {@code items} to each member by mail. */
    public record Rewards(int guildXp, long lc, List<ItemStack> items) {
        public static final Rewards NONE = new Rewards(0, 0, List.of());
        public static final Codec<Rewards> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, 100_000_000).optionalFieldOf("guild_xp", 0).forGetter(Rewards::guildXp),
                Codec.LONG.optionalFieldOf("lc", 0L).forGetter(Rewards::lc),
                ItemStack.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(Rewards::items)
        ).apply(instance, Rewards::new));
    }

    private static final Codec<ResourceLocation> OBJECTIVE = Codec.STRING.xmap(
            text -> text.contains(":") ? ResourceLocation.parse(text)
                    : ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, text),
            ResourceLocation::toString);

    public record Body(Optional<Component> name, Period period, ResourceLocation objective, Optional<String> target,
                       int count, int countPerMember, ResourceLocation icon, int weight, int minLevel, int maxLevel,
                       Rewards rewards) {
        public static final Codec<Body> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ComponentSerialization.CODEC.optionalFieldOf("name").forGetter(Body::name),
                Period.CODEC.optionalFieldOf("period", Period.DAILY).forGetter(Body::period),
                OBJECTIVE.fieldOf("objective").forGetter(Body::objective),
                Codec.STRING.optionalFieldOf("target").forGetter(Body::target),
                Codec.intRange(1, 100_000_000).optionalFieldOf("count", 1).forGetter(Body::count),
                Codec.intRange(0, 1_000_000).optionalFieldOf("count_per_member", 0).forGetter(Body::countPerMember),
                ResourceLocation.CODEC.optionalFieldOf("icon", ResourceLocation.withDefaultNamespace("paper"))
                        .forGetter(Body::icon),
                Codec.intRange(0, 10_000).optionalFieldOf("weight", 10).forGetter(Body::weight),
                Codec.intRange(1, 10_000).optionalFieldOf("min_level", 1).forGetter(Body::minLevel),
                Codec.intRange(0, 10_000).optionalFieldOf("max_level", 0).forGetter(Body::maxLevel),
                Rewards.CODEC.optionalFieldOf("rewards", Rewards.NONE).forGetter(Body::rewards)
        ).apply(instance, Body::new));

        GuildQuestDefinition withId(ResourceLocation id) {
            return new GuildQuestDefinition(id, name, period, objective, target, count, countPerMember, icon, weight,
                    minLevel, maxLevel, rewards);
        }
    }

    /** Whether a guild of this level may draw it. */
    public boolean fits(int level) {
        return weight > 0 && level >= minLevel && (maxLevel <= 0 || level <= maxLevel);
    }

    /** The goal for a guild of {@code members} members. */
    public int goal(int members) {
        long goal = (long) count + (long) countPerMember * Math.max(0, members);
        return (int) Math.min(Integer.MAX_VALUE, goal);
    }

    /** The card title: the authored name, else the objective without the amount (the bar shows it). */
    public Component cardTitle() {
        return name.orElseGet(() -> DailyObjectives.goal(objective, target));
    }

    /** The authored name, else the objective line for this goal (chat). */
    public Component title(int goal) {
        return name.orElseGet(() -> DailyObjectives.describe(objective, target, goal));
    }
}
