package net.ledok.eden_ld.hub.moderation;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.command.EdenPermissions;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.compat.opac.OpacCompat;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.hub.daily.DailyObjectives;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildBank;
import net.ledok.eden_ld.hub.guild.GuildBankServer;
import net.ledok.eden_ld.hub.guild.GuildBankService;
import net.ledok.eden_ld.hub.guild.GuildLevels;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.hub.guild.GuildMemberClaims;
import net.ledok.eden_ld.hub.guild.GuildRank;
import net.ledok.eden_ld.hub.guild.GuildRules;
import net.ledok.eden_ld.hub.guild.GuildServer;
import net.ledok.eden_ld.hub.guild.quest.GuildQuestDefinition;
import net.ledok.eden_ld.hub.guild.quest.GuildQuests;
import net.ledok.eden_ld.hub.lang.ServerLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moderation → Guilds, one guild's detail ({@link AdminTools} area {@code guild}, keyed by the guild's id) and the staff
 * actions on it (under {@code eden_ld.admin.guilds}). The server keeps what each admin looks at — the sub-tab
 * ({@code part}), the vault tab, the bank log's filter — so every answer carries only that part: vault contents and
 * chat travel only while their sub-tab is open (chat needs {@code eden_ld.admin.guild_chat} as well). Every action needs
 * a reason, goes through {@link GuildManager} / {@link GuildBankService} as the members' own would, lands in the guild's
 * staff log and the server log, and is answered with a notice and fresh data. Server thread.
 */
public final class GuildDesk {

    /** The detail's sub-tabs, in the window's order. */
    static final int OVERVIEW = 0, MEMBERS = 1, BANK = 2, CHAT = 3, QUESTS = 4;
    /** Bank log filter kinds ("" = all). */
    static final List<String> KINDS = List.of("", "deposit", "withdraw", "purchase", "rights", "staff");
    /** Bank log rows sent at most (newest first); the rest are counted. */
    private static final int LOG_ROWS = 150;
    /** Vault stacks past this many bytes of NBT go as the bare item (no components): a tab of full shulkers stays sendable. */
    private static final int VAULT_BUDGET = 512 * 1024;
    /** Chat lines older than this many bytes of JSON stay behind. */
    private static final int CHAT_BUDGET = 256 * 1024;

    /** An admin's view of one guild: the sub-tab, the vault tab (-1: the first), and the log filter. */
    private record View(UUID guild, int part, int tab, String kind, String player) {
    }

    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

    private GuildDesk() {
    }

