package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.PartyAction;
import net.ledok.eden_ld.hub.HubPayloads.PartyAction.Action;
import net.ledok.eden_ld.hub.HubPayloads.PartyData;
import net.ledok.eden_ld.hub.HubPayloads.PartyMember;
import net.ledok.eden_ld.hub.party.Party;
import net.ledok.eden_ld.hub.party.PartyRank;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
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
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Party tab: members + selected-member card (left), party chat (center),
 * invites and join requests (right); a no-party state with create + invites;
 * a settings dialog for leader/vice. Every action is a payload the server
 * re-validates; the server answers with fresh {@link PartyData} for everyone.
 */
final class PartyTab {

    /** The members column's width until the player drags the divider beside it. */
    private static final float MEMBERS_WIDTH = 118;
    private static final float MEMBERS_MIN = 96;
    private static final float CHAT_MIN = 150;
    /** The invites column (the emoji picker takes its place while open). */
    private static final float SIDE_WIDTH = 108;
    private static final float GAP = 8;
    private static final float LANE = 6;

    private static UUID selectedMember;
    /** The chat field and its unsent text survive rebuilds (pushes land while typing). */
    private static TextField chatInput;
    private static boolean refocusChat;
    private static String chatDraft = "";
    /** The chat's emoji picker; while open it takes the invites column's place. */
    private static final HubEmojiPicker.State PICKER = new HubEmojiPicker.State();

