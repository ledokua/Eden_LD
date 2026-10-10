package net.ledok.eden_ld.chat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.compat.PlaceholdersCompat;
import net.ledok.eden_ld.config.EdenConfig;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eden's tab list (it replaces Styled Player List): every name as chat shows it — opt-in guild
 * tag, LuckPerms prefix, the name in its group color, suffix — plus a header and footer from
 * config, and players sorted by rank (the client sorts; see {@link HubPayloads.TabOrder}).
 * <p>
 * Names reach clients through vanilla's own field ({@code ServerPlayer#getTabListDisplayName},
 * mixin), so joining players get them at once; every {@code update_ticks} Eden rechecks names,
 * header/footer and ranks and sends only what changed — a prefix, color or guild edit shows up
 * within a second without any hook of its own.
 */
public final class TabList {

    /** LuckPerms meta that overrides the group weight for sorting ({@code /lp group prime meta set eden_ld.tab_weight 50}). */
    public static final String META_TAB_WEIGHT = "eden_ld.tab_weight";

    /** Names as last sent, by player. */
    private static final Map<UUID, Component> NAMES = new ConcurrentHashMap<>();
    /** Header and footer as last sent, by player. */
    private static final Map<UUID, ClientboundTabListPacket> HEADERS = new HashMap<>();
    /** Ranks as last sent (to everyone). */
    private static Map<UUID, Integer> order = Map.of();
    /** Players whose client has {@link #order}. */
    private static final java.util.Set<UUID> ORDER_SENT = new java.util.HashSet<>();
    private static int ticks;

    private TabList() {
    }

    public static void initialize() {
        if (FabricLoader.getInstance().isModLoaded("styledplayerlist")) {
            EdenLD.LOGGER.warn("[Chat] Styled Player List is installed: it overwrites the tab list's names, header "
                    + "and footer. Remove it (Eden replaces it).");
        }
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (enabled() && ++ticks >= Math.max(1, config().update_ticks)) {
                ticks = 0;
                refresh(server);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            if (enabled()) {
                refresh(server);
            }
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            NAMES.remove(handler.getPlayer().getUUID());
            HEADERS.remove(handler.getPlayer().getUUID());
            ORDER_SENT.remove(handler.getPlayer().getUUID());
        });
    }

    private static EdenConfig.TabList config() {
        return EdenLD.CONFIG.tab_list;
    }

    public static boolean enabled() {
        return config().enabled;
    }

    /** The player's tab list name (the mixin's answer to vanilla); computed once, then kept current by {@link #refresh}. */
    public static Component name(ServerPlayer player) {
        return NAMES.computeIfAbsent(player.getUUID(), uuid -> compute(player));
    }

    private static Component compute(ServerPlayer player) {
        MutableComponent line = Component.empty();
        MinecraftServer server = player.getServer();
        if (server != null && config().guild_tags) {
            Component tag = ChatStyle.guildTag(server, player);
            if (tag != null) {
                line.append(tag);
            }
        }
        // Plain name with its scoreboard team look (no hover card or click: nothing to click here).
        Component plain = PlayerTeam.formatNameForTeam(player.getTeam(), Component.literal(player.getGameProfile().getName()));
        return line.append(ChatStyle.displayName(player, plain));
    }

    private static void refresh(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        List<ServerPlayer> renamed = new ArrayList<>();
        for (ServerPlayer player : players) {
            Component now = compute(player);
            if (!now.equals(NAMES.put(player.getUUID(), now))) {
                renamed.add(player);
            }
        }
        if (!renamed.isEmpty()) {
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), renamed));
        }

        Map<UUID, Integer> ranks = new HashMap<>();
        if (config().sort_by_rank) {
            for (ServerPlayer player : players) {
                ranks.put(player.getUUID(), LuckPermsCompat.metaInt(player, META_TAB_WEIGHT)
                        .orElseGet(() -> LuckPermsCompat.groupWeight(player)));
            }
        }
        if (!ranks.equals(order)) {
            order = Map.copyOf(ranks);
            ORDER_SENT.clear();
        }

        String online = String.valueOf(players.size());
        String max = String.valueOf(server.getMaxPlayers());
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        double tps = Math.min(server.tickRateManager().tickrate(), 1000.0 / Math.max(mspt, 1.0e-3));
        String tpsText = (tps >= 19.5 ? "&a" : tps >= 15 ? "&e" : "&c") + String.format(Locale.ROOT, "%.1f", tps);
        String msptText = String.format(Locale.ROOT, "%.1f", mspt);
        for (ServerPlayer player : players) {
            if (ServerPlayNetworking.canSend(player, HubPayloads.TabOrder.TYPE) && ORDER_SENT.add(player.getUUID())) {
                ServerPlayNetworking.send(player, new HubPayloads.TabOrder(order));
            }
            Map<String, String> values = Map.of("{online}", online, "{max}", max, "{tps}", tpsText,
                    "{mspt}", msptText, "{ping}", String.valueOf(player.connection.latency()),
                    "{player}", player.getGameProfile().getName());
            ClientboundTabListPacket packet = new ClientboundTabListPacket(
                    lines(config().header, values, player), lines(config().footer, values, player));
            if (!packet.equals(HEADERS.put(player.getUUID(), packet))) {
                player.connection.send(packet);
            }
        }
    }

    /** Config lines to one component (each line's colors start fresh, as in Styled Player List). */
    private static Component lines(List<String> lines, Map<String, String> values, ServerPlayer player) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append("\n");
            }
            String line = lines.get(i);
            for (Map.Entry<String, String> value : values.entrySet()) {
                line = line.replace(value.getKey(), value.getValue());
            }
            out.append(LegacyText.parse(line));
        }
        return PlaceholdersCompat.parse(out, player);
    }
}
