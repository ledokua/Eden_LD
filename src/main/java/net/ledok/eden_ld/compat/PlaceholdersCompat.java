package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Placeholder API (optional): placeholders for chat/tab-list formats of Styled Chat,
 * Styled Player List, and friends.
 * <ul>
 *   <li>{@code %eden_ld:guild_tag%} — the colored "[TAG] " when the player chose to show it
 *       (Settings → Show guild tag), else nothing;</li>
 *   <li>{@code %eden_ld:guild_name%} — the guild's name, else nothing;</li>
 *   <li>{@code %eden_ld:minestar_level%} — the Minestar activity level ({@code 12});</li>
 *   <li>{@code %eden_ld:minestar_xp%} — XP into the level, out of what it needs ({@code 712/2500});</li>
 *   <li>{@code %eden_ld:minestar_playtime%} — hours played on all Minestar servers;</li>
 *   <li>{@code %eden_ld:minestar_rewards%} — how many Minestar rewards the player has.</li>
 * </ul>
 * The Minestar ones are empty while the player's profile isn't loaded (or without Minestar).
 */
public final class PlaceholdersCompat {

    private PlaceholdersCompat() {
    }

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("placeholder-api");

    /** Placeholder API's {@code %…%} placeholders in the text resolved for the player; as is without the mod. */
    public static Component parse(Component text, ServerPlayer player) {
        return LOADED ? Hook.parse(text, player) : text;
    }

    /**
     * A written text (mail, news) with the reader's placeholders filled in: {@code {player}},
     * {@code {player_uuid}}, and Placeholder API's {@code %…%} when installed.
     */
    public static String fill(String text, ServerPlayer reader) {
        String filled = text.replace("{player}", reader.getGameProfile().getName())
                .replace("{player_uuid}", reader.getUUID().toString());
        if (filled.indexOf('%') < 0) {
            return filled;
        }
        return parse(Component.literal(filled), reader).getString();
    }

    /**
     * Every Placeholder API placeholder on the server as {token, its value for this player} —
     * the Moderation window's placeholder browser. Empty without Placeholder API.
     */
    public static java.util.List<String[]> all(ServerPlayer player) {
        return LOADED ? Hook.all(player) : java.util.List.of();
    }

    public static void initialize() {
        if (LOADED) {
            Hook.register();
            EdenLD.LOGGER.info("[Placeholders] %eden_ld:guild_tag%, %eden_ld:guild_name% and %eden_ld:minestar_*% registered.");
        }
    }

    /** The only class touching Placeholder API types. */
    private static final class Hook {
        static java.util.List<String[]> all(ServerPlayer player) {
            java.util.List<String[]> list = new java.util.ArrayList<>();
            eu.pb4.placeholders.api.PlaceholderContext context = eu.pb4.placeholders.api.PlaceholderContext.of(player);
            var handlers = eu.pb4.placeholders.api.Placeholders.getPlaceholders();
            java.util.List<net.minecraft.resources.ResourceLocation> ids = new java.util.ArrayList<>(handlers.keySet());
            ids.sort(java.util.Comparator.comparing(net.minecraft.resources.ResourceLocation::toString));
            for (net.minecraft.resources.ResourceLocation id : ids) {
                // The handler itself, not parseText: placeholders that need an argument
                // (luckperms:meta <key>, server:objective_*…) fail without one, and parseText logs
                // each failure with a stack trace. Those are left out of the list.
                String value;
                try {
                    eu.pb4.placeholders.api.PlaceholderResult result = handlers.get(id).onPlaceholderRequest(context, null);
                    if (result == null || !result.isValid()) {
                        continue;
                    }
                    value = result.string();
                } catch (RuntimeException e) {
                    continue;
                }
                list.add(new String[]{"%" + id + "%", value.length() > 60 ? value.substring(0, 60) + "…" : value});
            }
            return list;
        }

        static Component parse(Component text, ServerPlayer player) {
            return eu.pb4.placeholders.api.Placeholders.parseText(text, eu.pb4.placeholders.api.PlaceholderContext.of(player));
        }

        static void register() {
            eu.pb4.placeholders.api.Placeholders.register(
                    ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "guild_tag"), (context, argument) -> {
                        Guild guild = guildOf(context);
                        if (guild == null || !HubSettings.showGuildTag(context.player().getServer(),
                                context.player().getUUID())) {
                            return eu.pb4.placeholders.api.PlaceholderResult.value(Component.empty());
                        }
                        return eu.pb4.placeholders.api.PlaceholderResult.value(Component.literal("[" + guild.tag + "] ")
                                .withStyle(style -> style.withColor(guild.color)));
                    });
            eu.pb4.placeholders.api.Placeholders.register(
                    ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "guild_name"), (context, argument) -> {
                        Guild guild = guildOf(context);
                        return eu.pb4.placeholders.api.PlaceholderResult.value(
                                guild == null ? Component.empty() : Component.literal(guild.name));
                    });
            minestar("minestar_level", profile -> String.valueOf(profile.level()));
            minestar("minestar_xp", profile -> profile.levelXp() + "/" + profile.levelXpNeeded());
            minestar("minestar_playtime", profile -> String.valueOf(profile.playtimeSeconds() / 3600));
            minestar("minestar_rewards", profile -> String.valueOf(profile.rewards().size()));
        }

        private static void minestar(String name,
                                     java.util.function.Function<net.ledok.eden_ld.minestar.ProfileSnapshot, String> value) {
            eu.pb4.placeholders.api.Placeholders.register(
                    ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, name), (context, argument) -> {
                        if (!context.hasPlayer()) {
                            return eu.pb4.placeholders.api.PlaceholderResult.value(Component.empty());
                        }
                        String text = net.ledok.eden_ld.minestar.MinestarHook.profile(context.player().getUUID())
                                .map(value).orElse("");
                        return eu.pb4.placeholders.api.PlaceholderResult.value(Component.literal(text));
                    });
        }

        private static Guild guildOf(eu.pb4.placeholders.api.PlaceholderContext context) {
            if (!context.hasPlayer()) {
                return null;
            }
            ServerPlayer player = context.player();
            return player.getServer() == null ? null
                    : GuildManager.get(player.getServer()).guildOf(player.getUUID());
        }
    }
}
