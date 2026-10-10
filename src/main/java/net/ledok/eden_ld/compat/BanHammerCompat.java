package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.HubPayloads.PunishView;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Optional BanHammer reads (its API, plus a few of 0.11.1's public internals — the IP cache and
 * the history table, which the API doesn't expose). Punishing itself goes through BanHammer's
 * commands. The reads hit its database: call them off the server thread.
 */
public final class BanHammerCompat {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("banhammer");

    private BanHammerCompat() {
    }

    /** A player's active punishments (bans, mutes, warnings, and IP bans of {@code ip}). */
    public static List<PunishView> active(UUID player, @Nullable String ip) {
        return LOADED ? Hook.active(player, ip) : List.of();
    }

    /** The last IP BanHammer saw the player join from; null when unknown. */
    @Nullable
    public static String lastIp(UUID player) {
        return LOADED ? Hook.lastIp(player) : null;
    }

    /** Whether BanHammer keeps a history of every punishment ({@code storeAllPunishmentsInHistory}). */
    public static boolean historyKept() {
        return LOADED && Hook.historyKept();
    }

    /** A player's past punishments, newest first (empty when no history is kept). */
    public static List<PunishView> history(UUID player) {
        return LOADED ? Hook.history(player) : List.of();
    }

    /** Everyone's latest punishments, newest first. */
    public static List<PunishView> recent(int limit) {
        return LOADED ? Hook.recent(limit) : List.of();
    }

    /** Runs {@code listener} with the punished player whenever BanHammer punishes someone (any thread). */
    public static void onPunishment(Consumer<UUID> listener) {
        if (LOADED) {
            Hook.onPunishment(listener);
        }
    }

    /** The only class touching BanHammer types. */
    private static final class Hook {
        private static final eu.pb4.banhammer.api.PunishmentType[] BY_PLAYER = {
                eu.pb4.banhammer.api.PunishmentType.BAN, eu.pb4.banhammer.api.PunishmentType.MUTE,
                eu.pb4.banhammer.api.PunishmentType.WARN};

        static List<PunishView> active(UUID player, @Nullable String ip) {
            List<PunishView> list = new ArrayList<>();
            try {
                for (var type : BY_PLAYER) {
                    for (var p : eu.pb4.banhammer.api.BanHammer.getPunishments(player, type)) {
                        list.add(view(p));
                    }
                }
                if (ip != null) {
                    for (var p : eu.pb4.banhammer.api.BanHammer.getPunishments(ip, eu.pb4.banhammer.api.PunishmentType.IP_BAN)) {
                        list.add(view(p));
                    }
                }
            } catch (Throwable e) {
                EdenLD.LOGGER.warn("Couldn't read BanHammer punishments", e);
            }
            list.sort(Comparator.comparingLong(PunishView::time).reversed());
            return list;
        }

        @Nullable
        static String lastIp(UUID player) {
            try {
                return eu.pb4.banhammer.impl.BanHammerImpl.UUID_TO_IP_CACHE.get(player);
            } catch (Throwable e) {
                return null;
            }
        }

        static boolean historyKept() {
            try {
                return eu.pb4.banhammer.impl.config.ConfigManager.getConfig().configData.storeAllPunishmentsInHistory;
            } catch (Throwable e) {
                return false;
            }
        }

        static List<PunishView> history(UUID player) {
            List<PunishView> list = new ArrayList<>();
            try {
                var database = eu.pb4.banhammer.impl.BanHammerImpl.DATABASE;
                if (database != null && historyKept()) {
                    database.getPunishmentsHistory(player.toString(), p -> list.add(view(p)));
                }
            } catch (Throwable e) {
                EdenLD.LOGGER.warn("Couldn't read BanHammer history", e);
            }
            list.sort(Comparator.comparingLong(PunishView::time).reversed());
            return list;
        }

        static List<PunishView> recent(int limit) {
            List<PunishView> list = new ArrayList<>();
            try {
                var database = eu.pb4.banhammer.impl.BanHammerImpl.DATABASE;
                if (database != null && historyKept()) {
                    database.getAllPunishmentsHistory(p -> list.add(view(p)));
                }
            } catch (Throwable e) {
                EdenLD.LOGGER.warn("Couldn't read BanHammer history", e);
            }
            list.sort(Comparator.comparingLong(PunishView::time).reversed());
            return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
        }

        static void onPunishment(Consumer<UUID> listener) {
            eu.pb4.banhammer.api.BanHammer.registerPunishmentEvent((punishment, silent, invisible) ->
                    listener.accept(punishment.playerUUID));
        }

        private static PunishView view(eu.pb4.banhammer.api.PunishmentData p) {
            String admin = p.adminDisplayName == null ? "" : p.adminDisplayName.getString();
            return new PunishView(p.type.name, p.playerUUID, p.playerName == null ? "" : p.playerName, p.time,
                    p.duration, p.reason == null ? "" : p.reason, admin);
        }
    }
}
