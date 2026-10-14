package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.hub.moderation.AdminTools;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.settings.NumberOption;
import net.ledok.vectorlib.client.canvas.settings.Option;
import net.ledok.vectorlib.client.canvas.settings.Settings;
import net.ledok.vectorlib.client.canvas.settings.SettingsView;
import net.ledok.vectorlib.client.canvas.settings.TextOption;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.Slider;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.border.WorldBorder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.text;

/**
 * Moderation → World settings (docs/PLAN-world-rules.md): a column of pages — GLOBAL, then every
 * dimension, each marked with how many things it changes and whether it has a border — and the page as
 * a VectorLib settings page. GLOBAL edits the server-wide gamerules (as {@code /gamerule}): the Global tab the
 * rules with one value for the whole server, Gamerules and Modded the ones a dimension can set for itself (each
 * naming the dimensions that do); a dimension
 * page its PvP, difficulty and flight, its own gamerules, the mods' rules and its border. A dimension
 * shows only what can differ per dimension; a reset arrow takes a value back to GLOBAL's (on GLOBAL, to
 * Minecraft's default). Every change goes to the server at once; the page comes back with the result.
 */
final class WorldSection {

    private static final float PAGES = 112;
    private static final String GLOBAL = "";
    private static final long NUMBER_MAX = 1_000_000;
    /** A border switched on from none starts this wide. */
    private static final double NEW_BORDER = 10_000;

    private final ModerationScreen screen;
    private final float width;
    @Nullable
    private CompoundTag data;
    /** The open page: {@link #GLOBAL} or a dimension id. */
    private String page = GLOBAL;
    /** Data came while the admin was in one of the page's boxes or sliders: the page is built again once they leave it. */
    private boolean stale;

    WorldSection(ModerationScreen screen, float width) {
        this.screen = screen;
        this.width = width;
    }

    void request() {
        PlayerTabs.request(AdminTools.WORLD, Util.NIL_UUID);
    }

    /** Returns whether it was this section's data. */
    boolean onData(String area, CompoundTag tag) {
        if (!area.equals(AdminTools.WORLD)) {
            return false;
        }
        data = tag;
        return true;
    }

    /**
     * Shows new data — unless the admin is in one of the page's boxes or sliders: building the page again
     * would take it away mid-edit (and the wheel steps a number box or slider only while it's clicked into),
     * so the page waits until they leave it. Every change is answered with new data, so this runs after each.
     */
    void refresh() {
        if (editing()) {
            stale = true;
        } else {
            screen.rebuildNow();
        }
    }

    /** Each frame while the section is open: the page held back by {@link #refresh} is built once the box is left. */
    void tick() {
        if (stale && !editing()) {
            screen.rebuildNow();
        }
    }

    private boolean editing() {
        CanvasNode focused = screen.input() == null ? null : screen.input().focusedNode();
        return (focused instanceof TextField || focused instanceof Slider) && focused.canvas() != null;
    }

    void build(Flex main) {
        stale = false;
        main.item(text(Component.translatable("gui.eden_ld.moderation.world.title"), TEXT));
        if (data == null) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        CompoundTag dimension = page.equals(GLOBAL) ? null : dimension(page);
        if (dimension == null) {
            page = GLOBAL;
        }
        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.expand());

        Flex pages = Flex.column().gap(2);
        pages.item(pageButton(GLOBAL, Component.translatable("gui.eden_ld.moderation.world.global"), 0, false));
        for (CompoundTag entry : dimensions()) {
            pages.item(pageButton(entry.getString("id"), dimensionName(entry.getString("id")), changes(entry),
                    entry.getCompound("border").getBoolean("on")));
        }
        ScrollPanel list = new ScrollPanel(PAGES, 100, pages);
        list.sizing(Sizing.fixed(PAGES), Sizing.fill());
        columns.item(list);

