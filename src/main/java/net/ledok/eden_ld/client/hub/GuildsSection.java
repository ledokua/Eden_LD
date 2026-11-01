package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.client.HubGlyphs;
import net.ledok.eden_ld.hub.HubPayloads.HomeData;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildBank;
import net.ledok.eden_ld.hub.guild.GuildRank;
import net.ledok.eden_ld.hub.guild.GuildRules;
import net.ledok.eden_ld.hub.moderation.AdminTools;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Grid;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Dropdown;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.NumberField;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import static net.ledok.eden_ld.client.hub.ModerationScreen.ACCENT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.BAD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.GOOD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.LINE;
import static net.ledok.eden_ld.client.hub.ModerationScreen.PANEL;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.small;
import static net.ledok.eden_ld.client.hub.ModerationScreen.text;

/**
 * The Moderation window's <b>Guilds</b> section ({@code eden_ld.admin.guilds}): every guild in a list, and the picked
 * one's detail in sub-tabs — <b>Overview</b> (settings, level, limits, claims, bank, applications, the staff log),
 * <b>Members</b>, <b>Bank</b> (read-only vault tabs, rights, the audit log with a filter), <b>Chat</b>
 * ({@code eden_ld.admin.guild_chat}) and <b>Quests</b> — with the staff actions, each confirmed with a reason. The
 * server keeps what the admin looks at ({@code GuildDesk}), so each answer is that sub-tab's data. Against a server
 * from before 1.3.21 (no guild ids in the list) the old list and members view shows instead ({@link ServerSections}).
 */
final class GuildsSection {

    private static final float LIST = 130;
    private static final float ROW_H = 24;
    private static final float SLOT = 18;
    private static final int OVERVIEW = 0, MEMBERS = 1, BANK = 2, CHAT = 3, QUESTS = 4;
    private static final String[] PARTS = {"overview", "members", "bank", "chat", "quests"};
    /** The bank log's filter kinds, as the server knows them ("" = all). */
    private static final String[] KINDS = {"", "deposit", "withdraw", "purchase", "rights", "staff"};
    private static final int WARN = 0xFFE0C850;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private final ModerationScreen screen;
    private final float width;
    @Nullable
    private CompoundTag list;
    @Nullable
    private CompoundTag detail;
    private long detailAt;
    @Nullable
    private UUID selected;
    private int part = OVERVIEW;
    private int vaultTab = -1;
    private String kind = "";
    private String player = "";

