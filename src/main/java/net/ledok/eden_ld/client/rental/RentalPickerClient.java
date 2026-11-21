package net.ledok.eden_ld.client.rental;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.theme.HubTheme;
import net.ledok.eden_ld.client.theme.HubThemes;
import net.ledok.eden_ld.rental.RentalPickerPayloads.CloseRentalPicker;
import net.ledok.eden_ld.rental.RentalPickerPayloads.Kit;
import net.ledok.eden_ld.rental.RentalPickerPayloads.OpenRentalPicker;
import net.ledok.eden_ld.rental.RentalPickerPayloads.RentalPickerChoice;
import net.ledok.eden_ld.rental.RentalPickerPayloads.RentalPickerClosed;
import net.ledok.eden_ld.rental.RentalSpells;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.ImageNode;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.ledok.vectorlib.client.presentation.CanvasScreen;
import net.ledok.vectorlib.client.presentation.Placement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The rental picker on the client (ported from Arenas_LD 4.41.1's lobby preview): every preset the server sent, the
 * player's class first ("Recommended"), each with its items (vanilla tooltips) and its spell book's choice (Spell
 * Engine icons and tooltips; one spell per tier as radio rows, or a free choice with a counter), and a Pick button.
 * A pick closes the picker; Esc or Close tells the server it was closed without one. A request with a deadline shows
 * a bar that drains here (the server closes the picker when its caller stops waiting).
 * <p>
 * Where it opens: as a modal on a registered host's canvas when one offers it ({@link #registerHost}: a menu screen
 * that must stay open, Arenas' lobby), else as a screen of its own.
 */
public final class RentalPickerClient {

    private static final float WIDTH = 440;
    private static final int SLOTS_PER_ROW = 12;

    private static final List<Supplier<@Nullable VectorCanvas>> HOSTS = new CopyOnWriteArrayList<>();

    /** The picker on screen: its request, and how to take it away. */
    private static final class Shown {
        final int requestId;
        /** Picked, closed by the server, or replaced: its going away tells the server nothing. */
        boolean finished;
        Runnable dismiss = () -> { };

        Shown(int requestId) {
            this.requestId = requestId;
        }
    }

    private static @Nullable Shown shown;

    private RentalPickerClient() {
    }

    /**
     * Lets a screen host the picker as a modal on its own canvas (so the screen stays open; Arenas' lobby uses this
     * through Eden's Arenas compat). The supplier is asked each time a picker opens: return the canvas while the host
     * is on screen, else null. The latest registered host that returns a canvas wins; none: the picker opens as its
     * own screen.
     */
    public static void registerHost(Supplier<@Nullable VectorCanvas> host) {
        HOSTS.add(host);
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(OpenRentalPicker.TYPE, (payload, context) -> open(payload));
        ClientPlayNetworking.registerGlobalReceiver(CloseRentalPicker.TYPE, (payload, context) -> {
            Shown current = shown;
            if (current != null && current.requestId == payload.requestId()) {
                finish(current);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            Shown current = shown;
            if (current != null) {
                current.finished = true; // nothing to tell a server that's gone
                shown = null;
            }
        }));
    }

    /** Takes the picker away without telling the server. */
    private static void finish(Shown current) {
        current.finished = true;
        current.dismiss.run();
        if (shown == current) {
            shown = null;
        }
    }

    private static void send(CustomPacketPayload payload) {
        try {
            if (Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(payload.type())) {
                ClientPlayNetworking.send(payload);
            }
        } catch (IllegalStateException ignored) {
            // Leaving the server: nothing to tell.
        }
    }

    /** The picker went away without a pick (Esc, Close, the host closed it): the server runs the caller's onClose. */
    private static void closedByPlayer(Shown current) {
        if (!current.finished) {
            current.finished = true;
            send(new RentalPickerClosed(current.requestId));
        }
        if (shown == current) {
            shown = null;
        }
    }

    // ---------------------------------------------------------------- opening

    private static void open(OpenRentalPicker payload) {
        Shown previous = shown;
        if (previous != null) {
            finish(previous); // replaced: the server already forgot it
        }
        Shown current = new Shown(payload.requestId());
        shown = current;
        long deadlineAt = payload.deadlineMs() > 0 ? System.currentTimeMillis() + payload.deadlineMs() : 0;
        VectorCanvas host = null;
        for (int i = HOSTS.size() - 1; i >= 0 && host == null; i--) {
            try {
                host = HOSTS.get(i).get();
            } catch (RuntimeException ignored) {
                // A host that broke can't take it: the next one, or a screen.
            }
        }
        if (host != null) {
            openModal(host, payload, current, deadlineAt);
        } else {
            openScreen(payload, current, deadlineAt);
        }
    }

    private static void openModal(VectorCanvas canvas, OpenRentalPicker payload, Shown current, long deadlineAt) {
        float width = Math.max(1, Math.min(WIDTH, canvas.width() - 16));
        float listHeight = Math.max(24, Math.min(220, canvas.height() - (deadlineAt > 0 ? 110 : 94)));
        Modal[] modal = new Modal[1];
        Button[] close = new Button[1];
        Flex panel = panel(payload, current, width, listHeight, deadlineAt, () -> modal[0].close(), close);
        var input = canvas.input();
        var opener = input == null ? null : input.focusedNode();
        Modal opened = Modal.open(canvas, fitDialog(panel));
        modal[0] = opened;
        current.dismiss = opened::close;
        opened.onClose(() -> {
            closedByPlayer(current);
            canvas.focus(opener != null && opener.canvas() == canvas ? opener : null);
        });
        // No initial focus (review finding 78): the picker replaces whatever was open, so a held Space or
        // the Enter that sent a chat line would fire a focused «Close» (or a Pick). Esc still closes.
        canvas.focus(null);
    }

    private static void openScreen(OpenRentalPicker payload, Shown current, long deadlineAt) {
        Minecraft minecraft = Minecraft.getInstance();
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();
        float listHeight = Math.max(80, Math.min(260, guiHeight - (deadlineAt > 0 ? 116 : 100)));
        PickerScreen[] screen = new PickerScreen[1];
        Button[] close = new Button[1];
        Flex panel = panel(payload, current, WIDTH, listHeight, deadlineAt, () -> screen[0].onClose(), close);
        screen[0] = new PickerScreen(payload.title(), panel, current);
        current.dismiss = () -> {
            if (minecraft.screen == screen[0]) {
                minecraft.setScreen(null);
            }
        };
        minecraft.setScreen(screen[0]);
        // No initial focus (review finding 78): see openModal.
        screen[0].canvas().focus(null);
    }

    /** The modal's frame: scales the panel down when the host canvas is smaller than it. */
    private static CanvasGroup fitDialog(Flex panel) {
        CanvasGroup frame = new CanvasGroup() {
            @Override
            public Rect bounds() {
                Rect bounds = panel.bounds();
                VectorCanvas canvas = canvas();
                if (canvas == null || bounds.width() <= 0 || bounds.height() <= 0) {
                    return bounds;
                }
                float scale = Math.min(1, Math.min(Math.max(1, canvas.width() - 12) / bounds.width(),
                        Math.max(1, canvas.height() - 12) / bounds.height()));
                panel.transform.scale(scale);
                return new Rect(bounds.x() * scale, bounds.y() * scale, bounds.width() * scale, bounds.height() * scale);
            }
        };
        frame.add(panel);
        return frame;
    }

    /** The picker as a screen of its own: centered, scaled to fit the window. Not a pause screen. */
    private static final class PickerScreen extends CanvasScreen {
        private final Shown current;
        private final float panelWidth;
        private final float panelHeight;

        PickerScreen(Component title, Flex panel, Shown current) {
            super(title, VectorCanvas.create(WIDTH, 100), Placement.Screen.center());
            this.current = current;
            canvas().theme(EdenParchment.THEME);
            panel.layoutIn(WIDTH, 4000);
            Rect bounds = panel.bounds();
            panelWidth = Math.max(1, bounds.width());
            panelHeight = Math.max(1, bounds.height());
            canvas().size(panelWidth, panelHeight);
            canvas().add(panel);
            panel.layoutIn(panelWidth, panelHeight);
        }

        @Override
        protected void init() {
            super.init();
            float fit = Math.min(1f, Math.min(Math.max(1f, width - 8f) / panelWidth, Math.max(1f, height - 8f) / panelHeight));
            placement(Placement.Screen.center().scale(fit));
        }

        @Override
        public void removed() {
            super.removed();
            closedByPlayer(current); // Esc, Close, or another screen took its place; nothing after a pick
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    // ---------------------------------------------------------------- the panel

    private static HubTheme.Palette palette() {
        HubTheme theme = HubThemes.get(HubThemes.selected());
        return theme == null ? HubTheme.Palette.PARCHMENT : theme.palette();
    }

    /** One full-width line, cut with "…" and the full text in its tooltip. */
    private static Label line(Component text, int color) {
        Label label = Label.of(100, text).color(color).tooltipWhenCut(true);
        label.sizing(Sizing.fill(), Sizing.content());
        return label;
    }

    private static float textWidth(Component text) {
        return Minecraft.getInstance().font.width(text);
    }

    private static Button button(Component label, float width, Runnable onClick) {
        return new Button(width, 18, label, onClick);
    }

    private static Flex panel(OpenRentalPicker payload, Shown current, float width, float listHeight, long deadlineAt,
                              Runnable close, Button[] closeButton) {
        HubTheme.Palette p = palette();
        Flex panel = Flex.column().gap(4).padding(Insets.of(12));
        panel.sizing(Sizing.fixed(width), Sizing.content());
        panel.background(EdenParchment.panel());
        panel.item(line(payload.title(), p.ink()));
        payload.subtitle().ifPresent(subtitle -> panel.item(EdenParchment.text(subtitle.copy(), p.inkMid(),
                (int) Math.max(1, width - 24))));
        panel.item(line(Component.translatable("gui.eden_ld.rental_picker.level", payload.level()), p.inkDim()));
        if (deadlineAt > 0) {
            panel.item(new DeadlineBar(width - 24, deadlineAt, payload.deadlineMs(), p));
        }

        Flex list = Flex.column().gap(6);
        list.sizing(Sizing.fill(), Sizing.content());
        if (payload.kits().isEmpty()) {
            list.item(line(Component.translatable("gui.eden_ld.rental_picker.empty"), p.inkMid()));
        }
        for (Kit kit : payload.kits()) {
            list.item(kitBlock(p, current, kit, kit.kitId().equals(payload.currentKitId()), width - 32, close));
        }
        ScrollPanel kits = new ScrollPanel(width - 24, listHeight, list);
        kits.sizing(Sizing.fill(), Sizing.fixed(listHeight));
        panel.item(kits);

        Flex footer = Flex.row().gap(6).alignItems(Align.CENTER);
        footer.sizing(Sizing.fill(), Sizing.content());
        Component closeLabel = Component.translatable("gui.eden_ld.rental_picker.close");
        closeButton[0] = button(closeLabel, textWidth(closeLabel) + 14, close);
        footer.item(closeButton[0]);
        panel.item(footer);
        return panel;
    }

    /** One preset: its name, a Pick button, its spell choice and its items as slots (vanilla tooltips on hover). */
    private static Flex kitBlock(HubTheme.Palette p, Shown current, Kit kit, boolean isCurrent, float width, Runnable close) {
        Flex block = Flex.column().gap(3).padding(Insets.of(5, 6, 5, 6));
        block.sizing(Sizing.fill(), Sizing.content());
        block.backgroundFill(isCurrent ? p.rowBgAlt() : p.rowBg(), isCurrent ? p.accent() : p.hairline(), 1);

        Flex head = Flex.row().gap(8).alignItems(Align.CENTER);
        head.sizing(Sizing.fill(), Sizing.content());
        var title = Component.literal(kit.name())
                .append(Component.translatable("gui.eden_ld.rental_picker.kit_level", kit.level()));
        if (kit.recommended()) {
            title.append(Component.literal("  ")).append(Component.translatable("gui.eden_ld.rental_picker.recommended")
                    .withStyle(s -> s.withColor(p.good() & 0xFFFFFF)));
        }
        Label name = Label.of(40, title).color(isCurrent ? p.accent() : p.ink()).tooltipWhenCut(true);
        name.sizing(Sizing.expand(), Sizing.content());
        head.item(name);
        // The spell book's spells: toggles, up to the book's size, starting from the server's prefill.
        Set<String> selected = new LinkedHashSet<>(kit.chosen());
        Button[] pick = new Button[1];
        Runnable refreshPick = () -> {
            boolean changed = !new ArrayList<>(selected).equals(kit.chosen());
            pick[0].label(Component.translatable(isCurrent && !changed ? "gui.eden_ld.rental_picker.picked"
                    : isCurrent ? "gui.eden_ld.rental_picker.save_spells" : "gui.eden_ld.rental_picker.pick"));
            pick[0].enabled((!isCurrent || changed) && (kit.spells().isEmpty() || !selected.isEmpty()));
        };
        float pickWidth = Math.max(textWidth(Component.translatable("gui.eden_ld.rental_picker.save_spells")),
                textWidth(Component.translatable("gui.eden_ld.rental_picker.pick"))) + 12;
        pick[0] = button(Component.translatable("gui.eden_ld.rental_picker.pick"), pickWidth, () -> {
            if (current.finished) {
                return;
            }
            current.finished = true; // the server keeps the request; nothing more to tell it when this closes
            send(new RentalPickerChoice(current.requestId, kit.kitId(), new ArrayList<>(selected)));
            close.run();
        });
        refreshPick.run();
        head.item(pick[0]);
        block.item(head);

        int slotsPerRow = Math.max(1, Math.min(SLOTS_PER_ROW, (int) (width - 10) / 22));
        // The kit's book: its spell power goes into the spell tooltips' values, and its item slot shows the pick.
        ItemStack book = kit.spells().isEmpty() ? ItemStack.EMPTY : kit.items().stream()
                .filter(RentalSpells::hasContainer).findFirst().orElse(ItemStack.EMPTY);
        Supplier<ItemStack> shownBook = () -> RentalSpells.withSpells(book,
                kit.spells().stream().filter(selected::contains).toList());
        Slot[] bookSlot = new Slot[1];
        if (!kit.spells().isEmpty()) {
            List<SpellSlot> all = new ArrayList<>();
            Runnable changed = () -> {
                for (SpellSlot slot : all) {
                    slot.refresh(); // only on a click: nothing is polled per frame
                }
                if (bookSlot[0] != null) {
                    bookSlot[0].stack(shownBook.get());
                }
                refreshPick.run();
            };
            if (kit.perTier()) {
                // One spell per tier: a row per tier, picked like radio buttons.
                block.item(EdenParchment.text(Component.translatable("gui.eden_ld.rental_picker.spells_per_tier"), p.inkMid()));
                List<Integer> tiers = kit.spellTiers().stream().distinct().toList();
                for (int r = 0; r < tiers.size(); r++) {
                    int tier = tiers.get(r);
                    List<String> mates = new ArrayList<>();
                    for (int i = 0; i < kit.spells().size(); i++) {
                        if (kit.spellTiers().get(i) == tier) {
                            mates.add(kit.spells().get(i));
                        }
                    }
                    Flex row = Flex.row().gap(2).alignItems(Align.CENTER);
                    row.sizing(Sizing.fill(), Sizing.content());
                    Label label = Label.of(56, Component.translatable("gui.eden_ld.rental_picker.spell_tier", r + 1))
                            .color(p.inkDim());
                    label.sizing(Sizing.fixed(56), Sizing.content());
                    row.item(label);
                    block.item(row);
                    int spellsPerRow = Math.max(1, Math.min(SLOTS_PER_ROW, (int) (width - 68) / 22));
                    for (int i = 0; i < mates.size(); i++) {
                        if (i > 0 && i % spellsPerRow == 0) {
                            row = block.item(Flex.row().gap(2).padding(Insets.of(0, 0, 0, 58)));
                            row.sizing(Sizing.fill(), Sizing.content());
                        }
                        SpellSlot slot = new SpellSlot(p, mates.get(i), book, selected, kit.maxSpells(), mates, changed);
                        all.add(slot);
                        row.item(slot);
                    }
                }
            } else {
                Label counter = Label.of(width - 12, spellCounter(selected.size(), kit.maxSpells())).color(p.inkMid());
                counter.sizing(Sizing.fill(), Sizing.content());
                block.item(counter);
                Runnable inner = changed;
                changed = () -> {
                    counter.text(spellCounter(selected.size(), kit.maxSpells()));
                    inner.run();
                };
                Flex row = null;
                for (int i = 0; i < kit.spells().size(); i++) {
                    if (i % slotsPerRow == 0) {
                        row = Flex.row().gap(2);
                        row.sizing(Sizing.fill(), Sizing.content());
                        block.item(row);
                    }
                    SpellSlot slot = new SpellSlot(p, kit.spells().get(i), book, selected, kit.maxSpells(), null, changed);
                    all.add(slot);
                    row.item(slot);
                }
            }
        }

        Flex slots = null;
        for (int i = 0; i < kit.items().size(); i++) {
            if (i % slotsPerRow == 0) {
                slots = Flex.row().gap(2);
                slots.sizing(Sizing.fill(), Sizing.content());
                block.item(slots);
            }
            ItemStack item = kit.items().get(i);
            boolean isBook = item == book && !book.isEmpty();
            Slot slot = new Slot(p, isBook ? shownBook.get() : item);
            if (isBook) {
                bookSlot[0] = slot; // shows the current pick, so its tooltip lists the picked spells
            }
            slots.item(slot);
        }
        if (kit.items().isEmpty()) {
            block.item(EdenParchment.text(Component.translatable("gui.eden_ld.rental_picker.no_preview"), p.inkDim()));
        }
        return block;
    }

    /** "Spells in the book: 2 / 3". */
    private static Component spellCounter(int picked, int max) {
        return Component.translatable("gui.eden_ld.rental_picker.spells", picked, max);
    }

    // ---------------------------------------------------------------- spells

    /** Spell Engine's name key, the id's path when it has none. */
    private static Component spellName(String spellId) {
        ResourceLocation id = ResourceLocation.tryParse(spellId);
        String key = id == null ? spellId : "spell." + id.getNamespace() + "." + id.getPath() + ".name";
        String fallback = id == null ? spellId : id.getPath().replace('_', ' ');
        return Component.translatableWithFallback(key, fallback);
    }

    private static @Nullable Method spellEntry;
    private static boolean spellEntryLooked;

    /**
     * Spell Engine's own tooltip of a spell, as on the spell binding table (name, description with values, cast,
     * range, cooldown). Read by reflection (Eden runs without Spell Engine); name + description when it can't.
     */
    @SuppressWarnings("unchecked")
    static List<Component> spellTooltip(String spellId, ItemStack book) {
        ResourceLocation id = ResourceLocation.tryParse(spellId);
        Player player = Minecraft.getInstance().player;
        if (id != null && player != null) {
            if (!spellEntryLooked) {
                spellEntryLooked = true;
                try {
                    spellEntry = Class.forName("net.spell_engine.client.gui.SpellTooltip").getMethod("spellEntry",
                            ResourceLocation.class, Player.class, ItemStack.class, boolean.class, int.class);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    spellEntry = null;
                }
            }
            if (spellEntry != null) {
                try {
                    List<Component> lines = (List<Component>) spellEntry.invoke(null, id, player, book, true, 0);
                    if (lines != null && !lines.isEmpty()) {
                        return new ArrayList<>(lines);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // Fall back to the name and description.
                }
            }
        }
        List<Component> out = new ArrayList<>();
        out.add(spellName(spellId).copy().withStyle(ChatFormatting.BOLD));
        if (id != null) {
            String key = "spell." + id.getNamespace() + "." + id.getPath() + ".description";
            if (I18n.exists(key)) {
                out.add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
            }
        }
        return out;
    }

    /** Whether a spell icon texture exists, checked once per texture (a missing one draws no icon). */
    private static final Map<ResourceLocation, Boolean> ICONS = new HashMap<>();

    private static @Nullable ResourceLocation icon(String spell) {
        ResourceLocation id = ResourceLocation.tryParse(spell);
        if (id == null) {
            return null;
        }
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                "textures/spell/" + id.getPath() + ".png");
        boolean exists = ICONS.computeIfAbsent(texture,
                t -> Minecraft.getInstance().getResourceManager().getResource(t).isPresent());
        return exists ? texture : null;
    }

    /**
     * One spell of the book's pool: its Spell Engine icon in a slot. A spell in the book is framed in the accent color,
     * the others are dimmed. One per tier ({@code rowMates}): a click picks it instead of its row mate. Free choice
     * (no row mates): a click puts it in or takes it out, ignored while the book is full. Built once; it changes only
     * on a click ({@link #refresh} from the kit block), and its tooltip lines are built on the first hover.
     */
    private static final class SpellSlot extends Widget {
        private final HubTheme.Palette p;
        private final String spell;
        private final Set<String> selected;
        private final int max;
        private final @Nullable List<String> rowMates;
        private final Runnable changed;
        private final @Nullable ResourceLocation icon;
        private @Nullable List<Component> spellLines;

        SpellSlot(HubTheme.Palette p, String spell, ItemStack book, Set<String> selected, int max,
                  @Nullable List<String> rowMates, Runnable changed) {
            super(20, 20);
            this.p = p;
            this.spell = spell;
            this.selected = selected;
            this.max = max;
            this.rowMates = rowMates;
            this.changed = changed;
            this.icon = icon(spell);
            tooltip(() -> {
                if (spellLines == null) {
                    spellLines = spellTooltip(spell, book); // once, on the first hover
                }
                boolean on = selected.contains(spell);
                String hint = rowMates != null
                        ? (on ? "gui.eden_ld.rental_picker.spell_in_book" : "gui.eden_ld.rental_picker.spell_swap")
                        : on ? "gui.eden_ld.rental_picker.spell_click_off"
                        : selected.size() >= max ? "gui.eden_ld.rental_picker.spell_full"
                        : "gui.eden_ld.rental_picker.spell_click_on";
                List<Component> lines = new ArrayList<>(spellLines.size() + 1);
                lines.addAll(spellLines);
                lines.add(Component.translatable(hint).withStyle(rowMates == null && !on && selected.size() >= max
                        ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
                return lines;
            });
        }

        @Override
        protected void rebuild() {
            boolean on = selected.contains(spell);
            boolean hover = hovered || focused;
            int bg = on ? p.rowBgAlt() : hover ? p.rowBg() : (p.hairline() & 0x00FFFFFF) | 0x55000000;
            int frame = focused ? p.ink() : on ? p.accent() : hover ? p.inkMid() : (p.hairline() & 0x00FFFFFF) | 0x88000000;
            add(Shapes.rect(0, 0, 20, 20).fill(bg).stroke(frame, on ? 2 : 1));
            if (icon != null) {
                add(ImageNode.of(icon, 2, 2, 16, 16, 16, 16).tint(on ? 0xFFFFFFFF : hover ? 0xFFB0B0B0 : 0xFF6E6E6E));
            }
        }

        @Override
        public boolean focusable() {
            return true;
        }

        @Override
        public boolean onKeyPressed(int key, int scanCode, int modifiers) {
            return switch (key) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> onMouseDown(0, 0, 0);
                default -> false;
            };
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            if (rowMates != null) {
                if (selected.contains(spell)) {
                    return true; // a row always keeps one
                }
                selected.removeAll(rowMates);
                selected.add(spell);
            } else if (selected.contains(spell)) {
                selected.remove(spell);
            } else if (selected.size() < max) {
                selected.add(spell);
            } else {
                return true; // full: switch one off first (the tooltip says so)
            }
            UiSounds.click();
            changed.run();
            return true;
        }
    }

    /** An item in a slot: count and durability, the vanilla tooltip on hover. */
    private static final class Slot extends Widget {
        private final HubTheme.Palette p;
        private ItemStack stack;

        Slot(HubTheme.Palette p, ItemStack stack) {
            super(20, 20);
            this.p = p;
            this.stack = stack;
        }

        void stack(ItemStack stack) {
            this.stack = stack;
            refresh();
        }

        @Override
        protected void rebuild() {
            add(Shapes.rect(0, 0, 20, 20).fill((p.hairline() & 0x00FFFFFF) | 0x55000000));
            add(ItemStackNode.of(stack, 2, 2, 16));
        }
    }

    /**
     * "Closes in 12 s" over a thin bar that drains to the deadline (counted here from when the picker arrived). At 0
     * it stays at 0: the server closes the picker. Rebuilt only when the second or the bar's pixel changes.
     */
    private static final class DeadlineBar extends Widget {
        private static final float BAR_Y = 11;
        private static final float BAR_H = 3;
        private final long deadlineAt;
        private final long total;
        private final HubTheme.Palette p;

        DeadlineBar(float width, long deadlineAt, long total, HubTheme.Palette p) {
            super(width, BAR_Y + BAR_H);
            this.deadlineAt = deadlineAt;
            this.total = Math.max(1, total);
            this.p = p;
            sizing(Sizing.fill(), Sizing.fixed(BAR_Y + BAR_H));
            bind(() -> List.of(secondsLeft(), filled()));
        }

        private long left() {
            return Math.max(0, deadlineAt - System.currentTimeMillis());
        }

        private int secondsLeft() {
            return (int) Math.ceil(left() / 1000.0);
        }

        private int filled() {
            return Math.round(width * Math.min(1f, left() / (float) total));
        }

        @Override
        protected void rebuild() {
            int seconds = secondsLeft();
            int color = seconds <= 5 ? p.danger() : p.inkMid();
            add(TextNode.of(Component.translatable("gui.eden_ld.rental_picker.closes_in", seconds))
                    .color(color).shadow(EdenParchment.TEXT_SHADOW));
            add(Shapes.rect(0, BAR_Y, width, BAR_H).fill(p.barTrack()));
            int fill = filled();
            if (fill > 0) {
                add(Shapes.rect(0, BAR_Y, fill, BAR_H).fill(seconds <= 5 ? p.danger() : p.accent()));
            }
        }
    }
}
