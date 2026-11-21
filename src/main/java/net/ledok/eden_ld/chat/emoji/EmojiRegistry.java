package net.ledok.eden_ld.chat.emoji;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.chat.ChatChannels;
import net.ledok.eden_ld.chat.ChatLinks;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.hub.HubData;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's Discord emojis, usable in game chat as {@code :name:}. The list is
 * {@code config/eden_ld/emoji.json} (name → Discord emoji id, or its image link; optionally kept
 * in step with the Discord server by {@link DiscordEmojiSync}); images are downloaded once from
 * Discord's CDN into {@code config/eden_ld/emoji/}. Eden clients draw them from a runtime font
 * ({@link #FONT}) — no resource pack. Each emoji gets a private-use character (U+F4000 on, in file
 * order; see {@link #FIRST_CODEPOINT}); animated emojis play (a strip of frames, {@link GifStrip}).
 * <p>
 * Custom emojis arriving from Discord that aren't listed become {@link GuestEmojis guests}
 * (fetched on the spot, kept across restarts). Players with the picker can favorite any emoji,
 * guests included (saved on the server per player).
 * <p>
 * Clients cache images by emoji id (a Discord id never changes its image): on join they get only
 * the list ({@link HubPayloads.EmojiList}) and ask for the images they don't have yet.
 */
public final class EmojiRegistry {

    public static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "emoji");
    /**
     * Eden's emoji characters all live in Unicode's Supplementary Private Use Area-A (plane 15):
     * standard emojis U+F0000 on ({@link UnicodeEmoji}), the listed ones from here (file position),
     * guests U+F8000–U+F8FFF ({@link GuestEmojis}). Fonts and resource packs put their own glyphs
     * in the BMP's private use area (U+E000–U+F8FF — Economy_LD's coin is U+E000), so Eden's
     * characters never take theirs over.
     */
    private static final int FIRST_CODEPOINT = 0xF4000;
    private static final int IMAGE_SIZE = 32;
    /** Per-player favorites limit: LuckPerms meta (e.g. primes 20), else config chat.emoji_favorites. */
    public static final String META_FAVORITES = "eden_ld.emoji_favorites";
    /** Guests fetched for one Discord line at most (a spammed line can't flood the CDN). */
    private static final int MAX_NEW_GUESTS_PER_LINE = 8;
    private static final String FAVORITES_TAG = "emoji_favorites";
    /** Saved stickers per player: LuckPerms meta (e.g. primes 20), else config chat.sticker_favorites. */
    public static final String META_STICKERS = "eden_ld.sticker_favorites";
    /** Limits last sent to each player, so a bonus ending (or starting) is noticed within a minute. */
    private static final Map<UUID, Long> SENT_LIMITS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String STICKERS_TAG = "sticker_favorites";

    public record Emoji(String name, String discordName, String id, boolean animated, int codepoint, boolean guest) {
    }

    /**
     * A picker tab of the server's own emojis (emoji.json "groups"): shown with {@code icon};
     * with a {@code permission}, only players having it see the tab and can use its emojis.
     */
    public record Group(String id, String name, String icon, String permission, List<String> emojis) {
    }

    private static final Path FOLDER = FabricLoader.getInstance().getConfigDir().resolve(EdenLD.MOD_ID);
    private static final Path FILE = FOLDER.resolve("emoji.json");
    private static final Path CACHE = FOLDER.resolve("emoji");
    private static final GuestEmojis GUESTS = new GuestEmojis(CACHE.resolve("guests.json"));
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Pattern ID_IN_LINK = Pattern.compile("/emojis/(\\d+)");
    /** {@code :name:} typed in game. */
    public static final Pattern SHORTCODE = Pattern.compile(":([A-Za-z0-9_~+\\-]{1,40}):");
    /** {@code <:name:id>} / {@code <a:name:id>} as Discord writes custom emoji. */
    private static final Pattern DISCORD = Pattern.compile("<(a?):([A-Za-z0-9_~\\-]{1,32}):(\\d+)>");
    /**
     * An emoji posted as its image: {@code [name](https://cdn.discordapp.com/emojis/<id>.webp?...)}
     * (how people without Nitro — or with FakeNitro-style plugins — use other servers' emojis),
     * or the bare CDN link.
     */
    private static final Pattern CDN = Pattern.compile(
            "\\[([^\\]\\n]{0,64})]\\(<?(https://(?:cdn|media)\\.discordapp\\.(?:com|net)/emojis/(\\d+)[^)\\s]*)>?\\)"
                    + "|(https://(?:cdn|media)\\.discordapp\\.(?:com|net)/emojis/(\\d+)\\S*)");
    /** An emoji's own character (Eden clients type it in place of :name:; guests too). */
    public static final Pattern CHARACTER = Pattern.compile("([\\x{F0000}-\\x{F8FFF}])");

    /** Whether the character is one the server hands out: a listed emoji's or a guest's. */
    public static boolean isServerCharacter(int codepoint) {
        return codepoint >= FIRST_CODEPOINT && codepoint <= GuestEmojis.LAST_CODEPOINT;
    }

    /**
     * The owner's Discord emojis (2026-09-29), in their order: what a fresh emoji.json starts
     * with. Empty ids are still to be pasted in. In-game names may differ from Discord's (Discord
     * matches by id): Discord's emoji_11…emoji_23, cry~1 and rage~1 got typeable names.
     */
    private static final Map<String, String> STARTER = new LinkedHashMap<>();

    static {
        STARTER.put("AZOV", "981668123281330186");
        STARTER.put("KEKNerv", "1233415406304104551");
        STARTER.put("KEKW", "1233414671781068881");
        STARTER.put("KEKWait", "1233415321147281478");
        STARTER.put("Ordo", "1235630282468495391");
        STARTER.put("Sano", "1235630330153795716");
        STARTER.put("Thaumonomicon", "1235630114272841798");
        STARTER.put("UPAOUN", "981667560862941204");
        STARTER.put("administrator", "1483920627651051620");
        STARTER.put("ban", "1227744263299666012");
        STARTER.put("capybara", "997087526730866791");
        STARTER.put("catallblack", "1227382149397741640");
        STARTER.put("catcalico", "1227381639546667149");
        STARTER.put("catjellie", "1227381638275530832");
        STARTER.put("catocelot", "1227381661268836422");
        STARTER.put("catpersian", "1227381648019034213");
        STARTER.put("catragdoll", "1227381656814354492");
        STARTER.put("catred", "1227381631627563018");
        STARTER.put("catshorthair", "1227381632764477501");
        STARTER.put("catsiamese", "1227382112714227812");
        STARTER.put("cattabby", "1227381883264827432");
        STARTER.put("cattuxedo", "1227381628553400482");
        STARTER.put("catwhite", "1227381629723348995");
        STARTER.put("concern", "1286368368886288455");
        STARTER.put("cry", "1014803122255839292");
        STARTER.put("devsys", "1290512616124583968");
        STARTER.put("diamondpickaxe", "1227720256190025820");
        STARTER.put("diamondsword", "1227381931973414963");
        STARTER.put("catstare", "1014802905959780413");
        STARTER.put("ping", "1014802983139156018");
        STARTER.put("pikachu", "1014803051871227905");
        STARTER.put("pepediamond", "1014803184004374638");
        STARTER.put("bread", "1014803207484088410");
        STARTER.put("tnt", "1014803232058507324");
        STARTER.put("cake", "1014803253898260562");
        STARTER.put("heart", "1014803276492963870");
        STARTER.put("goldsword", "1227381643535450133");
        STARTER.put("harold", "1279550727110398074");
        STARTER.put("helper", "1483920544377077771");
        STARTER.put("hmmm", "1014803375029768252");
        STARTER.put("idi", "1551465897271758859");
        STARTER.put("ironsword", "1227381655375974460");
        STARTER.put("likeee", "1014803348861501521");
        STARTER.put("lp_icon", "1520498345855156224");
        STARTER.put("mcmissingtexture", "1279552057291833377");
        STARTER.put("minestar", "1241451269835460770");
        STARTER.put("moderator", "1483920589575159912");
        STARTER.put("mt_neo_money", "1469584904802271387");
        STARTER.put("netheresword", "1227381636870443090");
        STARTER.put("no", "1097478193197367486");
        STARTER.put("obsc1", "1227383629039140926");
        STARTER.put("obsc2", "1227383630473597070");
        STARTER.put("pokeball", "1236270881714737255");
        STARTER.put("rage", "1014802946803908658");
        STARTER.put("sadcat", "1014803324161249321");
        STARTER.put("stonesword", "1227381634106523748");
        STARTER.put("totemofundying", "1227384055054471249");
        STARTER.put("woodensword", "1227382056699564132");
        STARTER.put("wtfcat", "1239297480428880013");
        STARTER.put("wut", "1014803093101228112");
        STARTER.put("yes", "1097478190701744189");
    }

    /** Listed emojis (typeable by name). */
    private static volatile Map<String, Emoji> byName = Map.of();
    private static volatile List<Group> groups = List.of();
    /** Emoji name (lowercase) → its group. */
    private static volatile Map<String, Group> groupOf = Map.of();
    private static volatile Map<String, Emoji> listedById = Map.of();
    private static volatile Map<Integer, Emoji> listedByCodepoint = Map.of();
    /** Listed emojis whose image is ready — every client gets these. */
    private static volatile List<HubPayloads.EmojiRef> ready = List.of();
    /** Ready images by emoji id (listed and guests), for clients' requests. */
    private static final Map<String, Image> IMAGES = new java.util.concurrent.ConcurrentHashMap<>();

    /** What clients get for one emoji: the PNG, and frame delays (ms) when it's animated. */
    private record Image(byte[] png, List<Integer> delays) {
    }

    /** Most images one request may ask for (a client asks once per list). */
    private static final int MAX_REQUEST = 512;
    /**
     * Image bytes one answer carries at most; the rest go in further answers (review finding 43: one request could
     * ask for one big image hundreds of times, all in one packet).
     */
    private static final int MAX_ANSWER_BYTES = 256 * 1024;
    /**
     * Image bytes a player is sent in a burst at most, refilled over {@link #IMAGE_REFILL_MS} (review finding 43): a
     * fresh client needs every image once (a few hundred KB); a client asking in a loop gets no more than the refill.
     */
    private static final double IMAGE_BURST_BYTES = 2 * 1024 * 1024;
    private static final long IMAGE_REFILL_MS = 10_000;
    /**
     * Images each online player asked for past their budget, in order: sent once it refills ({@link #sendWaiting}),
     * since a client asks only when a list or guest notice arrives (review finding 43). Server thread.
     */
    private static final Map<UUID, Set<String>> WAITING = new java.util.concurrent.ConcurrentHashMap<>();
    /** Per-player image-byte buckets: bytes left, and when last topped up ({@link net.minecraft.Util#getMillis}). */
    private static final Map<UUID, double[]> IMAGE_BUDGET = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * New guests one player's text may make the server fetch per {@link #GUEST_WINDOW_MS}, and every source together
     * (the Discord bridge included) — review finding 40: pasted ids were fetched without limit.
     */
    private static final int GUESTS_PER_PLAYER = 8;
    private static final int GUESTS_PER_SERVER = 60;
    private static final long GUEST_WINDOW_MS = 60_000;
    private static final Map<UUID, double[]> GUEST_BUDGET = new java.util.concurrent.ConcurrentHashMap<>();
    private static final double[] SERVER_GUEST_BUDGET = {GUESTS_PER_SERVER, net.minecraft.Util.getMillis()};
    /** Ids Discord gave no usable image for, and when: not fetched again for {@link #FAILED_MS} (review finding 40). */
    private static final Map<String, Long> FAILED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FAILED_MS = 10 * 60_000;
    private static final int MAX_FAILED = 4096;
    /** A guest image (still PNG or frame strip) bigger than this isn't kept: a 32 px emoji never is (review finding 43). */
    private static final int MAX_GUEST_BYTES = 256 * 1024;
    /**
     * Guest fetches run here, two at a time with a short queue; past that a line's new guests are skipped (they stay
     * links or ":name:") rather than piling up on the common pool (review finding 40).
     */
    private static final java.util.concurrent.ThreadPoolExecutor FETCHER = fetcher();

    private static java.util.concurrent.ThreadPoolExecutor fetcher() {
        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(2, 2, 30,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16), task -> {
                    Thread thread = new Thread(task, "Eden emoji fetch");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private EmojiRegistry() {
    }

    public static void initialize() {
        // guests.json once, before any chat line can add a guest (review finding 69): loaded by every reload on the
        // common pool, it raced the fetcher's adds (a guest lost, its character handed out again), and an add before
        // the first reload's load saved a guests.json holding only that guest. A small file, read on the server thread.
        ServerLifecycleEvents.SERVER_STARTING.register(starting -> GUESTS.load());
        ServerLifecycleEvents.SERVER_STARTED.register(EmojiRegistry::reload);
        // Discord's channel and role names, so the first bridge lines already read "#name".
        ServerLifecycleEvents.SERVER_STARTED.register(started -> net.ledok.eden_ld.chat.DiscordNames.preload());
        // With the Discord sync set up, emojis added or deleted on Discord arrive by themselves.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int minutes = EdenLD.CONFIG.chat.emoji_sync_minutes;
            if (minutes > 0 && DiscordEmojiSync.enabled() && server.getTickCount() % (minutes * 1200) == 0) {
                reload(server);
            }
            if (server.getTickCount() % 1200 == 0) {
                refreshLimits(server);
            }
            if (server.getTickCount() % 20 == 0 && !WAITING.isEmpty()) {
                sendWaiting(server);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            // A fresh client asks for what it lacks itself.
            WAITING.remove(handler.getPlayer().getUUID());
            send(handler.getPlayer());
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                    UUID id = handler.getPlayer().getUUID();
                    SENT_LIMITS.remove(id);
                    WAITING.remove(id);
                    IMAGE_BUDGET.remove(id);
                    GUEST_BUDGET.remove(id);
                }));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.EmojiRequest.TYPE,
                (payload, context) -> answer(context.player(), payload.ids()));
        // Group permissions granted or taken apply live.
        ServerLifecycleEvents.SERVER_STARTED.register(started -> LuckPermsCompat.onUserDataChange(() ->
                started.execute(() -> started.getPlayerList().getPlayers().forEach(EmojiRegistry::send))));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.EmojiFavorite.TYPE,
                (payload, context) -> setFavorite(context.player(), payload.id(), payload.add()));
    }

    /**
     * The images a client asked for (review finding 43): duplicates dropped, within the player's byte budget, split
     * into answers of at most {@link #MAX_ANSWER_BYTES}. Ids past the budget wait in {@link #WAITING} for the refill;
     * an id asked for again (the client dropped it) is sent again, within the budget.
     */
    private static void answer(ServerPlayer player, List<String> ids) {
        UUID uuid = player.getUUID();
        double[] budget = IMAGE_BUDGET.computeIfAbsent(uuid, key -> new double[]{IMAGE_BURST_BYTES, net.minecraft.Util.getMillis()});
        List<HubPayloads.EmojiImage> batch = new ArrayList<>();
        int batchBytes = 0;
        Set<String> later = null;
        for (String id : new java.util.LinkedHashSet<>(ids.subList(0, Math.min(MAX_REQUEST, ids.size())))) {
            Image image = IMAGES.get(id);
            // An image bigger than the whole budget could never go: it isn't kept waiting.
            if (image == null || image.png().length > IMAGE_BURST_BYTES) {
                continue;
            }
            int bytes = image.png().length;
            if (later != null || !take(budget, IMAGE_BURST_BYTES, IMAGE_REFILL_MS, bytes)) {
                if (later == null) {
                    later = WAITING.computeIfAbsent(uuid, key -> new java.util.LinkedHashSet<>());
                }
                if (later.size() < MAX_REQUEST) {
                    later.add(id);
                }
                continue;
            }
            if (!batch.isEmpty() && batchBytes + bytes > MAX_ANSWER_BYTES) {
                ServerPlayNetworking.send(player, new HubPayloads.EmojiImages(batch));
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(new HubPayloads.EmojiImage(id, image.png()));
            batchBytes += bytes;
        }
        if (!batch.isEmpty()) {
            ServerPlayNetworking.send(player, new HubPayloads.EmojiImages(batch));
        }
    }

    /** Once a second: images owed past a player's budget go as far as it has refilled (the rest keep waiting). */
    private static void sendWaiting(MinecraftServer server) {
        for (UUID uuid : List.copyOf(WAITING.keySet())) {
            Set<String> owed = WAITING.remove(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (owed != null && !owed.isEmpty() && player != null && !player.hasDisconnected()) {
                answer(player, List.copyOf(owed));
            }
        }
    }

    /**
     * Takes {@code amount} from a bucket ({@code [left, last top-up]}) holding up to {@code capacity}, refilled fully
     * every {@code refillMs}; false (nothing taken) when there isn't enough.
     */
    private static boolean take(double[] bucket, double capacity, long refillMs, double amount) {
        synchronized (bucket) {
            long now = net.minecraft.Util.getMillis();
            bucket[0] = Math.min(capacity, bucket[0] + Math.max(0, now - (long) bucket[1]) * capacity / refillMs);
            bucket[1] = now;
            if (bucket[0] < amount) {
                return false;
            }
            bucket[0] -= amount;
            return true;
        }
    }

    /** One new guest fetch for {@code player}'s text (null: the Discord bridge), within theirs and the server's budget. */
    private static boolean guestBudget(@Nullable UUID player) {
        if (player != null && !take(GUEST_BUDGET.computeIfAbsent(player,
                key -> new double[]{GUESTS_PER_PLAYER, net.minecraft.Util.getMillis()}), GUESTS_PER_PLAYER, GUEST_WINDOW_MS, 1)) {
            return false;
        }
        return take(SERVER_GUEST_BUDGET, GUESTS_PER_SERVER, GUEST_WINDOW_MS, 1);
    }

    /** Whether fetching this id failed less than {@link #FAILED_MS} ago. */
    private static boolean failedRecently(String id) {
        Long at = FAILED.get(id);
        if (at == null) {
            return false;
        }
        if (net.minecraft.Util.getMillis() - at < FAILED_MS) {
            return true;
        }
        FAILED.remove(id);
        return false;
    }

    private static void markFailed(String id) {
        long now = net.minecraft.Util.getMillis();
        if (FAILED.size() >= MAX_FAILED) {
            FAILED.values().removeIf(at -> now - at >= FAILED_MS);
            if (FAILED.size() >= MAX_FAILED) {
                FAILED.clear();
            }
        }
        FAILED.put(id, now);
    }

    // ---------------------------------------------------------------- loading

    /** Re-reads the list, fetches missing images off-thread, then updates everyone online. */
    public static void reload(MinecraftServer server) {
        // Discord and the CDN are slow: all of it runs off the server thread.
        CompletableFuture.runAsync(() -> load(server));
    }

    private static void load(MinecraftServer server) {
        Map<String, Emoji> names = new LinkedHashMap<>();
        Map<String, Emoji> ids = new HashMap<>();
        int codepoint = FIRST_CODEPOINT;
        List<Group> readGroups = readGroups();
        for (Map.Entry<String, JsonElement> entry : readList().entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("_") || !entry.getValue().isJsonPrimitive()) {
                continue;
            }
            String value = entry.getValue().getAsString().trim();
            String id = idOf(value);
            // Every listed name keeps its character (file position), filled in or not, so
            // pasting an id later never shifts the others.
            int character = codepoint++;
            if (id == null || character >= GuestEmojis.FIRST_CODEPOINT) {
                continue;
            }
            boolean animated = value.startsWith("a:") || value.contains(".gif") || value.contains("animated=true");
            Emoji emoji = new Emoji(name, name.replaceAll("~\\d+$", ""), id, animated, character, false);
            names.put(name.toLowerCase(Locale.ROOT), emoji);
            ids.put(id, emoji);
        }
        // Guests aren't re-read here (review finding 69): they're loaded once at SERVER_STARTING, and from then on only
        // the fetcher changes them.
        List<Emoji> all = new ArrayList<>(names.values());
        Map<String, Image> loaded = new HashMap<>();
        for (Emoji emoji : all) {
            Image image = fetch(emoji.id(), emoji.animated(), emoji.name());
            if (image != null) {
                loaded.put(emoji.id(), image);
            }
        }
        // Guests' images are all cached already (fetched when they arrived).
        for (GuestEmojis.Guest guest : GUESTS.all()) {
            Image image = fetch(guest.id(), guest.animated(), guest.name());
            if (image != null) {
                loaded.put(guest.id(), image);
            }
        }
        List<HubPayloads.EmojiRef> list = new ArrayList<>();
        for (Emoji emoji : all) {
            Image image = loaded.get(emoji.id());
            if (image != null) {
                list.add(ref(emoji, image));
            }
        }
        Map<Integer, Emoji> characters = new HashMap<>();
        names.values().forEach(emoji -> characters.put(emoji.codepoint(), emoji));
        Map<String, Group> byEmoji = new HashMap<>();
        for (Group group : readGroups) {
            group.emojis().forEach(name -> byEmoji.putIfAbsent(name.toLowerCase(Locale.ROOT), group));
        }
        net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
            groups = readGroups;
            groupOf = byEmoji;
            byName = names;
            listedById = ids;
            listedByCodepoint = characters;
            // Images of guests added while this reload ran stay (review finding 69: clearing them left those guests
            // known but imageless until the next reload). Checked per id at removal time: the fetcher registers a
            // guest before it puts its image.
            IMAGES.keySet().removeIf(id -> !loaded.containsKey(id) && GUESTS.get(id) == null);
            IMAGES.putAll(loaded);
            // Failed ids get another try.
            FAILED.clear();
            List<HubPayloads.EmojiRef> previous = ready;
            ready = List.copyOf(list);
            // Players only hear about it when something changed (the hourly sync usually doesn't).
            // Groups are re-sent every time: they're cheap, and permissions may have changed.
            if (!ready.equals(previous) || !readGroups.isEmpty()) {
                server.getPlayerList().getPlayers().forEach(EmojiRegistry::send);
                EdenLD.LOGGER.info("[Emoji] {} of {} emojis ready ({} guests).", list.size(), all.size(),
                        GUESTS.all().size());
            }
        });
    }

    private static HubPayloads.EmojiRef ref(Emoji emoji, Image image) {
        return new HubPayloads.EmojiRef(emoji.name(), emoji.codepoint(), emoji.id(), image.delays(), emoji.guest());
    }

    /**
     * The {@code emojis} object of emoji.json (written with the starter list when missing), merged
     * with the Discord server's emojis when the sync is set up — saved when that changed it.
     */
    private static JsonObject readList() {
        JsonObject list = readFile();
        if (!DiscordEmojiSync.enabled()) {
            return list;
        }
        JsonObject merged = DiscordEmojiSync.merge(list, HTTP);
        if (!merged.equals(list)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
                root.add("emojis", merged);
                Files.writeString(FILE, GSON.toJson(root));
            } catch (IOException | RuntimeException e) {
                EdenLD.LOGGER.error("[Emoji] Can't save the synced list to {}.", FILE, e);
            }
        }
        return merged;
    }

    private static JsonObject readFile() {
        try {
            if (!Files.exists(FILE)) {
                JsonObject root = new JsonObject();
                root.addProperty("_comment", "Discord emojis for :name: in chat. Value: the emoji's id "
                        + "(Copy Emoji ID), or its image link (Copy Emoji Image Link; needed for animated ones). "
                        + "Empty = not used yet. Order sets each emoji's character: add new ones at the end. "
                        + "/emojis reload applies changes. With the Discord sync set up (chat.json: "
                        + "discord_bot_token + discord_guild_id) the list follows the server by itself; "
                        + "renaming entries here is kept.");
                JsonObject list = new JsonObject();
                STARTER.forEach(list::addProperty);
                root.add("emojis", list);
                root.add("groups", new JsonObject());
                Files.createDirectories(FOLDER);
                Files.writeString(FILE, GSON.toJson(root));
                return list;
            }
            JsonObject root = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            return root.has("emojis") && root.get("emojis").isJsonObject() ? root.getAsJsonObject("emojis") : new JsonObject();
        } catch (IOException | RuntimeException e) {
            EdenLD.LOGGER.error("[Emoji] Can't read {}.", FILE, e);
            return new JsonObject();
        }
    }

    /**
     * emoji.json's {@code groups}: {@code "id": {"name": ..., "icon": emoji name, "permission":
     * optional node, "emojis": [emoji names]}}.
     */
    private static List<Group> readGroups() {
        List<Group> read = new ArrayList<>();
        try {
            if (!Files.exists(FILE)) {
                return read;
            }
            JsonObject root = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            if (!root.has("groups") || !root.get("groups").isJsonObject()) {
                return read;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("groups").entrySet()) {
                if (entry.getKey().startsWith("_") || !entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject group = entry.getValue().getAsJsonObject();
                List<String> emojis = new ArrayList<>();
                if (group.has("emojis") && group.get("emojis").isJsonArray()) {
                    group.getAsJsonArray("emojis").forEach(element -> emojis.add(element.getAsString()));
                }
                read.add(new Group(entry.getKey(), text(group, "name", entry.getKey()), text(group, "icon", ""),
                        text(group, "permission", ""), List.copyOf(emojis)));
            }
        } catch (IOException | RuntimeException e) {
            EdenLD.LOGGER.error("[Emoji] Can't read the groups in {}.", FILE, e);
        }
        return read;
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString().trim() : fallback;
    }

    /** Whether the player may use (and pick) this emoji: its group's permission, if any. */
    public static boolean canUse(@Nullable ServerPlayer player, Emoji emoji) {
        if (player == null || emoji.guest()) {
            return true;
        }
        Group group = groupOf.get(emoji.name().toLowerCase(Locale.ROOT));
        return group == null || group.permission().isEmpty()
                || LuckPermsCompat.hasPermission(player, group.permission());
    }

    private static boolean canSee(ServerPlayer player, Group group) {
        return group.permission().isEmpty() || LuckPermsCompat.hasPermission(player, group.permission());
    }

    /** "123", "a:123" or a CDN link → "123"; null when there's no id. */
    @Nullable
    static String idOf(String value) {
        if (value.isEmpty()) {
            return null;
        }
        String bare = value.startsWith("a:") ? value.substring(2) : value;
        if (bare.chars().allMatch(Character::isDigit)) {
            return bare;
        }
        Matcher matcher = ID_IN_LINK.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * An emoji's image from the cache, downloading it when missing: a still PNG, or for animated
     * emojis the GIF (decoded into a frame strip on every load — cheap; the cache keeps the
     * original). Null when Discord doesn't have it. Blocking: off the server thread only.
     */
    @Nullable
    private static Image fetch(String id, boolean animated, String name) {
        String extension = animated ? "gif" : "png";
        Path file = CACHE.resolve(id + "." + extension);
        try {
            if (!Files.exists(file)) {
                HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(
                                "https://cdn.discordapp.com/emojis/" + id + "." + extension + "?size=" + IMAGE_SIZE))
                        .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    EdenLD.LOGGER.warn("[Emoji] :{}: ({}) — Discord answered {}.", name, id, response.statusCode());
                    return null;
                }
                Files.createDirectories(CACHE);
                Files.write(file, response.body());
            }
            byte[] bytes = Files.readAllBytes(file);
            if (animated) {
                GifStrip strip = GifStrip.decode(bytes);
                return new Image(strip.png(), strip.delays());
            }
            return new Image(bytes, List.of());
        } catch (IOException | RuntimeException e) {
            EdenLD.LOGGER.warn("[Emoji] Can't get :{}: ({}): {}", name, id, e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ---------------------------------------------------------------- players

    /** The player's emoji list: every listed emoji, plus the guests they favorited, and their favorites. */
    private static void send(ServerPlayer player) {
        if (player.hasDisconnected() || !ServerPlayNetworking.canSend(player, HubPayloads.EmojiList.TYPE)) {
            return;
        }
        List<String> favorites = favorites(player);
        List<HubPayloads.EmojiRef> list = new ArrayList<>(ready);
        for (String id : favorites) {
            GuestEmojis.Guest guest = listedById.containsKey(id) ? null : GUESTS.get(id);
            Image image = guest == null ? null : IMAGES.get(id);
            if (image != null) {
                list.add(ref(asEmoji(guest), image));
            }
        }
        List<HubPayloads.EmojiGroup> tabs = new ArrayList<>();
        List<String> locked = new ArrayList<>();
        for (Group group : groups) {
            List<String> ids = new ArrayList<>();
            for (String name : group.emojis()) {
                Emoji emoji = byName(name);
                if (emoji != null) {
                    ids.add(emoji.id());
                }
            }
            if (canSee(player, group)) {
                tabs.add(new HubPayloads.EmojiGroup(group.id(), group.name(), group.icon(), ids));
            } else {
                locked.addAll(ids);
            }
        }
        ServerPlayNetworking.send(player, new HubPayloads.EmojiList(list, favorites, favoritesLimit(player), tabs, locked,
                stored(player, STICKERS_TAG), stickersLimit(player)));
        SENT_LIMITS.put(player.getUUID(), limits(player));
    }

    /** The player's saved-stickers limit, built like {@link #favoritesLimit}. */
    public static int stickersLimit(ServerPlayer player) {
        int base = LuckPermsCompat.metaInt(player, META_STICKERS).orElse(EdenLD.CONFIG.chat.sticker_favorites);
        return Math.max(0, base + bonus(player, FavoriteBonus.Kind.STICKERS)
                + levelPerk(player, net.ledok.eden_ld.hub.profile.LevelPerks.Kind.STICKER_SLOTS)
                + guildPerk(player, net.ledok.eden_ld.hub.guild.GuildPerks.Kind.STICKER_SLOTS));
    }

    /** Slots the player's Minestar level gives (profile.level_perks). */
    private static int levelPerk(ServerPlayer player, net.ledok.eden_ld.hub.profile.LevelPerks.Kind kind) {
        MinecraftServer server = player.getServer();
        return server == null ? 0 : net.ledok.eden_ld.hub.profile.ProfileLevels.perk(server, player.getUUID(), kind);
    }

    /** Slots the player's guild level gives its members (guilds.level_perks). */
    private static int guildPerk(ServerPlayer player, net.ledok.eden_ld.hub.guild.GuildPerks.Kind kind) {
        MinecraftServer server = player.getServer();
        return server == null ? 0 : net.ledok.eden_ld.hub.guild.GuildLevels.memberPerk(server, player.getUUID(), kind);
    }

    private static int bonus(ServerPlayer player, FavoriteBonus.Kind kind) {
        MinecraftServer server = player.getServer();
        return server == null ? 0 : FavoriteBonus.active(server, player.getUUID(), kind);
    }

    private static long limits(ServerPlayer player) {
        return ((long) favoritesLimit(player) << 32) | (stickersLimit(player) & 0xFFFFFFFFL);
    }

    /** Sends the list again to online players whose limits changed (a bonus ended or began, a guild changed). */
    public static void refreshLimits(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Long sent = SENT_LIMITS.get(player.getUUID());
            if (sent != null && sent != limits(player)) {
                send(player);
            }
        }
    }

    /** After a grant: the player (if online) gets their new limits at once. */
    public static void limitsChanged(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            send(online);
        }
    }

    /**
     * The player's favorites limit: base (LuckPerms meta, else config) + {@code /emojis bonus}
     * grants (see {@link FavoriteBonus}).
     */
    public static int favoritesLimit(ServerPlayer player) {
        int base = LuckPermsCompat.metaInt(player, META_FAVORITES).orElse(EdenLD.CONFIG.chat.emoji_favorites);
        return Math.max(0, base + bonus(player, FavoriteBonus.Kind.EMOJIS)
                + levelPerk(player, net.ledok.eden_ld.hub.profile.LevelPerks.Kind.EMOJI_SLOTS)
                + guildPerk(player, net.ledok.eden_ld.hub.guild.GuildPerks.Kind.EMOJI_SLOTS));
    }

    private static List<String> favorites(ServerPlayer player) {
        return stored(player, FAVORITES_TAG);
    }

    private static List<String> stored(ServerPlayer player, String key) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return List.of();
        }
        ListTag tag = HubData.get(server).peek(player.getUUID()).getList(key, Tag.TAG_STRING);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < tag.size(); i++) {
            ids.add(tag.getString(i));
        }
        return ids;
    }

    /**
     * Stars or unstars an emoji — or saves or drops a sticker ({@code s:} ids, a list and limit
     * of their own) — in the player's picker (only with the picker permission).
     */
    private static void setFavorite(ServerPlayer player, String id, boolean add) {
        MinecraftServer server = player.getServer();
        if (server == null || !LuckPermsCompat.hasPermission(player, ChatChannels.PERMISSION_EMOJI_PICKER)) {
            return;
        }
        boolean sticker = id.startsWith(ChatLinks.Sticker.PREFIX);
        if (sticker) {
            if (add && ChatLinks.stickerFavorite(id) == null) {
                return;
            }
        } else {
            Emoji custom = byId(id);
            if ((custom == null && UnicodeEmoji.byId(id) == null) || (custom != null && !canUse(player, custom))) {
                return;
            }
        }
        String key = sticker ? STICKERS_TAG : FAVORITES_TAG;
        int limit = sticker ? stickersLimit(player) : favoritesLimit(player);
        List<String> ids = new ArrayList<>(stored(player, key));
        boolean changed = add ? !ids.contains(id) && ids.size() < limit && ids.add(id) : ids.remove(id);
        if (changed) {
            ListTag tag = new ListTag();
            ids.forEach(favorite -> tag.add(StringTag.valueOf(favorite)));
            CompoundTag root = HubData.get(server).player(player.getUUID());
            root.put(key, tag);
        }
        send(player);
    }

    /** Every player's favorites (guests they star are never evicted). */
    private static Set<String> allFavorites(MinecraftServer server) {
        Set<String> ids = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ids.addAll(favorites(player));
        }
        return ids;
    }

    // ---------------------------------------------------------------- lookups

    private static Emoji asEmoji(GuestEmojis.Guest guest) {
        return new Emoji(guest.name(), guest.name(), guest.id(), guest.animated(), guest.codepoint(), true);
    }

    /** A listed emoji by name (guests aren't typeable), or null. */
    @Nullable
    public static Emoji byName(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    /** A listed emoji or a guest by Discord id, or null. */
    @Nullable
    public static Emoji byId(String id) {
        Emoji listed = listedById.get(id);
        if (listed != null) {
            return listed;
        }
        GuestEmojis.Guest guest = GUESTS.get(id);
        return guest == null ? null : asEmoji(guest);
    }

    /** The emoji (listed or guest) whose character this is, or null. */
    @Nullable
    public static Emoji byCodepoint(int codepoint) {
        Emoji listed = listedByCodepoint.get(codepoint);
        if (listed != null) {
            return listed;
        }
        if (codepoint >= GuestEmojis.FIRST_CODEPOINT && codepoint <= GuestEmojis.LAST_CODEPOINT) {
            for (GuestEmojis.Guest guest : GUESTS.all()) {
                if (guest.codepoint() == codepoint) {
                    return asEmoji(guest);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- text

    /**
     * The emoji as chat text: its character in Eden's emoji font, ":name:" on hover. The
     * insertion is the character itself (Shift+click puts it in the chat box; right-click
     * favorites it).
     */
    public static MutableComponent glyph(Emoji emoji) {
        String character = Character.toString(emoji.codepoint());
        return Component.literal(character).withStyle(style -> style
                .withFont(FONT)
                .withColor(ChatFormatting.WHITE)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(":" + emoji.name() + ":")))
                .withInsertion(character));
    }

    /** A standard emoji as chat text, like {@link #glyph(Emoji)}. */
    public static MutableComponent glyph(UnicodeEmoji.Entry emoji) {
        String character = Character.toString(emoji.codepoint());
        return Component.literal(character).withStyle(style -> style
                .withFont(FONT)
                .withColor(ChatFormatting.WHITE)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(":" + emoji.name() + ":")))
                .withInsertion(character));
    }

    /**
     * The emoji a {@code :name:} stands for as chat text — the server's own first, then the
     * standard ones — or null.
     */
    @Nullable
    public static MutableComponent glyphForName(String name) {
        return glyphForName(name, null);
    }

    /** As {@link #glyphForName(String)}, for text {@code user} typed: their group permissions apply. */
    @Nullable
    public static MutableComponent glyphForName(String name, @Nullable ServerPlayer user) {
        Emoji custom = byName(name);
        if (custom != null) {
            return canUse(user, custom) ? glyph(custom) : null;
        }
        UnicodeEmoji.Entry standard = UnicodeEmoji.byName(name);
        return standard == null ? null : glyph(standard);
    }

    /** The emoji whose character this is (server's, guest or standard) as chat text, or null. */
    @Nullable
    public static MutableComponent glyphForCharacter(int codepoint) {
        return glyphForCharacter(codepoint, null);
    }

    /** As {@link #glyphForCharacter(int)}, for text {@code user} typed: their group permissions apply. */
    @Nullable
    public static MutableComponent glyphForCharacter(int codepoint, @Nullable ServerPlayer user) {
        Emoji custom = byCodepoint(codepoint);
        if (custom != null) {
            return canUse(user, custom) ? glyph(custom) : null;
        }
        UnicodeEmoji.Entry standard = UnicodeEmoji.byCodepoint(codepoint);
        return standard == null ? null : glyph(standard);
    }

    /**
     * The text as Discord should get it: known {@code :name:} and emoji characters →
     * {@code <:name:id>}; guests (maybe from another server, which the bridge's bot can't show)
     * → their image link, the way people without Nitro post emojis.
     */
    public static String toDiscord(String text) {
        Matcher matcher = Pattern.compile(SHORTCODE.pattern() + "|" + CHARACTER.pattern()).matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Emoji emoji = matcher.group(1) != null ? byName(matcher.group(1)) : byCodepoint(matcher.group(2).codePointAt(0));
            UnicodeEmoji.Entry standard = emoji != null ? null : matcher.group(1) != null
                    ? UnicodeEmoji.byName(matcher.group(1)) : UnicodeEmoji.byCodepoint(matcher.group(2).codePointAt(0));
            String replacement;
            if (standard != null) {
                replacement = standard.unicode();
            } else if (emoji == null) {
                replacement = matcher.group();
            } else if (emoji.guest()) {
                // Discord shows each such link's text with the image under the message; glued to
                // its neighbors (another guest, a word) it breaks — keep them apart with spaces.
                replacement = "[" + emoji.name() + "](https://cdn.discordapp.com/emojis/" + emoji.id() + ".webp?size=48"
                        + (emoji.animated() ? "&animated=true" : "") + "&name=" + emoji.name() + ")";
                if (matcher.start() > 0 && !Character.isWhitespace(text.charAt(matcher.start() - 1))) {
                    replacement = " " + replacement;
                }
                if (matcher.end() < text.length() && !Character.isWhitespace(text.charAt(matcher.end()))) {
                    replacement = replacement + " ";
                }
            } else {
                replacement = "<" + (emoji.animated() ? "a" : "") + ":" + emoji.discordName() + ":" + emoji.id() + ">";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** One custom emoji a Discord line mentions: id, name, animated. */
    private record Mention(String id, String name, boolean animated) {
    }

    /**
     * A plain-text line from the Discord bridge (legacy colors), shown the Eden way: emojis as
     * images — unknown custom emojis fetched as guests first — links clickable (GIFs as
     * "[GIF]"), Discord's markdown and spoilers applied. Returns false when the line has nothing to change (it goes out as is).
     */
    public static boolean showDiscordLine(MinecraftServer server, String received, java.util.function.Consumer<Component> broadcast) {
        // Minestar turns every "&x" of the line into a color code — links' "&name=" included.
        // Inside links, put the "&" back (a link never holds "§"); the rest keeps its colors.
        String restored = restoreLinkAmpersands(received);
        String line = UnicodeEmoji.toCharacters(restored);
        boolean standard = !line.equals(restored);
        CompletableFuture<Void> guests = fetchGuests(server, line);
        // Discord's raw mentions (<#channel>, <@user>, <@&role>) read as names; missing ones are asked for first.
        CompletableFuture<Void> names = net.ledok.eden_ld.chat.DiscordNames.fetch(server, line);
        boolean mentions = net.ledok.eden_ld.chat.DiscordNames.hasMention(line);
        if (guests.isDone() && names.isDone()) {
            if (!standard && !mentions && !hasEmoji(line) && !ChatLinks.hasLink(line)
                    && !net.ledok.eden_ld.chat.ChatFormat.hasFormatting(line, net.ledok.eden_ld.chat.ChatStyle.formatOptions())) {
                return false;
            }
            broadcast.accept(renderDiscord(line));
            return true;
        }
        CompletableFuture.allOf(guests, names).thenRunAsync(() -> broadcast.accept(renderDiscord(line)), server);
        return true;
    }

    /**
     * Makes every custom emoji the text mentions — {@code <:name:id>} or an image link (FakeNitro
     * style, from Discord or pasted in game) — known: unknown ones are fetched and registered as
     * guests, and every player learns about them. Completes on the server thread once that's
     * done (at once when nothing is unknown); at most {@link #MAX_NEW_GUESTS_PER_LINE} per text.
     * Counts against the server's guest budget only; {@link #fetchGuests(MinecraftServer, String, UUID)}
     * also charges the player who wrote it.
     */
    public static CompletableFuture<Void> fetchGuests(MinecraftServer server, String text) {
        return fetchGuests(server, text, null);
    }

    /**
     * {@link #fetchGuests(MinecraftServer, String)} for a player's text: new guests count against their budget
     * ({@value #GUESTS_PER_PLAYER} a minute) and the server's ({@value #GUESTS_PER_SERVER}); ids that failed lately
     * aren't fetched again. Those left out stay a link or ":name:" (review finding 40).
     */
    public static CompletableFuture<Void> fetchGuests(MinecraftServer server, String text, @Nullable UUID player) {
        List<Mention> unknown = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Mention mention : mentions(text)) {
            if (byId(mention.id()) == null && seen.add(mention.id()) && unknown.size() < MAX_NEW_GUESTS_PER_LINE
                    && !failedRecently(mention.id()) && guestBudget(player)) {
                unknown.add(mention);
            }
        }
        // Guests the server already knows go to everyone too: players who joined after a guest
        // first came in (or unfavorited it) don't have it otherwise, and see an empty box.
        List<HubPayloads.EmojiRef> known = new ArrayList<>();
        Set<String> knownIds = new HashSet<>();
        for (Mention mention : mentions(text)) {
            Emoji emoji = byId(mention.id());
            Image image = emoji != null && emoji.guest() ? IMAGES.get(emoji.id()) : null;
            if (image != null && knownIds.add(emoji.id())) {
                known.add(ref(emoji, image));
            }
        }
        // ...and guests typed as their character (picked from favorites).
        guestsByCharacter(text, knownIds, known);
        if (unknown.isEmpty()) {
            announceGuests(server, known);
            return CompletableFuture.completedFuture(null);
        }
        Set<String> keep = allFavorites(server);
        CompletableFuture<List<HubPayloads.EmojiRef>> fetched;
        try {
            fetched = CompletableFuture.supplyAsync(() -> fetchNew(unknown, keep), FETCHER);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Too many fetches waiting already: this text's new guests are skipped.
            announceGuests(server, known);
            return CompletableFuture.completedFuture(null);
        }
        return fetched.exceptionally(error -> List.of()).thenAcceptAsync(added -> {
            List<HubPayloads.EmojiRef> all = new ArrayList<>(known);
            all.addAll(added);
            announceGuests(server, all);
        }, server);
    }

    /**
     * Fetches and registers new guests ({@link #FETCHER}). A failed or oversized one is remembered as failed; a guest
     * evicted to make room loses its image and cached file unless it's listed (review finding 40).
     */
    private static List<HubPayloads.EmojiRef> fetchNew(List<Mention> unknown, Set<String> keep) {
        List<HubPayloads.EmojiRef> added = new ArrayList<>();
        List<GuestEmojis.Guest> evicted = new ArrayList<>();
        for (Mention mention : unknown) {
            Image image = fetch(mention.id(), mention.animated(), mention.name());
            if (image == null || image.png().length > MAX_GUEST_BYTES) {
                if (image != null) {
                    EdenLD.LOGGER.warn("[Emoji] Guest {} ({}) is too big to keep ({} bytes).", mention.name(), mention.id(),
                            image.png().length);
                    deleteCached(mention.id(), mention.animated());
                }
                markFailed(mention.id());
                continue;
            }
            GuestEmojis.Guest guest = GUESTS.add(mention.id(), sanitize(mention.name()), mention.animated(), keep,
                    evicted::add);
            if (guest != null) {
                IMAGES.put(guest.id(), image);
                added.add(ref(asEmoji(guest), image));
            }
        }
        for (GuestEmojis.Guest gone : evicted) {
            if (!listedById.containsKey(gone.id()) && GUESTS.get(gone.id()) == null) {
                IMAGES.remove(gone.id());
                deleteCached(gone.id(), gone.animated());
            }
        }
        if (!added.isEmpty() || !evicted.isEmpty()) {
            GUESTS.save();
        }
        return added;
    }

    /** Deletes a guest's downloaded file (unless a listed emoji uses the id). Off the server thread. */
    private static void deleteCached(String id, boolean animated) {
        if (listedById.containsKey(id)) {
            return;
        }
        try {
            Files.deleteIfExists(CACHE.resolve(id + "." + (animated ? "gif" : "png")));
        } catch (IOException e) {
            EdenLD.LOGGER.warn("[Emoji] Can't delete the cached guest {}: {}", id, e.toString());
        }
    }

    /** Guests to every online Eden client (they keep them for the session; images load from cache or on request). */
    private static void announceGuests(MinecraftServer server, List<HubPayloads.EmojiRef> guests) {
        if (guests.isEmpty()) {
            return;
        }
        HubPayloads.EmojiGuests payload = new HubPayloads.EmojiGuests(guests);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, HubPayloads.EmojiGuests.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    /**
     * Tells a joining player about the guests in text they're about to see (the recent chat, their
     * guild's and party's chat panels), so old lines don't show empty boxes.
     */
    public static void sendGuestsIn(ServerPlayer player, Iterable<Component> texts) {
        if (!ServerPlayNetworking.canSend(player, HubPayloads.EmojiGuests.TYPE)) {
            return;
        }
        List<HubPayloads.EmojiRef> refs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Component text : texts) {
            guestsByCharacter(text.getString(), ids, refs);
        }
        if (!refs.isEmpty()) {
            ServerPlayNetworking.send(player, new HubPayloads.EmojiGuests(refs));
        }
    }

    /** Adds the guests whose characters are in {@code text} (each once, by {@code ids}) to {@code refs}. */
    private static void guestsByCharacter(String text, Set<String> ids, List<HubPayloads.EmojiRef> refs) {
        if (text.codePoints().noneMatch(c -> c >= GuestEmojis.FIRST_CODEPOINT && c <= GuestEmojis.LAST_CODEPOINT)) {
            return;
        }
        Map<Integer, GuestEmojis.Guest> byCodepoint = new HashMap<>();
        GUESTS.all().forEach(guest -> byCodepoint.put(guest.codepoint(), guest));
        text.codePoints()
                .filter(c -> c >= GuestEmojis.FIRST_CODEPOINT && c <= GuestEmojis.LAST_CODEPOINT)
                .forEach(c -> {
                    GuestEmojis.Guest guest = byCodepoint.get(c);
                    Image image = guest == null ? null : IMAGES.get(guest.id());
                    if (image != null && ids.add(guest.id())) {
                        refs.add(ref(asEmoji(guest), image));
                    }
                });
    }

    /** An emoji's image link at {@code start} — "[name](https://cdn…/emojis/…)" or the bare link — as {@link CDN} finds it. */
    public static Pattern cdnPattern() {
        return CDN;
    }

    /**
     * A bridge line as shown: mentions as names, Discord's embed-less {@code <link>} without its brackets, then
     * colors, emojis, links and markdown.
     */
    private static Component renderDiscord(String line) {
        String resolved = net.ledok.eden_ld.chat.DiscordNames.resolve(line).replaceAll("<(https?://[^\\s<>]+)>", "$1");
        return render(net.ledok.eden_ld.chat.LegacyText.parse(resolved, false));
    }

    /** Every "§" inside a link back to the "&" it was (see {@link #showDiscordLine}). */
    private static String restoreLinkAmpersands(String line) {
        if (line.indexOf('§') < 0) {
            return line;
        }
        Matcher matcher = Pattern.compile("https?://\\S+").matcher(line);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group().replace('§', '&')));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String sanitize(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return clean.isEmpty() ? "emoji" : clean.length() > 32 ? clean.substring(0, 32) : clean;
    }

    /** Custom emojis a line mentions: {@code <a:name:id>} and CDN image links. */
    private static List<Mention> mentions(String text) {
        List<Mention> found = new ArrayList<>();
        Matcher discord = DISCORD.matcher(text);
        while (discord.find()) {
            found.add(new Mention(discord.group(3), discord.group(2), !discord.group(1).isEmpty()));
        }
        Matcher cdn = CDN.matcher(text);
        while (cdn.find()) {
            String link = cdn.group(2) != null ? cdn.group(2) : cdn.group(4);
            String id = cdn.group(3) != null ? cdn.group(3) : cdn.group(5);
            String name = cdn.group(1) != null && !cdn.group(1).isBlank() ? cdn.group(1) : nameParam(link);
            found.add(new Mention(id, name, link.contains("animated=true") || link.contains(".gif")));
        }
        return found;
    }

    private static String nameParam(String link) {
        Matcher matcher = Pattern.compile("[?&]name=([A-Za-z0-9_\\-~]+)").matcher(link);
        return matcher.find() ? matcher.group(1) : "emoji";
    }

    /** Whether a plain text line holds a known emoji (by id or name). */
    public static boolean hasEmoji(String text) {
        for (Mention mention : mentions(text)) {
            if (byId(mention.id()) != null) {
                return true;
            }
        }
        Matcher matcher = SHORTCODE.matcher(text);
        while (matcher.find()) {
            if (byName(matcher.group(1)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * A copy of {@code text} with its literal parts rendered: emoji codes as emojis (Discord's
     * {@code <:name:id>} — unknown ids fall back to ":name:" — CDN image links, known
     * {@code :name:}) and links clickable.
     */
    public static Component render(Component text) {
        MutableComponent out;
        if (text.getContents() instanceof PlainTextContents.LiteralContents literal) {
            out = Component.empty().withStyle(text.getStyle());
            appendRendered(out, literal.text());
        } else {
            out = text.plainCopy().withStyle(text.getStyle());
        }
        for (Component sibling : text.getSiblings()) {
            out.append(render(sibling));
        }
        return out;
    }

    /** Markdown and tags ({@link net.ledok.eden_ld.chat.ChatFormat}) around the emojis and links. */
    private static void appendRendered(MutableComponent out, String text) {
        net.ledok.eden_ld.chat.ChatFormat.build(out, net.ledok.eden_ld.chat.ChatFormat.parse(text,
                net.ledok.eden_ld.chat.ChatStyle.formatOptions()), EmojiRegistry::appendTokens);
    }

    private static final Pattern TOKENS = Pattern.compile(CDN.pattern() + "|" + DISCORD.pattern() + "|"
            + SHORTCODE.pattern() + "|" + ChatLinks.STICKER.pattern() + "|" + ChatLinks.URL.pattern() + "|"
            + CHARACTER.pattern() + "|" + ChatLinks.MASKED.pattern());

    private static void appendTokens(MutableComponent out, String text) {
        // Groups: 1-5 CDN image link, 6-8 <a:name:id>, 9 :name:, 10-12 sticker link, 13 any other
        // link, 14 emoji character, 15-16 a masked link [text](link) (Discord's; stickers matched first).
        Matcher matcher = TOKENS.matcher(text);
        int last = 0;
        while (matcher.find()) {
            Component replacement;
            if (matcher.group(3) != null || matcher.group(5) != null) {
                Emoji emoji = byId(matcher.group(3) != null ? matcher.group(3) : matcher.group(5));
                replacement = emoji != null ? glyph(emoji)
                        : ChatLinks.link(matcher.group(2) != null ? matcher.group(2) : matcher.group(4));
            } else if (matcher.group(8) != null) {
                Emoji emoji = byId(matcher.group(8));
                replacement = emoji != null ? glyph(emoji) : Component.literal(":" + matcher.group(7) + ":");
            } else if (matcher.group(9) != null) {
                replacement = glyphForName(matcher.group(9));
                if (replacement == null) {
                    continue;
                }
            } else if (matcher.group(11) != null || matcher.group(12) != null) {
                replacement = ChatLinks.sticker(matcher.group(10), matcher.group(11) != null ? matcher.group(11) : matcher.group(12));
            } else if (matcher.group(13) != null) {
                replacement = ChatLinks.link(matcher.group(13));
            } else if (matcher.group(16) != null) {
                replacement = ChatLinks.masked(matcher.group(15), matcher.group(16));
            } else {
                replacement = glyphForCharacter(matcher.group(14).codePointAt(0));
                if (replacement == null) {
                    continue;
                }
            }
            out.append(text.substring(last, matcher.start()));
            out.append(replacement);
            last = matcher.end();
        }
        out.append(text.substring(last));
    }
}
