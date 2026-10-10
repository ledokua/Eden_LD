package net.ledok.eden_ld.hub.moderation;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.command.EdenPermissions;
import net.ledok.eden_ld.compat.BanHammerCompat;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.HomeData;
import net.ledok.eden_ld.hub.HubPayloads.PunishPlayer;
import net.ledok.eden_ld.hub.HubPayloads.PunishView;
import net.ledok.eden_ld.mixin.GameProfileCacheAccessor;
import net.ledok.eden_ld.mixin.GameProfileInfoAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The server side of the Moderation window's Players and Punishments sections, on top of
 * BanHammer. Punishing runs BanHammer's own commands as the admin — its permission nodes,
 * protected players, duration limits, messages, warn actions and Discord webhooks all apply, and
 * its replies come back as notices in the window. Reading (active punishments, history) uses its
 * database off the server thread.
 */
public final class PunishStaff {

    private static final int SEARCH_LIMIT = 40;
    /** Offline players listed before anything is typed, most recently active first. */
    private static final int RECENT_LIMIT = 30;
    private static final int HISTORY_LIMIT = 100;
    private static final int LOG_SIZE = 150;
    /** BanHammer writes to its database on another thread: pages refresh a moment after a change. */
    private static final int REFRESH_TICKS = 30;
    private static final Pattern DURATION = Pattern.compile("[0-9][0-9a-z.]{0,11}");
    private static final TextColor RED = TextColor.fromLegacyFormat(ChatFormatting.RED);

    /** The player each admin's window shows. */
    private static final Map<UUID, UUID> VIEWING = new ConcurrentHashMap<>();
    /** Admins whose page is due for a refresh, and in how many ticks. */
    private static final Map<UUID, Integer> DUE = new ConcurrentHashMap<>();

    private PunishStaff() {
    }