        float pageWidth = width - PAGES - 8;
        Settings settings = dimension == null ? globalPage() : dimensionPage(dimension);
        SettingsView view = new SettingsView(settings, pageWidth)
                .controlWidth(100)
                .searchable(true)
                .labelColor(TEXT).hintColor(DIM).headingColor(ModerationScreen.ACCENT)
                .tabs(bar -> bar.activeStyle(ModerationScreen.SELECTED))
                // One memory for every dimension page: switching dimensions keeps the tab and the search.
                .remember("eden_ld/moderation/world/" + (dimension == null ? "global" : "dimension"));
        view.sizing(Sizing.fixed(pageWidth), Sizing.fill());
        columns.item(view);
        main.item(columns);
    }

    // ---------------------------------------------------------------- the page column

    private Button pageButton(String id, Component name, int changes, boolean border) {
        MutableComponent label = Component.empty().append(name);
        if (changes > 0) {
            label.append(Component.literal("  " + changes).withStyle(ChatFormatting.GRAY));
        }
        if (border) {
            label.append(Component.literal(" ⬜").withStyle(ChatFormatting.GRAY));
        }
        Button button = new Button(PAGES - 12, 16, label, () -> {
            page = id;
            screen.rebuildNow();
        });
        if (id.equals(page)) {
            button.style(ModerationScreen.SELECTED);
        }
        if (!id.equals(GLOBAL)) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(id));
            if (changes > 0) {
                tip.add(Component.translatable("gui.eden_ld.moderation.world.changes", changes).withStyle(ChatFormatting.GRAY));
            }
            if (border) {
                tip.add(Component.translatable("gui.eden_ld.moderation.world.has_border").withStyle(ChatFormatting.GRAY));
            }
            button.tooltip(tip.toArray(Component[]::new));
        }
        return button;
    }

    /** How many things a dimension sets for itself (gamerules, PvP, difficulty, flight, border). */
    private static int changes(CompoundTag dimension) {
        int count = dimension.getCompound("rules").size();
        count += dimension.contains("pvp") ? 1 : 0;
        count += dimension.contains("difficulty") ? 1 : 0;
        count += dimension.getInt("flight") != 0 ? 1 : 0;
        count += dimension.getCompound("border").getBoolean("on") ? 1 : 0;
        return count;
    }

    // ---------------------------------------------------------------- GLOBAL

    private Settings globalPage() {
        Settings settings = new Settings();
        // What really is the whole server's: one value, no dimension can change it.
        settings.section("global", Component.translatable("gui.eden_ld.moderation.world.tab.global"));
        addRules(settings, rule -> rule.getString("scope").equals("GLOBAL"), rule -> globalOption(rule, false));
        // The rest is a default: a dimension's own value wins there (the hint names those dimensions).
        settings.section("gamerules", Component.translatable("gui.eden_ld.moderation.world.tab.gamerules"));
        addRules(settings, rule -> !rule.getBoolean("modded") && rule.getString("scope").equals("DIMENSION"),
                rule -> globalOption(rule, true));
        if (rules().stream().anyMatch(rule -> rule.getBoolean("modded"))) {
            settings.section("modded", Component.translatable("gui.eden_ld.moderation.world.tab.modded"));
            addRules(settings, rule -> rule.getBoolean("modded"), rule -> globalOption(rule, true));
        }
        return settings;
    }

    private Option<?, ?> globalOption(CompoundTag rule) {
        return globalOption(rule, false);
    }

    /**
     * A rule's server-wide value; the reset arrow goes back to Minecraft's default. {@code overrides}: the hint also
     * names the dimensions that set it themselves (the value here doesn't reach them).
     */
    private Option<?, ?> globalOption(CompoundTag rule, boolean overrides) {
        return ruleOption(rule, rule.getString("value"), rule.getString("default"),
                value -> action("global_rule", null, args -> {
                    args.putString("rule", rule.getString("id"));
                    args.putString("value", value);
                }), null, overrides ? ownValues(rule) : null);
    }

    /** "Nether: Off, Spawn Dim: 3" — the dimensions with their own value of the rule; null when none has one. */
    @Nullable
    private Component ownValues(CompoundTag rule) {
        String id = rule.getString("id");
        MutableComponent list = null;
        for (CompoundTag dimension : dimensions()) {
            CompoundTag own = dimension.getCompound("rules");
            if (!own.contains(id)) {
                continue;
            }
            String value = own.getString(id);
            MutableComponent entry = Component.empty().append(dimensionName(dimension.getString("id"))).append(": ")
                    .append(rule.getString("type").equals("bool") ? onOff(Boolean.parseBoolean(value)) : Component.literal(value));
            list = list == null ? entry : list.append(", ").append(entry);
        }
        return list == null ? null : Component.translatable("gui.eden_ld.moderation.world.own_values", list);
    }

    // ---------------------------------------------------------------- a dimension

    private Settings dimensionPage(CompoundTag dimension) {
        String id = dimension.getString("id");
        boolean overworld = id.equals("minecraft:overworld");
        @Nullable Component locked = data.getBoolean("writable") ? null
                : Component.translatable("message.eden_ld.world.file_broken");
        Settings settings = new Settings();

        settings.section("general", Component.translatable("gui.eden_ld.moderation.world.tab.general"));
        boolean serverPvp = data.getBoolean("pvp");
        String pvp = !dimension.contains("pvp") ? "server" : dimension.getBoolean("pvp") ? "on" : "off";
        settings.add(locked(Option.choice(Component.translatable("gui.eden_ld.moderation.world.pvp"),
                        List.of("server", "on", "off"), pvp,
                        value -> switch (value) {
                            case "server" -> Component.translatable("gui.eden_ld.moderation.world.server_value", onOff(serverPvp));
                            case "on" -> onOff(true);
                            default -> onOff(false);
                        },
                        value -> action("pvp", id, args -> args.putString("value", value)))
                .defaultValue("server")
                .hint(Component.translatable("gui.eden_ld.moderation.world.pvp.hint")), locked));
        Difficulty serverDifficulty = difficulty(data.getString("difficulty"));
        String difficulty = dimension.contains("difficulty") ? dimension.getString("difficulty") : "server";
        List<String> difficulties = new ArrayList<>(List.of("server"));
        for (Difficulty each : Difficulty.values()) {
            difficulties.add(each.getKey());
        }
        settings.add(locked(Option.choice(Component.translatable("gui.eden_ld.moderation.world.difficulty"), difficulties,
                        difficulty,
                        value -> value.equals("server")
                                ? Component.translatable("gui.eden_ld.moderation.world.server_value", serverDifficulty.getDisplayName())
                                : difficulty(value).getDisplayName(),
                        value -> action("difficulty", id, args -> args.putString("value", value)))
                .defaultValue("server")
                .hint(Component.translatable("gui.eden_ld.moderation.world.difficulty.hint")), locked));
        settings.add(Option.choice(Component.translatable("gui.eden_ld.moderation.world.flight"), List.of(0, 1, 2),
                        dimension.getInt("flight"),
                        mode -> Component.translatable("gui.eden_ld.moderation.world.flight." + switch (mode) {
                            case 1 -> "no_boosts";
                            case 2 -> "blocked";
                            default -> "allowed";
                        }),
                        mode -> action("flight", id, args -> args.putInt("mode", mode)))
                .defaultValue(0)
                .hint(Component.translatable("gui.eden_ld.moderation.world.flight.hint")));

        settings.section("gamerules", Component.translatable("gui.eden_ld.moderation.world.tab.gamerules"));
        CompoundTag own = dimension.getCompound("rules");
        addRules(settings, rule -> !rule.getBoolean("modded")
                        && (rule.getString("scope").equals("DIMENSION") || overworld && rule.getString("scope").equals("OVERWORLD")),
                rule -> rule.getString("scope").equals("OVERWORLD")
                        ? globalOption(rule).hint(Component.translatable("gui.eden_ld.moderation.world.overworld_only.hint"))
                        : dimensionOption(id, rule, own, locked));
        if (rules().stream().anyMatch(rule -> rule.getBoolean("modded"))) {
            settings.section("modded", Component.translatable("gui.eden_ld.moderation.world.tab.modded"));
            addRules(settings, rule -> rule.getBoolean("modded") && rule.getString("scope").equals("DIMENSION"),
                    rule -> dimensionOption(id, rule, own, locked));
        }

        settings.section("border", Component.translatable("gui.eden_ld.moderation.world.tab.border"));
        addBorder(settings, dimension, locked);
        return settings;
    }

    /** A rule in a dimension: its own value, else the server-wide one; the reset arrow goes back to that. */
    private Option<?, ?> dimensionOption(String dimension, CompoundTag rule, CompoundTag own, @Nullable Component locked) {
        String id = rule.getString("id");
        String value = own.contains(id) ? own.getString(id) : rule.getString("value");
        return ruleOption(rule, value, rule.getString("value"),
                changed -> action("rule", dimension, args -> {
                    args.putString("rule", id);
                    args.putString("value", changed);
                }), locked, null);
    }

    /**
     * The border's settings. A change starts from the border the server last sent ({@link #currentBorder}),
     * not the one this page was built with: the page may be waiting for the admin to leave a box.
     */
    private void addBorder(Settings settings, CompoundTag dimension, @Nullable Component locked) {
        String id = dimension.getString("id");
        CompoundTag border = dimension.getCompound("border");
        if (!data.getBoolean("borders")) {
            settings.add(Option.toggle(Component.translatable("gui.eden_ld.moderation.world.border.on"), false, on -> {
            }).disabled(Component.translatable("gui.eden_ld.moderation.world.border.not_managed")));
            return;
        }
        boolean on = border.getBoolean("on");
        Component off = locked != null ? locked : Component.translatable("gui.eden_ld.moderation.world.border.off_hint");
        Component dimensionName = dimensionName(id);
        settings.add(locked(Option.toggle(Component.translatable("gui.eden_ld.moderation.world.border.on"), on,
                        value -> {
                            if (!value) {
                                border(id, args -> args.putBoolean("on", false));
                                return;
                            }
                            // Switched on from none: a 10,000-block border where the old center was — confirmed first.
                            CompoundTag wanted = currentBorder(id).copy();
                            wanted.putBoolean("on", true);
                            if (wanted.getDouble("size") >= WorldBorder.MAX_SIZE) {
                                wanted.putDouble("size", NEW_BORDER);
                            }
                            confirmBorder(id, dimensionName, wanted);
                        })
                .defaultValue(false)
                .hint(Component.translatable("gui.eden_ld.moderation.world.border.on.hint")), locked));
        settings.add(borderField(whole(Component.translatable("gui.eden_ld.moderation.world.border.size"),
                        Math.round(border.getDouble("size")), 1, (long) WorldBorder.MAX_SIZE,
                        size -> confirmBorder(id, dimensionName, with(currentBorder(id), args -> args.putDouble("size", size))))
                .hint(Component.translatable("gui.eden_ld.moderation.world.border.size.hint")), on, off));
        settings.add(borderField(whole(Component.translatable("gui.eden_ld.moderation.world.border.center_x"),
                        Math.round(border.getDouble("center_x")), -29_999_984, 29_999_984,
                        x -> confirmBorder(id, dimensionName, with(currentBorder(id), args -> args.putDouble("center_x", x))))
                .defaultValue("0"), on, off));
        settings.add(borderField(whole(Component.translatable("gui.eden_ld.moderation.world.border.center_z"),
                        Math.round(border.getDouble("center_z")), -29_999_984, 29_999_984,
                        z -> confirmBorder(id, dimensionName, with(currentBorder(id), args -> args.putDouble("center_z", z))))
                .defaultValue("0"), on, off));
        var player = Minecraft.getInstance().player;
        boolean here = player != null && player.level().dimension().location().toString().equals(id);
        Option<?, ?> centerHere = Option.action(Component.translatable("gui.eden_ld.moderation.world.border.here"),
                        Component.translatable("gui.eden_ld.moderation.world.border.here.button"),
                        () -> {
                            var self = Minecraft.getInstance().player;
                            if (self != null) {
                                confirmBorder(id, dimensionName, with(currentBorder(id), args -> {
                                    args.putDouble("center_x", self.getBlockX());
                                    args.putDouble("center_z", self.getBlockZ());
                                }));
                            }
                        })
                .hint(Component.translatable("gui.eden_ld.moderation.world.border.here.hint"));
        settings.add(!on ? centerHere.disabled(off)
                : !here ? centerHere.disabled(Component.translatable("gui.eden_ld.moderation.world.border.here.elsewhere"))
                : locked(centerHere, locked));
        settings.add(borderField(Option.slider(Component.translatable("gui.eden_ld.moderation.world.border.damage"), 0, 5, 0.1,
                        border.getDouble("damage_per_block"),
                        damage -> border(id, args -> args.putDouble("damage_per_block", Math.round(damage * 10) / 10.0)))
                .formatter(damage -> Component.literal(String.format(Locale.ROOT, "%.1f", damage)))
                .defaultValue(0.2)
                .hint(Component.translatable("gui.eden_ld.moderation.world.border.damage.hint")), on, off));
        settings.add(borderField(numberBox(Component.translatable("gui.eden_ld.moderation.world.border.safe_zone"),
                        Math.round(border.getDouble("safe_zone")), 5, 0, 1000,
                        blocks -> border(id, args -> args.putDouble("safe_zone", blocks)))
                .hint(Component.translatable("gui.eden_ld.moderation.world.border.safe_zone.hint")), on, off));
        settings.add(borderField(numberBox(Component.translatable("gui.eden_ld.moderation.world.border.warning_blocks"),
                        border.getInt("warning_blocks"), 5, 0, 1000,
                        blocks -> border(id, args -> args.putInt("warning_blocks", (int) blocks)))
                .hint(Component.translatable("gui.eden_ld.moderation.world.border.warning_blocks.hint")), on, off));
        settings.add(borderField(numberBox(Component.translatable("gui.eden_ld.moderation.world.border.warning_time"),
                        border.getInt("warning_time"), 15, 0, 3600,
                        seconds -> border(id, args -> args.putInt("warning_time", (int) seconds)))
                .hint(Component.translatable("gui.eden_ld.moderation.world.border.warning_time.hint")), on, off));
    }

    /**
     * Where a border stands decides who is suddenly outside it and takes damage, so moving one — switching
     * it on, its size, its center — is confirmed first, saying where it will be.
     */
    private void confirmBorder(String dimension, Component name, CompoundTag wanted) {
        PlayerTabs.confirm(screen, Component.translatable("gui.eden_ld.moderation.world.border.confirm", name,
                        String.format(Locale.ROOT, "%,d", Math.round(wanted.getDouble("size"))),
                        Math.round(wanted.getDouble("center_x")), Math.round(wanted.getDouble("center_z"))),
                "gui.eden_ld.moderation.world.border.set", () -> sendBorder(dimension, wanted));
        // Until it's confirmed the page shows the border as it is (a cancelled dialog leaves nothing behind).
        screen.rebuildNow();
    }

    /** The border with one change made. */
    private static CompoundTag with(CompoundTag border, Consumer<CompoundTag> change) {
        CompoundTag copy = border.copy();
        change.accept(copy);
        return copy;
    }

    /** A border setting, greyed out while the border is off (or the file has an error). */
    private static Option<?, ?> borderField(Option<?, ?> option, boolean on, Component off) {
        return on ? option : option.disabled(off);
    }

    /** Sends the dimension's border as the server last sent it, with {@code change} made. */
    private void border(String dimension, Consumer<CompoundTag> change) {
        sendBorder(dimension, with(currentBorder(dimension), change));
    }

    private void sendBorder(String dimension, CompoundTag border) {
        action("border", dimension, args -> args.merge(border));
    }

    /** The dimension's border as the server last sent it (the page may be older: see {@link #refresh}). */
    private CompoundTag currentBorder(String dimension) {
        CompoundTag found = dimension(dimension);
        return found == null ? new CompoundTag() : found.getCompound("border");
    }

    // ---------------------------------------------------------------- rules

    private void addRules(Settings settings, java.util.function.Predicate<CompoundTag> which,
                          java.util.function.Function<CompoundTag, Option<?, ?>> option) {
        List<CompoundTag> rules = rules().stream().filter(which).toList();
        for (GameRules.Category category : GameRules.Category.values()) {
            List<CompoundTag> inCategory = rules.stream()
                    .filter(rule -> rule.getString("category").equals(category.name())).toList();
            if (inCategory.isEmpty()) {
                continue;
            }
            settings.heading(Component.translatable(category.getDescriptionId()));
            inCategory.forEach(rule -> settings.add(option.apply(rule)));
        }
    }

    /** A rule's control: a switch, a number box, or (a mod's own kind of value) a text field. */
    private Option<?, ?> ruleOption(CompoundTag rule, String value, String defaultValue, Consumer<String> send,
                                           @Nullable Component locked, @Nullable Component note) {
        String id = rule.getString("id");
        Component name = I18n.exists("gamerule." + id) ? Component.translatable("gamerule." + id) : Component.literal(id);
        Option<?, ?> option = switch (rule.getString("type")) {
            case "bool" -> Option.toggle(name, Boolean.parseBoolean(value), on -> send.accept(Boolean.toString(on)))
                    .defaultValue(Boolean.parseBoolean(defaultValue));
            case "int" -> numberBox(name, number(value), number(defaultValue), 0,
                    id.equals("spawnChunkRadius") ? 32 : NUMBER_MAX, n -> send.accept(Long.toString(n)));
            default -> Option.text(name, value, send).defaultValue(defaultValue);
        };
        MutableComponent hint = Component.literal(id);
        if (I18n.exists("gamerule." + id + ".description")) {
            hint.append(" · ").append(Component.translatable("gamerule." + id + ".description"));
        }
        if (note != null) {
            hint.append("\n").append(note.copy().withStyle(ChatFormatting.GOLD));
        }
        return locked(option.hint(hint).keywords(id), locked);
    }

    /**
     * The border's size and center: a whole number sent only on Enter or the Set button — not by leaving
     * the field, as a number box would — and then confirmed, since moving a border decides who is suddenly
     * outside it. A number outside {@code min..max}, or none, puts the page back.
     */
    private TextOption whole(Component label, long value, long min, long max, java.util.function.LongConsumer commit) {
        return Option.text(label, Long.toString(value), text -> {
                    long parsed;
                    try {
                        parsed = Long.parseLong(text.trim());
                    } catch (NumberFormatException e) {
                        screen.rebuildNow();
                        return;
                    }
                    if (parsed < min || parsed > max) {
                        screen.rebuildNow();
                        return;
                    }
                    if (parsed != value) {
                        commit.accept(parsed);
                    }
                })
                .filter(text -> text.matches(min < 0 ? "-?\\d{0,9}" : "\\d{0,9}"))
                .maxLength(10)
                .button(Component.translatable("gui.eden_ld.moderation.world.border.set"));
    }

    /**
     * A whole number box — sent on Enter, on leaving it, or by a wheel/arrow step once clicked into — whose
     * reset arrow goes back to {@code fallback}. A value outside {@code min..max} (set by a command or a mod)
     * widens the range, so clicking through the box never changes it.
     */
    private static NumberOption numberBox(Component label, long value, long fallback, long min, long max,
                                          java.util.function.LongConsumer commit) {
        return Option.number(label, Math.min(min, Math.min(value, fallback)), Math.max(max, Math.max(value, fallback)),
                value, commit).defaultValue(fallback);
    }

    private static Option<?, ?> locked(Option<?, ?> option, @Nullable Component reason) {
        return reason == null ? option : option.disabled(reason);
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- data

    private List<CompoundTag> rules() {
        List<CompoundTag> rules = new ArrayList<>();
        ListTag list = data.getList("rules", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            rules.add(list.getCompound(i));
        }
        return rules;
    }

    /** The Overworld, the Nether and the End first, then the mods' dimensions by id. */
    private List<CompoundTag> dimensions() {
        List<CompoundTag> dimensions = new ArrayList<>();
        ListTag list = data.getList("dimensions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            dimensions.add(list.getCompound(i));
        }
        List<String> first = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
        dimensions.sort(java.util.Comparator.<CompoundTag>comparingInt(entry -> {
            int at = first.indexOf(entry.getString("id"));
            return at < 0 ? first.size() : at;
        }).thenComparing(entry -> entry.getString("id")));
        return dimensions;
    }

    @Nullable
    private CompoundTag dimension(String id) {
        return dimensions().stream().filter(entry -> entry.getString("id").equals(id)).findFirst().orElse(null);
    }

    private void action(String action, @Nullable String dimension, Consumer<CompoundTag> fill) {
        CompoundTag args = new CompoundTag();
        fill.accept(args);
        if (dimension != null) {
            args.putString("dimension", dimension);
        }
        PlayerTabs.action(AdminTools.WORLD, Util.NIL_UUID, action, args);
    }

    private static Difficulty difficulty(String key) {
        Difficulty difficulty = Difficulty.byName(key);
        return difficulty == null ? Difficulty.NORMAL : difficulty;
    }

    private static Component onOff(boolean on) {
        return Component.translatable(on ? "gui.eden_ld.moderation.world.on" : "gui.eden_ld.moderation.world.off");
    }

    /** "Overworld", "Nether", "End" — else the id's path in words ("Black Bridge"). */
    static Component dimensionName(String id) {
        String key = "gui.eden_ld.moderation.world.dim." + id.replace(':', '.');
        if (I18n.exists(key)) {
            return Component.translatable(key);
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        String path = location == null ? id : location.getPath();
        StringBuilder words = new StringBuilder();
        for (String word : path.split("[_/]")) {
            if (!word.isEmpty()) {
                words.append(words.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return Component.literal(words.toString());
    }
}
