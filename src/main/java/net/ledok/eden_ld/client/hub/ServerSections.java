package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.hub.moderation.AdminTools;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Dropdown;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.ledok.eden_ld.client.hub.ModerationScreen.ACCENT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.GOOD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.LINE;
import static net.ledok.eden_ld.client.hub.ModerationScreen.PANEL;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.small;
import static net.ledok.eden_ld.client.hub.ModerationScreen.text;

/**
 * The Moderation window's server-wide sections: <b>Guilds</b> and <b>Parties</b> (every one with
 * its members; disband), and <b>Server</b> (reload emojis, write the command list, and the op-only
 * command list — commands only ops may use, whatever their mod allows).
 */
final class ServerSections {

    private static final float LIST = 170;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final ModerationScreen screen;
    private final float width;
    private final Map<String, CompoundTag> data = new HashMap<>();
    @Nullable
    private String selectedGuild;
    @Nullable
    private String selectedParty;
    /** The op-only list as edited here; null until the server's arrives (or after saving). */
    @Nullable
    private List<String> opOnly;
    private boolean opOnlyDirty;

    ServerSections(ModerationScreen screen, float width) {
        this.screen = screen;
        this.width = width;
    }

    void request(String area) {
        PlayerTabs.request(area, Util.NIL_UUID);
    }

    /** Returns whether it was one of these sections' data. */
    boolean onData(String area, CompoundTag tag) {
        if (!area.equals(AdminTools.GUILDS) && !area.equals(AdminTools.PARTIES) && !area.equals(AdminTools.SERVER)) {
            return false;
        }
        data.put(area, tag);
        if (area.equals(AdminTools.SERVER) && !opOnlyDirty) {
            opOnly = PlayerTabs.strings(tag, "op_only");
        }
        return true;
    }

    // ---------------------------------------------------------------- guilds and parties