    private PartyTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.party." + key, args);
    }

    private static void send(PartyAction action) {
        ClientPlayNetworking.send(action);
    }

    static void build(HubScreen screen, Flex content) {
        PartyData data = HubState.partyData;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"),
                    EdenParchment.INK_DIM));
            content.item(center);
            return;
        }
        if (data.inParty()) {
            buildInParty(screen, content, data);
        } else {
            buildNoParty(screen, content, data);
        }
    }

    // ---------------------------------------------------------------- no party

    private static void buildNoParty(HubScreen screen, Flex content, PartyData data) {
        Flex box = Flex.column().gap(8).alignItems(Align.CENTER).justify(Justify.CENTER);
        box.sizing(Sizing.fill(), Sizing.expand());

        box.item(EdenParchment.text(tr("no_party"), EdenParchment.INK_MID));
        // There is no explicit create: inviting someone creates the party on acceptance.
        box.item(EdenParchment.text(tr("no_party_hint"), EdenParchment.INK_DIM, 260));

        Button inviteButton = new Button(120, 16, tr("invite_menu"),
                () -> openInviteMenu(screen, data));
        inviteButton.enabled(!data.busy() && !data.inviteCandidates().isEmpty());
        if (data.busy()) {
            inviteButton.tooltip(tr("busy"));
        }
        box.item(inviteButton);

        if (data.busy()) {
            box.item(EdenParchment.text(tr("busy"), EdenParchment.WARN));
        }

        if (!data.outgoingInvites().isEmpty()) {
            box.item(EdenParchment.spacer(2));
            box.item(EdenParchment.text(
                    tr("pending", data.outgoingInvites().size()), EdenParchment.INK_MID));
            for (PartyMember pending : data.outgoingInvites()) {
                Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
                row.item(PlayerFace.of(pending.uuid(), pending.name(), 10));
                row.item(Label.of(96, Component.literal(pending.name())).color(EdenParchment.INK));
                row.item(new Button(14, 12, Component.literal("✖"),
                        () -> send(PartyAction.of(Action.CANCEL_INVITE, pending.uuid()))));
                box.item(row);
            }
        }

        if (!data.myInvites().isEmpty()) {
            box.item(EdenParchment.spacer(2));
            box.item(EdenParchment.text(tr("invites", data.myInvites().size()), EdenParchment.INK));
            for (HubPayloads.InviteRef invite : data.myInvites()) {
                Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
                row.item(PlayerFace.of(invite.inviter(), invite.inviterName(), 10));
                Component label = invite.partyName().isEmpty()
                        ? Component.literal(invite.inviterName())
                        : Component.literal(invite.inviterName() + " — " + invite.partyName());
                row.item(Label.of(140, label).color(EdenParchment.INK));
                Button accept = new Button(16, 14, Component.literal("✔"),
                        () -> send(PartyAction.of(Action.ACCEPT_INVITE, invite.inviter())));
                accept.enabled(!data.busy());
                row.item(accept);
                row.item(new Button(16, 14, Component.literal("✖"),
                        () -> send(PartyAction.of(Action.DECLINE_INVITE, invite.inviter()))));
                box.item(row);
            }
        }

        if (!data.joinable().isEmpty()) {
            box.item(EdenParchment.spacer(2));
            box.item(EdenParchment.text(tr("joinable", data.joinable().size()), EdenParchment.INK));
            for (HubPayloads.PartyRef open : data.joinable()) {
                Flex row = Flex.row().gap(6).alignItems(Align.CENTER);
                row.item(Label.of(140, partyName(open.name(), open.leader()).copy()
                        .append(" (" + open.members() + "/" + open.max() + ")")).color(EdenParchment.INK));
                Button joinButton = EdenParchment.fitButton(tr("join"), 40, 14,
                        () -> send(PartyAction.of(Action.JOIN, open.id())));
                joinButton.enabled(!data.busy());
                row.item(joinButton);
                box.item(row);
            }
        }
        content.item(box);
    }

    // ---------------------------------------------------------------- in party

    private static void buildInParty(HubScreen screen, Flex content, PartyData data) {
        // Members | chat + invites, the divider dragged to give the chat more room (or the members).
        var split = screen.splitLeft("party/members", MEMBERS_WIDTH, MEMBERS_MIN, CHAT_MIN + GAP + SIDE_WIDTH, GAP);
        float[] widths = split.sizesFor(HubScreen.innerWidth());
        split.first().item(leftColumn(screen, data, widths[0]));

        Flex rest = Flex.row().gap(GAP);
        rest.sizing(Sizing.fill(), Sizing.fill());
        boolean picker = HubEmojiPicker.showing(PICKER);
        float chatWidth = widths[1] - GAP - (picker ? HubEmojiPicker.WIDTH : SIDE_WIDTH);
        rest.item(centerColumn(screen, data, chatWidth));
        if (picker) {
            rest.item(HubEmojiPicker.panel(screen, PICKER, () -> chatInput, PartyTab::refresh));
        } else {
            rest.item(rightColumn(screen, data));
        }
        split.second().item(rest);
        content.item(split);
    }

    private static Flex leftColumn(HubScreen screen, PartyData data, float width) {
        Flex column = Flex.column().gap(6);
        column.sizing(Sizing.fill(), Sizing.fill());

        Flex members = Flex.column().gap(1).padding(Insets.of(4, 4, 4, 4));
        members.sizing(Sizing.fill(), Sizing.expand());
        members.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        members.item(EdenParchment.text(
                tr("members", data.members().size(), data.maxSize()), EdenParchment.INK_MID));
        // By rank as the server sends them; within a rank, ignored members after the others online.
        List<PartyMember> ordered = new ArrayList<>(data.members());
        ordered.sort(java.util.Comparator.comparingInt((PartyMember m) -> -m.rank())
                .thenComparingInt(m -> Ignoring.order(m.uuid(), m.online())));
        for (PartyMember member : ordered) {
            boolean selected = member.uuid().equals(selectedMember);
            String prefix = member.rank() == PartyRank.LEADER.level ? "♛ "
                    : member.rank() == PartyRank.VICE.level ? "★ " : "";
            if (HubState.ignores(member.uuid())) {
                prefix = prefix + Ignoring.MARK;
            }
            Flex row = Flex.row().gap(2).alignItems(Align.CENTER);
            row.item(PlayerFace.of(member.uuid(), member.name(), 11));
            Button button = new Button(width - 29, 13,
                    Component.literal(prefix + member.name()),
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
        column.item(members);
        column.item(memberCard(screen, data, width));
        return column;
    }

    private static Flex memberCard(HubScreen screen, PartyData data, float width) {
        float buttons = width - 22;
        Flex card = Flex.column().gap(3).padding(Insets.of(4, 5, 4, 5));
        card.sizing(Sizing.fill(), Sizing.content());
        card.background(EdenParchment.framed(EdenParchment.ROW_BG_ALT, EdenParchment.HAIRLINE));

        PartyMember member = data.members().stream()
                .filter(m -> m.uuid().equals(selectedMember))
                .findFirst().orElse(null);
        if (member == null) {
            card.item(EdenParchment.text(tr("summary",
                            data.members().size(), data.maxSize()), EdenParchment.INK_DIM, (int) (width - 18)));
            return card;
        }

        Label cardName = Label.of(width - 12, Component.literal(member.name()));
        cardName.color(EdenParchment.INK);
        cardName.tooltipWhenCut(true);
        card.item(cardName);
        String rankKey = member.rank() == PartyRank.LEADER.level ? "rank_leader"
                : member.rank() == PartyRank.VICE.level ? "rank_vice" : "rank_member";
        card.item(EdenParchment.text(tr(rankKey), EdenParchment.INK_DIM));
        card.item(EdenParchment.text(member.online()
                        ? Component.literal(member.dimension())
                        : tr("offline"), EdenParchment.INK_DIM));
        card.item(profileButton(screen, member.uuid(), member.profile(), buttons));

        boolean self = isSelf(member.uuid());
        boolean iAmLeader = data.myRank() == PartyRank.LEADER.level;
        boolean iManage = data.myRank() >= PartyRank.VICE.level;
        if (!self) {
            Flex actions = Flex.column().gap(2);
            if (iAmLeader && member.rank() == PartyRank.MEMBER.level) {
                actions.item(new Button(buttons, 13, tr("promote"),
                        () -> send(PartyAction.of(Action.PROMOTE, member.uuid()))));
            }
            if (iAmLeader && member.rank() == PartyRank.VICE.level) {
                actions.item(new Button(buttons, 13, tr("demote"),
                        () -> send(PartyAction.of(Action.DEMOTE, member.uuid()))));
            }
            if (iAmLeader) {
                actions.item(new Button(buttons, 13, tr("transfer"),
                        () -> send(PartyAction.of(Action.TRANSFER, member.uuid()))));
            }
            if (iManage && member.rank() < data.myRank()) {
                actions.item(new Button(buttons, 13, tr("kick"),
                        () -> send(PartyAction.of(Action.KICK, member.uuid()))));
            }
            card.item(actions);
        } else {
            card.item(new Button(buttons, 13, tr("leave"),
                    () -> send(PartyAction.of(Action.LEAVE))));
        }
        return card;
    }

    /** Opens the member's profile; greyed out when their privacy setting says no. */
    static Button profileButton(HubScreen screen, UUID target, boolean allowed, float width) {
        Button button = new Button(width, 13, Component.translatable("gui.eden_ld.hub.profile.open"),
                () -> screen.openProfile(target));
        button.enabled(allowed);
        if (!allowed) {
            button.tooltip(Component.translatable("gui.eden_ld.hub.profile.private_hint"));
        }
        return button;
    }

    private static Flex centerColumn(HubScreen screen, PartyData data, float width) {
        Flex column = Flex.column().gap(4);
        column.sizing(Sizing.fixed(width), Sizing.fill());

        Flex header = Flex.row().gap(6).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        // One line with ellipsis: a runaway party name must not push the header out.
        Label name = Label.of(width - 22, partyName(data.name(), data.members().stream()
                .filter(m -> m.rank() == PartyRank.LEADER.level).map(HubPayloads.PartyMember::name)
                .findFirst().orElse("?")));
        name.color(EdenParchment.INK);
        name.tooltipWhenCut(true);
        header.item(name);
        header.spacer();
        if (data.myRank() >= PartyRank.VICE.level) {
            header.item(new Button(16, 14, Component.literal("⚙"),
                    () -> openSettings(screen, data)));
        }
        column.item(header);
        column.item(EdenParchment.hairline());

        Flex chat = Flex.column().gap(2).padding(Insets.of(2, 3, 2, 3));
        int shownLines = 0;
        for (HubPayloads.ChatEntry entry : HubState.partyChat) {
            if (HubState.ignores(entry.sender())) {
                continue;
            }
            shownLines++;
            Flex line = Flex.row().gap(3);
            line.item(PlayerFace.of(entry.sender(), entry.name(), 8));
            // Hover and click work as in chat (items, spoilers, emojis, links, :pos:).
            line.item(ChatText.message(PlayerNames.chatName(entry.sender(), entry.name()).copy().append(": ")
                    .append(entry.text()), (int) (width - 8 - 3 - 6 - LANE)));
            chat.item(line);
        }
        if (shownLines == 0) {
            chat.item(EdenParchment.text(tr("chat_empty"), EdenParchment.INK_DIM, (int) (width - 6 - LANE)));
        }
        ScrollPanel chatPanel = new ScrollPanel(width, 100, chat);
        chatPanel.sizing(Sizing.fill(), Sizing.expand());
        chatPanel.scrollTo(Float.MAX_VALUE);
        column.item(chatPanel);

        Flex compose = Flex.row().gap(4).alignItems(Align.CENTER);
        compose.sizing(Sizing.fill(), Sizing.content());
        // Any push (chat line, someone joining) rebuilds the tab with a new field: carry the
        // unsent draft and the keyboard focus over to it.
        boolean hadFocus = refocusChat || chatInput != null && chatInput.isFocused();
        refocusChat = false;
        TextField input = new TextField(120, chatDraft, text -> chatDraft = text);
        input.sizing(Sizing.expand(), Sizing.fixed(16));
        input.maxLength(256);
        input.hint(Component.translatable("gui.eden_ld.hub.party.chat_hint").getString());
        Runnable sendChat = () -> {
            String message = input.text().trim();
            if (!message.isEmpty()) {
                send(PartyAction.of(Action.CHAT, net.ledok.eden_ld.client.chat.EmojiFont.expandStickers(message, 256)));
                input.text("");
                chatDraft = "";
                // The Send button took the focus; the echoed line's rebuild gives it back.
                refocusChat = true;
            }
        };
        input.onSubmit(sendChat);
        compose.item(input);
        if (HubEmojiPicker.available()) {
            compose.item(HubEmojiPicker.button(PICKER, PartyTab::refresh));
        }
        compose.item(EdenParchment.fitButton(tr("send"), 34, 16, sendChat));
        column.item(compose);
        chatInput = input;
        ChatMentions.target(input, () -> HubState.partyData == null ? java.util.List.of()
                : HubState.partyData.members().stream().map(PartyMember::name)
                        .filter(member -> net.minecraft.client.Minecraft.getInstance().player == null
                                || !net.minecraft.client.Minecraft.getInstance().player.getGameProfile().getName().equals(member))
                        .toList());
        if (hadFocus) {
            screen.canvas().focus(input);
        }
        return column;
    }

    /** A party's custom name, or "<leader>'s Party" in the reader's language. */
    static Component partyName(String name, String leader) {
        return name.isEmpty() ? tr("default_name", leader) : Component.literal(name);
    }

    private static Flex rightColumn(HubScreen screen, PartyData data) {
        Flex column = Flex.column().gap(6);
        column.sizing(Sizing.fixed(SIDE_WIDTH), Sizing.fill());

        Flex invites = Flex.column().gap(2).padding(Insets.of(4, 4, 4, 4));
        invites.sizing(Sizing.fill(), Sizing.expand());
        invites.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        // Always there, so inviting is discoverable; greyed out (with the reason) when it can't be used.
        Button inviteButton = new Button(96, 14, tr("invite_menu"),
                () -> openInviteMenu(screen, data));
        inviteButton.enabled(!data.busy() && !data.inviteCandidates().isEmpty());
        if (data.busy()) {
            inviteButton.tooltip(tr("busy"));
        } else if (data.inviteCandidates().isEmpty()) {
            inviteButton.tooltip(tr("no_candidates"));
        }
        invites.item(inviteButton);
        // Wrapped to the column (108 less padding): "Надіслані запрошення (0)" didn't fit.
        invites.item(EdenParchment.text(
                tr("pending", data.outgoingInvites().size()), EdenParchment.INK_MID, 100));
        for (PartyMember pending : data.outgoingInvites()) {
            Flex row = Flex.row().gap(3).alignItems(Align.CENTER);
            row.item(Label.of(76, Component.literal(pending.name())).color(EdenParchment.INK));
            row.item(new Button(14, 12, Component.literal("✖"),
                    () -> send(PartyAction.of(Action.CANCEL_INVITE, pending.uuid()))));
            invites.item(row);
        }
        column.item(invites);
        return column;
    }

    // ---------------------------------------------------------------- invite menu

    /** Searchable invite picker: online friends first, other online players below. */
    private static void openInviteMenu(HubScreen screen, PartyData data) {
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(196), Sizing.content());
        dialog.background(EdenParchment.panel());

        dialog.item(EdenParchment.text(tr("invite_menu"), EdenParchment.INK));
        dialog.item(EdenParchment.hairline());

        Flex list = Flex.column().gap(2);
        Modal[] modal = new Modal[1];
        TextField search = new TextField(172, "",
                query -> fillInviteList(list, data, query, modal));
        search.hint(Component.translatable("gui.eden_ld.hub.party.invite_search").getString());
        dialog.item(search);

        ScrollPanel panel = new ScrollPanel(172, 128, list);
        dialog.item(panel);
        fillInviteList(list, data, "", modal);

        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    private static void fillInviteList(Flex list, PartyData data, String query, Modal[] modal) {
        list.clear();
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        List<PartyMember> friends = new ArrayList<>();
        List<PartyMember> others = new ArrayList<>();
        for (PartyMember candidate : data.inviteCandidates()) {
            if (!needle.isEmpty()
                    && !candidate.name().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                continue;
            }
            (candidate.friend() ? friends : others).add(candidate);
        }
        // Ignored players last.
        java.util.Comparator<PartyMember> byName = java.util.Comparator
                .comparing((PartyMember m) -> HubState.ignores(m.uuid()))
                .thenComparing(m -> m.name().toLowerCase(java.util.Locale.ROOT));
        friends.sort(byName);
        others.sort(byName);

        if (!friends.isEmpty()) {
            list.item(EdenParchment.text(tr("friends_heading"), EdenParchment.INK_MID));
            friends.forEach(candidate -> list.item(inviteRow(candidate, data, modal)));
        }
        if (!others.isEmpty()) {
            list.item(EdenParchment.text(tr("players_heading"), EdenParchment.INK_MID));
            others.forEach(candidate -> list.item(inviteRow(candidate, data, modal)));
        }
        if (friends.isEmpty() && others.isEmpty()) {
            list.item(EdenParchment.text(tr("no_candidates"), EdenParchment.INK_DIM, 150));
        }
    }

    private static Flex inviteRow(PartyMember candidate, PartyData data, Modal[] modal) {
        Flex row = Flex.row().gap(3).alignItems(Align.CENTER);
        row.item(PlayerFace.of(candidate.uuid(), candidate.name(), 10));
        // The picker list is 172 wide: face, gaps, and scrollbar lane come off the name.
        float inviteWidth = EdenParchment.fitWidth(tr("invite"), 44);
        boolean ignored = HubState.ignores(candidate.uuid());
        Label name = Label.of(172 - 8 - 10 - 6 - inviteWidth,
                Component.literal((ignored ? Ignoring.MARK : "") + candidate.name()));
        name.color(ignored ? EdenParchment.INK_DIM : EdenParchment.INK);
        name.tooltipWhenCut(true);
        row.item(name);
        Button invite = new Button(inviteWidth, 12, tr("invite"), () -> {
            send(PartyAction.of(Action.INVITE, candidate.uuid()));
            if (modal[0] != null) {
                modal[0].close();
            }
        });
        invite.enabled(!data.busy());
        row.item(invite);
        return row;
    }

    // ---------------------------------------------------------------- settings dialog

    private static void openSettings(HubScreen screen, PartyData data) {
        VectorCanvas canvas = screen.canvas();
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(200), Sizing.content());
        dialog.background(EdenParchment.panel());

        dialog.item(EdenParchment.text(tr("settings"), EdenParchment.INK));
        // The permanent handle other parties ally-add you by (display names aren't unique).
        dialog.item(EdenParchment.text(tr("tag", data.tag()), EdenParchment.INK_DIM));
        dialog.item(EdenParchment.hairline());

        Settings form = new Settings();
        // Without a name of its own the party is "<leader>'s Party": shown greyed in the empty field.
        String leader = data.members().stream().filter(m -> m.rank() == PartyRank.LEADER.level)
                .map(PartyMember::name).findFirst().orElse("?");
        form.add(Option.text(tr("name_label"), data.name(), name -> send(PartyAction.of(Action.RENAME, name)))
                .maxLength(24).placeholder(partyName("", leader).getString()).button(tr("rename")));
        form.add(policy("invite_policy", Party.InvitePolicy.values().length, data.invitePolicy(), Action.SET_INVITE_POLICY));
        form.add(policy("join_policy", Party.JoinPolicy.values().length, data.joinPolicy(), Action.SET_JOIN_POLICY));
        dialog.item(new SettingsView(form, 180).layout(SettingsView.Layout.STACKED).rowGap(5));

        if (data.myRank() == PartyRank.LEADER.level) {
            dialog.item(EdenParchment.hairline());
            dialog.item(new Button(180, 14, tr("disband"),
                    () -> send(PartyAction.of(Action.DISBAND))));
        }

        Modal modal = Modal.open(canvas, dialog);
        modal.closeOnClickOutside(true);
    }

    /** Who may invite / how others join: a choice among the policy's values (lang {@code <key>.<n>}). */
    private static Option<?, ?> policy(String key, int count, int value, Action action) {
        List<Integer> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(i);
        }
        return Option.choice(tr(key), values, Math.floorMod(value, count), index -> tr(key + "." + index),
                index -> send(PartyAction.of(action, index.intValue())));
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isSelf(UUID uuid) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return player != null && player.getUUID().equals(uuid);
    }

    /** Rebuild the party tab of the open hub screen, if any. */
    static void refresh() {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof HubScreen screen) {
            screen.onPartyData();
        }
    }
}
