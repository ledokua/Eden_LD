package net.ledok.eden_ld.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discord's raw mentions in lines from the bridge, as readers know them: {@code <#id>} becomes the channel's link
 * (shown as "#name" by {@link ChatLinks#link}, opening it in Discord), {@code <@id>} / {@code <@!id>} "@name" and
 * {@code <@&id>} "@role"; channel links written out in full read "#name" as well. Names come from the bot the emoji sync already uses ({@code chat.discord_bot_token} /
 * {@code chat.discord_guild_id}): channels and roles are read when the server starts and every 30 minutes (again,
 * at most once a minute, when a line names one Eden doesn't know), members when a line first names them. Without
 * the bot, or for anything Discord doesn't answer, the mention reads "#channel", "@user" or "@role".
 */
public final class DiscordNames {

    private static final String API = "https://discord.com/api/v10/guilds/";
    private static final Pattern MENTION = Pattern.compile("<(#|@!?|@&)(\\d{5,25})>");
    /** A Discord channel link of this server (what {@code <#id>} becomes). Groups: 1 guild, 2 channel. */
    public static final Pattern CHANNEL_LINK = Pattern.compile("https://discord\\.com/channels/(\\d{5,25})/(\\d{5,25})/?");
    private static final long LIST_REFRESH_MILLIS = 30 * 60_000L;
    private static final long LIST_RETRY_MILLIS = 60_000L;
    private static final int MAX_MEMBERS_PER_LINE = 5;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Map<String, String> CHANNELS = new ConcurrentHashMap<>();
    private static final Map<String, String> ROLES = new ConcurrentHashMap<>();
    /** Member display names; "" while unknown (asked once per server run). */
    private static final Map<String, String> MEMBERS = new ConcurrentHashMap<>();
    private static volatile long listsReadAt;
    private static volatile boolean listsLoading;

    private DiscordNames() {
    }

    private static boolean enabled() {
        return !EdenLD.CONFIG.chat.discord_bot_token.isBlank() && !EdenLD.CONFIG.chat.discord_guild_id.isBlank();
    }

    private static String guild() {
        return EdenLD.CONFIG.chat.discord_guild_id.trim();
    }

    /** Whether the line names any channel, member or role. */
    public static boolean hasMention(String line) {
        return MENTION.matcher(line).find();
    }

    /** The channel's name, if known. */
    @Nullable
    public static String channel(String id) {
        String name = CHANNELS.get(id);
        return name == null || name.isEmpty() ? null : name;
    }

    /** Whether the link is a channel of this Discord server. */
    public static boolean ownGuild(String guildId) {
        return guildId.equals(guild());
    }

    /**
     * Makes every name the line mentions known (as far as Discord answers). Completes on the server thread; at once
     * when nothing is missing, and never fails (an unknown name just stays generic).
     */
    public static CompletableFuture<Void> fetch(MinecraftServer server, String line) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        long now = System.currentTimeMillis();
        boolean listsStale = now - listsReadAt >= LIST_REFRESH_MILLIS;
        List<CompletableFuture<?>> waits = new ArrayList<>();
        List<String> members = new ArrayList<>();
        List<String> channels = new ArrayList<>();
        Matcher matcher = MENTION.matcher(line);
        while (matcher.find()) {
            String kind = matcher.group(1);
            String id = matcher.group(2);
            if (kind.equals("#") && !CHANNELS.containsKey(id)) {
                // Threads and forum posts aren't in the channel list: asked for one by one.
                if (channels.size() < MAX_MEMBERS_PER_LINE && !channels.contains(id)) {
                    channels.add(id);
                }
            } else if (kind.equals("@&") && !ROLES.containsKey(id)) {
                listsStale |= now - listsReadAt >= LIST_RETRY_MILLIS;
            } else if (kind.startsWith("@") && !kind.equals("@&") && !MEMBERS.containsKey(id)
                    && members.size() < MAX_MEMBERS_PER_LINE && !members.contains(id)) {
                members.add(id);
            }
        }
        // Channel links written out in full (https://discord.com/channels/<guild>/<id>) read as "#name" too.
        Matcher links = CHANNEL_LINK.matcher(line);
        while (links.find()) {
            String id = links.group(2);
            if (ownGuild(links.group(1)) && !CHANNELS.containsKey(id) && channels.size() < MAX_MEMBERS_PER_LINE
                    && !channels.contains(id)) {
                channels.add(id);
            }
        }
        for (String channel : channels) {
            CHANNELS.put(channel, "");
            waits.add(CompletableFuture.runAsync(() -> readChannel(channel)));
        }
        if (listsStale && !listsLoading) {
            waits.add(readLists());
        }
        for (String member : members) {
            MEMBERS.put(member, "");
            waits.add(CompletableFuture.runAsync(() -> readMember(member)));
        }
        if (waits.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new))
                .completeOnTimeout(null, 5, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(error -> null)
                .thenRunAsync(() -> { }, server);
    }

    /** The line with every mention as readers know it (a channel as its Discord link, people and roles as "@name"). */
    public static String resolve(String line) {
        Matcher matcher = MENTION.matcher(line);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String kind = matcher.group(1);
            String id = matcher.group(2);
            String text;
            if (kind.equals("#")) {
                text = enabled() ? "https://discord.com/channels/" + guild() + "/" + id : "#" + name(CHANNELS, id, "channel");
            } else if (kind.equals("@&")) {
                text = "@" + name(ROLES, id, "role");
            } else {
                text = "@" + name(MEMBERS, id, "user");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String name(Map<String, String> names, String id, String fallback) {
        String name = names.get(id);
        return name == null || name.isEmpty() ? fallback : name;
    }

    /** Server start: the channel and role lists, so the first lines already read well. */
    public static void preload() {
        if (enabled()) {
            readLists();
        }
    }

    private static CompletableFuture<Void> readLists() {
        listsLoading = true;
        listsReadAt = System.currentTimeMillis();
        return CompletableFuture.runAsync(() -> {
            try {
                JsonArray channels = get(API + guild() + "/channels").getAsJsonArray();
                for (JsonElement element : channels) {
                    JsonObject channel = element.getAsJsonObject();
                    CHANNELS.put(channel.get("id").getAsString(), channel.get("name").getAsString());
                }
                JsonArray roles = get(API + guild() + "/roles").getAsJsonArray();
                for (JsonElement element : roles) {
                    JsonObject role = element.getAsJsonObject();
                    ROLES.put(role.get("id").getAsString(), role.get("name").getAsString());
                }
            } catch (Exception e) {
                EdenLD.LOGGER.warn("[Chat] Couldn't read Discord's channel and role names: {}", e.getMessage());
            } finally {
                listsLoading = false;
            }
        });
    }

    /** One channel, thread or forum post by id (the bot must be able to see it); stays "#channel" otherwise. */
    private static void readChannel(String id) {
        try {
            String name = text(get("https://discord.com/api/v10/channels/" + id).getAsJsonObject(), "name");
            if (name != null) {
                CHANNELS.put(id, name);
            }
        } catch (Exception e) {
            // Not visible to the bot, or deleted: "#channel".
        }
    }

    private static void readMember(String id) {
        try {
            JsonObject member = get(API + guild() + "/members/" + id).getAsJsonObject();
            JsonObject user = member.getAsJsonObject("user");
            String name = text(member, "nick");
            if (name == null) {
                name = user == null ? null : text(user, "global_name");
            }
            if (name == null && user != null) {
                name = text(user, "username");
            }
            if (name != null) {
                MEMBERS.put(id, name);
            }
        } catch (Exception e) {
            // Left as "@user" (someone who left the server, or Discord didn't answer); not asked again this run.
        }
    }

    @Nullable
    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || value.getAsString().isBlank() ? null : value.getAsString();
    }

    private static JsonElement get(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bot " + EdenLD.CONFIG.chat.discord_bot_token.trim())
                        .header("User-Agent", "DiscordBot (https://minestar.com.ua, 1) Eden_LD")
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new java.io.IOException("Discord answered " + response.statusCode());
        }
        return JsonParser.parseString(response.body());
    }
}