    void buildGroups(Flex main, String area) {
        boolean guilds = area.equals(AdminTools.GUILDS);
        main.item(text(Component.translatable("gui.eden_ld.moderation.section." + area), TEXT));
        CompoundTag tag = data.get(area);
        if (tag == null) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        ListTag list = tag.getList(area, Tag.TAG_COMPOUND);
        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.expand());
        Flex rows = Flex.column().gap(2);
        if (list.isEmpty()) {
            rows.item(text(Component.translatable("gui.eden_ld.moderation.admin.none_" + area), DIM, (int) LIST - 10));
        }
        CompoundTag open = null;
        String selected = guilds ? selectedGuild : selectedParty;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (selected == null) {
                selected = entry.getString("tag");
            }
            if (entry.getString("tag").equals(selected)) {
                open = entry;
            }
            rows.item(new GroupRow(entry, guilds, entry.getString("tag").equals(selected)));
        }
        if (guilds) {
            selectedGuild = selected;
        } else {
            selectedParty = selected;
        }
        ScrollPanel scroll = new ScrollPanel(LIST, 100, rows);
        scroll.sizing(Sizing.fixed(LIST), Sizing.fill());
        columns.item(scroll);

        Flex detail = Flex.column().gap(4);
        if (open != null) {
            float w = width - LIST - 8 - 10;
            CompoundTag group = open;
            Component title = guilds
                    ? Component.literal("[" + group.getString("tag") + "] ").withStyle(s -> s.withColor(group.getInt("color") & 0xFFFFFF))
                    .append(Component.literal(group.getString("name")).withStyle(s -> s.withColor(TEXT & 0xFFFFFF)))
                    : Component.literal(group.getString("name").isBlank() ? group.getString("tag") : group.getString("name"));
            detail.item(TextNode.of(title).color(TEXT).shadow(false).maxWidth((int) w).ellipsis(true));
            Component info = Component.translatable("gui.eden_ld.moderation.admin.leader", group.getString("leader"));
            if (guilds) {
                info = Component.empty().append(info).append(" · ").append(Component.translatable(
                        "gui.eden_ld.moderation.admin.founded", DATE.format(Instant.ofEpochMilli(group.getLong("created")))));
            }
            detail.item(text(info, DIM, (int) w));
            ListTag members = group.getList("members", Tag.TAG_COMPOUND);
            detail.item(text(Component.translatable("gui.eden_ld.moderation.admin.members", members.size()), TEXT));
            Flex memberList = Flex.column().gap(1);
            for (int i = 0; i < members.size(); i++) {
                CompoundTag member = members.getCompound(i);
                Component line = Component.literal(member.getString("name")).append(
                        Component.literal("  " + member.getString("rank").replace('_', ' ')).withStyle(s -> s.withColor(DIM & 0xFFFFFF)));
                memberList.item(text(Component.literal(member.getBoolean("online") ? "● " : "○ ")
                        .withStyle(s -> s.withColor((member.getBoolean("online") ? GOOD : DIM) & 0xFFFFFF)).append(line), TEXT));
            }
            ScrollPanel memberScroll = new ScrollPanel(w, 100, memberList);
            memberScroll.sizing(Sizing.fill(), Sizing.expand());
            detail.item(memberScroll);
            String label = group.getString(guilds ? "name" : "tag");
            detail.item(small("gui.eden_ld.moderation.admin.disband", 70, () -> PlayerTabs.confirm(screen,
                    Component.translatable("gui.eden_ld.moderation.admin.disband_confirm_" + area, label),
                    "gui.eden_ld.moderation.admin.disband", () -> {
                        CompoundTag args = new CompoundTag();
                        args.putString("tag", group.getString("tag"));
                        PlayerTabs.action(area, Util.NIL_UUID, "disband", args);
                        if (guilds) {
                            selectedGuild = null;
                        } else {
                            selectedParty = null;
                        }
                    })));
        }
        Flex detailBox = Flex.column();
        detailBox.sizing(Sizing.expand(), Sizing.fill());
        detail.sizing(Sizing.fill(), Sizing.fill());
        detailBox.item(detail);
        columns.item(detailBox);
        main.item(columns);
    }

    /** A guild or party in the list: tag, name, member count. */
    private final class GroupRow extends Widget {
        private final CompoundTag entry;
        private final boolean guild;
        private final boolean on;

        GroupRow(CompoundTag entry, boolean guild, boolean on) {
            super(LIST - 8, 24);
            this.entry = entry;
            this.guild = guild;
            this.on = on;
            sizing(Sizing.fixed(LIST - 8), Sizing.fixed(24));
        }

        @Override
        protected void rebuild() {
            add(Shapes.rect(0, 0, width, height).fill(on ? PANEL : 0xFF22252A)
                    .stroke(Stroke.of(on ? ACCENT : hovered ? DIM : LINE, 1)));
            int color = guild ? entry.getInt("color") | 0xFF000000 : TEXT;
            Component head = Component.literal(guild ? "[" + entry.getString("tag") + "] " : "")
                    .withStyle(s -> s.withColor(color & 0xFFFFFF))
                    .append(Component.literal(entry.getString("name").isBlank() ? entry.getString("tag") : entry.getString("name"))
                            .withStyle(s -> s.withColor(TEXT & 0xFFFFFF)));
            add(TextNode.of(head).at(5, 3).maxWidth((int) width - 10).ellipsis(true).shadow(false));
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
            if (guild) {
                selectedGuild = entry.getString("tag");
            } else {
                selectedParty = entry.getString("tag");
            }
            screen.rebuildNow();
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }

    // ---------------------------------------------------------------- server

    void buildServer(Flex main) {
        main.item(text(Component.translatable("gui.eden_ld.moderation.section.server"), TEXT));
        CompoundTag tag = data.get(AdminTools.SERVER);
        if (tag == null) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        float w = width;
        if (tag.getBoolean("emojis")) {
            Flex row = PlayerTabs.row(w);
            row.item(text(Component.translatable("gui.eden_ld.moderation.admin.emoji_reload_hint"), DIM, (int) (w - 110)));
            row.item(PlayerTabs.spacer());
            row.item(small("gui.eden_ld.moderation.admin.emoji_reload", 90,
                    () -> PlayerTabs.action(AdminTools.SERVER, Util.NIL_UUID, "emoji_reload", new CompoundTag())));
            main.item(row);
        }
        if (!tag.getBoolean("commands")) {
            return;
        }
        Flex dump = PlayerTabs.row(w);
        dump.item(text(Component.translatable("gui.eden_ld.moderation.admin.dump_hint"), DIM, (int) (w - 110)));
        dump.item(PlayerTabs.spacer());
        dump.item(small("gui.eden_ld.moderation.admin.dump", 90,
                () -> PlayerTabs.action(AdminTools.SERVER, Util.NIL_UUID, "dump", new CompoundTag())));
        main.item(dump);

        main.item(ModerationScreen.gap(4));
        main.item(text(Component.translatable("gui.eden_ld.moderation.admin.op_only"), TEXT));
        main.item(text(Component.translatable("gui.eden_ld.moderation.admin.op_only_hint"), DIM, (int) w));
        List<String> current = opOnly == null ? List.of() : opOnly;
        List<Widget> chips = new ArrayList<>();
        for (String name : current) {
            Button chip = new Button(Minecraft.getInstance().font.width("/" + name) + 16, 14,
                    Component.literal("/" + name + " ✕"), () -> {
                        List<String> next = new ArrayList<>(current);
                        next.remove(name);
                        opOnly = next;
                        opOnlyDirty = true;
                        screen.rebuildNow();
                    });
            chips.add(chip);
        }
        if (chips.isEmpty()) {
            main.item(text(Component.translatable("gui.eden_ld.moderation.none"), DIM));
        } else {
            Flex list = ModerationScreen.wrapped(chips, w - 10, 3);
            ScrollPanel scroll = new ScrollPanel(w, 70, list);
            scroll.sizing(Sizing.fill(), Sizing.fixed(Math.min(70, list.children().size() * 17)));
            main.item(scroll);
        }
        Flex add = Flex.row().gap(4).alignItems(Align.CENTER);
        add.sizing(Sizing.fill(), Sizing.content());
        List<String> roots = new ArrayList<>(PlayerTabs.strings(tag, "roots"));
        roots.removeAll(current);
        roots.add(0, "");
        Dropdown<String> picker = new Dropdown<>(160, roots, "", name -> name.isEmpty()
                ? Component.translatable("gui.eden_ld.moderation.admin.op_only_add")
                : Component.literal("/" + name), name -> {
            if (!name.isEmpty()) {
                List<String> next = new ArrayList<>(current);
                next.add(name);
                opOnly = next;
                opOnlyDirty = true;
                screen.rebuildNow();
            }
        });
        picker.searchable(true);
        picker.size(160, 14);
        add.item(picker);
        add.item(PlayerTabs.spacer());
        Button revert = small("gui.eden_ld.moderation.news_revert", 50, () -> {
            opOnlyDirty = false;
            opOnly = PlayerTabs.strings(tag, "op_only");
            screen.rebuildNow();
        });
        revert.enabled(opOnlyDirty);
        add.item(revert);
        Button save = small("gui.eden_ld.moderation.save", 60, () -> {
            CompoundTag args = new CompoundTag();
            ListTag names = new ListTag();
            current.forEach(n -> names.add(StringTag.valueOf(n)));
            args.put("names", names);
            opOnlyDirty = false;
            PlayerTabs.action(AdminTools.SERVER, Util.NIL_UUID, "op_only", args);
        });
        save.enabled(opOnlyDirty);
        add.item(save);
        main.item(add);
    }
}
