package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.client.profile.ProfileBackgrounds;
import net.ledok.eden_ld.hub.moderation.AdminTools;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.NumberField;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.Toggle;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.Util;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.ledok.eden_ld.client.hub.ModerationScreen.ACCENT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.GOOD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.LINE;
import static net.ledok.eden_ld.client.hub.ModerationScreen.PANEL;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.text;

/**
 * The Moderation window's Activities section: every dungeon, raid and arena (from Arenas_LD and
 * datapack files), and for the one picked whether the hub shows it, its card art (a profile
 * background) and the level each difficulty needs. New activities are hidden until shown here.
 * Changes save themselves a moment after the last one, like Backgrounds.
 */
final class ActivitiesSection {

    private static final float LIST = 190;
    private static final float ROW_H = 24;
    private static final float ART_W = 62;
    private static final float ART_H = 34;
    private static final long SAVE_DELAY = 600;

    private final ModerationScreen screen;
    private final float width;
    /** id → the activity as the server sent it, with this window's edits on top. */
    private final Map<String, CompoundTag> activities = new LinkedHashMap<>();
    private boolean loaded;
    @Nullable
    private String selected;
    @Nullable
    private String pending;
    private long changedAt;

    ActivitiesSection(ModerationScreen screen, float width) {
        this.screen = screen;
        this.width = width;
    }

    void request() {
        PlayerTabs.request(AdminTools.ACTIVITIES, Util.NIL_UUID);
    }

    /** Returns whether it was this section's data. */
    boolean onData(String area, CompoundTag tag) {
        if (!area.equals(AdminTools.ACTIVITIES)) {
            return false;
        }
        loaded = true;
        CompoundTag kept = pending == null ? null : activities.get(pending);
        activities.clear();
        ListTag list = tag.getList("activities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            activities.put(entry.getString("id"), entry);
        }
        // The one being edited keeps what's typed until it's saved.
        if (kept != null && activities.containsKey(pending)) {
            activities.put(pending, kept);
        }
        return true;
    }

    /** Autosave, from the window's frame. */
    void tick() {
        if (pending != null && System.currentTimeMillis() - changedAt > SAVE_DELAY) {
            flush();
        }
    }

    void flush() {
        if (pending == null) {
            return;
        }
        CompoundTag entry = activities.get(pending);
        pending = null;
        if (entry == null) {
            return;
        }
        CompoundTag args = new CompoundTag();
        args.putString("id", entry.getString("id"));
        args.putBoolean("visible", entry.getBoolean("visible"));
        args.putString("card_art", entry.getString("card_art"));
        args.putInt("level", entry.getInt("level"));
        CompoundTag levels = new CompoundTag();
        ListTag difficulties = entry.getList("difficulties", Tag.TAG_COMPOUND);
        for (int i = 0; i < difficulties.size(); i++) {
            levels.putInt(difficulties.getCompound(i).getString("id"), difficulties.getCompound(i).getInt("level"));
        }
        args.put("levels", levels);
        PlayerTabs.action(AdminTools.ACTIVITIES, Util.NIL_UUID, "save", args);
    }

    private void changed(String id, boolean rebuild) {
        if (pending != null && !pending.equals(id)) {
            flush();
        }
        pending = id;
        changedAt = System.currentTimeMillis();
        if (rebuild) {
            screen.rebuildNow();
        }
    }

    private static Component name(CompoundTag entry) {
        return PlayerTabs.json(entry.getString("name"));
    }

    private static Component type(CompoundTag entry) {
        return Component.translatable("gui.eden_ld.hub.activities.type." + entry.getInt("type"));
    }

    /** "Easy", or the raw id when there's no translation. */
    private static Component difficulty(String id) {
        String key = "gui.eden_ld.moderation.activities.difficulty." + id.toLowerCase(Locale.ROOT);
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(id);
    }

    /** The level it opens at: its first difficulty's, else its own. */
    private static int unlockLevel(CompoundTag entry) {
        ListTag difficulties = entry.getList("difficulties", Tag.TAG_COMPOUND);
        return difficulties.isEmpty() ? entry.getInt("level") : difficulties.getCompound(0).getInt("level");
    }

    // ---------------------------------------------------------------- layout

    void build(Flex main) {
        main.item(text(Component.translatable("gui.eden_ld.moderation.section.activities"), TEXT));
        if (!loaded) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        if (selected == null || !activities.containsKey(selected)) {
            selected = activities.isEmpty() ? null : activities.keySet().iterator().next();
        }
        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.expand());

