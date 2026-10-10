package net.ledok.eden_ld.hub.activity;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * One activity of the hub's Activities tab — a dungeon, raid or mob arena — as its source reports
 * it (Arenas_LD, or a datapack file). {@code difficulties}: the enabled ones, lowest first, each with
 * the level it needs (0 = none); arenas have none and use {@code requiredLevel}. {@code cardArt}: a
 * profile background id drawn on the card, or null for the default art.
 */
public record Activity(ResourceLocation id, Type type, Component name, List<Difficulty> difficulties,
                       int requiredLevel, @Nullable ResourceLocation cardArt) {

    public enum Type implements StringRepresentable {
        DUNGEON, RAID, ARENA;

        public static final com.mojang.serialization.Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A difficulty id ("easy", "normal", "hard", "nightmare") and the level it needs. */
    public record Difficulty(String id, int requiredLevel) {
    }

    /** The level that opens the activity: its first difficulty's, else its own. */
    public int unlockLevel() {
        return difficulties.isEmpty() ? Math.max(0, requiredLevel) : Math.max(0, difficulties.get(0).requiredLevel());
    }

    /** The level a difficulty needs (null = the activity itself); -1 when it isn't enabled. */
    public int levelFor(@Nullable String difficulty) {
        if (difficulty == null) {
            return unlockLevel();
        }
        for (Difficulty entry : difficulties) {
            if (entry.id().equals(difficulty)) {
                return Math.max(0, entry.requiredLevel());
            }
        }
        return -1;
    }
}
