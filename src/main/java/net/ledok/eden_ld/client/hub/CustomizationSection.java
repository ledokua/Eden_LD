package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.profile.ProfileBackgrounds;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.LookRule;
import net.ledok.eden_ld.hub.profile.LookRules;
import net.ledok.eden_ld.client.theme.HubTheme;
import net.ledok.eden_ld.client.theme.HubThemes;
import net.ledok.eden_ld.client.theme.ThemePreview;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.NumberField;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.Toggle;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.ledok.eden_ld.client.hub.ModerationScreen.ACCENT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.GOOD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.LINE;
import static net.ledok.eden_ld.client.hub.ModerationScreen.PANEL;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.text;

/**
 * The Moderation window's Customization section, with a tab each for profile backgrounds and hub
 * themes (whichever the admin may edit): every one this client has (plus any the server has a rule
 * for) as a picture with its state, and for the one picked a big preview and its rule — locked by
 * default, unlocked for LuckPerms groups, hidden while locked, price. Changes save themselves a
 * moment after the last one.
 */
final class CustomizationSection {

    private static final float LIST = 234;
    private static final float THUMB_W = 74;
    private static final float THUMB_H = 52;
    private static final long SAVE_DELAY = 600;

    private final ModerationScreen screen;
    private final float width;
    private final Map<String, LookRule> backgroundRules = new LinkedHashMap<>();
    private final Map<String, LookRule> themeRules = new LinkedHashMap<>();
    /** The open tab: backgrounds or themes. */
    private LookRules.Kind kind = LookRules.Kind.BACKGROUND;
    @Nullable
    private String selectedBackground;
    @Nullable
    private String selectedTheme;
    /** The look waiting to be saved, and which kind it is. */
    @Nullable
    private String pending;
    private LookRules.Kind pendingKind = LookRules.Kind.BACKGROUND;
    /** The picked background's thumbnail, refreshed when its price changes (no rebuild while typing). */
    @Nullable
    private Thumb selectedThumb;
    private long changedAt;

    CustomizationSection(ModerationScreen screen, float width) {
        this.screen = screen;
        this.width = width;
    }

    /** The server's rules (when nothing waits to be saved here). */
    void load(List<LookRule> backgrounds, List<LookRule> themes) {
        if (pending != null) {
            return;
        }
        backgroundRules.clear();
        backgrounds.forEach(rule -> backgroundRules.put(rule.id(), rule));
        themeRules.clear();
        themes.forEach(rule -> themeRules.put(rule.id(), rule));
    }

    private Map<String, LookRule> rules() {
        return rules(kind);
    }

    private Map<String, LookRule> rules(LookRules.Kind which) {
        return which == LookRules.Kind.THEME ? themeRules : backgroundRules;
    }

    @Nullable
    private String selected() {
        return kind == LookRules.Kind.THEME ? selectedTheme : selectedBackground;
    }

    private void select(String id) {
        if (kind == LookRules.Kind.THEME) {
            selectedTheme = id;
        } else {
            selectedBackground = id;
        }
    }

    /** Autosave, from the window's frame. */
    void tick() {
        if (pending != null && System.currentTimeMillis() - changedAt > SAVE_DELAY) {
            flush();
        }
    }

    void flush() {
        if (pending != null) {
            ClientPlayNetworking.send(new HubPayloads.LookRuleSave(pendingKind == LookRules.Kind.THEME,
                    rule(pendingKind, pending)));
            pending = null;
        }
    }

    private LookRule rule(String id) {
        return rule(kind, id);
    }

    private LookRule rule(LookRules.Kind which, String id) {
        LookRule rule = rules(which).get(id);
        return rule != null ? rule : new LookRule(id, !which.openByDefault.contains(id), List.of(), true, 0);
    }

    private void change(LookRule rule, boolean rebuild) {
        if (pending != null && (!pending.equals(rule.id()) || pendingKind != kind)) {
            flush();
        }
        rules().put(rule.id(), rule);
        pending = rule.id();
        pendingKind = kind;
        changedAt = System.currentTimeMillis();
        if (rebuild) {
            screen.rebuildNow();
        }
    }

