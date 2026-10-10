package net.ledok.eden_ld.chat.emoji;

import net.ledok.eden_ld.hub.HubData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extra favorite slots on top of a player's base limit (config / LuckPerms meta): grants of
 * +N (or −N) for emoji favorites or saved stickers, each permanent or ending after a time —
 * e.g. a shop item "+20 favorites for 30 days"; two bought = two grants = +40. Grants add up,
 * expire one by one, and live in the world's hub data (a wipe clears them). Given with
 * {@code /emojis bonus} (console-friendly, works on offline players).
 */
public final class FavoriteBonus {

    private static final String TAG = "favorite_bonus";
    private static final Pattern DURATION = Pattern.compile("(\\d+)([smhdw])");

    /** What a grant enlarges. */
    public enum Kind {
        EMOJIS, STICKERS;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        @Nullable
        public static Kind of(String id) {
            for (Kind kind : values()) {
                if (kind.id().equals(id)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** One grant; {@code expires} is epoch ms, 0 = permanent. */
    public record Grant(Kind kind, int amount, long expires) {
        public boolean active(long now) {
            return expires == 0 || expires > now;
        }
    }

    private FavoriteBonus() {
    }

    /** Every grant the player has that hasn't ended. */
    public static List<Grant> grants(MinecraftServer server, UUID player) {
        long now = System.currentTimeMillis();
        ListTag list = HubData.get(server).peek(player).getList(TAG, Tag.TAG_COMPOUND);
        List<Grant> grants = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Kind kind = Kind.of(entry.getString("kind"));
            Grant grant = kind == null ? null : new Grant(kind, entry.getInt("amount"), entry.getLong("expires"));
            if (grant != null && grant.active(now)) {
                grants.add(grant);
            }
        }
        return grants;
    }

    /** The extra slots active now (can be negative). */
    public static int active(MinecraftServer server, UUID player, Kind kind) {
        int total = 0;
        for (Grant grant : grants(server, player)) {
            if (grant.kind() == kind) {
                total += grant.amount();
            }
        }
        return total;
    }

    /** Adds a grant ({@code durationMs} 0 = permanent); ended grants are dropped on the way. */
    public static void grant(MinecraftServer server, UUID player, Kind kind, int amount, long durationMs) {
        List<Grant> grants = new ArrayList<>(grants(server, player));
        grants.add(new Grant(kind, amount, durationMs <= 0 ? 0 : System.currentTimeMillis() + durationMs));
        store(server, player, grants);
    }

    /** Removes every grant of the kind (null = both); how many went. */
    public static int clear(MinecraftServer server, UUID player, @Nullable Kind kind) {
        List<Grant> grants = new ArrayList<>(grants(server, player));
        int before = grants.size();
        grants.removeIf(grant -> kind == null || grant.kind() == kind);
        store(server, player, grants);
        return before - grants.size();
    }

    private static void store(MinecraftServer server, UUID player, List<Grant> grants) {
        ListTag list = new ListTag();
        for (Grant grant : grants) {
            CompoundTag entry = new CompoundTag();
            entry.putString("kind", grant.kind().id());
            entry.putInt("amount", grant.amount());
            entry.putLong("expires", grant.expires());
            list.add(entry);
        }
        HubData.get(server).player(player).put(TAG, list);
    }

    /**
     * A duration as commands take it: {@code 30d}, {@code 12h}, {@code 1d12h}, {@code 2w},
     * {@code 90m}, {@code 45s}; {@code permanent} (or {@code perm}, {@code forever}) = 0.
     * −1 when unreadable.
     */
    public static long parseDuration(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (value.equals("permanent") || value.equals("perm") || value.equals("forever")) {
            return 0;
        }
        Matcher matcher = DURATION.matcher(value);
        long total = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                return -1;
            }
            long amount = Long.parseLong(matcher.group(1));
            total += amount * switch (matcher.group(2)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> 604_800_000L;
            };
            end = matcher.end();
        }
        return end == value.length() && total > 0 ? total : -1;
    }

    /** Time left as "29d 23h", "5h 10m", "12m" ("" for permanent). */
    public static String remaining(Grant grant) {
        if (grant.expires() == 0) {
            return "";
        }
        long minutes = Math.max(1, (grant.expires() - System.currentTimeMillis()) / 60_000L);
        long days = minutes / 1440;
        long hours = minutes % 1440 / 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        return hours > 0 ? hours + "h " + minutes % 60 + "m" : minutes + "m";
    }
}
