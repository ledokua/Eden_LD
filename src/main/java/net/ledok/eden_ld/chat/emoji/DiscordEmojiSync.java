package net.ledok.eden_ld.chat.emoji;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ledok.eden_ld.EdenLD;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps emoji.json in step with the Discord server's own emojis, read through a bot the owner
 * adds to their server (config {@code chat.discord_bot_token} / {@code chat.discord_guild_id};
 * the bot needs no permissions, only membership). New emojis are appended under their Discord
 * name, deleted ones leave the list, and the in-game names chosen in emoji.json are kept (matched
 * by id). Animated emojis are marked as such.
 */
final class DiscordEmojiSync {

    private static final String API = "https://discord.com/api/v10/guilds/%s/emojis";

    private record Remote(String name, boolean animated) {
    }

    private DiscordEmojiSync() {
    }

    static boolean enabled() {
        return !EdenLD.CONFIG.chat.discord_bot_token.isBlank() && !EdenLD.CONFIG.chat.discord_guild_id.isBlank();
    }

    /**
     * The emoji list merged with the server's current emojis. Returns {@code list} itself when
     * Discord can't be reached (the file stays as it is).
     */
    static JsonObject merge(JsonObject list, HttpClient http) {
        Map<String, Remote> remote;
        try {
            remote = fetch(http);
        } catch (IOException e) {
            EdenLD.LOGGER.warn("[Emoji] Discord sync failed: {}", e.getMessage());
            return list;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return list;
        }
        JsonObject merged = new JsonObject();
        Set<String> kept = new HashSet<>();
        int removed = 0;
        // Known emojis keep their place and their in-game name.
        for (Map.Entry<String, JsonElement> entry : list.entrySet()) {
            String id = entry.getValue().isJsonPrimitive() ? EmojiRegistry.idOf(entry.getValue().getAsString().trim()) : null;
            Remote emoji = id == null ? null : remote.get(id);
            if (emoji == null) {
                removed++;
                continue;
            }
            merged.addProperty(entry.getKey(), value(id, emoji));
            kept.add(id);
        }
        int added = 0;
        for (Map.Entry<String, Remote> entry : remote.entrySet()) {
            if (kept.contains(entry.getKey())) {
                continue;
            }
            merged.addProperty(freeName(merged, entry.getValue().name()), value(entry.getKey(), entry.getValue()));
            added++;
        }
        if (added > 0 || removed > 0) {
            EdenLD.LOGGER.info("[Emoji] Discord sync: {} added, {} removed.", added, removed);
        }
        return merged;
    }

    /** The server's usable emojis by id (unavailable ones — lost boost slots — are left out). */
    private static Map<String, Remote> fetch(HttpClient http) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                                String.format(API, EdenLD.CONFIG.chat.discord_guild_id.trim())))
                        .header("Authorization", "Bot " + EdenLD.CONFIG.chat.discord_bot_token.trim())
                        .header("User-Agent", "DiscordBot (https://minestar.com.ua, 1) Eden_LD")
                        .timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Discord answered " + response.statusCode()
                    + (response.statusCode() == 401 ? " (check chat.discord_bot_token)"
                    : response.statusCode() == 403 || response.statusCode() == 404
                    ? " (is the bot in the server? check chat.discord_guild_id)" : ""));
        }
        Map<String, Remote> emojis = new LinkedHashMap<>();
        JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
        for (JsonElement element : array) {
            JsonObject emoji = element.getAsJsonObject();
            if (emoji.has("available") && !emoji.get("available").getAsBoolean()) {
                continue;
            }
            emojis.put(emoji.get("id").getAsString(), new Remote(emoji.get("name").getAsString(),
                    emoji.has("animated") && emoji.get("animated").getAsBoolean()));
        }
        return emojis;
    }

    private static String value(String id, Remote emoji) {
        return emoji.animated() ? "a:" + id : id;
    }

    /** A typeable in-game name, unique in the list ("name", "name_2", …). */
    private static String freeName(JsonObject list, String discordName) {
        String base = discordName.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (base.length() > 28) {
            base = base.substring(0, 28);
        }
        if (base.isEmpty()) {
            base = "emoji";
        }
        String name = base;
        for (int i = 2; hasName(list, name); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    private static boolean hasName(JsonObject list, String name) {
        for (String key : list.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
