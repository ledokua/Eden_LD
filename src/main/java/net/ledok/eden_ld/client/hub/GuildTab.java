package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.GuildAction;
import net.ledok.eden_ld.hub.HubPayloads.GuildAction.Action;
import net.ledok.eden_ld.hub.HubPayloads.GuildData;
import net.ledok.eden_ld.hub.HubPayloads.GuildInvite;
import net.ledok.eden_ld.hub.HubPayloads.GuildMember;
import net.ledok.eden_ld.hub.HubPayloads.GuildRef;
import net.ledok.eden_ld.hub.HubPayloads.NameRef;
import net.ledok.eden_ld.hub.HubPayloads.PartyMember;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildRank;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.settings.Option;
import net.ledok.vectorlib.client.canvas.settings.Settings;
import net.ledok.vectorlib.client.canvas.settings.SettingsView;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Dropdown;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The Guild tab. Without a guild: create form (left), searchable guild directory
 * (center), invites (right). In a guild: a header (name, level, sub-tabs, settings gear) over
 * the sub-tab — Members (roster + member card, MOTD and chat), Recruiting (officers: invite
 * players, applications, pending invites), Quests, Level, Bank — and a settings dialog
 * (leader: everything, officers: MOTD only). Every action is a payload
 * the server re-validates against ranks; it answers with fresh {@link GuildData}.
 */
final class GuildTab {

    private static final float CREATE_WIDTH = 130;
    private static final float INVITES_WIDTH = 118;
    /** The roster's width until the player drags the divider beside it. */
    private static final float ROSTER_WIDTH = 118;
    private static final float ROSTER_MIN = 96;
    private static final float CHAT_MIN = 200;
    private static final float GAP = 8;
    /** The slim bar that hides/shows the right panel (like the Friends requests bar). */
    private static final float BAR_WIDTH = 14;
    /** No-guild side panels: the create form (open until toggled) and invites (null = open while any wait). */
    private static boolean createOpen = true;
    private static Boolean invitesOpen;
    private static int seenInvites;
    /** Narrowest directory card before the list drops back to one column. */
    private static final float DIRECTORY_CARD_MIN = 118;
    private static final float DIRECTORY_COLUMN_GAP = 4;
    /** Gap between a side panel and its toggle bar. */
    private static final float PAIR_GAP = 2;
    /** A ScrollPanel's scrollbar lane (default bar 5 + 1). */
    private static final float LANE = 6;

    /** Guild colors offered on creation and in settings: bright chat colors that read on parchment and in chat. */
    private static final List<ColorOption> COLORS = List.of(
            color("dark_red", ChatFormatting.DARK_RED),
            color("red", ChatFormatting.RED),
            color("gold", ChatFormatting.GOLD),
            color("yellow", ChatFormatting.YELLOW),
            color("dark_green", ChatFormatting.DARK_GREEN),
            color("green", ChatFormatting.GREEN),
            color("dark_aqua", ChatFormatting.DARK_AQUA),
            color("aqua", ChatFormatting.AQUA),
            color("dark_blue", ChatFormatting.DARK_BLUE),
            color("blue", ChatFormatting.BLUE),
            color("dark_purple", ChatFormatting.DARK_PURPLE),
            color("light_purple", ChatFormatting.LIGHT_PURPLE),
            color("gray", ChatFormatting.GRAY),
            color("white", ChatFormatting.WHITE));

    private static UUID selectedMember;
    /** The chat field and its unsent text survive rebuilds (pushes land while typing). */
    private static TextField chatInput;
    private static boolean refocusChat;
    private static String chatDraft = "";
    /** The chat's emoji picker: beside the chat, only while the right panel is collapsed (it takes that room). */
    private static final HubEmojiPicker.State PICKER = new HubEmojiPicker.State();

    // Create form and directory search survive tab rebuilds (pushes land while typing).
    private static String createName = "";
    private static String createTag = "";
    private static int createColor = COLORS.get(2).rgb();
    private static TextField nameField;
    private static TextField tagField;
    private static String directoryQuery = "";
    private static TextField directoryField;

    private record ColorOption(String key, int rgb) {
    }

    private static ColorOption color(String key, ChatFormatting formatting) {
        Integer rgb = formatting.getColor();
        return new ColorOption(key, rgb == null ? 0xFFFFFF : rgb);
    }