        Flex rows = Flex.column().gap(2);
        if (activities.isEmpty()) {
            rows.item(text(Component.translatable("gui.eden_ld.moderation.activities.none"), DIM, (int) LIST - 10));
        }
        for (CompoundTag entry : activities.values()) {
            rows.item(new Row(entry));
        }
        // Kept scroll positions: every save brings the server's list back and rebuilds the section.
        ScrollPanel list = HubScroll.panel("mod/activities/list", LIST, 100, rows);
        list.sizing(Sizing.fixed(LIST), Sizing.fill());
        columns.item(list);

        Flex detail = Flex.column().gap(5);
        if (selected != null) {
            buildDetail(detail, activities.get(selected));
        }
        ScrollPanel detailScroll = HubScroll.panel("mod/activities/" + selected, width - LIST - 8, 100, detail);
        detailScroll.sizing(Sizing.expand(), Sizing.fill());
        columns.item(detailScroll);
        main.item(columns);
    }

    private void buildDetail(Flex detail, CompoundTag entry) {
        // Room for the scroll bar.
        float w = width - LIST - 8 - 8;
        String id = entry.getString("id");

        // The card as the hub draws it (art, name; dimmed would be "locked").
        float cardW = Math.min(w, 150);
        float cardH = Math.round(cardW * 74 / 135f);
        CanvasGroup card = new CanvasGroup();
        String art = entry.getString("card_art");
        if (!art.isEmpty() && ProfileBackgrounds.get(art) != null) {
            card.add(ProfileBackgrounds.card(art, cardW, cardH));
        } else {
            card.add(Shapes.rect(0, 0, cardW, cardH).fill(0xFF2C3036));
        }
        card.add(TextNode.of(name(entry)).at(5, 5).maxWidth((int) cardW - 10).ellipsis(true).color(0xFFFFFFFF).shadow(true));
        card.add(Shapes.rect(0, 0, cardW, cardH).stroke(Stroke.of(LINE, 1)));
        Flex framed = Flex.column();
        framed.sizing(Sizing.fixed(cardW), Sizing.fixed(cardH));
        framed.item(card);
        detail.item(framed);

        detail.item(TextNode.of(Component.empty().append(name(entry)).append(
                        Component.literal("  " + id).withStyle(s -> s.withColor(DIM & 0xFFFFFF))))
                .color(TEXT).shadow(false).maxWidth((int) w).ellipsis(true));
        String source = entry.getString("source");
        detail.item(text(source.isEmpty() ? type(entry)
                : Component.empty().append(type(entry)).append(" · ").append(
                Component.translatable("gui.eden_ld.moderation.activities.source", source)), DIM, (int) w));

        Toggle shown = new Toggle(w, Component.translatable("gui.eden_ld.moderation.activities.shown"),
                entry.getBoolean("visible"), value -> {
                    entry.putBoolean("visible", value);
                    changed(id, true);
                });
        // One tooltip line per sentence (tooltips don't wrap).
        shown.tooltip(java.util.Arrays.stream(Component.translatable("gui.eden_ld.moderation.activities.shown_hint")
                .getString().split("\n")).map(Component::literal).toArray(Component[]::new));
        detail.item(shown);

        // Required levels: one per difficulty (arenas have none: one for the whole activity).
        detail.item(text(Component.translatable("gui.eden_ld.moderation.activities.levels"), TEXT, (int) w));
        ListTag difficulties = entry.getList("difficulties", Tag.TAG_COMPOUND);
        if (difficulties.isEmpty()) {
            detail.item(levelRow(Component.translatable("gui.eden_ld.moderation.activities.level_all"),
                    entry.getInt("level"), value -> {
                        // Fields report their value again when rebuilt: only a real change saves.
                        if (value != entry.getInt("level")) {
                            entry.putInt("level", value);
                            changed(id, false);
                        }
                    }));
        }
        for (int i = 0; i < difficulties.size(); i++) {
            CompoundTag difficulty = difficulties.getCompound(i);
            detail.item(levelRow(difficulty(difficulty.getString("id")), difficulty.getInt("level"), value -> {
                if (value != difficulty.getInt("level")) {
                    difficulty.putInt("level", value);
                    changed(id, false);
                }
            }));
        }
        detail.item(text(Component.translatable("gui.eden_ld.moderation.activities.levels_hint"), DIM, (int) w));

        // Card art: any profile background (they're hidden from the profile picker until opened there).
        detail.item(text(Component.translatable("gui.eden_ld.moderation.activities.card_art"), TEXT, (int) w));
        List<Widget> arts = new ArrayList<>();
        arts.add(new ArtChoice(entry, ""));
        for (ProfileBackgrounds.Background background : ProfileBackgrounds.all()) {
            arts.add(new ArtChoice(entry, background.id().toString()));
        }
        detail.item(ModerationScreen.wrapped(arts, w, 3));
        detail.item(text(Component.translatable("gui.eden_ld.moderation.activities.card_art_hint"), DIM, (int) w));
    }

    private static Flex levelRow(Component label, int value, java.util.function.IntConsumer onChange) {
        Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
        row.item(Label.of(70, label).color(TEXT).tooltipWhenCut(true));
        NumberField field = new NumberField(60, value, number -> onChange.accept((int) number));
        field.range(0, net.ledok.eden_ld.hub.activity.ActivitySettings.MAX_LEVEL).step(1);
        field.size(60, 14);
        row.item(field);
        return row;
    }

    /** An activity in the list: name, then type · shown/hidden · the level it opens at. */
    private final class Row extends Widget {
        private final CompoundTag entry;

        Row(CompoundTag entry) {
            super(LIST - 8, ROW_H);
            this.entry = entry;
            sizing(Sizing.fixed(LIST - 8), Sizing.fixed(ROW_H));
            tooltip(() -> List.of(name(entry), Component.literal(entry.getString("id"))));
        }

        @Override
        protected void rebuild() {
            boolean on = entry.getString("id").equals(selected);
            boolean visible = entry.getBoolean("visible");
            add(Shapes.rect(0, 0, width, height).fill(on ? PANEL : 0xFF22252A)
                    .stroke(Stroke.of(on ? ACCENT : hovered ? DIM : LINE, 1)));
            add(TextNode.of(name(entry)).at(4, 3).maxWidth((int) width - 8).ellipsis(true).color(TEXT).shadow(false));
            Component state = Component.empty().append(type(entry)).append(" · ")
                    .append(Component.translatable(visible ? "gui.eden_ld.moderation.activities.state_shown"
                            : "gui.eden_ld.moderation.activities.state_hidden"))
                    .append(" · ").append(Component.translatable("gui.eden_ld.moderation.activities.state_level",
                            unlockLevel(entry)));
            add(TextNode.of(state).at(4, 13).maxWidth((int) ((width - 8) / 0.8f)).ellipsis(true)
                    .color(visible ? GOOD : DIM).shadow(false).scale(0.8f));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            flush();
            selected = entry.getString("id");
            screen.rebuildNow();
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }

    /** A card art choice: a background's thumbnail ("" = none); the picked one is outlined. */
    private final class ArtChoice extends Widget {
        private final CompoundTag entry;
        private final String art;

        ArtChoice(CompoundTag entry, String art) {
            super(ART_W, ART_H);
            this.entry = entry;
            this.art = art;
            sizing(Sizing.fixed(ART_W), Sizing.fixed(ART_H));
            ProfileBackgrounds.Background background = art.isEmpty() ? null : ProfileBackgrounds.get(art);
            tooltip(art.isEmpty() ? Component.translatable("gui.eden_ld.moderation.activities.no_art")
                    : background == null ? Component.literal(art) : background.name());
        }

        @Override
        protected void rebuild() {
            boolean on = art.equals(entry.getString("card_art"));
            if (art.isEmpty()) {
                add(Shapes.rect(0, 0, width, height).fill(0xFF2C3036));
                add(TextNode.of(Component.translatable("gui.eden_ld.moderation.activities.no_art"))
                        .at(width / 2, height / 2 - 4).align(TextNode.Align.CENTER).maxWidth((int) width - 4)
                        .ellipsis(true).color(DIM).shadow(false).scale(0.8f));
            } else {
                add(ProfileBackgrounds.thumbnail(art, width, height, 0));
            }
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1)
                    .stroke(Stroke.of(on ? ACCENT : hovered ? DIM : LINE, on ? 2 : 1)));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            if (!art.equals(entry.getString("card_art"))) {
                entry.putString("card_art", art);
                changed(entry.getString("id"), true);
            }
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }
}