    static void initialize() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> VIEWS.remove(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> VIEWS.clear());
    }

    private static boolean mayReadChat(ServerPlayer admin) {
        return LuckPermsCompat.hasPermission(admin, EdenPermissions.GUILD_CHAT);
    }

    private static View view(ServerPlayer admin, UUID guild) {
        View view = VIEWS.get(admin.getUUID());
        if (view == null) {
            return new View(guild, OVERVIEW, -1, "", "");
        }
        // Another guild: the same sub-tab, from its first vault tab and unfiltered.
        return view.guild().equals(guild) ? view : new View(guild, view.part(), -1, "", "");
    }

    // ---------------------------------------------------------------- data

    /**
     * The guild's header ({@code id, tag, name, color}, {@code frozen} with {@code by, reason, since}), the {@code part}
     * shown, {@code chat_allowed}, and that part's data; {@code gone} when the guild no longer exists.
     */
    static CompoundTag data(ServerPlayer admin, MinecraftServer server, UUID id) {
        CompoundTag tag = new CompoundTag();
        Guild guild = GuildManager.get(server).byId(id);
        if (guild == null) {
            tag.putBoolean("gone", true);
            return tag;
        }
        View view = view(admin, id);
        boolean chatAllowed = mayReadChat(admin);
        tag.putUUID("id", guild.id);
        tag.putString("tag", guild.tag);
        tag.putString("name", guild.name);
        tag.putInt("color", guild.color);
        tag.putInt("part", view.part());
        tag.putBoolean("chat_allowed", chatAllowed);
        if (guild.freeze != null) {
            CompoundTag frozen = new CompoundTag();
            frozen.putString("by", guild.freeze.byName());
            frozen.putString("reason", guild.freeze.reason());
            frozen.putLong("since", guild.freeze.since());
            tag.put("frozen", frozen);
        }
        switch (view.part()) {
            case MEMBERS -> members(server, guild, tag);
            case BANK -> bank(server, guild, view, tag);
            case CHAT -> {
                if (chatAllowed) {
                    chat(server, guild, tag);
                } else {
                    tag.putBoolean("chat_denied", true);
                }
            }
            case QUESTS -> quests(admin, server, guild, tag);
            default -> overview(server, guild, tag);
        }
        return tag;
    }

    private static void overview(MinecraftServer server, Guild guild, CompoundTag tag) {
        GuildManager manager = GuildManager.get(server);
        tag.putString("motd", guild.motd);
        tag.putInt("join_mode", guild.joinMode.ordinal());
        tag.putLong("created", guild.created);
        GuildLevels.Progress progress = GuildLevels.progress(guild.xp);
        tag.putInt("level", progress.level());
        tag.putInt("max_level", GuildLevels.maxLevel());
        tag.putLong("xp", guild.xp);
        tag.putLong("xp_into", progress.into());
        tag.putLong("xp_needed", progress.needed());
        tag.putInt("members", guild.members.size());
        tag.putInt("member_limit", manager.memberLimit(guild));
        tag.putInt("member_level", guild.bank.memberLevel);
        tag.putInt("claim_level", guild.bank.claimLevel);
        tag.putInt("claim_step", Math.max(0, EdenLD.CONFIG.hub.guild_claim_upgrade_step));
        tag.putInt("prime_claims", GuildMemberClaims.get(server).total(guild));
        OpacCompat.ClaimsInfo claims = OpacCompat.claims(server, guild);
        tag.putInt("claims_used", claims == null ? 0 : claims.used());
        tag.putInt("claims_limit", claims == null ? -1 : claims.limit());
        tag.putLong("balance", guild.bank.balance);
        tag.putInt("tabs", guild.bank.tabs.size());
        tag.putInt("tab_cap", GuildBankService.tabCap(guild));
        manager.pruneApplicationsOf(guild);
        ListTag applications = new ListTag();
        guild.applications.forEach((uuid, time) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("name", guild.applicantNames.getOrDefault(uuid, "?"));
            entry.putLong("time", time);
            applications.add(entry);
        });
        tag.put("applications", applications);
        ListTag log = new ListTag();
        for (int i = guild.staffLog.size() - 1; i >= 0; i--) {
            Guild.StaffEntry entry = guild.staffLog.get(i);
            CompoundTag row = new CompoundTag();
            row.putLong("time", entry.time());
            row.putString("staff", entry.staffName());
            row.putString("action", entry.action());
            row.putString("reason", entry.reason());
            row.putString("details", entry.details());
            log.add(row);
        }
        tag.put("staff_log", log);
    }

    /**
     * Each member: rank, online or when last here (their player file's date, as the Players section shows), joined
     * (0 for members from before it was kept), the claims they bring (Prime), today's bank withdrawals, and on how
     * many of the current board's quests they helped.
     */
    private static void members(MinecraftServer server, Guild guild, CompoundTag tag) {
        GuildMemberClaims claims = GuildMemberClaims.get(server);
        List<GuildQuests.BoardQuest> board = GuildQuests.board(server, guild);
        List<CompoundTag> rows = new ArrayList<>();
        guild.members.forEach((uuid, rank) -> {
            CompoundTag member = new CompoundTag();
            member.putUUID("uuid", uuid);
            member.putString("name", guild.memberNames.getOrDefault(uuid, uuid.toString()));
            member.putInt("rank", rank.level);
            boolean online = server.getPlayerList().getPlayer(uuid) != null;
            member.putBoolean("online", online);
            member.putLong("last_seen", online ? 0 : PunishStaff.lastSeen(server, uuid));
            member.putLong("joined", guild.joinedAt.getOrDefault(uuid, 0L));
            member.putInt("prime", claims.of(uuid));
            long[] taken = GuildBankService.takenToday(guild, uuid);
            member.putLong("items_today", taken[0]);
            member.putLong("lc_today", taken[1]);
            member.putInt("quests", (int) board.stream().filter(q -> q.by().getOrDefault(uuid, 0) > 0).count());
            rows.add(member);
        });
        // Leader first, then by rank, online before offline, then name (as the Guild tab's roster).
        rows.sort(Comparator.comparingInt((CompoundTag m) -> -m.getInt("rank"))
                .thenComparing(m -> !m.getBoolean("online"))
                .thenComparing(m -> m.getString("name").toLowerCase(Locale.ROOT)));
        ListTag list = new ListTag();
        list.addAll(rows);
        tag.put("members", list);
        tag.putInt("quests_total", board.size());
    }

    private static void bank(MinecraftServer server, Guild guild, View view, CompoundTag tag) {
        GuildBank bank = guild.bank;
        tag.putLong("balance", bank.balance);
        tag.putBoolean("economy", EconomyCompat.available());
        int viewed = view.tab() >= 0 && view.tab() < bank.tabs.size() ? view.tab() : bank.tabs.isEmpty() ? -1 : 0;
        tag.putInt("tab", viewed);
        ListTag tabs = new ListTag();
        for (int t = 0; t < bank.tabs.size(); t++) {
            tabs.add(StringTag.valueOf(t < bank.tabNames.size() ? bank.tabNames.get(t) : ""));
        }
        tag.put("tabs", tabs);
        // Leader and vice-leader always have everything; the rest per tab and per day.
        ListTag rights = new ListTag();
        for (GuildRank rank : List.of(GuildRank.OFFICER, GuildRank.MEMBER, GuildRank.RECRUIT)) {
            CompoundTag row = new CompoundTag();
            row.putInt("rank", rank.level);
            row.putLong("lc", bank.lcPerDay(rank));
            int[] access = new int[bank.tabs.size()];
            int[] items = new int[bank.tabs.size()];
            for (int t = 0; t < bank.tabs.size(); t++) {
                GuildBank.TabRights tabRights = bank.tabRights(rank, t);
                access[t] = tabRights.access().ordinal();
                items[t] = tabRights.itemsPerDay();
            }
            row.putIntArray("access", access);
            row.putIntArray("items", items);
            rights.add(row);
        }
        tag.put("rights", rights);
        if (viewed >= 0) {
            ListTag contents = new ListTag();
            int used = 0;
            boolean trimmed = false;
            NonNullList<ItemStack> slots = bank.tabs.get(viewed);
            for (int slot = 0; slot < slots.size(); slot++) {
                ItemStack stack = slots.get(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                CompoundTag item = (CompoundTag) stack.save(server.registryAccess());
                if (used + item.sizeInBytes() > VAULT_BUDGET) {
                    item = (CompoundTag) new ItemStack(stack.getItem(), stack.getCount()).save(server.registryAccess());
                    trimmed = true;
                }
                used += item.sizeInBytes();
                CompoundTag entry = new CompoundTag();
                entry.putInt("slot", slot);
                entry.put("item", item);
                contents.add(entry);
            }
            tag.put("contents", contents);
            tag.putBoolean("trimmed", trimmed);
        }

        // The log, newest first, filtered; items as id, count and name (enough to read it, light to send).
        ListTag log = new ListTag();
        TreeSet<String> players = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        int total = 0;
        for (int i = bank.log.size() - 1; i >= 0; i--) {
            GuildBank.LogEntry entry = bank.log.get(i);
            if (!entry.name().isEmpty()) {
                players.add(entry.name());
            }
            if (!matches(entry, view.kind(), view.player())) {
                continue;
            }
            total++;
            if (log.size() >= LOG_ROWS) {
                continue;
            }
            CompoundTag row = new CompoundTag();
            row.putLong("time", entry.time());
            row.putString("name", entry.name());
            row.putInt("type", entry.type().ordinal());
            row.putInt("tab", entry.tab());
            if (!entry.stack().isEmpty()) {
                row.putString("item", BuiltInRegistries.ITEM.getKey(entry.stack().getItem()).toString());
                row.putInt("count", entry.stack().getCount());
                row.putString("item_name", json(server, entry.stack().getHoverName()));
            }
            row.putLong("amount", entry.amount());
            row.putString("detail", entry.detail());
            log.add(row);
        }
        tag.put("log", log);
        tag.putInt("log_total", total);
        ListTag names = new ListTag();
        players.forEach(name -> names.add(StringTag.valueOf(name)));
        tag.put("log_players", names);
        tag.putString("kind", view.kind());
        tag.putString("player", view.player());
    }

    private static boolean matches(GuildBank.LogEntry entry, String kind, String player) {
        if (!player.isEmpty() && !entry.name().equalsIgnoreCase(player)) {
            return false;
        }
        return switch (kind) {
            case "deposit" -> entry.type() == GuildBank.LogType.DEPOSIT_ITEM || entry.type() == GuildBank.LogType.DEPOSIT_LC
                    || entry.type() == GuildBank.LogType.QUEST_REWARD;
            case "withdraw" -> entry.type() == GuildBank.LogType.WITHDRAW_ITEM || entry.type() == GuildBank.LogType.WITHDRAW_LC;
            case "purchase" -> entry.type() == GuildBank.LogType.BUY_TAB || entry.type() == GuildBank.LogType.BUY_UPGRADE;
            case "rights" -> entry.type() == GuildBank.LogType.RIGHTS;
            case "staff" -> entry.type().staff();
            default -> true;
        };
    }

    /** The stored chat, oldest first (as much of the newest as fits the budget). */
    private static void chat(MinecraftServer server, Guild guild, CompoundTag tag) {
        List<CompoundTag> lines = new ArrayList<>();
        int used = 0;
        for (int i = guild.chat.size() - 1; i >= 0; i--) {
            Guild.ChatLine line = guild.chat.get(i);
            String text = json(server, line.text());
            used += text.length() * 2;
            if (used > CHAT_BUDGET) {
                break;
            }
            CompoundTag entry = new CompoundTag();
            entry.putUUID("sender", line.sender());
            entry.putString("name", line.name());
            entry.putString("text", text);
            entry.putLong("time", line.time());
            lines.add(entry);
        }
        ListTag list = new ListTag();
        for (int i = lines.size() - 1; i >= 0; i--) {
            list.add(lines.get(i));
        }
        tag.put("chat", list);
    }

    /** The daily and weekly boards in the admin's language: each quest, its progress, and the three biggest helpers. */
    private static void quests(ServerPlayer admin, MinecraftServer server, Guild guild, CompoundTag tag) {
        tag.putLong("daily_reset", GuildQuests.millisUntilReset(GuildQuestDefinition.Period.DAILY));
        tag.putLong("weekly_reset", GuildQuests.millisUntilReset(GuildQuestDefinition.Period.WEEKLY));
        ListTag list = new ListTag();
        for (GuildQuests.BoardQuest entry : GuildQuests.board(server, guild)) {
            GuildQuestDefinition quest = entry.quest();
            CompoundTag row = new CompoundTag();
            row.putInt("period", entry.period().ordinal());
            row.putString("title", json(server, ServerLang.localize(admin, quest.cardTitle())));
            row.putString("icon", quest.icon().toString());
            row.putInt("progress", DailyObjectives.shown(quest.objective(), quest.target(), entry.progress(), false));
            row.putInt("goal", DailyObjectives.shown(quest.objective(), quest.target(), entry.goal(), true));
            row.putBoolean("done", entry.done());
            ListTag top = new ListTag();
            entry.by().entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                    .limit(3)
                    .forEach(e -> {
                        CompoundTag helper = new CompoundTag();
                        helper.putString("name", guild.memberNames.getOrDefault(e.getKey(), "?"));
                        helper.putInt("amount", DailyObjectives.shown(quest.objective(), quest.target(), e.getValue(), false));
                        top.add(helper);
                    });
            row.put("top", top);
            list.add(row);
        }
        tag.put("quests", list);
    }

    // ---------------------------------------------------------------- actions

    /**
     * {@code view} ({@code part}, {@code tab}, {@code kind}, {@code player}: what the admin looks at — no notice), else
     * a staff action with its {@code reason}. Null when there's nothing more to say (a refusal is already shown).
     */
    @Nullable
    static Component act(ServerPlayer admin, String action, UUID id, CompoundTag args) {
        if (action.equals("view")) {
            View view = view(admin, id);
            int part = args.contains("part") ? Math.max(OVERVIEW, Math.min(QUESTS, args.getInt("part"))) : view.part();
            int tab = args.contains("tab") ? args.getInt("tab") : view.tab();
            String kind = args.contains("kind") && KINDS.contains(args.getString("kind")) ? args.getString("kind") : view.kind();
            String player = args.contains("player") ? args.getString("player").strip() : view.player();
            VIEWS.put(admin.getUUID(), new View(id, part, tab, kind, player.length() > 32 ? "" : player));
            return null;
        }
        MinecraftServer server = admin.server;
        GuildManager manager = GuildManager.get(server);
        Guild guild = manager.byId(id);
        if (guild == null) {
            return refuse(admin, Component.translatable("message.eden_ld.guild.unknown"));
        }
        String reason = args.getString("reason").strip();
        String problem = GuildRules.reasonProblem(reason);
        if (problem != null) {
            return refuse(admin, Component.translatable("message.eden_ld.moderation.guild." + problem, GuildRules.MAX_REASON));
        }
        String staff = admin.getGameProfile().getName();
        String label = "[" + guild.tag + "] " + guild.name;
        return switch (action) {
            case "rename" -> {
                String old = guild.name;
                Component error = manager.staffRename(guild, args.getString("name"));
                if (error != null) {
                    yield refuse(admin, error);
                }
                if (old.equals(guild.name)) {
                    yield refuse(admin, Component.translatable("message.eden_ld.moderation.guild.unchanged"));
                }
                logStaff(admin, guild, "rename", reason, old, guild.name);
                tell(server, guild, Component.translatable("message.eden_ld.guild.staff_renamed", guild.name, reason));
                GuildServer.syncAll(server);
                EdenLD.LOGGER.info("[Guilds] {} renamed {} to \"{}\": {}", staff, label, guild.name, reason);
                yield Component.translatable("message.eden_ld.moderation.guild.renamed", guild.name);
            }
            case "retag" -> {
                String old = guild.tag;
                Component error = manager.staffSetTag(guild, args.getString("tag"));
                if (error != null) {
                    yield refuse(admin, error);
                }
                if (old.equals(guild.tag)) {
                    yield refuse(admin, Component.translatable("message.eden_ld.moderation.guild.unchanged"));
                }
                logStaff(admin, guild, "retag", reason, old, guild.tag);
                tell(server, guild, Component.translatable("message.eden_ld.guild.staff_retagged", guild.tag, reason));
                GuildServer.syncAll(server);
                EdenLD.LOGGER.info("[Guilds] {} changed the tag of {} to [{}]: {}", staff, label, guild.tag, reason);
                yield Component.translatable("message.eden_ld.moderation.guild.retagged", guild.tag);
            }
            case "recolor" -> {
                int old = guild.color;
                manager.staffSetColor(guild, args.getInt("color"));
                if (old == guild.color) {
                    yield refuse(admin, Component.translatable("message.eden_ld.moderation.guild.unchanged"));
                }
                logStaff(admin, guild, "recolor", reason, hex(old), hex(guild.color));
                tell(server, guild, Component.translatable("message.eden_ld.guild.staff_recolored", reason));
                GuildServer.syncAll(server);
                EdenLD.LOGGER.info("[Guilds] {} changed the color of {} to {}: {}", staff, label, hex(guild.color), reason);
                yield Component.translatable("message.eden_ld.moderation.guild.recolored");
            }
            case "kick", "rank", "transfer" -> member(admin, manager, guild, action, args, reason);
            case "xp" -> {
                long amount = Math.max(-1_000_000_000L, Math.min(1_000_000_000L, args.getLong("amount")));
                if (amount == 0) {
                    yield refuse(admin, Component.translatable("message.eden_ld.moderation.guild.amount_zero"));
                }
                int before = GuildLevels.level(guild);
                long xpBefore = guild.xp;
                // As /guild-admin xp add: levels follow, up or down, with what a level brings.
                GuildLevels.addXp(server, guild, amount);
                logStaff(admin, guild, "xp", reason, String.valueOf(guild.xp - xpBefore), String.valueOf(before),
                        String.valueOf(GuildLevels.level(guild)));
                EdenLD.LOGGER.info("[Guilds] {} changed the XP of {} by {} (now {}, level {}): {}", staff, label,
                        guild.xp - xpBefore, guild.xp, GuildLevels.level(guild), reason);
                yield Component.translatable("message.eden_ld.guild.admin_xp", guild.name, guild.xp, GuildLevels.level(guild));
            }
            case "quests_reset" -> {
                // As /guild-admin quests reset.
                GuildQuests.reset(server, guild);
                logStaff(admin, guild, "quests_reset", reason);
                EdenLD.LOGGER.info("[Guilds] {} drew new quests for {}: {}", staff, label, reason);
                yield Component.translatable("message.eden_ld.guild.admin_quests_reset", guild.name);
            }
            case "lc" -> {
                long amount = Math.max(-1_000_000_000_000L, Math.min(1_000_000_000_000L, args.getLong("amount")));
                if (amount == 0) {
                    yield refuse(admin, Component.translatable("message.eden_ld.moderation.guild.amount_zero"));
                }
                Component error = GuildBankService.staffLc(manager, guild, admin.getUUID(), staff, amount, reason);
                if (error != null) {
                    yield refuse(admin, error);
                }
                logStaff(admin, guild, "lc", reason, String.valueOf(amount), String.valueOf(guild.bank.balance));
                GuildBankServer.pushToViewers(server, guild);
                EdenLD.LOGGER.info("[Guilds] {} changed the bank of {} by {} LC (now {}): {}", staff, label, amount,
                        guild.bank.balance, reason);
                yield Component.translatable("message.eden_ld.moderation.guild.lc_changed", guild.bank.balance);
            }
            case "freeze" -> {
                boolean on = args.getBoolean("on");
                if (on == (guild.freeze != null)) {
                    yield refuse(admin, Component.translatable(on ? "message.eden_ld.moderation.guild.already_frozen"
                            : "message.eden_ld.moderation.guild.not_frozen"));
                }
                GuildBankService.staffFreeze(manager, guild, admin.getUUID(), staff, on, reason);
                logStaff(admin, guild, on ? "freeze" : "unfreeze", reason);
                GuildBankServer.pushToViewers(server, guild);
                EdenLD.LOGGER.info("[Guilds] {} {} the bank of {}: {}", staff, on ? "froze" : "thawed", label, reason);
                yield Component.translatable(on ? "message.eden_ld.moderation.guild.frozen"
                        : "message.eden_ld.moderation.guild.unfrozen");
            }
            default -> null;
        };
    }

    /** Kick, set a rank, or hand over leadership — through the manager's staff paths, then everything that follows membership. */
    @Nullable
    private static Component member(ServerPlayer admin, GuildManager manager, Guild guild, String action,
                                    CompoundTag args, String reason) {
        MinecraftServer server = admin.server;
        if (!args.hasUUID("member")) {
            return refuse(admin, Component.translatable("message.eden_ld.guild.staff_not_member"));
        }
        UUID target = args.getUUID("member");
        String name = guild.memberNames.getOrDefault(target, "?");
        String staff = admin.getGameProfile().getName();
        String label = "[" + guild.tag + "] " + guild.name;
        Component error;
        Component done;
        switch (action) {
            case "kick" -> {
                error = manager.staffKick(server, guild, target);
                if (error == null) {
                    logStaff(admin, guild, "kick", reason, name);
                    EdenLD.LOGGER.info("[Guilds] {} removed {} from {}: {}", staff, name, label, reason);
                }
                done = Component.translatable("message.eden_ld.moderation.guild.kicked", name);
            }
            case "rank" -> {
                int level = args.getInt("rank");
                if (level < GuildRank.RECRUIT.level || level >= GuildRank.LEADER.level) {
                    return refuse(admin, Component.translatable("message.eden_ld.guild.staff_leader"));
                }
                GuildRank rank = GuildRank.of(level);
                GuildRank before = guild.rank(target);
                if (before == rank) {
                    return refuse(admin, Component.translatable("message.eden_ld.moderation.guild.unchanged"));
                }
                error = manager.staffSetRank(guild, target, rank);
                if (error == null) {
                    logStaff(admin, guild, "rank", reason, name, before.name().toLowerCase(Locale.ROOT),
                            rank.name().toLowerCase(Locale.ROOT));
                    EdenLD.LOGGER.info("[Guilds] {} set {} in {} to {}: {}", staff, name, label, rank, reason);
                }
                done = Component.translatable("message.eden_ld.moderation.guild.rank_set", name);
            }
            default -> {
                UUID leader = guild.leader();
                String old = leader == null ? "?" : guild.memberNames.getOrDefault(leader, "?");
                error = manager.staffTransfer(server, guild, target);
                if (error == null) {
                    logStaff(admin, guild, "transfer", reason, name, old);
                    EdenLD.LOGGER.info("[Guilds] {} made {} the leader of {} (was {}): {}", staff, name, label, old, reason);
                }
                done = Component.translatable("message.eden_ld.moderation.guild.transferred", name);
            }
        }
        if (error != null) {
            return refuse(admin, error);
        }
        // As after the members' own changes: OPAC claims follow the leader, tags, chat channels, bank rights, waystones.
        GuildServer.syncAll(server);
        GuildBankServer.pushAll(server);
        HubServer.refreshWaystones(server);
        return done;
    }

    /**
     * {@code guilds/disband} with its reason (the old Disband): the guild is gone afterwards, so only the server log
     * keeps it. Null when refused.
     */
    @Nullable
    static Component disband(ServerPlayer admin, CompoundTag args) {
        MinecraftServer server = admin.server;
        GuildManager manager = GuildManager.get(server);
        Guild guild = args.hasUUID("id") ? manager.byId(args.getUUID("id")) : manager.byTag(args.getString("tag"));
        if (guild == null) {
            return refuse(admin, Component.translatable("message.eden_ld.guild.unknown"));
        }
        String reason = args.getString("reason").strip();
        String problem = GuildRules.reasonProblem(reason);
        if (problem != null) {
            return refuse(admin, Component.translatable("message.eden_ld.moderation.guild." + problem, GuildRules.MAX_REASON));
        }
        // The leader's land over their own limit becomes free land: say so first; disbanding again within a minute
        // goes ahead.
        Component landWarning = GuildManager.landWarning(server, guild, admin.getUUID());
        if (landWarning != null) {
            EdenLD.LOGGER.info("[Guilds] {} was warned before disbanding [{}] {}: {}", admin.getGameProfile().getName(),
                    guild.tag, guild.name, landWarning.getString());
            return refuse(admin, Component.translatableWithFallback("message.eden_ld.admin.disband_frees_land",
                    "Warning for the leader's land: %s", landWarning));
        }
        int members = guild.members.size();
        net.ledok.eden_ld.compat.opac.OpacCompat.Released freed = manager.forceDisband(server, guild);
        GuildServer.syncAll(server);
        HubServer.refreshWaystones(server);
        EdenLD.LOGGER.info("[Guilds] {} disbanded the guild [{}] {} ({} members, {} LC in the bank): {}",
                admin.getGameProfile().getName(), guild.tag, guild.name, members, guild.bank.balance, reason);
        VIEWS.values().removeIf(view -> view.guild().equals(guild.id));
        return freed == null ? Component.translatable("message.eden_ld.admin.disbanded", guild.name)
                : Component.translatableWithFallback("message.eden_ld.admin.disbanded_freed",
                        "Guild %1$s disbanded; %2$s of the leader's chunks over their own limit became free land "
                        + "(listed in the server log).",
                        guild.name, freed.chunks());
    }

    // ---------------------------------------------------------------- helpers

    /** Into the guild's staff log; {@code details} are tab-joined values the window words per action. */
    private static void logStaff(ServerPlayer admin, Guild guild, String action, String reason, String... details) {
        GuildManager.get(admin.server).addStaffLog(guild, new Guild.StaffEntry(System.currentTimeMillis(),
                admin.getUUID(), admin.getGameProfile().getName(), action, reason, String.join("\t", details)));
    }

    /** Members are told: a chat line to those online, and a system line in the guild's chat (the hub shows it). */
    private static void tell(MinecraftServer server, Guild guild, Component text) {
        Component line = text.copy().withStyle(ChatFormatting.GOLD);
        for (UUID member : guild.members.keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) {
                online.sendSystemMessage(line);
            }
        }
        Guild.ChatLine chat = new Guild.ChatLine(Guild.ChatLine.SYSTEM, "", text, System.currentTimeMillis());
        GuildManager.get(server).appendChat(guild, chat);
        GuildServer.broadcastChatLine(server, guild, chat);
    }

    @Nullable
    private static Component refuse(ServerPlayer admin, Component message) {
        AdminTools.notice(admin, message, false);
        return null;
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static String json(MinecraftServer server, Component component) {
        return Component.Serializer.toJson(component, server.registryAccess());
    }
}
