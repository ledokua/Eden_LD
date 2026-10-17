package net.ledok.eden_ld.chat;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.BanHammerCompat;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What text Eden sends on a channel for a player ({@link ChatChannels#send}: the hub's party and guild chat, {@code /p},
 * {@code /g}, {@code /l}) is checked against — what typed chat gets from vanilla and BanHammer: no {@code §} or control
 * characters, at most 256 characters, not while muted, and a rate limit ({@code chat.rate_limit_messages} in a row,
 * as many again every {@code chat.rate_limit_seconds}). Other player-written text (guild names and MOTD, party names,
 * bank tab names, gift notes) uses {@link #allowedText} and {@link #mutedNow}.
 * <p>
 * Mutes come from BanHammer, whose database is never read on the server thread:
 * <ul>
 *   <li>a new mute is taken from BanHammer's punish event itself (its database write may not have landed yet);</li>
 *   <li>each online player's mutes are read off-thread at join, again while a muted player keeps trying (at most
 *       every {@value #RECHECK_MS} ms — BanHammer has no unmute event), and every {@value #REFRESH_MS} ms while a
 *       player chats, which also corrects a read that failed (BanHammer reports database errors as "no punishments");
 *   <li>a single empty read never lifts a known mute: the next read has to agree;</li>
 *   <li>a read that started before a punish event never overwrites what the event set.</li>
 * </ul>
 */
public final class ChatGuard {

    /** Vanilla's chat limit. */
    public static final int MAX_LENGTH = 256;
    private static final long RECHECK_MS = 10_000;
    private static final long REFRESH_MS = 60_000;

    /**
     * An online player's mute: when it ends (epoch millis, 0 = not muted), when it was last read ({@link Util#getMillis}),
     * a counter bumped by every punish event (a read started before one is dropped), and whether one empty read
     * already disagreed with a known mute.
     */
    private record Mute(long until, long read, int events, boolean doubted) {
    }

    private static final Map<UUID, Mute> MUTES = new ConcurrentHashMap<>();
    private static final Set<UUID> READING = ConcurrentHashMap.newKeySet();
    /** Asked for a read while one was running: one more read when it's done. */
    private static final Set<UUID> AGAIN = ConcurrentHashMap.newKeySet();
    /** Per-player token buckets: tokens left and when they were last topped up (server thread only). */
    private static final Map<UUID, double[]> BUCKETS = new ConcurrentHashMap<>();

    private ChatGuard() {
    }

    public static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID id = handler.getPlayer().getUUID();
            if (BanHammerCompat.LOADED) {
                MUTES.put(id, new Mute(0, 0, 0, false));
                read(id);
            }
        });
        // DISCONNECT fires while the player is still in the player list (and server.execute would run at once on the
        // server thread): forget them in a queued task, after they're gone — unless they're back already.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            server.tell(new net.minecraft.server.TickTask(server.getTickCount(), () -> {
                if (server.getPlayerList().getPlayer(id) == null) {
                    MUTES.remove(id);
                    BUCKETS.remove(id);
                }
            }));
        });
        BanHammerCompat.onMute(ChatGuard::muted);
    }

    /** Why the player can't send {@code text} now, or null when they can (one rate-limit token is taken then). */
    @Nullable
    public static Component check(ServerPlayer sender, String text) {
        if (sender.hasDisconnected() || !sender.connection.isAcceptingMessages()) {
            return Component.empty();
        }
        if (text.length() > MAX_LENGTH) {
            return Component.translatable("message.eden_ld.chat.too_long", MAX_LENGTH);
        }
        if (!allowedText(text)) {
            return Component.translatable("message.eden_ld.chat.illegal_characters");
        }
        if (mutedNow(sender.getUUID())) {
            return Component.translatable("message.eden_ld.chat.muted");
        }
        if (!take(sender.getUUID())) {
            return Component.translatable("message.eden_ld.chat.rate_limited");
        }
        return null;
    }

    /** No {@code §} and no control characters — what vanilla allows in chat. */
    public static boolean allowedText(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!StringUtil.isAllowedChatCharacter(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the player is muted, as far as the cache knows (never blocks; schedules a read when it's due). */
    public static boolean mutedNow(UUID id) {
        Mute mute = MUTES.get(id);
        if (mute == null) {
            return false;
        }
        boolean muted = mute.until() > System.currentTimeMillis();
        long age = Util.getMillis() - mute.read();
        if (age > (muted ? RECHECK_MS : REFRESH_MS)) {
            read(id);
        }
        return muted;
    }

    /** BanHammer just muted the player until {@code until}: taken as is, no database read. */
    private static void muted(UUID id, long until) {
        MUTES.computeIfPresent(id, (key, old) ->
                new Mute(Math.max(old.until(), until), Util.getMillis(), old.events() + 1, false));
    }

    /** Reads the player's mutes off the server thread (one read at a time per player; online players only). */
    private static void read(UUID id) {
        Mute start = MUTES.get(id);
        if (!BanHammerCompat.LOADED || start == null) {
            return;
        }
        if (!READING.add(id)) {
            AGAIN.add(id);
            // The running read may have finished between the two steps: then this one starts now.
            if (!READING.add(id)) {
                return;
            }
            AGAIN.remove(id);
        }
        int events = start.events();
        CompletableFuture.runAsync(() -> {
            long until = BanHammerCompat.mutedUntil(id);
            MUTES.computeIfPresent(id, (key, old) -> {
                if (old.events() != events) {
                    // A punish event came in meanwhile: it's newer than what this read saw.
                    return old;
                }
                long now = System.currentTimeMillis();
                if (until <= now && old.until() > now) {
                    // An empty read against a known mute: an unmute, or a database error. It lifts the mute only when
                    // a read at least RECHECK_MS later agrees (two quick reads could both hit the same outage).
                    if (!old.doubted()) {
                        return new Mute(old.until(), Util.getMillis(), events, true);
                    }
                    if (Util.getMillis() - old.read() < RECHECK_MS) {
                        return old;
                    }
                }
                return new Mute(until, Util.getMillis(), events, false);
            });
        }, Util.ioPool()).whenComplete((ok, error) -> {
            if (error != null) {
                EdenLD.LOGGER.warn("Couldn't check whether {} is muted", id, error);
            }
            READING.remove(id);
            if (AGAIN.remove(id)) {
                read(id);
            }
        });
    }

    /** A token from the player's bucket; false when they've sent too much too fast. */
    private static boolean take(UUID id) {
        int capacity = EdenLD.CONFIG.chat.rate_limit_messages;
        int seconds = EdenLD.CONFIG.chat.rate_limit_seconds;
        if (capacity <= 0 || seconds <= 0) {
            return true;
        }
        long now = Util.getMillis();
        double[] bucket = BUCKETS.computeIfAbsent(id, key -> new double[]{capacity, now});
        double perMs = capacity / (seconds * 1000.0);
        bucket[0] = Math.min(capacity, bucket[0] + (now - bucket[1]) * perMs);
        bucket[1] = now;
        if (bucket[0] < 1) {
            return false;
        }
        bucket[0] -= 1;
        return true;
    }
}
