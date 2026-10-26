package net.ledok.eden_ld.command;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Permission nodes of Eden's admin commands. With LuckPerms a node set on a player or group
 * decides (true grants it to non-ops, false takes it from an op); unset — or without LuckPerms —
 * op level 2 does, as before. {@code eden_ld.admin.*} covers them all.
 * <p>
 * A client only shows (and suggests) the commands it was sent, so a LuckPerms edit that changes
 * any of these resends that player's command list at once — no relog.
 */
public final class EdenPermissions {

    /** {@code /hub theme unlock|lock}. */
    public static final String THEMES = "eden_ld.admin.themes";
    /** {@code /hub background unlock|lock}, and background rules in the Moderation window. */
    public static final String BACKGROUNDS = "eden_ld.admin.backgrounds";
    /** {@code /emojis reload|bonus}. */
    public static final String EMOJIS = "eden_ld.admin.emojis";
    /** {@code /guild-admin}. */
    public static final String GUILDS = "eden_ld.admin.guilds";
    /** {@code /party-admin}. */
    public static final String PARTIES = "eden_ld.admin.parties";
    /** {@code /dungeons unlock|lock|complete}. */
    public static final String DUNGEONS = "eden_ld.admin.dungeons";
    /** {@code /dailies give-rerolls|reset}. */
    public static final String DAILIES = "eden_ld.admin.dailies";
    /** {@code /eden commands dump}. */
    public static final String COMMANDS = "eden_ld.admin.commands";
    /** Writing and sending mail in the Moderation window (and recalling one's own). */
    public static final String MAIL = "eden_ld.admin.mail";
    /** Seeing (and recalling) every staff member's mail in the Moderation window's log. */
    public static final String MAIL_LOG = "eden_ld.admin.mail.log";
    /**
     * The Moderation window's punishment log (everyone's BanHammer punishments). Punishing itself
     * uses BanHammer's own nodes ({@code banhammer.punish.ban}, …).
     */
    public static final String PUNISH_LOG = "eden_ld.admin.punish.log";
    /** Writing and publishing the Home tab's news in the Moderation window. */
    public static final String NEWS = "eden_ld.admin.news";
    /** A dimension's own settings: {@code /eden gamerule|pvp|difficulty}, and Moderation → World settings (docs/PLAN-world-rules.md). */
    public static final String WORLD = "eden_ld.admin.world";
    /** {@code /eden tutorial start|finish|status} (docs/PLAN-tutorial.md). */
    public static final String TUTORIAL = "eden_ld.admin.tutorial";
    /** Players' reports (other mods file them): Moderation → Reports, {@code /hub reports}, and the chat lines about them. */
    public static final String REPORTS = "eden_ld.admin.reports";

    private static final List<String> ALL = List.of(THEMES, BACKGROUNDS, EMOJIS, GUILDS, PARTIES, DUNGEONS, DAILIES, COMMANDS, MAIL,
            MAIL_LOG, PUNISH_LOG, NEWS, WORLD, TUTORIAL, REPORTS);

    /** Which nodes each online player had when their command list was last sent (bit per {@link #ALL} entry). */
    private static final Map<UUID, Integer> SENT = new ConcurrentHashMap<>();

    private EdenPermissions() {
    }

    /** A command's {@code requires}: the node, else op level 2. */
    public static Predicate<CommandSourceStack> require(String node) {
        return source -> LuckPermsCompat.hasPermission(source, node);
    }

    public static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                SENT.put(handler.getPlayer().getUUID(), granted(handler.getPlayer())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT.remove(handler.getPlayer().getUUID()));
        // The event arrives off-thread, for any user's recalculation; only real changes resend.
        ServerLifecycleEvents.SERVER_STARTED.register(started ->
                LuckPermsCompat.onUserDataChange(() -> started.execute(() -> resendChanged(started))));
    }

    private static void resendChanged(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int now = granted(player);
            Integer before = SENT.put(player.getUUID(), now);
            if (before != null && before != now) {
                server.getCommands().sendCommands(player);
            }
        }
    }

    /**
     * The Moderation window rights for Home data ({@code HomeData.STAFF_*} bits): the window at all
     * (any admin node, any BanHammer command, or op), writing mail, everyone's mail log, punishing,
     * the punishment log, the news.
     */
    public static int staff(ServerPlayer player) {
        int granted = granted(player);
        int punish = net.ledok.eden_ld.hub.moderation.PunishStaff.staff(player);
        if (granted == 0) {
            return punish;
        }
        int staff = net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_MODERATION | punish;
        if ((granted & (1 << ALL.indexOf(MAIL))) != 0) {
            staff |= net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_MAIL;
        }
        if ((granted & (1 << ALL.indexOf(MAIL_LOG))) != 0) {
            staff |= net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_MAIL_LOG;
        }
        if ((granted & (1 << ALL.indexOf(NEWS))) != 0) {
            staff |= net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_NEWS;
        }
        String[] nodes = {BACKGROUNDS, THEMES, DAILIES, DUNGEONS, EMOJIS, GUILDS, PARTIES, COMMANDS, WORLD, REPORTS};
        int[] bits = {net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_BACKGROUNDS,
                net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_THEMES,
                net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_DAILIES,
                net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_DUNGEONS,
                net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_EMOJIS,
                net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_GUILDS,
                net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_PARTIES,
                net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_COMMANDS,
                net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_WORLD,
                net.ledok.eden_ld.hub.HubPayloads.HomeData.STAFF_REPORTS};
        for (int i = 0; i < nodes.length; i++) {
            if ((granted & (1 << ALL.indexOf(nodes[i]))) != 0) {
                staff |= bits[i];
            }
        }
        return staff;
    }

    private static int granted(ServerPlayer player) {
        int bits = 0;
        for (int i = 0; i < ALL.size(); i++) {
            if (LuckPermsCompat.hasPermission(player, ALL.get(i))) {
                bits |= 1 << i;
            }
        }
        return bits;
    }
}