    GuildsSection(ModerationScreen screen, float width) {
        this.screen = screen;
        this.width = width;
    }

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.moderation.guild." + key, args);
    }

    /** The Guild tab's own words (ranks, bank log lines, quest boards). */
    private static MutableComponent hub(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.guild." + key, args);
    }

    void request() {
        PlayerTabs.request(AdminTools.GUILDS, Util.NIL_UUID);
        sendView();
    }

    /** The server's list has no guild ids (before 1.3.21): only the old view works. */
    boolean legacy() {
        if (list == null) {
            return false;
        }
        ListTag guilds = list.getList(AdminTools.GUILDS, Tag.TAG_COMPOUND);
        return !guilds.isEmpty() && !guilds.getCompound(0).hasUUID("id");
    }

    /** Returns whether it was this section's data (the list, or the picked guild's detail). */
    boolean onData(String area, CompoundTag tag) {
        if (area.equals(AdminTools.GUILDS)) {
            list = tag;
            if (legacy()) {
                return true;
            }
            ListTag guilds = tag.getList(AdminTools.GUILDS, Tag.TAG_COMPOUND);
            boolean found = false;
            for (int i = 0; i < guilds.size(); i++) {
                found |= guilds.getCompound(i).getUUID("id").equals(selected);
            }
            if (!found) {
                select(guilds.isEmpty() ? null : guilds.getCompound(0).getUUID("id"));
            }
            return true;
        }
        if (!area.equals(AdminTools.GUILD)) {
            return false;
        }
        if (tag.getBoolean("gone")) {
            // Disbanded meanwhile: the list (asked for again) picks another.
            selected = null;
            detail = null;
            PlayerTabs.request(AdminTools.GUILDS, Util.NIL_UUID);
        } else if (selected != null && tag.hasUUID("id") && tag.getUUID("id").equals(selected)) {
            detail = tag;
            detailAt = System.currentTimeMillis();
            if (tag.getInt("part") == BANK) {
                vaultTab = tag.getInt("tab");
            }
        }
        return true;
    }

    private void select(@Nullable UUID guild) {
        selected = guild;
        detail = null;
        vaultTab = -1;
        kind = "";
        player = "";
        sendView();
    }

    /** What the admin looks at; the server answers with that part's data. */
    private void sendView() {
        if (selected == null) {
            return;
        }
        CompoundTag args = new CompoundTag();
        args.putInt("part", part);
        args.putInt("tab", vaultTab);
        args.putString("kind", kind);
        args.putString("player", player);
        PlayerTabs.action(AdminTools.GUILD, selected, "view", args);
    }

    private boolean chatAllowed() {
        return (screen.staff() & HomeData.STAFF_GUILD_CHAT) != 0;
    }

    // ---------------------------------------------------------------- layout

    void build(Flex main) {
        main.item(text(Component.translatable("gui.eden_ld.moderation.section.guilds"), TEXT));
        if (list == null) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.expand());
        Flex rows = Flex.column().gap(2);
        ListTag guilds = list.getList(AdminTools.GUILDS, Tag.TAG_COMPOUND);
        if (guilds.isEmpty()) {
            rows.item(text(Component.translatable("gui.eden_ld.moderation.admin.none_guilds"), DIM, (int) LIST - 10));
        }
        CompoundTag picked = null;
        for (int i = 0; i < guilds.size(); i++) {
            CompoundTag entry = guilds.getCompound(i);
            boolean on = entry.getUUID("id").equals(selected);
            if (on) {
                picked = entry;
            }
            rows.item(new GuildRow(entry, on));
        }
        ScrollPanel scroll = HubScroll.panel("mod/guilds/list", LIST, 100, rows);
        scroll.sizing(Sizing.fixed(LIST), Sizing.fill());
        columns.item(scroll);

        Flex right = Flex.column().gap(3);
        right.sizing(Sizing.expand(), Sizing.fill());
        if (picked != null) {
            buildDetail(right, picked);
        }
        columns.item(right);
        main.item(columns);
    }

    private void buildDetail(Flex box, CompoundTag entry) {
        float w = width - LIST - 8;
        CompoundTag d = detail;
        CompoundTag head = d != null ? d : entry;
        int color = head.getInt("color") & 0xFFFFFF;
        Component title = Component.literal("[" + head.getString("tag") + "] ").withStyle(s -> s.withColor(color))
                .append(Component.literal(head.getString("name")).withStyle(s -> s.withColor(TEXT & 0xFFFFFF)));
        Flex header = Flex.row().gap(6).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        boolean frozen = d != null ? d.contains("frozen", Tag.TAG_COMPOUND) : entry.getBoolean("frozen");
        Component badge = tr("frozen_badge");
        float badgeWidth = frozen ? Minecraft.getInstance().font.width(badge) + 6 : 0;
        header.item(TextNode.of(title).color(TEXT).shadow(false).maxWidth((int) (w - badgeWidth)).ellipsis(true));
        header.item(PlayerTabs.spacer());
        if (frozen) {
            header.item(text(badge, BAD));
        }
        box.item(header);

        if (part == CHAT && !chatAllowed()) {
            // The right went away meanwhile.
            part = OVERVIEW;
            sendView();
        }
        List<Widget> tabs = new ArrayList<>();
        for (int p = OVERVIEW; p <= QUESTS; p++) {
            if (p == CHAT && !chatAllowed()) {
                continue;
            }
            int target = p;
            Button button = small("gui.eden_ld.moderation.guild.part." + PARTS[p], 40, () -> {
                if (part != target) {
                    part = target;
                    sendView();
                    screen.rebuildNow();
                }
            });
            if (p == part) {
                button.style(ModerationScreen.SELECTED);
            }
            tabs.add(button);
        }
        box.item(ModerationScreen.wrapped(tabs, w, 3));

        if (d == null || d.getInt("part") != part) {
            box.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        switch (part) {
            case MEMBERS -> members(box, d, w);
            case BANK -> bank(box, d, w);
            case CHAT -> chat(box, d, w);
            case QUESTS -> quests(box, d, w);
            default -> overview(box, d, w);
        }
    }

    /** A sub-tab's content, scrolling in what's left of the column (position kept per guild and sub-tab). */
    private void scroll(Flex box, Flex content, float w) {
        ScrollPanel scroll = HubScroll.panel("mod/guilds/" + PARTS[part] + "/" + selected, w, 100, content);
        scroll.sizing(Sizing.fill(), Sizing.expand());
        box.item(scroll);
    }

    // ---------------------------------------------------------------- overview

    private void overview(Flex box, CompoundTag d, float w) {
        UUID guild = selected;
        String name = d.getString("name");
        boolean frozen = d.contains("frozen", Tag.TAG_COMPOUND);
        List<Widget> actions = new ArrayList<>();
        actions.add(small("gui.eden_ld.moderation.guild.action.rename", 40, () -> {
            TextField field = field(name, GuildRules.MAX_NAME_LENGTH);
            dialog(tr("rename_dialog", name, GuildRules.MIN_NAME_LENGTH, GuildRules.MAX_NAME_LENGTH), List.of(field),
                    AdminTools.GUILD, guild, "rename", args -> args.putString("name", field.text().trim()), field);
        }));
        actions.add(small("gui.eden_ld.moderation.guild.action.retag", 30, () -> {
            TextField field = field(d.getString("tag"), GuildRules.MAX_TAG_LENGTH);
            dialog(tr("retag_dialog", name, GuildRules.MAX_TAG_LENGTH), List.of(field), AdminTools.GUILD, guild, "retag",
                    args -> args.putString("tag", field.text().trim()), field);
        }));
        actions.add(small("gui.eden_ld.moderation.guild.action.recolor", 30, () -> {
            List<GuildTab.ColorOption> colors = new ArrayList<>(GuildTab.COLORS);
            int rgb = d.getInt("color") & 0xFFFFFF;
            GuildTab.ColorOption current = colors.stream().filter(c -> c.rgb() == rgb).findFirst().orElse(null);
            if (current == null) {
                current = new GuildTab.ColorOption("custom", rgb);
                colors.add(0, current);
            }
            Dropdown<GuildTab.ColorOption> picker = new Dropdown<>(220, colors, current, GuildTab::colorLabel, c -> {
            });
            picker.size(220, 16);
            dialog(tr("recolor_dialog", name), List.of(picker), AdminTools.GUILD, guild, "recolor",
                    args -> args.putInt("color", picker.selected().rgb()), null);
        }));
        actions.add(small("gui.eden_ld.moderation.guild.action.xp", 40, () -> {
            NumberField amount = number(1_000_000_000L, 100);
            dialog(tr("xp_dialog", name), List.of(amount), AdminTools.GUILD, guild, "xp",
                    args -> args.putLong("amount", amount.value()), amount);
        }));
        actions.add(small("gui.eden_ld.moderation.guild.action.quests_reset", 40, () ->
                dialog(tr("quests_dialog", name), List.of(), AdminTools.GUILD, guild, "quests_reset", args -> {
                }, null)));
        actions.add(small("gui.eden_ld.moderation.guild.action.lc", 40, () -> {
            NumberField amount = number(1_000_000_000_000L, 1000);
            dialog(tr("lc_dialog", name), List.of(amount), AdminTools.GUILD, guild, "lc",
                    args -> args.putLong("amount", amount.value()), amount);
        }));
        actions.add(small(frozen ? "gui.eden_ld.moderation.guild.action.unfreeze" : "gui.eden_ld.moderation.guild.action.freeze",
                50, () -> dialog(tr(frozen ? "unfreeze_dialog" : "freeze_dialog", name), List.of(), AdminTools.GUILD,
                        guild, "freeze", args -> args.putBoolean("on", !frozen), null)));
        actions.add(small("gui.eden_ld.moderation.admin.disband", 40, () -> dialog(
                Component.translatable("gui.eden_ld.moderation.admin.disband_confirm_guilds", name), List.of(),
                AdminTools.GUILDS, Util.NIL_UUID, "disband", args -> {
                    args.putUUID("id", guild);
                    args.putString("tag", d.getString("tag"));
                }, null)));
        box.item(ModerationScreen.wrapped(actions, w, 3));

        float inner = w - 10;
        Flex content = Flex.column().gap(3);
        if (frozen) {
            CompoundTag freeze = d.getCompound("frozen");
            content.item(text(tr("frozen_line", freeze.getString("by"), PunishSection.ago(freeze.getLong("since")),
                    freeze.getString("reason")), BAD, (int) inner));
        }
        content.item(text(tr("founded", HubDates.full(d.getLong("created")))
                .append(" · ").append(tr("join_mode", hub("join_mode." + d.getInt("join_mode")))), TEXT, (int) inner));
        String motd = d.getString("motd");
        content.item(text(motd.isBlank() ? tr("no_motd") : tr("motd", motd), motd.isBlank() ? DIM : TEXT, (int) inner));
        content.item(text(d.getLong("xp_needed") > 0
                ? tr("level", d.getInt("level"), d.getInt("max_level"), d.getLong("xp_into"), d.getLong("xp_needed"),
                d.getLong("xp"))
                : tr("level_max", d.getInt("level"), d.getLong("xp")), TEXT, (int) inner));
        content.item(text(tr("members_line", d.getInt("members"), d.getInt("member_limit"), d.getInt("member_level")),
                TEXT, (int) inner));
        int limit = d.getInt("claims_limit");
        content.item(text(limit >= 0
                ? tr("claims", d.getInt("claims_used"), limit, d.getInt("claim_level"), d.getInt("claim_step"),
                d.getInt("prime_claims"))
                : tr("claims_off", d.getInt("claim_level"), d.getInt("prime_claims")),
                limit >= 0 && d.getInt("claims_used") >= limit ? WARN : TEXT, (int) inner));
        content.item(text(tr("bank_line", d.getLong("balance"), d.getInt("tabs"), d.getInt("tab_cap")), TEXT, (int) inner));

        ListTag applications = d.getList("applications", Tag.TAG_COMPOUND);
        content.item(ModerationScreen.gap(2));
        content.item(text(applications.isEmpty() ? tr("applications_none") : tr("applications", applications.size()),
                applications.isEmpty() ? DIM : TEXT, (int) inner));
        for (int i = 0; i < applications.size(); i++) {
            CompoundTag application = applications.getCompound(i);
            content.item(text(Component.literal("  " + application.getString("name") + " · ")
                    .append(PunishSection.ago(application.getLong("time"))), DIM, (int) inner));
        }

        ListTag log = d.getList("staff_log", Tag.TAG_COMPOUND);
        content.item(ModerationScreen.gap(2));
        content.item(text(log.isEmpty() ? tr("staff_log_empty") : tr("staff_log", log.size()),
                log.isEmpty() ? DIM : TEXT, (int) inner));
        for (int i = 0; i < log.size(); i++) {
            CompoundTag row = log.getCompound(i);
            Flex entry = Flex.column().gap(1).padding(Insets.of(2, 4, 2, 4));
            entry.sizing(Sizing.fixed(inner), Sizing.content());
            entry.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(0xFF22252A).stroke(Stroke.of(LINE, 1)));
            entry.item(text(Component.literal(TIME.format(Instant.ofEpochMilli(row.getLong("time"))) + " · ")
                    .withStyle(s -> s.withColor(DIM & 0xFFFFFF))
                    .append(Component.literal(row.getString("staff") + " — ").withStyle(s -> s.withColor(TEXT & 0xFFFFFF)))
                    .append(staffAction(row)), TEXT, (int) inner - 8));
            entry.item(text(tr("reason_line", row.getString("reason")), DIM, (int) inner - 8));
            content.item(entry);
        }
        scroll(box, content, w);
    }

    /** A staff log entry's action, worded from its tab-separated details. */
    private static Component staffAction(CompoundTag row) {
        String[] d = row.getString("details").split("\t", -1);
        String action = row.getString("action");
        return switch (action) {
            case "rename", "retag", "transfer" -> tr("log." + action, arg(d, 0), arg(d, 1));
            case "recolor" -> tr("log.recolor", swatch(arg(d, 0)), swatch(arg(d, 1)));
            case "kick" -> tr("log.kick", arg(d, 0));
            case "rank" -> tr("log.rank", arg(d, 0), hub("rank." + arg(d, 1)), hub("rank." + arg(d, 2)));
            case "xp" -> tr("log.xp", signed(arg(d, 0)), arg(d, 1), arg(d, 2));
            case "lc" -> tr("log.lc", signed(arg(d, 0)), arg(d, 1));
            case "quests_reset", "freeze", "unfreeze" -> tr("log." + action);
            default -> Component.literal(action);
        };
    }

    private static String arg(String[] values, int index) {
        return index < values.length ? values[index] : "?";
    }

    private static String signed(String number) {
        return number.startsWith("-") ? number : "+" + number;
    }

    /** "■ #AABBCC" in that color. */
    private static Component swatch(String hex) {
        int rgb;
        try {
            rgb = Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16);
        } catch (NumberFormatException e) {
            return Component.literal(hex);
        }
        return Component.literal("■ " + hex).withStyle(s -> s.withColor(rgb & 0xFFFFFF));
    }

    // ---------------------------------------------------------------- members

    private void members(Flex box, CompoundTag d, float w) {
        Flex content = Flex.column().gap(2);
        ListTag members = d.getList("members", Tag.TAG_COMPOUND);
        int quests = d.getInt("quests_total");
        for (int i = 0; i < members.size(); i++) {
            content.item(memberRow(d, members.getCompound(i), quests, w - 10));
        }
        scroll(box, content, w);
    }

    /** Name, rank and the staff buttons; under them when they were last here, joined, Prime, today's bank, quests. */
    private Flex memberRow(CompoundTag d, CompoundTag member, int quests, float w) {
        UUID guild = selected;
        UUID uuid = member.getUUID("uuid");
        String name = member.getString("name");
        String guildName = d.getString("name");
        int rank = member.getInt("rank");
        boolean online = member.getBoolean("online");
        boolean leader = rank == GuildRank.LEADER.level;

        Flex row = Flex.column().gap(2).padding(Insets.of(2, 4, 2, 4));
        row.sizing(Sizing.fixed(w), Sizing.content());
        row.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(0xFF22252A).stroke(Stroke.of(LINE, 1)));
        Flex top = Flex.row().gap(4).alignItems(Align.CENTER);
        top.sizing(Sizing.fill(), Sizing.content());
        List<Button> buttons = new ArrayList<>();
        if (!leader) {
            buttons.add(small("gui.eden_ld.moderation.guild.action.rank", 30, () -> {
                List<Integer> ranks = new ArrayList<>();
                for (GuildRank option : GuildRank.values()) {
                    if (option != GuildRank.LEADER) {
                        ranks.add(option.level);
                    }
                }
                Dropdown<Integer> picker = new Dropdown<>(220, ranks, rank, GuildsSection::rankName, value -> {
                });
                picker.size(220, 16);
                dialog(tr("rank_dialog", name), List.of(picker), AdminTools.GUILD, guild, "rank", args -> {
                    args.putUUID("member", uuid);
                    args.putInt("rank", picker.selected());
                }, null);
            }));
            buttons.add(small("gui.eden_ld.moderation.guild.action.kick", 30, () -> dialog(tr("kick_dialog", name, guildName),
                    List.of(), AdminTools.GUILD, guild, "kick", args -> args.putUUID("member", uuid), null)));
            buttons.add(small("gui.eden_ld.moderation.guild.action.transfer", 40, () -> dialog(
                    tr("transfer_dialog", name, guildName), List.of(), AdminTools.GUILD, guild, "transfer",
                    args -> args.putUUID("member", uuid), null)));
        }
        float used = 0;
        for (Button button : buttons) {
            used += button.width() + 4;
        }
        Component head = Component.literal(online ? "● " : "○ ").withStyle(s -> s.withColor((online ? GOOD : DIM) & 0xFFFFFF))
                .append(Component.literal(name).withStyle(s -> s.withColor(TEXT & 0xFFFFFF)))
                .append(Component.literal("  ").append(rankName(rank)).withStyle(s -> s.withColor(DIM & 0xFFFFFF)));
        top.item(TextNode.of(head).color(TEXT).shadow(false).maxWidth((int) (w - 8 - used)).ellipsis(true));
        top.item(PlayerTabs.spacer());
        buttons.forEach(top::item);
        row.item(top);

        long lastSeen = member.getLong("last_seen");
        long joined = member.getLong("joined");
        MutableComponent info = Component.empty()
                .append(online ? tr("member_online") : lastSeen > 0 ? tr("member_seen", PunishSection.ago(lastSeen))
                        : tr("member_offline"))
                .append(" · ")
                .append(joined > 0 ? tr("member_joined", HubDates.full(joined)) : tr("member_joined_unknown"));
        if (member.getInt("prime") > 0) {
            info.append(" · ").append(tr("member_prime", member.getInt("prime")));
        }
        info.append(" · ").append(tr("member_today", member.getLong("items_today"), member.getLong("lc_today")))
                .append(" · ").append(tr("member_quests", member.getInt("quests"), quests));
        row.item(text(info, DIM, (int) w - 8));
        return row;
    }

    private static Component rankName(int level) {
        return hub("rank." + GuildRank.of(level).name().toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- bank

    private void bank(Flex box, CompoundTag d, float w) {
        float inner = w - 10;
        Flex content = Flex.column().gap(3);
        content.item(text(tr("bank_balance", d.getLong("balance")), TEXT, (int) inner));
        if (d.contains("frozen", Tag.TAG_COMPOUND)) {
            CompoundTag freeze = d.getCompound("frozen");
            content.item(text(tr("frozen_line", freeze.getString("by"), PunishSection.ago(freeze.getLong("since")),
                    freeze.getString("reason")), BAD, (int) inner));
        }
        ListTag tabs = d.getList("tabs", Tag.TAG_STRING);
        int tab = d.getInt("tab");
        if (tabs.isEmpty()) {
            content.item(text(tr("no_tabs"), DIM, (int) inner));
        } else {
            List<Widget> buttons = new ArrayList<>();
            for (int i = 0; i < tabs.size(); i++) {
                int index = i;
                Component label = tabName(tabs, i);
                float bw = Math.max(36, Minecraft.getInstance().font.width(label) + 10);
                Button button = new Button(bw, 14, label, () -> {
                    if (vaultTab != index) {
                        vaultTab = index;
                        sendView();
                    }
                });
                button.sizing(Sizing.fixed(bw), Sizing.fixed(14));
                if (i == tab) {
                    button.style(ModerationScreen.SELECTED);
                }
                buttons.add(button);
            }
            content.item(ModerationScreen.wrapped(buttons, inner, 3));
            Flex row = Flex.row().gap(6);
            row.sizing(Sizing.fixed(inner), Sizing.content());
            row.item(vault(d));
            row.item(rights(d, tabs, tab, inner - SLOT * 9 - 6));
            content.item(row);
            if (d.getBoolean("trimmed")) {
                content.item(text(tr("trimmed"), WARN, (int) inner));
            }
        }

        content.item(ModerationScreen.gap(3));
        content.item(text(tr("log", d.getInt("log_total")), TEXT));
        List<Widget> filters = new ArrayList<>();
        for (String k : KINDS) {
            Button button = small("gui.eden_ld.moderation.guild.kind." + (k.isEmpty() ? "all" : k), 30, () -> {
                if (!kind.equals(k)) {
                    kind = k;
                    sendView();
                }
            });
            if (k.equals(kind)) {
                button.style(ModerationScreen.SELECTED);
            }
            filters.add(button);
        }
        List<String> players = new ArrayList<>(PlayerTabs.strings(d, "log_players"));
        if (!player.isEmpty() && !players.contains(player)) {
            players.add(0, player);
        }
        players.add(0, "");
        Dropdown<String> who = new Dropdown<>(100, players, player, value -> value.isEmpty() ? tr("player_all")
                : Component.literal(value), value -> {
            if (!player.equals(value)) {
                player = value;
                sendView();
            }
        });
        who.searchable(true);
        who.size(100, 14);
        filters.add(who);
        content.item(ModerationScreen.wrapped(filters, inner, 3));

        ListTag log = d.getList("log", Tag.TAG_COMPOUND);
        if (log.isEmpty()) {
            content.item(text(tr("log_empty"), DIM, (int) inner));
        }
        for (int i = 0; i < log.size(); i++) {
            content.item(logRow(log.getCompound(i), tabs, inner));
        }
        if (d.getInt("log_total") > log.size()) {
            content.item(text(tr("log_more", d.getInt("log_total") - log.size()), DIM, (int) inner));
        }
        scroll(box, content, w);
    }

    /** The viewed tab's 54 slots, read-only; hovering an item shows its tooltip. */
    private static Grid vault(CompoundTag d) {
        ItemStack[] stacks = new ItemStack[GuildBank.TAB_SIZE];
        ListTag contents = d.getList("contents", Tag.TAG_COMPOUND);
        var level = Minecraft.getInstance().level;
        for (int i = 0; i < contents.size(); i++) {
            CompoundTag entry = contents.getCompound(i);
            int slot = entry.getInt("slot");
            if (slot >= 0 && slot < stacks.length && level != null) {
                stacks[slot] = ItemStack.parseOptional(level.registryAccess(), entry.getCompound("item"));
            }
        }
        Grid grid = Grid.of(9).cell(SLOT, SLOT);
        for (ItemStack stack : stacks) {
            grid.item(new VaultSlot(stack == null ? ItemStack.EMPTY : stack));
        }
        return grid;
    }

    /** Who may do what in the viewed tab, and how much LC each rank may take a day. */
    private static Flex rights(CompoundTag d, ListTag tabs, int tab, float w) {
        Flex column = Flex.column().gap(2);
        column.sizing(Sizing.fixed(w), Sizing.content());
        column.item(text(tr("rights_title", tabName(tabs, tab)), TEXT, (int) w));
        column.item(text(tr("rights_managers"), DIM, (int) w));
        ListTag rights = d.getList("rights", Tag.TAG_COMPOUND);
        for (int i = 0; i < rights.size(); i++) {
            CompoundTag row = rights.getCompound(i);
            int[] access = row.getIntArray("access");
            int[] items = row.getIntArray("items");
            int a = tab >= 0 && tab < access.length ? access[tab] : 0;
            int perDay = tab >= 0 && tab < items.length ? items[tab] : 0;
            Component accessName = hub("bank.access." + Math.max(0, Math.min(3, a)));
            column.item(text(a == GuildBank.Access.WITHDRAW.ordinal()
                    ? tr("rights_row", rankName(row.getInt("rank")), accessName,
                    perDay < 0 ? hub("bank.unlimited") : Component.literal(String.valueOf(perDay)))
                    : tr("rights_row_plain", rankName(row.getInt("rank")), accessName), DIM, (int) w));
        }
        column.item(ModerationScreen.gap(2));
        column.item(text(tr("lc_title"), TEXT, (int) w));
        for (int i = 0; i < rights.size(); i++) {
            CompoundTag row = rights.getCompound(i);
            long lc = row.getLong("lc");
            column.item(text(lc < 0 ? tr("rights_lc", rankName(row.getInt("rank")), hub("bank.unlimited"))
                    : lc == 0 ? tr("rights_lc_none", rankName(row.getInt("rank")))
                    : tr("rights_lc", rankName(row.getInt("rank")), lc + " " + HubGlyphs.LC_TEXT), DIM, (int) w));
        }
        return column;
    }

    private static Component tabName(ListTag tabs, int index) {
        String name = index >= 0 && index < tabs.size() ? tabs.getString(index) : "";
        return name.isBlank() ? hub("bank.tab_default", index + 1) : Component.literal(name);
    }

    private static Flex logRow(CompoundTag row, ListTag tabs, float w) {
        Flex line = Flex.row().gap(3);
        line.sizing(Sizing.fixed(w), Sizing.content());
        line.item(text(Component.literal(TIME.format(Instant.ofEpochMilli(row.getLong("time")))), DIM));
        float textWidth = w - Minecraft.getInstance().font.width("00.00 00:00") - 3;
        if (row.contains("item")) {
            ResourceLocation id = ResourceLocation.tryParse(row.getString("item"));
            ItemStack icon = new ItemStack(id == null ? Items.PAPER : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.PAPER));
            line.item(ItemStackNode.of(icon, 0, 0, 9).decorations(false).stackTooltip(false));
            textWidth -= 12;
        }
        GuildBank.LogType[] types = GuildBank.LogType.values();
        GuildBank.LogType type = types[Math.floorMod(row.getInt("type"), types.length)];
        line.item(text(bankLogText(row, type, tabs), type.staff() ? WARN : TEXT, (int) textWidth));
        return line;
    }

    /** As the Guild tab's bank log words it; staff entries with their reason. */
    private static Component bankLogText(CompoundTag row, GuildBank.LogType type, ListTag tabs) {
        String name = row.getString("name");
        long amount = row.getLong("amount");
        String detail = row.getString("detail");
        Component item = row.contains("item_name") ? PlayerTabs.json(row.getString("item_name")) : Component.empty();
        Component tab = tabName(tabs, row.getInt("tab"));
        return switch (type) {
            case DEPOSIT_ITEM -> hub("bank.log.deposit_item", name, amount, item, tab);
            case WITHDRAW_ITEM -> hub("bank.log.withdraw_item", name, amount, item, tab);
            case DEPOSIT_LC -> hub("bank.log.deposit_lc", name, amount);
            case WITHDRAW_LC -> hub("bank.log.withdraw_lc", name, amount);
            case BUY_TAB -> hub("bank.log.buy_tab", name, tab, amount);
            case BUY_UPGRADE -> hub("CLAIMS".equals(detail) ? "bank.log.buy_claims" : "bank.log.buy_members", name, amount);
            case QUEST_REWARD -> hub("bank.log.quest_reward", amount);
            case RIGHTS -> {
                GuildRank rank;
                try {
                    rank = GuildRank.valueOf(detail);
                } catch (IllegalArgumentException e) {
                    yield hub("bank.log.rights", name);
                }
                Component rankName = rankName(rank.level);
                yield row.getInt("tab") >= 0 ? hub("bank.log.rights_tab", name, rankName, tab)
                        : hub("bank.log.rights_lc", name, rankName, amount < 0 ? hub("bank.unlimited")
                        : Component.literal(amount + " " + HubGlyphs.LC_TEXT));
            }
            case STAFF_LC -> amount >= 0 ? hub("bank.log.staff_lc_add", name, amount, detail)
                    : hub("bank.log.staff_lc_take", name, -amount, detail);
            case STAFF_FREEZE -> hub("bank.log.staff_freeze", name, detail);
            case STAFF_UNFREEZE -> hub("bank.log.staff_unfreeze", name, detail);
        };
    }

    // ---------------------------------------------------------------- chat

    /** The stored chat, read-only, newest at the bottom (where it opens). */
    private void chat(Flex box, CompoundTag d, float w) {
        if (d.getBoolean("chat_denied")) {
            box.item(text(tr("chat_denied"), DIM, (int) w));
            return;
        }
        ListTag lines = d.getList("chat", Tag.TAG_COMPOUND);
        box.item(text(lines.isEmpty() ? tr("chat_empty") : tr("chat_count", lines.size()), DIM, (int) w));
        Flex content = Flex.column().gap(2).padding(Insets.of(2, 3, 2, 3));
        float stamp = Minecraft.getInstance().font.width("00.00 00:00") + 3;
        int wrap = (int) (w - 6 - 6 - stamp - 8 - 3);
        for (int i = 0; i < lines.size(); i++) {
            CompoundTag entry = lines.getCompound(i);
            UUID sender = entry.getUUID("sender");
            Component message = PlayerTabs.json(entry.getString("text"));
            Flex line = Flex.row().gap(3);
            line.item(text(Component.literal(TIME.format(Instant.ofEpochMilli(entry.getLong("time")))), DIM));
            if (sender.equals(Guild.ChatLine.SYSTEM)) {
                line.item(text(message, WARN, wrap + 11));
            } else {
                line.item(PlayerFace.of(sender, entry.getString("name"), 8));
                line.item(new ChatText(PlayerNames.chatName(sender, entry.getString("name")).copy().append(": ")
                        .append(message), TEXT, wrap));
            }
            content.item(line);
        }
        ScrollPanel scroll = new ScrollPanel(w, 100, content);
        scroll.sizing(Sizing.fill(), Sizing.expand());
        scroll.scrollTo(Float.MAX_VALUE);
        box.item(scroll);
    }

    // ---------------------------------------------------------------- quests

    private void quests(Flex box, CompoundTag d, float w) {
        float inner = w - 10;
        Flex content = Flex.column().gap(3);
        ListTag quests = d.getList("quests", Tag.TAG_COMPOUND);
        if (quests.isEmpty()) {
            content.item(text(tr("quests_none"), DIM, (int) inner));
        }
        long elapsed = System.currentTimeMillis() - detailAt;
        for (int period = 0; period <= 1; period++) {
            List<CompoundTag> board = new ArrayList<>();
            for (int i = 0; i < quests.size(); i++) {
                if (quests.getCompound(i).getInt("period") == period) {
                    board.add(quests.getCompound(i));
                }
            }
            if (board.isEmpty()) {
                continue;
            }
            Flex heading = Flex.row().gap(6).alignItems(Align.CENTER);
            heading.sizing(Sizing.fixed(inner), Sizing.content());
            heading.item(text(hub(period == 0 ? "quests.daily" : "quests.weekly"), TEXT));
            heading.item(PlayerTabs.spacer());
            heading.item(text(GuildQuestsView.countdown(d.getLong(period == 0 ? "daily_reset" : "weekly_reset") - elapsed), DIM));
            content.item(heading);
            for (CompoundTag quest : board) {
                content.item(questRow(quest, inner));
            }
        }
        scroll(box, content, w);
    }

    private static Flex questRow(CompoundTag quest, float w) {
        Flex row = Flex.row().gap(5).alignItems(Align.CENTER).padding(Insets.of(2, 4, 2, 4));
        row.sizing(Sizing.fixed(w), Sizing.content());
        row.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(0xFF22252A).stroke(Stroke.of(LINE, 1)));
        ResourceLocation id = ResourceLocation.tryParse(quest.getString("icon"));
        ItemStack icon = new ItemStack(id == null ? Items.PAPER : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.PAPER));
        row.item(ItemStackNode.of(icon, 0, 0, 16).decorations(false).stackTooltip(false));
        float textWidth = w - 8 - 16 - 5;
        Flex text = Flex.column().gap(1);
        boolean done = quest.getBoolean("done");
        text.item(TextNode.of(PlayerTabs.json(quest.getString("title"))).color(done ? DIM : TEXT).shadow(false)
                .maxWidth((int) textWidth).ellipsis(true));
        text.item(text(done ? hub("quests.done") : hub("quests.progress", quest.getInt("progress"), quest.getInt("goal")),
                done ? GOOD : TEXT));
        ListTag top = quest.getList("top", Tag.TAG_COMPOUND);
        MutableComponent helpers = Component.empty();
        for (int i = 0; i < top.size(); i++) {
            CompoundTag helper = top.getCompound(i);
            if (i > 0) {
                helpers.append(", ");
            }
            helpers.append(helper.getString("name") + " (" + helper.getInt("amount") + ")");
        }
        text.item(text(top.isEmpty() ? tr("quest_top_none") : tr("quest_top", helpers), DIM, (int) textWidth));
        row.item(text);
        return row;
    }

    // ---------------------------------------------------------------- dialogs

    private static TextField field(String value, int maxLength) {
        TextField field = new TextField(220, value, text -> {
        });
        field.size(220, 16);
        field.maxLength(maxLength);
        return field;
    }

    /** A signed amount (negative takes away). */
    private static NumberField number(long max, long step) {
        NumberField field = new NumberField(220, 0, null);
        field.range(-max, max).step(step);
        field.size(220, 16);
        return field;
    }

    /**
     * A staff action's confirmation: the question, the action's own inputs, and its reason (required, up to
     * {@link GuildRules#MAX_REASON} characters — Apply waits for one); Apply sends {@code action} with what
     * {@code fill} puts into the args, plus the reason.
     */
    private void dialog(Component question, List<? extends Widget> inputs, String area, UUID target, String action,
                        Consumer<CompoundTag> fill, @Nullable Widget focus) {
        float inner = 220;
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(inner + 20), Sizing.content());
        dialog.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL).stroke(Stroke.of(LINE, 1)));
        dialog.item(text(question, TEXT, (int) inner));
        inputs.forEach(dialog::item);
        Button apply = small("gui.eden_ld.moderation.apply", 60, () -> {
        });
        apply.enabled(false);
        TextField reason = new TextField(inner, "", value -> apply.enabled(!value.isBlank()));
        reason.size(inner, 16);
        reason.maxLength(GuildRules.MAX_REASON);
        reason.hint(tr("reason_hint", GuildRules.MAX_REASON).getString());
        dialog.item(reason);
        Modal[] modal = new Modal[1];
        Runnable send = () -> {
            if (reason.text().isBlank()) {
                return;
            }
            CompoundTag args = new CompoundTag();
            fill.accept(args);
            args.putString("reason", reason.text().strip());
            PlayerTabs.action(area, target, action, args);
            modal[0].close();
        };
        apply.onClick(send);
        reason.onSubmit(send);
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        buttons.item(apply);
        buttons.item(small("gui.eden_ld.moderation.cancel", 50, () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
        screen.canvas().focus(focus != null ? focus : reason);
    }

    // ---------------------------------------------------------------- widgets

    /** A guild in the list: tag and name in its color, members and leader; ❄ while its bank is frozen. */
    private final class GuildRow extends Widget {
        private final CompoundTag entry;
        private final boolean on;

        GuildRow(CompoundTag entry, boolean on) {
            super(LIST - 8, ROW_H);
            this.entry = entry;
            this.on = on;
            sizing(Sizing.fixed(LIST - 8), Sizing.fixed(ROW_H));
        }

        @Override
        protected void rebuild() {
            add(Shapes.rect(0, 0, width, height).fill(on ? PANEL : 0xFF22252A)
                    .stroke(Stroke.of(on ? ACCENT : hovered ? DIM : LINE, 1)));
            int color = entry.getInt("color") & 0xFFFFFF;
            Component head = Component.literal("[" + entry.getString("tag") + "] ").withStyle(s -> s.withColor(color))
                    .append(Component.literal(entry.getString("name")).withStyle(s -> s.withColor(TEXT & 0xFFFFFF)));
            boolean frozen = entry.getBoolean("frozen");
            add(TextNode.of(head).at(5, 3).maxWidth((int) width - (frozen ? 18 : 10)).ellipsis(true).shadow(false));
            if (frozen) {
                add(TextNode.of(Component.literal("❄")).at(width - 4, 3).align(TextNode.Align.RIGHT).color(BAD).shadow(false));
            }
            add(TextNode.of(Component.translatable("gui.eden_ld.moderation.admin.group_row",
                            entry.getList("members", Tag.TAG_COMPOUND).size(), entry.getString("leader")))
                    .at(5, 13).maxWidth((int) width - 10).ellipsis(true).color(DIM).shadow(false));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            UUID id = entry.getUUID("id");
            if (!id.equals(selected)) {
                select(id);
            }
            screen.rebuildNow();
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }

    /** A read-only vault slot: the item and its tooltip, nothing to click. */
    private static final class VaultSlot extends Widget {
        private final ItemStack stack;

        VaultSlot(ItemStack stack) {
            super(SLOT, SLOT);
            this.stack = stack;
            sizing(Sizing.fixed(SLOT), Sizing.fixed(SLOT));
            tooltip(() -> stack.isEmpty() ? List.of()
                    : net.minecraft.client.gui.screens.Screen.getTooltipFromItem(Minecraft.getInstance(), stack));
        }

        @Override
        protected void rebuild() {
            add(Shapes.rect(0, 0, width, height).fill(0xFF14161A).stroke(Stroke.of(hovered && !stack.isEmpty() ? ACCENT : LINE, 1)));
            if (!stack.isEmpty()) {
                add(ItemStackNode.of(stack, 1, 1, 16).stackTooltip(false));
            }
        }
    }
}