    private GuildTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.guild." + key, args);
    }

    private static void send(GuildAction action) {
        ClientPlayNetworking.send(action);
    }

    static void build(HubScreen screen, Flex content) {
        GuildData data = HubState.guildData;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"),
                    EdenParchment.INK_DIM));
            content.item(center);
            return;
        }
        Flex columns = Flex.row().gap(GAP);
        columns.sizing(Sizing.fill(), Sizing.expand());
        if (!data.inGuild() && HubState.guildView != GuildView.MEMBERS) {
            // Left (or kicked from) the guild while on another sub-tab.
            if (HubState.guildView == GuildView.BANK) {
                GuildBankView.closed();
            }
            HubState.guildView = GuildView.MEMBERS;
        }
        if (data.inGuild()) {
            if (!HubState.guildView.visibleTo(data.myRank())) {
                // Demoted below officer while on Recruiting.
                HubState.guildView = GuildView.MEMBERS;
            }
            content.item(header(screen, data));
            switch (HubState.guildView) {
                case BANK -> {
                    GuildBankView.build(screen, columns, data, HubScreen.innerWidth());
                    content.item(columns);
                    return;
                }
                case RECRUITING -> {
                    recruiting(screen, data, columns);
                    content.item(columns);
                    return;
                }
                case QUESTS -> {
                    GuildQuestsView.build(content);
                    return;
                }
                case LEVEL -> {
                    GuildLevelView.build(content);
                    return;
                }
                case MEMBERS -> {
                }
            }
            // Roster | chat, the divider between them dragged to give the chat more room (or the roster).
            var split = screen.splitLeft("guild/members", ROSTER_WIDTH, ROSTER_MIN, CHAT_MIN, GAP);
            float[] widths = split.sizesFor(HubScreen.innerWidth());
            split.first().item(rosterColumn(screen, data, widths[0]));
            split.second().item(chatColumn(screen, data, widths[1], true));
            content.item(split);
            return;
        } else {
            // A new invite re-arms the automatic open state (open while invites wait).
            if (data.myInvites().size() > seenInvites) {
                invitesOpen = null;
            }
            seenInvites = data.myInvites().size();
            boolean invites = invitesOpen != null ? invitesOpen : !data.myInvites().isEmpty();
            // [create] bar directory bar [invites], like the Friends tab: hidden side panels
            // widen the directory.
            float directoryWidth = HubScreen.innerWidth() - BAR_WIDTH * 2 - PAIR_GAP * 2 - GAP
                    - (createOpen ? CREATE_WIDTH + PAIR_GAP : 0)
                    - (invites ? INVITES_WIDTH + GAP : 0);
            Flex left = Flex.row().gap(PAIR_GAP);
            left.sizing(Sizing.content(), Sizing.fill());
            if (createOpen) {
                left.item(createColumn(screen, data));
            }
            left.item(createBar());
            columns.item(left);
            Flex center = Flex.row().gap(PAIR_GAP);
            center.sizing(Sizing.content(), Sizing.fill());
            center.item(directoryColumn(screen, data, directoryWidth));
            center.item(invitesBar(data, invites));
            columns.item(center);
            if (invites) {
                columns.item(invitesColumn(data));
            }
        }
        content.item(columns);
    }

    // ---------------------------------------------------------------- no guild: create

    private static Flex createColumn(HubScreen screen, GuildData data) {
        Flex box = Flex.column().gap(4).padding(Insets.of(4, 4, 4, 4));
        box.id(HubSpotlights.GUILD_CREATE);
        box.sizing(Sizing.fixed(CREATE_WIDTH), Sizing.fill());
        box.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        float inner = CREATE_WIDTH - 8;

        box.item(EdenParchment.text(tr("create_title"), EdenParchment.INK));
        box.item(EdenParchment.hairline());

        boolean nameFocus = nameField != null && nameField.isFocused();
        boolean tagFocus = tagField != null && tagField.isFocused();

        // Preview of how the guild shows up in lists; the fields keep it current.
        Label preview = Label.of(inner, createPreview());
        preview.color(EdenParchment.INK);
        preview.tooltipWhenCut(true);

        box.item(EdenParchment.text(tr("name"), EdenParchment.INK_DIM));
        TextField name = new TextField(inner, createName, text -> {
            createName = text;
            preview.text(createPreview());
        });
        name.sizing(Sizing.fixed(inner), Sizing.fixed(16));
        name.maxLength(24);
        name.hint(tr("name_hint").getString());
        nameField = name;
        box.item(name);

        box.item(EdenParchment.text(tr("tag"), EdenParchment.INK_DIM));
        TextField tag = new TextField(inner, createTag, text -> {
            createTag = text;
            preview.text(createPreview());
        });
        tag.sizing(Sizing.fixed(inner), Sizing.fixed(16));
        tag.maxLength(5);
        tag.hint(tr("tag_hint").getString());
        tagField = tag;
        box.item(tag);

        box.item(EdenParchment.text(tr("color"), EdenParchment.INK_DIM));
        ColorOption current = COLORS.stream().filter(c -> c.rgb() == createColor).findFirst().orElse(COLORS.get(0));
        box.item(new Dropdown<>(inner, COLORS, current, GuildTab::colorLabel, selected -> {
            createColor = selected.rgb();
            preview.text(createPreview());
        }));
        box.item(preview);

        box.spacer();
        boolean affordable = data.createCost() <= 0 || data.economy();
        box.item(EdenParchment.text(data.createCost() > 0 ? tr("cost", data.createCost()) : tr("free"),
                affordable ? EdenParchment.INK_MID : EdenParchment.DANGER, (int) inner));
        Button create = new Button(inner, 16, tr("create"),
                () -> send(GuildAction.create(createName.trim(), createTag.trim(), createColor)));
        // Name/tag rules are checked server side; the reply explains what to fix.
        create.enabled(affordable);
        if (!affordable) {
            create.tooltip(tr("no_economy"));
        }
        create.id(HubSpotlights.GUILD_CREATE_BUTTON);
        box.item(create);

        if (nameFocus) {
            screen.canvas().focus(name);
        } else if (tagFocus) {
            screen.canvas().focus(tag);
        }
        return box;
    }

    // ---------------------------------------------------------------- no guild: directory

    private static Flex directoryColumn(HubScreen screen, GuildData data, float width) {
        Flex column = Flex.column().gap(3);
        column.id(HubSpotlights.GUILD_DIRECTORY);
        column.sizing(Sizing.fixed(width), Sizing.fill());
        float rowWidth = width - LANE;

        column.item(EdenParchment.text(tr("directory", data.directory().size()), EdenParchment.INK_MID));

        boolean hadFocus = directoryField != null && directoryField.isFocused();
        Flex list = Flex.column().gap(2);
        TextField search = new TextField(width, directoryQuery, query -> {
            directoryQuery = query;
            fillDirectory(list, data, rowWidth);
        });
        search.sizing(Sizing.fixed(width), Sizing.fixed(16));
        search.hint(tr("search").getString());
        directoryField = search;
        search.id(HubSpotlights.GUILD_SEARCH);
        column.item(search);

        ScrollPanel panel = HubScroll.panel("guild/directory", width, 100, list);
        panel.sizing(Sizing.fixed(width), Sizing.expand());
        column.item(panel);
        fillDirectory(list, data, rowWidth);

        if (hadFocus) {
            screen.canvas().focus(search);
        }
        return column;
    }

    private static void fillDirectory(Flex list, GuildData data, float rowWidth) {
        list.clear();
        String needle = directoryQuery.trim().toLowerCase(Locale.ROOT);
        // As many cards per line as fit: two once a hidden side panel leaves room, three in a wide hub.
        int gridColumns = Math.max(1, Math.min(3,
                (int) ((rowWidth + DIRECTORY_COLUMN_GAP) / (DIRECTORY_CARD_MIN + DIRECTORY_COLUMN_GAP))));
        float cardWidth = (rowWidth - DIRECTORY_COLUMN_GAP * (gridColumns - 1)) / gridColumns;
        int shown = 0;
        Flex line = null;
        for (GuildRef guild : data.directory()) {
            if (!needle.isEmpty()
                    && !guild.name().toLowerCase(Locale.ROOT).contains(needle)
                    && !guild.tag().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            // Shading alternates per line, so neighbors in a line match.
            Flex card = directoryRow(guild, data, cardWidth, shown / gridColumns % 2 == 1);
            if (gridColumns == 1) {
                list.item(card);
            } else {
                if (shown % gridColumns == 0) {
                    line = Flex.row().gap(DIRECTORY_COLUMN_GAP);
                    list.item(line);
                }
                line.item(card);
            }
            shown++;
        }
        if (shown == 0) {
            list.item(EdenParchment.text(tr(data.directory().isEmpty() ? "no_guilds" : "no_matches"),
                    EdenParchment.INK_DIM, (int) rowWidth));
        }
    }

    /** Two-line card: "[TAG] Name" on top, size + join mode and the action below. */
    private static Flex directoryRow(GuildRef guild, GuildData data, float width, boolean alt) {
        // Roomier bottom/right than top/left so the action button sits clear of the frame.
        Flex card = Flex.column().gap(2).padding(Insets.of(3, 5, 4, 4));
        card.sizing(Sizing.fixed(width), Sizing.content());
        card.background(EdenParchment.framed(alt ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG,
                EdenParchment.HAIRLINE));
        float inner = width - 9;

        Label title = Label.of(inner, guildTitle(guild.tag(), guild.name(), guild.color()));
        title.color(EdenParchment.INK);
        title.tooltipWhenCut(true);
        card.item(title);

        Flex bottom = Flex.row().gap(3).alignItems(Align.CENTER);
        bottom.sizing(Sizing.fixed(inner), Sizing.content());
        boolean full = guild.members() >= guild.limit();
        Guild.JoinMode mode = joinMode(guild.joinMode());
        Button action = switch (mode) {
            case OPEN -> {
                Button join = EdenParchment.fitButton(tr("join"), 34, 12,
                        () -> send(GuildAction.of(Action.JOIN, guild.id())));
                join.enabled(!full);
                yield join;
            }
            case APPLICATIONS -> {
                if (data.myApplications().contains(guild.id())) {
                    yield EdenParchment.fitButton(tr("cancel_application"), 34, 12,
                            () -> send(GuildAction.of(Action.CANCEL_APPLICATION, guild.id())));
                }
                Button apply = EdenParchment.fitButton(tr("apply"), 34, 12,
                        () -> send(GuildAction.of(Action.APPLY, guild.id())));
                apply.enabled(!full);
                yield apply;
            }
            case INVITE_ONLY -> null;
        };
        if (action != null && full) {
            action.tooltip(tr("full"));
        }
        float actionWidth = action == null ? 0 : action.bounds().width() + 3;

        Component info = tr("level_short", Math.max(1, guild.level())).copy().append(" · ")
                .append(tr("size", guild.members(), guild.limit()));
        if (mode == Guild.JoinMode.INVITE_ONLY) {
            info = info.copy().append(" · ").append(tr("join_mode." + mode.ordinal()));
        }
        Label infoLabel = Label.of(inner - actionWidth, info);
        infoLabel.color(full ? EdenParchment.WARN : EdenParchment.INK_DIM);
        infoLabel.pickable(true);
        infoLabel.tooltip(tr("join_mode." + mode.ordinal()), tr("size", guild.members(), guild.limit()));
        bottom.item(infoLabel);
        bottom.spacer();
        if (action != null) {
            bottom.item(action);
        }
        card.item(bottom);
        return card;
    }

    // ---------------------------------------------------------------- no guild: side bars

    /** Left bar: hides/shows the create form. */
    private static Button createBar() {
        Button bar = new Button(BAR_WIDTH, 40, Component.literal(createOpen ? "‹" : "›"), () -> {
            createOpen = !createOpen;
            if (Minecraft.getInstance().screen instanceof HubScreen hub) {
                hub.onGuildData();
            }
        });
        bar.sizing(Sizing.fixed(BAR_WIDTH), Sizing.fill());
        bar.tooltip(tr("create_toggle"));
        bar.id(HubSpotlights.GUILD_CREATE_TOGGLE);
        return bar;
    }

    /** Right bar: hides/shows the invites panel; collapsed with invites waiting, it shows their count. */
    private static Button invitesBar(GuildData data, boolean open) {
        int invites = data.myInvites().size();
        String label = open ? "›" : invites > 0 ? String.valueOf(invites) : "‹";
        Button bar = new Button(BAR_WIDTH, 40, Component.literal(label), () -> {
            invitesOpen = !open;
            if (Minecraft.getInstance().screen instanceof HubScreen hub) {
                hub.onGuildData();
            }
        });
        bar.sizing(Sizing.fixed(BAR_WIDTH), Sizing.fill());
        bar.tooltip(tr("invites_toggle", invites));
        bar.id(HubSpotlights.GUILD_INVITES_TOGGLE);
        return bar;
    }

    // ---------------------------------------------------------------- no guild: invites

    private static Flex invitesColumn(GuildData data) {
        Flex list = Flex.column().gap(2);
        float panelWidth = INVITES_WIDTH - 6;
        float rowWidth = panelWidth - LANE;

        list.item(EdenParchment.text(tr("invites", data.myInvites().size()), EdenParchment.INK));
        if (data.myInvites().isEmpty()) {
            list.item(EdenParchment.text(tr("no_invites"), EdenParchment.INK_DIM, (int) rowWidth));
        }
        for (GuildInvite invite : data.myInvites()) {
            GuildRef guild = invite.guild();
            Flex row = Flex.row().gap(2).alignItems(Align.CENTER);
            Label name = Label.of(rowWidth - 14 * 2 - 4, guildTitle(guild.tag(), guild.name(), guild.color()));
            name.color(EdenParchment.INK);
            name.pickable(true);
            name.tooltip(guildTitle(guild.tag(), guild.name(), guild.color()),
                    tr("invited_by", invite.inviterName()),
                    tr("size", guild.members(), guild.limit()));
            row.item(name);
            Button accept = new Button(14, 12, Component.literal("✔"),
                    () -> send(GuildAction.of(Action.ACCEPT_INVITE, guild.id())));
            accept.tooltip(tr("accept"));
            accept.enabled(guild.members() < guild.limit());
            row.item(accept);
            Button decline = new Button(14, 12, Component.literal("✖"),
                    () -> send(GuildAction.of(Action.DECLINE_INVITE, guild.id())));
            decline.tooltip(tr("decline"));
            row.item(decline);
            list.item(row);
        }

        ScrollPanel panel = HubScroll.panel("guild/invites", panelWidth, 100, list);
        panel.sizing(Sizing.fixed(panelWidth), Sizing.fill());
        Flex column = Flex.column().padding(Insets.of(3, 3, 3, 3));
        column.id(HubSpotlights.GUILD_INVITES);
        column.sizing(Sizing.fixed(INVITES_WIDTH), Sizing.fill());
        column.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        column.item(panel);
        return column;
    }

    // ---------------------------------------------------------------- in guild: roster

    private static Flex rosterColumn(HubScreen screen, GuildData data, float width) {
        Flex column = Flex.column().gap(6);
        column.sizing(Sizing.fill(), Sizing.fill());

        Flex members = Flex.column().gap(1);
        members.item(EdenParchment.text(
                tr("members", data.members().size(), data.guild().limit()), EdenParchment.INK_MID));
        // By rank, then online, ignored members (after the others online), offline; by name within each.
        List<GuildMember> ordered = new ArrayList<>(data.members());
        ordered.sort(Comparator.comparingInt((GuildMember m) -> -m.rank())
                .thenComparingInt(m -> Ignoring.order(m.uuid(), m.online()))
                .thenComparing(m -> m.name().toLowerCase(Locale.ROOT)));
        for (GuildMember member : ordered) {
            boolean selected = member.uuid().equals(selectedMember);
            Flex row = Flex.row().gap(2).alignItems(Align.CENTER);
            row.item(PlayerFace.of(member.uuid(), member.name(), 11));
            // Offline and ignored members go dim.
            MutableComponent label = Component.literal(rankMark(member.rank()));
            boolean ignored = HubState.ignores(member.uuid());
            label.append(Component.literal((ignored ? Ignoring.MARK : "") + member.name()));
            if (!member.online() || ignored) {
                label.withColor(EdenParchment.INK_DIM & 0xFFFFFF);
            }
            Button button = new Button(width - 8 - LANE - 11 - 2, 13, label,
                    () -> {
                        selectedMember = member.uuid();
                        refresh();
                    });
            // A plain Button draws its label whole; an empty secondary makes it cut long
            // names with "…" inside the frame (full name on hover).
            button.secondary(Component.empty()).cutSecondaryFirst(true);
            button.tooltipWhenCut(true);
            if (selected) {
                button.style(EdenParchment.TAB_ACTIVE);
            }
            row.item(button);
            members.item(row);
        }
        ScrollPanel rosterPanel = HubScroll.panel("guild/roster", width - 8, 100, members);
        rosterPanel.sizing(Sizing.fixed(width - 8), Sizing.expand());

        Flex box = Flex.column().padding(Insets.of(4, 4, 4, 4));
        box.id(HubSpotlights.GUILD_ROSTER);
        box.sizing(Sizing.fill(), Sizing.expand());
        box.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        box.item(rosterPanel);
        column.item(box);
        column.item(memberCard(screen, data, width));
        return column;
    }

    private static Flex memberCard(HubScreen screen, GuildData data, float width) {
        Flex card = Flex.column().gap(3).padding(Insets.of(4, 5, 4, 5));
        card.sizing(Sizing.fill(), Sizing.content());
        card.background(EdenParchment.framed(EdenParchment.ROW_BG_ALT, EdenParchment.HAIRLINE));
        float inner = width - 10;

        GuildMember member = data.members().stream()
                .filter(m -> m.uuid().equals(selectedMember))
                .findFirst().orElse(null);
        if (member == null) {
            card.item(EdenParchment.text(tr("summary", data.members().size(), data.guild().limit()),
                    EdenParchment.INK_DIM, (int) inner));
            return card;
        }

        Label name = Label.of(inner, Component.literal(member.name()));
        name.color(EdenParchment.INK);
        name.tooltipWhenCut(true);
        card.item(name);
        card.item(EdenParchment.text(rankName(member.rank()), EdenParchment.INK_MID));
        card.item(EdenParchment.text(member.online() ? tr("online") : tr("offline"),
                member.online() ? EdenParchment.GOOD : EdenParchment.INK_DIM));
        card.item(PartyTab.profileButton(screen, member.uuid(), member.profile(), inner));

        int my = data.myRank();
        int rank = member.rank();
        boolean leader = my == GuildRank.LEADER.level;
        boolean officer = my >= GuildRank.OFFICER.level;
        if (!isSelf(member.uuid())) {
            Flex actions = Flex.column().gap(2);
            // Mirrors GuildManager: up to one step below your own rank; only ranks below you.
            boolean canPromote = officer && rank + 1 < my;
            boolean canDemote = officer && rank < my && rank > GuildRank.RECRUIT.level;
            if (canPromote) {
                actions.item(new Button(inner, 13, tr("promote"),
                        () -> send(GuildAction.of(Action.PROMOTE, member.uuid()))));
            }
            if (canDemote) {
                actions.item(new Button(inner, 13, tr("demote"),
                        () -> send(GuildAction.of(Action.DEMOTE, member.uuid()))));
            }
            if (leader) {
                // Irreversible from the leader's side — confirm first.
                actions.item(new Button(inner, 13, tr("transfer"), () -> {
                    if (net.minecraft.client.Minecraft.getInstance().screen instanceof HubScreen hub) {
                        confirm(hub, tr("confirm_transfer", member.name()), tr("transfer"),
                                () -> send(GuildAction.of(Action.TRANSFER, member.uuid())));
                    }
                }));
            }
            if (officer && rank < my) {
                actions.item(new Button(inner, 13, tr("kick"),
                        () -> send(GuildAction.of(Action.KICK, member.uuid()))));
            }
            card.item(actions);
        } else {
            Button leave = new Button(inner, 13, tr("leave"), () -> send(GuildAction.of(Action.LEAVE)));
            if (leader && data.members().size() > 1) {
                leave.enabled(false);
                leave.tooltip(tr("leave_transfer_first"));
            }
            card.item(leave);
        }
        return card;
    }

    // ---------------------------------------------------------------- in guild: chat

    private static Flex chatColumn(HubScreen screen, GuildData data, float width, boolean pickerRoom) {
        Flex column = Flex.column().gap(4);
        column.sizing(Sizing.fill(), Sizing.fill());
        GuildRef guild = data.guild();

        if (data.motd().isBlank()) {
            column.item(EdenParchment.text(tr("no_motd"), EdenParchment.INK_DIM));
        } else {
            column.item(EdenParchment.text(Component.literal(data.motd()), EdenParchment.INK_MID, (int) width));
        }
        column.item(EdenParchment.hairline());

        boolean picker = pickerRoom && HubEmojiPicker.showing(PICKER);
        // The open picker sits right of the chat lines, above the chat field.
        float linesWidth = picker ? width - HubEmojiPicker.WIDTH - 4 : width;
        Flex chat = Flex.column().gap(2).padding(Insets.of(2, 3, 2, 3));
        if (HubState.guildChat.stream().allMatch(entry -> HubState.ignores(entry.sender()))) {
            chat.item(EdenParchment.text(tr("chat_empty"), EdenParchment.INK_DIM, (int) (linesWidth - 20)));
        }
        for (HubPayloads.ChatEntry entry : HubState.guildChat) {
            if (HubState.ignores(entry.sender())) {
                continue;
            }
            Flex line = Flex.row().gap(3);
            line.item(PlayerFace.of(entry.sender(), entry.name(), 8));
            // Hover and click work as in chat (items, spoilers, emojis, links, :pos:).
            line.item(ChatText.message(PlayerNames.chatName(entry.sender(), entry.name()).copy().append(": ")
                    .append(entry.text()), (int) (linesWidth - 8 - 3 - 6 - LANE)));
            chat.item(line);
        }
        ScrollPanel chatPanel = new ScrollPanel(linesWidth, 100, chat);
        chatPanel.id(HubSpotlights.GUILD_CHAT);
        chatPanel.scrollTo(Float.MAX_VALUE);
        if (picker) {
            Flex area = Flex.row().gap(4);
            area.sizing(Sizing.fill(), Sizing.expand());
            chatPanel.sizing(Sizing.expand(), Sizing.fill());
            area.item(chatPanel);
            area.item(HubEmojiPicker.panel(screen, PICKER, () -> chatInput, GuildTab::refresh));
            column.item(area);
        } else {
            chatPanel.sizing(Sizing.fill(), Sizing.expand());
            column.item(chatPanel);
        }

        Flex compose = Flex.row().gap(4).alignItems(Align.CENTER);
        compose.sizing(Sizing.fill(), Sizing.content());
        // Any push (chat line, someone joining) rebuilds the tab with a new field: carry the
        // unsent draft and the keyboard focus over to it.
        boolean hadFocus = refocusChat || chatInput != null && chatInput.isFocused();
        refocusChat = false;
        TextField input = new TextField(120, chatDraft, text -> chatDraft = text);
        input.sizing(Sizing.expand(), Sizing.fixed(16));
        input.maxLength(256);
        input.hint(tr("chat_hint").getString());
        Runnable sendChat = () -> {
            String message = input.text().trim();
            if (!message.isEmpty()) {
                send(GuildAction.of(Action.CHAT, net.ledok.eden_ld.client.chat.EmojiFont.expandStickers(message, 256)));
                input.text("");
                chatDraft = "";
                // The Send button took the focus; the echoed line's rebuild gives it back.
                refocusChat = true;
            }
        };
        input.onSubmit(sendChat);
        compose.item(input);
        if (pickerRoom && HubEmojiPicker.available()) {
            compose.item(HubEmojiPicker.button(PICKER, GuildTab::refresh));
        }
        compose.item(EdenParchment.fitButton(tr("send"), 34, 16, sendChat));
        column.item(compose);
        chatInput = input;
        ChatMentions.target(input, () -> HubState.guildData == null ? List.of()
                : HubState.guildData.members().stream().map(GuildMember::name).filter(name -> !isSelfName(name)).toList());
        if (hadFocus) {
            screen.canvas().focus(input);
        }
        return column;
    }

    // ---------------------------------------------------------------- in guild: header + sub-tabs

    /** "[TAG] Name · Lv 3 [xp bar]" left, the sub-tabs and (officers) the settings gear right. */
    private static Flex header(HubScreen screen, GuildData data) {
        GuildRef guild = data.guild();
        Flex header = Flex.row().gap(3).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        List<GuildView> views = new ArrayList<>();
        for (GuildView view : GuildView.values()) {
            if (view.visibleTo(data.myRank())) {
                views.add(view);
            }
        }
        // Tighter than the other rows (4 a side): five sub-tabs share the row with the guild's name.
        net.ledok.vectorlib.client.canvas.widget.TabBar<GuildView> tabs = EdenParchment.tabs(views, view -> {
            Component label = tr("view." + view.name().toLowerCase(Locale.ROOT));
            if (view == GuildView.RECRUITING && !data.applications().isEmpty()) {
                // Waiting applications show on the tab.
                label = label.copy().append(Component.literal(" (" + data.applications().size() + ")")
                        .withColor(EdenParchment.NOTIFY & 0xFFFFFF));
            }
            return label;
        }, views.contains(HubState.guildView) ? HubState.guildView : views.get(0), GuildTab::show, 28, "guild/view")
                .tabPadding(4);
        tabs.id(HubSpotlights.GUILD_SUBTABS);
        float tabsWidth = tabs.width() + 3;
        boolean canSettings = data.myRank() >= GuildRank.OFFICER.level;
        Component level = tr("level_short", Math.max(1, guild.level()));
        float barWidth = Math.max(XP_BAR, Minecraft.getInstance().font.width(level) + 8);
        // One line with ellipsis: a long guild name must not push the sub-tabs out.
        Label name = Label.of(Math.max(40, HubScreen.innerWidth() - tabsWidth - barWidth - 3 - (canSettings ? 19 : 0) - 3),
                guildTitle(guild.tag(), guild.name(), guild.color()));
        name.color(EdenParchment.INK);
        name.tooltipWhenCut(true);
        header.item(name);
        header.item(xpBar(level, barWidth));
        header.spacer();
        header.item(tabs);
        if (canSettings) {
            Button gear = new Button(16, 14, Component.literal("⚙"), () -> openSettings(screen, data));
            gear.tooltip(tr("settings"));
            gear.id(HubSpotlights.GUILD_SETTINGS);
            header.item(gear);
        }
        return header;
    }

    private static final float XP_BAR = 40;

    /**
     * "Lv 3" as text with a thin unframed bar under it toward the next level (full at the top
     * level), XP on hover — not drawn like a button, since it sits beside the sub-tab buttons.
     */
    private static Flex xpBar(Component level, float width) {
        HubPayloads.GuildQuestsData quests = HubState.guildQuests;
        float ratio = quests == null ? 0 : quests.xpNeeded() <= 0 ? 1 : (float) quests.xpInto() / quests.xpNeeded();
        float fill = Math.max(0, Math.min(1, ratio));
        Flex bar = Flex.column().gap(1).alignItems(Align.CENTER);
        bar.id(HubSpotlights.GUILD_XP);
        bar.sizing(Sizing.fixed(width), Sizing.content());
        bar.item(Label.of(width, level).color(EdenParchment.INK_MID).align(net.ledok.vectorlib.client.canvas.TextNode.Align.CENTER));
        Flex track = Flex.row();
        track.sizing(Sizing.fixed(width - 4), Sizing.fixed(3));
        track.background(rect -> {
            net.ledok.vectorlib.client.canvas.CanvasGroup group = new net.ledok.vectorlib.client.canvas.CanvasGroup();
            group.add(net.ledok.vectorlib.client.canvas.Shapes.rect(0, 0, rect.width(), rect.height()).fill(EdenParchment.BAR_TRACK));
            if (fill > 0) {
                group.add(net.ledok.vectorlib.client.canvas.Shapes.rect(0, 0, rect.width() * fill, rect.height()).fill(EdenParchment.BAR_FILL));
            }
            return group;
        });
        bar.item(track);
        bar.pickable(true);
        bar.tooltip(quests == null ? Component.empty()
                : quests.xpNeeded() <= 0 ? tr("level.max")
                : tr("level.xp", quests.xpInto(), quests.xpNeeded()));
        return bar;
    }

    /** Opens a sub-tab: the bank subscribes or unsubscribes, quests and level ask for fresh data. */
    static void show(GuildView view) {
        if (view == HubState.guildView || !(Minecraft.getInstance().screen instanceof HubScreen screen)) {
            return;
        }
        if (HubState.guildView == GuildView.BANK) {
            GuildBankView.closed();
        }
        HubState.guildView = view;
        if (view == GuildView.BANK) {
            GuildBankView.opened(screen);
        } else if (view == GuildView.QUESTS || view == GuildView.LEVEL) {
            ClientPlayNetworking.send(HubPayloads.GuildQuestsRequest.INSTANCE);
        }
        screen.onGuildData();
    }

    // ---------------------------------------------------------------- in guild: recruiting (officers)

    private static final float RECRUIT_GAP = 6;
    private static final float INVITE_COLUMN = 160;
    /** The invite search survives the rebuilds every guild push causes. */
    private static String inviteQuery = "";
    private static TextField inviteSearch;

    /** Invite players | applications | pending invites, side by side. */
    private static void recruiting(HubScreen screen, GuildData data, Flex columns) {
        columns.gap(RECRUIT_GAP);
        float rest = (HubScreen.innerWidth() - INVITE_COLUMN - RECRUIT_GAP * 2) / 2;
        boolean full = data.members().size() >= data.guild().limit();

        // Invite: search + online players without a guild (friends first).
        Flex invite = recruitBox(INVITE_COLUMN);
        invite.item(EdenParchment.text(tr("invite_menu"), EdenParchment.INK));
        invite.item(EdenParchment.text(full ? tr("full") : tr("size", data.members().size(), data.guild().limit()),
                full ? EdenParchment.WARN : EdenParchment.INK_DIM));
        boolean hadFocus = inviteSearch != null && inviteSearch.isFocused();
        Flex list = Flex.column().gap(2);
        float listWidth = INVITE_COLUMN - 8;
        TextField search = new TextField(listWidth, inviteQuery, query -> {
            inviteQuery = query;
            fillInviteList(list, data, query, listWidth, full);
        });
        search.hint(tr("invite_search").getString());
        invite.item(search);
        fillInviteList(list, data, inviteQuery, listWidth, full);
        ScrollPanel invitePanel = HubScroll.panel("guild/invite", listWidth, 100, list);
        invitePanel.sizing(Sizing.fixed(listWidth), Sizing.expand());
        invite.item(invitePanel);
        columns.item(invite);
        inviteSearch = search;
        if (hadFocus) {
            screen.canvas().focus(search);
        }

        // Applications: accept / decline.
        Flex applications = recruitBox(rest);
        applications.item(EdenParchment.text(tr("applications", data.applications().size()), EdenParchment.INK));
        Flex applicationList = Flex.column().gap(2);
        float rowWidth = rest - 8 - LANE;
        if (data.applications().isEmpty()) {
            applicationList.item(EdenParchment.text(tr("no_applications"), EdenParchment.INK_DIM, (int) rowWidth));
        }
        for (NameRef applicant : data.applications()) {
            Flex row = Flex.row().gap(2).alignItems(Align.CENTER);
            row.item(PlayerFace.of(applicant.uuid(), applicant.name(), 10));
            Label name = Label.of(rowWidth - 10 - 14 * 2 - 6, Component.literal(applicant.name()));
            name.color(EdenParchment.INK);
            name.tooltipWhenCut(true);
            row.item(name);
            Button accept = new Button(14, 12, Component.literal("✔"),
                    () -> send(GuildAction.of(Action.ACCEPT_APPLICATION, applicant.uuid())));
            accept.tooltip(full ? tr("full") : tr("accept"));
            accept.enabled(!full);
            row.item(accept);
            Button decline = new Button(14, 12, Component.literal("✖"),
                    () -> send(GuildAction.of(Action.DECLINE_APPLICATION, applicant.uuid())));
            decline.tooltip(tr("decline"));
            row.item(decline);
            applicationList.item(row);
        }
        ScrollPanel applicationPanel = HubScroll.panel("guild/applications", rest - 8, 100, applicationList);
        applicationPanel.sizing(Sizing.fixed(rest - 8), Sizing.expand());
        applications.item(applicationPanel);
        columns.item(applications);

        // Pending invites: cancel.
        Flex pending = recruitBox(rest);
        pending.item(EdenParchment.text(tr("pending", data.outgoing().size()), EdenParchment.INK));
        Flex pendingList = Flex.column().gap(2);
        if (data.outgoing().isEmpty()) {
            pendingList.item(EdenParchment.text(tr("no_pending"), EdenParchment.INK_DIM, (int) rowWidth));
        }
        for (NameRef invited : data.outgoing()) {
            Flex row = Flex.row().gap(2).alignItems(Align.CENTER);
            row.item(PlayerFace.of(invited.uuid(), invited.name(), 10));
            Label name = Label.of(rowWidth - 10 - 14 - 4, Component.literal(invited.name()));
            name.color(EdenParchment.INK_DIM);
            name.tooltipWhenCut(true);
            row.item(name);
            Button cancel = new Button(14, 12, Component.literal("✖"),
                    () -> send(GuildAction.of(Action.CANCEL_INVITE, invited.uuid())));
            cancel.tooltip(tr("cancel_invite"));
            row.item(cancel);
            pendingList.item(row);
        }
        ScrollPanel pendingPanel = HubScroll.panel("guild/pending", rest - 8, 100, pendingList);
        pendingPanel.sizing(Sizing.fixed(rest - 8), Sizing.expand());
        pending.item(pendingPanel);
        columns.item(pending);
    }

    private static Flex recruitBox(float width) {
        Flex box = Flex.column().gap(4).padding(Insets.of(4, 4, 4, 4));
        box.sizing(Sizing.fixed(width), Sizing.fill());
        box.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        return box;
    }

    private static void fillInviteList(Flex list, GuildData data, String query, float width, boolean full) {
        list.clear();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<PartyMember> friends = new ArrayList<>();
        List<PartyMember> others = new ArrayList<>();
        for (PartyMember candidate : data.inviteCandidates()) {
            if (!needle.isEmpty() && !candidate.name().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            (candidate.friend() ? friends : others).add(candidate);
        }
        // Ignored players last.
        Comparator<PartyMember> byName = Comparator.comparing((PartyMember m) -> HubState.ignores(m.uuid()))
                .thenComparing(m -> m.name().toLowerCase(Locale.ROOT));
        friends.sort(byName);
        others.sort(byName);

        if (!friends.isEmpty()) {
            list.item(EdenParchment.text(tr("friends_heading"), EdenParchment.INK_MID));
            friends.forEach(candidate -> list.item(inviteRow(candidate, width, full)));
        }
        if (!others.isEmpty()) {
            list.item(EdenParchment.text(tr("players_heading"), EdenParchment.INK_MID));
            others.forEach(candidate -> list.item(inviteRow(candidate, width, full)));
        }
        if (friends.isEmpty() && others.isEmpty()) {
            list.item(EdenParchment.text(tr("no_candidates"), EdenParchment.INK_DIM, (int) width - 22));
        }
    }

    private static Flex inviteRow(PartyMember candidate, float width, boolean full) {
        Flex row = Flex.row().gap(3).alignItems(Align.CENTER);
        row.item(PlayerFace.of(candidate.uuid(), candidate.name(), 10));
        // Face, gaps, and scrollbar lane come off the name.
        float inviteWidth = EdenParchment.fitWidth(tr("invite"), 44);
        boolean ignored = HubState.ignores(candidate.uuid());
        Label name = Label.of(width - 8 - 10 - 6 - inviteWidth,
                Component.literal((ignored ? Ignoring.MARK : "") + candidate.name()));
        name.color(ignored ? EdenParchment.INK_DIM : EdenParchment.INK);
        name.tooltipWhenCut(true);
        row.item(name);
        Button invite = new Button(inviteWidth, 12, tr("invite"),
                () -> send(GuildAction.of(Action.INVITE, candidate.uuid())));
        invite.enabled(!full);
        if (full) {
            invite.tooltip(tr("full"));
        }
        row.item(invite);
        return row;
    }

    // ---------------------------------------------------------------- settings dialog

    private static void openSettings(HubScreen screen, GuildData data) {
        GuildRef guild = data.guild();
        // Leader and vice-leader edit everything; only the leader disbands.
        boolean manager = data.myRank() >= GuildRank.VICE_LEADER.level;
        boolean leader = data.myRank() == GuildRank.LEADER.level;
        float inner = 200;
        Flex dialog = Flex.column().gap(5).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(inner + 20), Sizing.content());
        dialog.background(EdenParchment.panel());

        dialog.item(EdenParchment.text(tr("settings"), EdenParchment.INK));
        dialog.item(EdenParchment.hairline());

        // Each field saves with its button or Enter (not by clicking away: a rename shouldn't happen by accident).
        Settings form = new Settings();
        if (manager) {
            form.add(Option.text(tr("name"), guild.name(), name -> send(GuildAction.of(Action.RENAME, name.trim())))
                    .maxLength(24).button(tr("rename")));
            form.add(Option.text(tr("tag"), guild.tag(), tag -> send(GuildAction.of(Action.SET_TAG, tag.trim())))
                    .maxLength(5).button(tr("set_tag")));
            List<ColorOption> colors = new ArrayList<>(COLORS);
            ColorOption current = colors.stream().filter(c -> c.rgb() == guild.color()).findFirst().orElse(null);
            if (current == null) {
                // A color set some other way (commands): keep it selectable as "Custom".
                current = new ColorOption("custom", guild.color());
                colors.add(0, current);
            }
            form.add(Option.choice(tr("color"), colors, current, GuildTab::colorLabel,
                    selected -> send(GuildAction.of(Action.SET_COLOR, selected.rgb()))));
            List<Integer> modes = new ArrayList<>();
            for (Guild.JoinMode mode : Guild.JoinMode.values()) {
                modes.add(mode.ordinal());
            }
            form.add(Option.choice(tr("join_mode"), modes, joinMode(guild.joinMode()).ordinal(),
                    mode -> tr("join_mode." + mode), selected -> send(GuildAction.of(Action.SET_JOIN_MODE, selected.intValue()))));
        }
        form.add(Option.text(tr("motd"), data.motd(), motd -> send(GuildAction.of(Action.SET_MOTD, motd.trim())))
                .maxLength(120).placeholder(tr("motd_hint").getString()).button(tr("save")));
        dialog.item(new SettingsView(form, inner).layout(SettingsView.Layout.STACKED).rowGap(5));

        if (leader) {
            dialog.item(EdenParchment.hairline());
            dialog.item(new Button(inner, 14, tr("disband"), () -> confirmDisband(screen, guild)));
        }

        Modal modal = Modal.open(screen.canvas(), dialog);
        modal.closeOnClickOutside(true);
    }

    private static void confirmDisband(HubScreen screen, GuildRef guild) {
        confirm(screen, tr("confirm_disband", guild.name()), tr("disband"), () -> send(GuildAction.of(Action.DISBAND)));
    }

    /** Small yes/cancel dialog for actions that can't be undone. */
    private static void confirm(HubScreen screen, Component message, Component yes, Runnable action) {
        Flex dialog = Flex.column().gap(8).padding(Insets.of(8, 10, 8, 10)).alignItems(Align.CENTER);
        dialog.sizing(Sizing.fixed(180), Sizing.content());
        dialog.background(EdenParchment.panel());
        dialog.item(EdenParchment.text(message, EdenParchment.INK, 160));

        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6);
        buttons.item(EdenParchment.fitButton(yes, 70, 14, () -> {
            action.run();
            modal[0].close();
        }));
        buttons.item(EdenParchment.fitButton(tr("cancel"), 70, 14, () -> modal[0].close()));
        dialog.item(buttons);

        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    // ---------------------------------------------------------------- helpers

    /** "[TAG] Name" with the tag in the guild's color. */
    private static Component guildTitle(String tag, String name, int rgb) {
        return Component.empty()
                .append(Component.literal("[" + tag + "] ").withColor(rgb & 0xFFFFFF))
                .append(Component.literal(name));
    }

    private static Component createPreview() {
        String tag = createTag.isBlank() ? "TAG" : createTag.trim();
        String name = createName.isBlank() ? tr("name_hint").getString() : createName.trim();
        return guildTitle(tag, name, createColor);
    }

    /** A small colored square + the color's name. */
    private static Component colorLabel(ColorOption option) {
        return Component.empty()
                .append(Component.literal("■ ").withColor(option.rgb() & 0xFFFFFF))
                .append(tr("color." + option.key()));
    }

    private static Guild.JoinMode joinMode(int ordinal) {
        Guild.JoinMode[] modes = Guild.JoinMode.values();
        return modes[Math.floorMod(ordinal, modes.length)];
    }

    private static String rankMark(int rank) {
        if (rank == GuildRank.LEADER.level) {
            return "♛ ";
        }
        if (rank == GuildRank.VICE_LEADER.level) {
            return "♚ ";
        }
        if (rank == GuildRank.OFFICER.level) {
            return "★ ";
        }
        return rank == GuildRank.RECRUIT.level ? "· " : "";
    }

    private static Component rankName(int rank) {
        return tr("rank." + GuildRank.of(rank).name().toLowerCase(Locale.ROOT));
    }

    private static boolean isSelfName(String name) {
        var player = Minecraft.getInstance().player;
        return player != null && player.getGameProfile().getName().equals(name);
    }

    private static boolean isSelf(UUID uuid) {
        var player = Minecraft.getInstance().player;
        return player != null && player.getUUID().equals(uuid);
    }

    /** Rebuild the guild tab of the open hub screen, if any. */
    static void refresh() {
        if (Minecraft.getInstance().screen instanceof HubScreen screen) {
            screen.onGuildData();
        }
    }
}
