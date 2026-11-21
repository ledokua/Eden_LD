package net.ledok.eden_ld.hub;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A per-player token bucket for hub C2S payloads (review findings 37, 41 and 45): a modified client could send party
 * and friend actions or Home requests hundreds of times a second, and each one ran server-wide resyncs or platform
 * HTTP calls. A bucket holds {@code capacity} tokens, refills at {@code perSecond}, and every accepted packet takes
 * one, so normal clicking (a burst, then a pause) is never refused while a flood is cut to the refill rate.
 * <p>
 * Server thread only (payload receivers run there); a bucket is dropped when its player disconnects and all of them
 * when the server stops.
 */
public final class HubRateLimit {

    /** Every limit, for the disconnect and stop cleanup; declared before the limits, which add themselves. */
    private static final List<HubRateLimit> ALL = new ArrayList<>();

    /** Party actions other than chat (chat has ChatGuard): a burst of 6, then 3 a second. */
    public static final HubRateLimit PARTY_ACTION = new HubRateLimit(6, 3.0);
    /** Friend actions: a burst of 3, then one every 0.75 s (the review's 0.5–1 s cooldown). */
    public static final HubRateLimit FRIEND_ACTION = new HubRateLimit(3, 1.0 / 0.75);
    /** Home tab requests (each one may ask the website for pending purchases): a burst of 6, then one a second. */
    public static final HubRateLimit HOME_REQUEST = new HubRateLimit(6, 1.0);
    /** Guild actions other than chat (each can resync the guild): a burst of 6, then 3 a second (review finding 38). */
    public static final HubRateLimit GUILD_ACTION = new HubRateLimit(6, 3.0);
    /** Guild bank actions: a burst of 8, then 4 a second (review finding 25). */
    public static final HubRateLimit BANK_ACTION = new HubRateLimit(8, 4.0);
    /** Profile pages (an offline one reads a stats file, a platform one asks the website): 12, then 4 a second (36). */
    public static final HubRateLimit PROFILE_REQUEST = new HubRateLimit(12, 4.0);
    /**
     * Dailies tab requests (each rebuilds the tab and may look up the LC balance): a burst of 10, then 3 a second
     * (review finding 52; widened in the review of 1.3.133 so the day-rollover refresh is never dropped). Over it the
     * request is dropped silently: the tab already has data that is kept up to date.
     */
    public static final HubRateLimit DAILIES_REQUEST = new HubRateLimit(10, 3.0);

    /** A refused player hears about it at most this often, so the refusal itself isn't a packet amplifier. */
    private static final long WARN_GAP_MS = 1_000;

    private final int capacity;
    private final double perSecond;
    private final Map<UUID, Bucket> buckets = new HashMap<>();

    private static final class Bucket {
        double tokens;
        long refilledAt;
        long warnedAt;
    }

    private HubRateLimit(int capacity, double perSecond) {
        this.capacity = capacity;
        this.perSecond = perSecond;
        ALL.add(this);
    }

    /** Drops buckets on disconnect and server stop; called from {@link HubServer#initialize()}. */
    static void initialize() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> net.ledok.eden_ld.util.OnServerThread.run(
                server, () -> {
                    UUID uuid = handler.getPlayer().getUUID();
                    for (HubRateLimit limit : ALL) {
                        limit.buckets.remove(uuid);
                    }
                }));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            for (HubRateLimit limit : ALL) {
                limit.buckets.clear();
            }
        });
    }

    /** Takes a token for this packet; false when the player is over the limit and the packet should be dropped. */
    public boolean tryAcquire(ServerPlayer player) {
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(player.getUUID(), uuid -> {
            Bucket fresh = new Bucket();
            fresh.tokens = capacity;
            fresh.refilledAt = now;
            return fresh;
        });
        double elapsedSeconds = (now - bucket.refilledAt) / 1_000_000_000.0;
        bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * perSecond);
        bucket.refilledAt = now;
        if (bucket.tokens < 1.0) {
            return false;
        }
        bucket.tokens -= 1.0;
        return true;
    }

    /** Takes a token; when refused, tells the player "slow down" (at most once a second). True when allowed. */
    public boolean allow(ServerPlayer player) {
        if (tryAcquire(player)) {
            return true;
        }
        if (warnNow(player)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.eden_ld.hub.too_fast")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
        }
        return false;
    }

    /** After a refusal: whether to tell the player (at most once per {@link #WARN_GAP_MS}). */
    public boolean warnNow(ServerPlayer player) {
        Bucket bucket = buckets.get(player.getUUID());
        if (bucket == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - bucket.warnedAt < WARN_GAP_MS) {
            return false;
        }
        bucket.warnedAt = now;
        return true;
    }
}