    private List<String> ids() {
        List<String> ids = new ArrayList<>();
        if (kind == LookRules.Kind.THEME) {
            for (HubTheme theme : HubThemes.all()) {
                ids.add(theme.id().toString());
            }
        } else {
            for (ProfileBackgrounds.Background background : ProfileBackgrounds.all()) {
                ids.add(background.id().toString());
            }
        }
        for (String id : rules().keySet()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Component name(String id) {
        if (kind == LookRules.Kind.THEME) {
            HubTheme theme = theme(id);
            return theme == null ? Component.literal(id) : theme.name();
        }
        ProfileBackgrounds.Background background = ProfileBackgrounds.get(id);
        return background == null ? Component.literal(id) : background.name();
    }

    @Nullable
    private static HubTheme theme(String id) {
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.tryParse(id);
        return location == null ? null : HubThemes.get(location);
    }

    /** The look's picture: a background's image, or a drawn preview of a theme. */
    private net.ledok.vectorlib.client.canvas.CanvasNode picture(String id, float w, float h, float dim) {
        if (kind == LookRules.Kind.THEME) {
            HubTheme theme = theme(id);
            CanvasGroup group = new CanvasGroup();
            if (theme != null) {
                group.add(ThemePreview.node(theme, w, h));
            }
            if (dim > 0) {
                group.add(Shapes.rect(0, 0, w, h).fill(((int) (dim * 255) << 24)));
            }
            return group;
        }
        return ProfileBackgrounds.thumbnail(id, w, h, dim);
    }

    /** "Open", "Locked · hidden", "Locked · 500 LC", "+ 2 groups"… */
    private Component state(LookRule rule) {
        if (!rule.locked()) {
            return Component.translatable("gui.eden_ld.moderation.bg.state_open");
        }
        Component state = Component.translatable(rule.hidden() ? "gui.eden_ld.moderation.bg.state_hidden"
                : rule.price() > 0 ? "gui.eden_ld.moderation.bg.state_price" : "gui.eden_ld.moderation.bg.state_locked",
                rule.price());
        return rule.groups().isEmpty() ? state
                : Component.empty().append(state).append(" · ")
                .append(Component.translatable("gui.eden_ld.moderation.bg.state_groups", rule.groups().size()));
    }

    // ---------------------------------------------------------------- layout

    void build(Flex main, List<String> groups, boolean backgrounds, boolean themes) {
        if (!backgrounds && themes) {
            kind = LookRules.Kind.THEME;
        } else if (!themes) {
            kind = LookRules.Kind.BACKGROUND;
        }
        // Tabs: Backgrounds · Themes (only the ones this admin may edit).
        Flex tabs = Flex.row().gap(3).alignItems(Align.CENTER);
        tabs.item(text(Component.translatable("gui.eden_ld.moderation.section.customization"), TEXT));
        tabs.item(Flex.row().sizing(Sizing.fixed(6), Sizing.fixed(1)));
        for (LookRules.Kind candidate : LookRules.Kind.values()) {
            if (candidate == LookRules.Kind.THEME ? !themes : !backgrounds) {
                continue;
            }
            Button tab = new Button(Minecraft.getInstance().font.width(kindName(candidate)) + 14, 14, kindName(candidate), () -> {
                flush();
                kind = candidate;
                screen.rebuildNow();
            });
            if (candidate == kind) {
                tab.style(ModerationScreen.SELECTED);
            }
            tabs.item(tab);
        }
        main.item(tabs);

        List<String> ids = ids();
        String selected = selected();
        if (selected == null || !ids.contains(selected)) {
            selected = ids.isEmpty() ? null : ids.get(0);
            select(selected);
        }
        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.fill());

        Flex grid = Flex.column().gap(3);
        Flex row = null;
        for (int i = 0; i < ids.size(); i++) {
            if (i % 3 == 0) {
                row = Flex.row().gap(3);
                grid.item(row);
            }
            Thumb thumb = new Thumb(ids.get(i));
            if (ids.get(i).equals(selected)) {
                selectedThumb = thumb;
            }
            row.item(thumb);
        }
        if (ids.isEmpty()) {
            grid.item(text(Component.translatable(kind == LookRules.Kind.THEME
                    ? "gui.eden_ld.moderation.bg.none_themes" : "gui.eden_ld.moderation.bg.none"), DIM, (int) LIST - 10));
        }
        // Kept scroll positions: a save brings the server's rules back and rebuilds the section.
        ScrollPanel list = HubScroll.panel("mod/customization/" + kind + "/list", LIST, 100, grid);
        list.sizing(Sizing.fixed(LIST), Sizing.fill());
        columns.item(list);

        // The rule scrolls when its texts (and the group list) are taller than the window.
        Flex detail = Flex.column().gap(5);
        if (selected != null) {
            buildDetail(detail, rule(selected), groups);
        }
        ScrollPanel detailScroll = HubScroll.panel("mod/customization/" + kind + "/" + selected,
                width - LIST - 8, 100, detail);
        detailScroll.sizing(Sizing.expand(), Sizing.fill());
        columns.item(detailScroll);
        main.item(columns);
    }

    private void buildDetail(Flex detail, LookRule rule, List<String> groups) {
        // Room for the scroll bar.
        float w = width - LIST - 8 - 8;
        float previewH = Math.round(w / 2.4f);
        CanvasGroup picture = new CanvasGroup();
        picture.add(picture(rule.id(), w, previewH, 0));
        picture.add(Shapes.rect(0, 0, w, previewH).stroke(Stroke.of(LINE, 1)));
        Flex framed = Flex.column();
        framed.sizing(Sizing.fixed(w), Sizing.fixed(previewH));
        framed.item(picture);
        detail.item(framed);
        detail.item(TextNode.of(Component.empty().append(name(rule.id())).append(
                        Component.literal("  " + rule.id()).withStyle(s -> s.withColor(DIM & 0xFFFFFF))))
                .color(TEXT).shadow(false).maxWidth((int) w).ellipsis(true));

        Toggle locked = new Toggle(w, Component.translatable("gui.eden_ld.moderation.bg.locked"), rule.locked(),
                value -> change(new LookRule(rule.id(), value, rule.groups(), rule.hidden(), rule.price()), true));
        locked.tooltip(Component.translatable("gui.eden_ld.moderation.bg.locked_hint"));
        detail.item(locked);
        if (!rule.locked()) {
            detail.item(text(Component.translatable("gui.eden_ld.moderation.bg.open_hint"), DIM, (int) w));
            return;
        }
        Toggle hidden = new Toggle(w, Component.translatable("gui.eden_ld.moderation.bg.hidden"), rule.hidden(),
                value -> change(new LookRule(rule.id(), rule.locked(), rule.groups(), value, rule.price()), true));
        hidden.tooltip(Component.translatable("gui.eden_ld.moderation.bg.hidden_hint"));
        detail.item(hidden);

        Flex price = Flex.row().gap(4).alignItems(Align.CENTER);
        price.item(text(Component.translatable("gui.eden_ld.moderation.bg.price"), TEXT));
        // Shown while it's hidden but has a price; refilled as the price is typed.
        Flex warning = Flex.column();
        Runnable refreshWarning = () -> {
            warning.clear();
            LookRule now = rule(rule.id());
            if (now.hidden() && now.price() > 0) {
                warning.item(text(Component.translatable("gui.eden_ld.moderation.bg.hidden_price"), 0xFFE0C850, (int) w));
            }
        };
        NumberField field = new NumberField(70, rule.price(), value -> {
            if (value != rule(rule.id()).price()) {
                LookRule now = rule(rule.id());
                change(new LookRule(now.id(), now.locked(), now.groups(), now.hidden(), value), false);
                refreshWarning.run();
                if (selectedThumb != null) {
                    selectedThumb.refresh();
                }
                screen.relayout();
            }
        });
        field.range(0, 1_000_000_000L).step(50);
        field.size(70, 14);
        price.item(field);
        price.item(text(Component.literal(net.ledok.eden_ld.client.HubGlyphs.LC_TEXT), DIM));
        detail.item(price);
        detail.item(text(Component.translatable("gui.eden_ld.moderation.bg.price_hint"), DIM, (int) w));
        refreshWarning.run();
        detail.item(warning);

        detail.item(text(Component.translatable("gui.eden_ld.moderation.bg.groups"), TEXT, (int) w));
        if (groups.isEmpty()) {
            detail.item(text(Component.translatable("gui.eden_ld.moderation.no_groups"), DIM, (int) w));
        }
        List<Widget> chips = new ArrayList<>();
        for (String group : groups) {
            boolean on = rule.groups().stream().anyMatch(g -> g.equalsIgnoreCase(group));
            Button chip = new Button(Minecraft.getInstance().font.width(group) + 16, 14,
                    Component.literal((on ? "✔ " : "") + group), () -> {
                        List<String> next = new ArrayList<>(rule.groups());
                        if (!next.removeIf(g -> g.equalsIgnoreCase(group))) {
                            next.add(group);
                        }
                        change(new LookRule(rule.id(), rule.locked(), next, rule.hidden(), rule.price()), true);
                    });
            if (on) {
                chip.style(ModerationScreen.SELECTED);
            }
            chips.add(chip);
        }
        detail.item(ModerationScreen.wrapped(chips, w, 3));
        detail.item(text(Component.translatable("gui.eden_ld.moderation.bg.keep_hint"), DIM, (int) w));
    }

    /** A background in the grid: picture, name and state; click shows its rule. */
    private final class Thumb extends Widget {
        private final String id;

        Thumb(String id) {
            super(THUMB_W, THUMB_H);
            this.id = id;
            sizing(Sizing.fixed(THUMB_W), Sizing.fixed(THUMB_H));
            tooltip(() -> List.of(name(id), state(rule(id))));
        }

        @Override
        protected void rebuild() {
            boolean on = id.equals(selected());
            LookRule rule = rule(id);
            add(Shapes.rect(0, 0, width, height).fill(on ? PANEL : 0xFF22252A)
                    .stroke(Stroke.of(on ? ACCENT : hovered ? DIM : LINE, 1)));
            add(picture(id, width - 4, 30, rule.locked() ? 0.45f : 0).at(2, 2));
            // The text band below the picture, so nothing of the picture shows under the words.
            add(Shapes.rect(1, 32, width - 2, height - 33).fill(on ? PANEL : 0xFF22252A));
            add(TextNode.of(name(id)).at(3, 34).maxWidth((int) width - 6).ellipsis(true).color(TEXT).shadow(false)
                    .scale(0.8f));
            add(TextNode.of(state(rule)).at(3, 43).maxWidth((int) width - 6).ellipsis(true)
                    .color(rule.locked() ? DIM : GOOD).shadow(false).scale(0.7f));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            select(id);
            screen.rebuildNow();
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }

    private static Component kindName(LookRules.Kind kind) {
        return Component.translatable(kind == LookRules.Kind.THEME ? "gui.eden_ld.moderation.bg.tab_themes"
                : "gui.eden_ld.moderation.bg.tab_backgrounds");
    }
}
