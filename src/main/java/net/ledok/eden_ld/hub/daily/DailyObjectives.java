package net.ledok.eden_ld.hub.daily;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * The quest's objective as a line of translate components — "Kill Zombie ×20" — so every
 * player reads it in their own language with no authored text: mob, item, and spell names
 * come from the game's (and mods') own translations. Custom objective types look up
 * {@code eden_ld.objective.<ns>.<path>} ({@code %s} = target, {@code %s} = count), and for
 * cards (no amount) {@code eden_ld.objective.<ns>.<path>.goal} ({@code %s} = target), with
 * readable fallbacks.
 */
public final class DailyObjectives {

    private DailyObjectives() {
    }

    private static final String KEY = "gui.eden_ld.hub.dailies.objective.";
    private static final String GOAL = "gui.eden_ld.hub.dailies.goal.";

    public static Component describe(DailyQuestDefinition quest) {
        return describe(quest.objective(), quest.target(), quest.count());
    }

    /** The line for any quest (daily or guild): objective type, optional target, amount. */
    public static Component describe(ResourceLocation type, java.util.Optional<String> targetId, int count) {
        String builtIn = type.getNamespace().equals(net.ledok.eden_ld.EdenLD.MOD_ID) ? type.getPath() : null;
        Component target = targetId.map(t -> targetName(type, t)).orElse(null);
        if ("catch_fish".equals(builtIn)) {
            return Component.translatable(KEY + "catch_fish", count);
        }
        if ("stat".equals(builtIn)) {
            return Component.translatable(KEY + "stat",
                    targetId.map(DailyObjectives::statName).orElse(Component.literal("?")), count);
        }
        if (builtIn != null && java.util.Set.of("kill_mobs", "craft_items", "cast_spells", "complete_dungeon",
                "place_blocks", "break_blocks", "eat_food").contains(builtIn)) {
            return target == null
                    ? Component.translatable(KEY + builtIn + ".any", count)
                    : Component.translatable(KEY + builtIn, target, count);
        }
        String customKey = "eden_ld.objective." + type.getNamespace() + "." + type.getPath().replace('/', '.');
        Component what = target != null ? target : Component.literal(pretty(type.getPath()));
        return Component.translatableWithFallback(customKey, "%s ×%s", what, count);
    }

    /**
     * The same line without the amount ("Kill Zombie", "Eat food") — for cards, whose progress
     * bar already shows "12 / 25".
     */
    public static Component goal(ResourceLocation type, java.util.Optional<String> targetId) {
        String builtIn = type.getNamespace().equals(net.ledok.eden_ld.EdenLD.MOD_ID) ? type.getPath() : null;
        Component target = targetId.map(t -> targetName(type, t)).orElse(null);
        if ("catch_fish".equals(builtIn)) {
            return Component.translatable(GOAL + "catch_fish");
        }
        if ("stat".equals(builtIn)) {
            return targetId.map(DailyObjectives::statName).orElse(Component.literal("?"));
        }
        if (builtIn != null && java.util.Set.of("kill_mobs", "craft_items", "cast_spells", "complete_dungeon",
                "place_blocks", "break_blocks", "eat_food").contains(builtIn)) {
            return target == null
                    ? Component.translatable(GOAL + builtIn + ".any")
                    : Component.translatable(GOAL + builtIn, target);
        }
        String customKey = "eden_ld.objective." + type.getNamespace() + "." + type.getPath().replace('/', '.') + ".goal";
        return Component.translatableWithFallback(customKey, "%s",
                target != null ? target : Component.literal(pretty(type.getPath())));
    }

    /** A target id or #tag as a name: registry translations, else a readable fallback. */
    private static Component targetName(ResourceLocation type, String target) {
        boolean tag = target.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tag ? target.substring(1) : target);
        if (id == null) {
            return Component.literal(target);
        }
        String path = type.getPath();
        if (tag) {
            String registry = switch (path) {
                case "kill_mobs" -> "entity_type";
                case "craft_items", "eat_food" -> "item";
                case "place_blocks", "break_blocks" -> "block";
                case "cast_spells" -> "spell";
                default -> "misc";
            };
            return Component.translatableWithFallback(
                    "tag." + registry + "." + id.getNamespace() + "." + id.getPath().replace('/', '.'),
                    pretty(id.getPath()));
        }
        switch (path) {
            case "kill_mobs" -> {
                return BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                        .<Component>map(entity -> entity.getDescription())
                        .orElse(Component.literal(pretty(id.getPath())));
            }
            case "place_blocks", "break_blocks" -> {
                return BuiltInRegistries.BLOCK.getOptional(id)
                        .<Component>map(block -> block.getName())
                        .orElse(Component.literal(pretty(id.getPath())));
            }
            case "craft_items", "eat_food" -> {
                return BuiltInRegistries.ITEM.getOptional(id)
                        .<Component>map(item -> item.getDescription())
                        .orElse(Component.literal(pretty(id.getPath())));
            }
            case "cast_spells" -> {
                // Spell Engine's naming convention for spell display names.
                return Component.translatableWithFallback(
                        "spell." + id.getNamespace() + "." + id.getPath() + ".name", pretty(id.getPath()));
            }
            case "complete_dungeon" -> {
                // The activity's own name (Arenas_LD or a datapack file); needs the running server.
                net.minecraft.server.MinecraftServer server = net.ledok.eden_ld.EdenLD.server();
                net.ledok.eden_ld.hub.activity.Activity activity = server == null ? null
                        : net.ledok.eden_ld.hub.activity.Activities.get(server, id);
                return activity != null ? activity.name() : Component.literal(pretty(id.getPath()));
            }
            default -> {
                return Component.literal(pretty(id.getPath()));
            }
        }
    }

    /**
     * A stat's scoreboard name as text: custom stats ("minecraft.custom:minecraft.jump") use
     * vanilla's own "stat.minecraft.jump" translation; others fall back to the value's name.
     */
    private static Component statName(String name) {
        int colon = name.indexOf(':');
        if (colon < 0) {
            return Component.literal(name);
        }
        String type = name.substring(0, colon);
        ResourceLocation value = ResourceLocation.tryParse(name.substring(colon + 1).replaceFirst("\\.", ":"));
        if (value == null) {
            return Component.literal(name);
        }
        if (type.equals("minecraft.custom")) {
            return Component.translatableWithFallback("stat." + value.getNamespace() + "." + value.getPath(),
                    pretty(value.getPath()));
        }
        // Item, block and mob stats: the thing's own name with a verb ("Use Bow", "Mine Stone").
        Component subject = switch (type) {
            case "minecraft.mined" -> BuiltInRegistries.BLOCK.getOptional(value)
                    .<Component>map(block -> block.getName()).orElse(null);
            case "minecraft.killed", "minecraft.killed_by" -> BuiltInRegistries.ENTITY_TYPE.getOptional(value)
                    .<Component>map(entity -> entity.getDescription()).orElse(null);
            default -> BuiltInRegistries.ITEM.getOptional(value)
                    .<Component>map(item -> item.getDescription()).orElse(null);
        };
        if (subject == null) {
            subject = Component.literal(pretty(value.getPath()));
        }
        String verb = type.startsWith("minecraft.") ? type.substring("minecraft.".length()) : type;
        return java.util.Set.of("used", "crafted", "mined", "broken", "picked_up", "dropped", "killed", "killed_by")
                .contains(verb) ? Component.translatable(KEY + "stat." + verb, subject) : subject;
    }

    /** "bone_crypt/level_2" → "Level 2". */
    static String pretty(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        for (String word : last.split("_")) {
            if (!word.isEmpty()) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