    public static void initialize() {
        // The player search serves the whole player page, BanHammer or not.
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.PunishSearch.TYPE,
                (payload, context) -> context.server().execute(() -> search(context.player(), payload.query())));
        if (!BanHammerCompat.LOADED) {
            return;
        }
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.PunishPageRequest.TYPE,
                (payload, context) -> context.server().execute(() -> page(context.player(), payload.target())));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.PunishAction.TYPE,
                (payload, context) -> context.server().execute(() -> act(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.PunishLogRequest.TYPE,
                (payload, context) -> context.server().execute(() -> log(context.player())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            VIEWING.remove(handler.getPlayer().getUUID());
            DUE.remove(handler.getPlayer().getUUID());
        });
        ServerLifecycleEvents.SERVER_STARTED.register(started ->
                BanHammerCompat.onPunishment(target -> started.execute(() -> refreshViewers(target))));
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            VIEWING.clear();
            DUE.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(PunishStaff::tick);
    }

    // ---------------------------------------------------------------- rights

    /** Which of {@link HubPayloads#PUNISH_COMMANDS} the player may run — exactly what the command tree allows them. */
    public static int allowed(ServerPlayer player) {
        if (!BanHammerCompat.LOADED) {
            return 0;
        }
        CommandSourceStack source = player.createCommandSourceStack();
        var root = player.server.getCommands().getDispatcher().getRoot();
        int bits = 0;
        for (int i = 0; i < HubPayloads.PUNISH_COMMANDS.size(); i++) {
            CommandNode<CommandSourceStack> node = root.getChild(HubPayloads.PUNISH_COMMANDS.get(i));
            if (node != null && node.canUse(source)) {
                bits |= 1 << i;
            }
        }
        return bits;
    }

    /**
     * The Moderation window rights BanHammer adds ({@code HomeData.STAFF_*} bits): punishing with any
     * of its commands, and the punishment log ({@code eden_ld.admin.punish.log}).
     */
    public static int staff(ServerPlayer player) {
        if (!BanHammerCompat.LOADED) {
            return 0;
        }
        int staff = 0;
        if (allowed(player) != 0) {
            staff |= HomeData.STAFF_MODERATION | HomeData.STAFF_PUNISH;
        }
        if (LuckPermsCompat.hasPermission(player, EdenPermissions.PUNISH_LOG)) {
            staff |= HomeData.STAFF_MODERATION | HomeData.STAFF_PUNISH_LOG;
        }
        return staff;
    }

    // ---------------------------------------------------------------- search

    private static void search(ServerPlayer admin, String query) {
        if ((EdenPermissions.staff(admin) & HomeData.STAFF_PLAYER_TABS) == 0) {
            return;
        }
        MinecraftServer server = admin.server;
        String q = query.trim().toLowerCase(Locale.ROOT);
        Map<UUID, PunishPlayer> found = new LinkedHashMap<>();
        List<ServerPlayer> online = new ArrayList<>(server.getPlayerList().getPlayers());
        online.sort(Comparator.comparing(p -> p.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER));
        for (ServerPlayer player : online) {
            String name = player.getGameProfile().getName();
            if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)) {
                found.put(player.getUUID(), new PunishPlayer(player.getUUID(), name, true, 0));
            }
        }
        if (q.isEmpty()) {
            recent(server, found);
        }
        if (!q.isEmpty() && server.getProfileCache() != null) {
            List<GameProfile> known = new ArrayList<>();
            for (Object entry : ((GameProfileCacheAccessor) server.getProfileCache()).eden_ld$profilesByName().values()) {
                GameProfile profile = ((GameProfileInfoAccessor) entry).eden_ld$profile();
                if (profile != null && profile.getId() != null && profile.getName() != null
                        && profile.getName().toLowerCase(Locale.ROOT).contains(q)) {
                    known.add(profile);
                }
            }
            // Exact and prefix matches first.
            known.sort(Comparator.comparingInt((GameProfile p) -> rank(p.getName(), q))
                    .thenComparing(GameProfile::getName, String.CASE_INSENSITIVE_ORDER));
            for (GameProfile profile : known) {
                if (found.size() >= SEARCH_LIMIT) {
                    break;
                }
                found.putIfAbsent(profile.getId(), new PunishPlayer(profile.getId(), profile.getName(), false,
                        lastSeen(server, profile.getId())));
            }
        }
        List<PunishPlayer> list = new ArrayList<>(found.values());
        if (list.size() > SEARCH_LIMIT) {
            list = list.subList(0, SEARCH_LIMIT);
        }
        ServerPlayNetworking.send(admin, new HubPayloads.PunishPlayers(query, List.copyOf(list)));
    }

    /**
     * The most recently active offline players: every player's data file is saved when they leave,
     * so its date is when they were last here (players from before Eden count too).
     */
    private static void recent(MinecraftServer server, Map<UUID, PunishPlayer> found) {
        java.io.File[] files = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
                .toFile().listFiles((dir, name) -> name.endsWith(".dat"));
        if (files == null || server.getProfileCache() == null) {
            return;
        }
        List<java.io.File> sorted = new ArrayList<>(List.of(files));
        sorted.sort(Comparator.comparingLong(java.io.File::lastModified).reversed());
        int added = 0;
        for (java.io.File file : sorted) {
            if (added >= RECENT_LIMIT) {
                break;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (found.containsKey(uuid)) {
                continue;
            }
            java.util.Optional<GameProfile> profile = server.getProfileCache().get(uuid);
            if (profile.isPresent()) {
                found.put(uuid, new PunishPlayer(uuid, profile.get().getName(), false, file.lastModified()));
                added++;
            }
        }
    }

    /** When an offline player was last here (their data file's date); 0 when unknown. */
    private static long lastSeen(MinecraftServer server, UUID player) {
        java.io.File file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
                .resolve(player + ".dat").toFile();
        return file.isFile() ? file.lastModified() : 0;
    }

    private static int rank(String name, String query) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(query) ? 0 : lower.startsWith(query) ? 1 : 2;
    }

    // ---------------------------------------------------------------- a player's page

    private static void page(ServerPlayer admin, UUID target) {
        int allowed = allowed(admin);
        if (allowed == 0) {
            return;
        }
        VIEWING.put(admin.getUUID(), target);
        MinecraftServer server = admin.server;
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        String name = online != null ? online.getGameProfile().getName()
                : server.getProfileCache() == null ? null
                : server.getProfileCache().get(target).map(GameProfile::getName).orElse(null);
        String ip = online != null ? online.getIpAddress() : BanHammerCompat.lastIp(target);
        UUID adminId = admin.getUUID();
        CompletableFuture.supplyAsync(() -> {
            List<PunishView> active = BanHammerCompat.active(target, ip);
            boolean kept = BanHammerCompat.historyKept();
            List<PunishView> history = BanHammerCompat.history(target);
            return new Object[]{active, kept, history.size() > HISTORY_LIMIT ? history.subList(0, HISTORY_LIMIT) : history};
        }).thenAccept(read -> server.execute(() -> {
            ServerPlayer viewer = server.getPlayerList().getPlayer(adminId);
            if (viewer == null || !target.equals(VIEWING.get(adminId))) {
                return;
            }
            @SuppressWarnings("unchecked") List<PunishView> active = (List<PunishView>) read[0];
            @SuppressWarnings("unchecked") List<PunishView> history = (List<PunishView>) read[2];
            String shown = name;
            if (shown == null) {
                shown = history.isEmpty() ? active.isEmpty() ? target.toString() : active.get(0).playerName()
                        : history.get(0).playerName();
            }
            ServerPlayNetworking.send(viewer, new HubPayloads.PunishPage(target, shown,
                    server.getPlayerList().getPlayer(target) != null, allowed(viewer), (Boolean) read[1],
                    List.copyOf(active), List.copyOf(history)));
        }));
    }

    private static void refreshViewers(UUID target) {
        VIEWING.forEach((viewer, shown) -> {
            if (shown.equals(target)) {
                DUE.put(viewer, REFRESH_TICKS);
            }
        });
    }

    private static void tick(MinecraftServer server) {
        if (DUE.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Integer>> it = DUE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int left = entry.getValue() - 1;
            if (left > 0) {
                entry.setValue(left);
                continue;
            }
            it.remove();
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            UUID target = VIEWING.get(entry.getKey());
            if (viewer != null && target != null) {
                page(viewer, target);
            }
        }
    }

    // ---------------------------------------------------------------- punishing

    private static void act(ServerPlayer admin, HubPayloads.PunishAction action) {
        int index = HubPayloads.PUNISH_COMMANDS.indexOf(action.command());
        if (index < 0 || (allowed(admin) & (1 << index)) == 0) {
            notice(admin, Component.translatable("message.eden_ld.moderation.not_allowed"), false);
            return;
        }
        String command = action.command();
        StringBuilder line = new StringBuilder(command).append(' ').append(action.target());
        if (command.startsWith("temp")) {
            String duration = action.duration().trim().toLowerCase(Locale.ROOT);
            if (!DURATION.matcher(duration).matches()) {
                notice(admin, Component.translatable("message.eden_ld.moderation.bad_duration"), false);
                return;
            }
            line.append(' ').append(duration);
        }
        String reason = reason(action.reason());
        if (action.silent()) {
            line.append(" -s");
        }
        if (!reason.isEmpty()) {
            line.append(' ').append(reason);
        }
        EdenLD.LOGGER.info("{} ran /{} from the Moderation window", admin.getGameProfile().getName(), line);
        MinecraftServer server = admin.server;
        UUID adminId = admin.getUUID();
        CommandSource replies = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                // BanHammer answers from its own thread.
                server.execute(() -> {
                    ServerPlayer viewer = server.getPlayerList().getPlayer(adminId);
                    if (viewer != null) {
                        notice(viewer, message, !RED.equals(message.getStyle().getColor()));
                    }
                });
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        server.getCommands().performPrefixedCommand(admin.createCommandSourceStack().withSource(replies), line.toString());
        refreshViewers(action.target());
        DUE.put(adminId, REFRESH_TICKS);
    }

    /** One line, no leading dash (BanHammer would read it as flags), at most 200 characters. */
    private static String reason(String raw) {
        String reason = raw.replaceAll("[\\p{Cntrl}]", " ").trim();
        while (reason.startsWith("-")) {
            reason = reason.substring(1).trim();
        }
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    private static void notice(ServerPlayer player, Component message, boolean ok) {
        ServerPlayNetworking.send(player, new HubPayloads.ModNotice(oneLine(message), ok));
    }

    /** BanHammer's messages have line breaks (for chat); a notice is one line, styles kept. */
    private static Component oneLine(Component message) {
        net.minecraft.network.chat.MutableComponent line = Component.empty();
        message.visit((style, text) -> {
            line.append(Component.literal(text.replace("\n", " · ")).withStyle(style));
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return line;
    }

    // ---------------------------------------------------------------- log

    private static void log(ServerPlayer admin) {
        if ((staff(admin) & HomeData.STAFF_PUNISH_LOG) == 0) {
            return;
        }
        MinecraftServer server = admin.server;
        UUID adminId = admin.getUUID();
        CompletableFuture.supplyAsync(() -> new Object[]{BanHammerCompat.historyKept(), BanHammerCompat.recent(LOG_SIZE)})
                .thenAccept(read -> server.execute(() -> {
                    ServerPlayer viewer = server.getPlayerList().getPlayer(adminId);
                    if (viewer != null) {
                        @SuppressWarnings("unchecked") List<PunishView> entries = (List<PunishView>) read[1];
                        ServerPlayNetworking.send(viewer, new HubPayloads.PunishLog((Boolean) read[0], List.copyOf(entries)));
                    }
                }));
    }
}
