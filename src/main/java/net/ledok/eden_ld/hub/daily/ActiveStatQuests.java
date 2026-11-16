package net.ledok.eden_ld.hub.daily;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which stat quests a player has open right now (their daily slots and their guild's board), so the stat hook can
 * skip the many stats nobody needs (1.3.114, from the live spark profile: walking/sprinting/flying stats tick for
 * every moving player, and each went through the full daily and guild reports: ~0.15 ms per tick at 15 players).
 * <p>
 * Rebuilt for a player when quests were drawn, rerolled, claimed or replaced ({@link #invalidate}), on a new day, and
 * at least every {@link #MAX_AGE_TICKS} (a guild joined or left, anything not invalidated). Progress itself doesn't
 * invalidate it: a finished quest stays in the set for up to that long, which only costs one report call that does
 * nothing. Server thread.
 */
public final class ActiveStatQuests {

    private static final int MAX_AGE_TICKS = 40;
    /** A stat quest without a target: any stat counts. */
    public static final String ANY = "*";
    /** Entries kept before the map starts over (players who left aren't removed one by one). */
    private static final int MAX_ENTRIES = 256;

    private record Entry(long day, int generation, int builtAt, List<String> targets) {
    }

    private static final Map<UUID, Entry> CACHE = new HashMap<>();
    private static int generation;

    private ActiveStatQuests() {
    }

    /** Quest lists changed somewhere (drawn, rerolled, claimed, replaced, a guild board): rebuild on next use. */
    public static void invalidate() {
        generation++;
    }

    /** Whether one of the player's open daily or guild stat quests counts {@code statName}. */
    public static boolean wants(ServerPlayer player, String statName) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        long day = DailyQuests.today();
        int tick = server.getTickCount();
        Entry entry = CACHE.get(player.getUUID());
        if (entry == null || entry.day() != day || entry.generation() != generation
                || tick - entry.builtAt() >= MAX_AGE_TICKS || tick < entry.builtAt()) {
            List<String> targets = new java.util.ArrayList<>(DailyQuests.openStatTargets(server, player.getUUID()));
            targets.addAll(net.ledok.eden_ld.hub.guild.quest.GuildQuests.openStatTargets(server, player.getUUID()));
            entry = new Entry(day, generation, tick, List.copyOf(targets));
            if (CACHE.size() >= MAX_ENTRIES) {
                CACHE.clear();
            }
            CACHE.put(player.getUUID(), entry);
        }
        for (String target : entry.targets()) {
            if (ANY.equals(target) || DailyObjectives.statMatches(target, statName)) {
                return true;
            }
        }
        return false;
    }
}
