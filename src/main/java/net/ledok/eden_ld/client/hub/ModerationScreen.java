package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.HomeData;
import net.ledok.eden_ld.hub.HubPayloads.MailDraftData;
import net.ledok.eden_ld.hub.HubPayloads.MailText;
import net.ledok.eden_ld.hub.HubPayloads.MailView;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.Theme;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Dropdown;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.NumberField;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.Toast;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.ledok.vectorlib.client.presentation.CanvasHandledScreen;
import net.ledok.vectorlib.client.presentation.Placement;
import net.ledok.vectorlib.client.text.Placeholder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The staff Moderation window: a plain, dense editor (not the hub theme) with sections on the
 * left. <b>Mail</b>: a composer — recipients (players, everyone, LuckPerms groups), sender, expiry,
 * coins, one title and Markdown text per language (VectorLib's editor with the server's
 * placeholders), items in ghost slots (click one in your inventory, or drag from EMI; copies, the
 * admin keeps theirs), a live preview exactly as the Mail tab will show it — and drafts saved on
 * the server while typing. <b>Mail log</b>: sent staff mail with read / claimed counts and recall.
 * <b>Players</b> and <b>Punishments</b> (with BanHammer): see {@link PunishSection}.
 * <p>
 * It is a container screen (with a client-only, slot-less menu) only so EMI shows its panels.
 */
public final class ModerationScreen extends CanvasHandledScreen<net.ledok.eden_ld.hub.moderation.ModerationMenu> {

    static final float WIDTH = 532;
    static final float HEIGHT = 300;
    private static final float SIDEBAR = 92;
    private static final float FORM = 244;
    /** The composer's right column (inventory, send): nine 19-unit slots. */
    private static final float RIGHT = 172;
    /** Room kept free on the right for EMI's item list when EMI is installed. */
    private static final float EMI_ROOM = 130;
    private static final boolean EMI = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("emi");
    private static final DateTimeFormatterHolder DATES = new DateTimeFormatterHolder();
    private static final float SLOT = 18;

    static final int BG = 0xFF1D2024;
    static final int PANEL = 0xFF262A30;
    static final int LINE = 0xFF3A4048;
    static final int TEXT = 0xFFE4E6EA;
    static final int DIM = 0xFF9AA0AA;
    static final int ACCENT = 0xFF4A9EFF;
    static final int GOOD = 0xFF5CC46A;
    static final int BAD = 0xFFE06050;

    /** A picked option among buttons (section, audience, kind, duration, group): blue, accent-framed. */
    static final net.ledok.vectorlib.client.canvas.widget.WidgetStyle SELECTED = selectedStyle();

    private static net.ledok.vectorlib.client.canvas.widget.WidgetStyle selectedStyle() {
        var m = net.ledok.vectorlib.client.canvas.widget.WidgetStyle.MODERN;
        var on = new net.ledok.vectorlib.client.canvas.widget.WidgetStyle.Skin.Flat(0xFF24497E, 0xFF3F9EFF, 1, 2);
        var hover = new net.ledok.vectorlib.client.canvas.widget.WidgetStyle.Skin.Flat(0xFF2C5896, 0xFF7CC0FF, 1, 2);
        return new net.ledok.vectorlib.client.canvas.widget.WidgetStyle(on, hover, on, m.buttonDisabled(), m.field(),
                m.fieldFocused(), m.popup(), 0xFFFFFFFF, 0xFFFFFFFF, m.textDisabled(), m.hint(), m.accent(), m.selection(),
                m.buttonHeight(), m.fieldHeight(), m.padding(), m.textShadow());
    }

    private enum Section { MAIL, LOG, NEWS, CUSTOMIZATION, ACTIVITIES, PLAYERS, PUNISH_LOG, GUILDS, PARTIES, WORLD, SERVER }

    /** The window open now (payload handlers update it). */
    @Nullable
    private static ModerationScreen open;

    private final Screen parent;
    private final Flex root;
    private final Flex main;
    private Section section = Section.MAIL;
    private float fit = 1f;
    private float offsetX;
    @Nullable
    private Dropdown<Long> draftList;

    @Nullable
    private HubPayloads.ModInfo info;
    /** A player to open once the server's info is in (which tabs this staff member has decides their page). */
    @Nullable
    private java.util.UUID pendingPlayer;
    private String pendingName = "";
    private Draft draft;
    private String editing = "en_us";
    private boolean dirty;
    private long changedAt;
    private boolean logEveryone;
    @Nullable
    private HubPayloads.MailLog log;
    private long logSelected = -1;
    /** The draft just sent: when the server's refresh arrives, the composer starts a new one. */
    private long sentDraft = -1;

    private final List<GhostSlot> slots = new ArrayList<>();
    @Nullable
    private Button sendButton;
    private final PunishSection punish = new PunishSection(this, WIDTH - SIDEBAR - 16);
    private final CustomizationSection customization = new CustomizationSection(this, WIDTH - SIDEBAR - 16);
    private final ServerSections serverSections = new ServerSections(this, WIDTH - SIDEBAR - 16);
    private final ActivitiesSection activities = new ActivitiesSection(this, WIDTH - SIDEBAR - 16);
    private final WorldSection world = new WorldSection(this, WIDTH - SIDEBAR - 16);

    /** The news as edited here (published with Publish); loaded from the server until edited. */
    private final Map<String, String> newsTexts = new LinkedHashMap<>();
    private String newsDefault = "en_us";
    private String newsEditing = "en_us";
    private boolean newsDirty;

    private ModerationScreen(Screen parent) {
        super(new net.ledok.eden_ld.hub.moderation.ModerationMenu(0, Minecraft.getInstance().player.getInventory()),
                Minecraft.getInstance().player.getInventory(), Component.translatable("gui.eden_ld.moderation.title"),
                VectorCanvas.create(WIDTH, HEIGHT), Placement.Screen.center());
        this.parent = parent;
        canvas().theme(Theme.MODERN);
        overlays(true);
        root = Flex.row();
        root.sizing(Sizing.fixed(WIDTH), Sizing.fixed(HEIGHT));
        root.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(BG).stroke(Stroke.of(LINE, 1)));
        main = Flex.column().gap(4).padding(Insets.of(6, 8, 6, 8));
        main.sizing(Sizing.expand(), Sizing.fill());
        canvas().add(root);
        rebuild();
    }

    /** A player's page in the Players section (the chat's message menu: "Moderate"). */
    public static void openPlayerFrom(Screen parent, java.util.UUID player, String name) {
        ClientPlayNetworking.send(HubPayloads.ModOpen.INSTANCE);
        ModerationScreen screen = new ModerationScreen(parent);
        screen.section = Section.PLAYERS;
        screen.pendingPlayer = player;
        screen.pendingName = name;
        Minecraft.getInstance().setScreen(screen);
    }

    /**
     * Back on the Activities section with {@code activity} picked (after a source's admin screen, see
     * {@link ModerationReturn}); {@code parent} is where Esc goes from here (null: the game).
     */
    static void openActivitiesFrom(@Nullable Screen parent, String activity) {
        ClientPlayNetworking.send(HubPayloads.ModOpen.INSTANCE);
        ModerationScreen screen = new ModerationScreen(parent);
        screen.section = Section.ACTIVITIES;
        screen.activities.select(activity);
        Minecraft.getInstance().setScreen(screen);
        screen.activities.request();
    }

    /** From the hub's Moderation tab. */
    public static void openFrom(Screen parent) {
        ClientPlayNetworking.send(HubPayloads.ModOpen.INSTANCE);
        Minecraft.getInstance().setScreen(new ModerationScreen(parent));
    }

    @Override
    protected void init() {
        super.init();
        float room = EMI ? EMI_ROOM : 0;
        fit = Math.min(1f, Math.min((width - 8f - room) / WIDTH, (height - 8f) / HEIGHT));
        // With EMI the window moves left, leaving its item list room on the right.
        float w = WIDTH * fit;
        float centered = (width - w) / 2f;
        float left = Math.max(4f, (width - room - w) / 2f);
        offsetX = left - centered;
        placement(Placement.Screen.center().scale(fit).offset(offsetX, 0));
        open = this;
    }

    /** A staff window, not an inventory: the inventory key (E) doesn't close it — Esc does. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            input.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // Nothing here is a real container: no slot clicks, no "clicked outside" drops.
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top, int button) {
        return false;
    }

    @Override
    protected void slotClicked(net.minecraft.world.inventory.Slot slot, int slotId, int button,
                               net.minecraft.world.inventory.ClickType type) {
    }

    @Override
    public void removed() {
        super.removed();
        saveNow();
        customization.flush();
        activities.flush();
        if (open == this) {
            open = null;
        }
    }

    @Override
    public void onClose() {
        // Left by the admin (Esc): an Admin screen still on its way isn't followed back here.
        ModerationReturn.disarm();
        Minecraft.getInstance().setScreen(parent);
    }

    /** Autosave: a draft is saved a moment after the last change. */
    @Override
    protected void onCanvasFrame(float partialTick) {
        autosave();
        customization.tick();
        // The live poll runs only while the section shows (and stops with the window).
        activities.tick(section == Section.ACTIVITIES);
        if (section == Section.WORLD) {
            world.tick();
        }
    }

    /** A draft is saved a moment after the last change. */
    private void autosave() {
        if (dirty && System.currentTimeMillis() - changedAt > 800) {
            saveNow();
        }
    }

    private void changed() {
        dirty = true;
        changedAt = System.currentTimeMillis();
        if (draftList != null) {
            draftList.options(draftIds());
        }
    }

    private void saveNow() {
        if (dirty && draft != null && info != null && (info.staff() & HomeData.STAFF_MAIL) != 0) {
            dirty = false;
            ClientPlayNetworking.send(new HubPayloads.DraftSave(draft.toData(), false));
        }
    }

    // ---------------------------------------------------------------- server answers

    public static void onInfo(HubPayloads.ModInfo info) {
        if (open == null) {
            return;
        }
        ModerationScreen screen = open;
        boolean first = screen.info == null;
        screen.info = info;
        if (!screen.newsDirty) {
            screen.loadNews(info.news());
        }
        screen.customization.load(info.backgroundRules(), info.themeRules());
        if (first || screen.draft == null || screen.draft.id == screen.sentDraft) {
            screen.draft = info.drafts().isEmpty() ? Draft.fresh(info) : Draft.of(info.drafts().get(0));
            screen.editing = screen.draft.defaultLanguage;
        }
        if (first && screen.pendingPlayer != null && screen.allowed(Section.PLAYERS)) {
            // Opened on a player (the chat's "Moderate"): their page, now that their tabs are known.
            java.util.UUID player = screen.pendingPlayer;
            screen.pendingPlayer = null;
            screen.openPlayer(player, screen.pendingName);
            return;
        }
        if (first && !screen.allowed(screen.section)) {
            for (Section section : Section.values()) {
                if (screen.allowed(section)) {
                    screen.show(section);
                    return;
                }
            }
        }
        screen.rebuild();
    }

    public static void onCheck(HubPayloads.MailSendCheck check) {
        if (open != null) {
            open.confirmSend(check);
        }
    }

    public static void onLog(HubPayloads.MailLog log) {
        if (open != null) {
            open.log = log;
            if (open.section == Section.LOG) {
                open.rebuild();
            }
        }
    }

    public static void onPlayers(HubPayloads.PunishPlayers players) {
        if (open != null) {
            open.punish.onPlayers(players);
        }
    }

    public static void onPage(HubPayloads.PunishPage page) {
        if (open != null && open.section == Section.PLAYERS) {
            open.punish.onPage(page);
        }
    }

    public static void onPunishLog(HubPayloads.PunishLog log) {
        if (open != null) {
            open.punish.onLog(log);
            if (open.section == Section.PUNISH_LOG) {
                open.rebuild();
            }
        }
    }

    public static void onAdminData(HubPayloads.AdminData data) {
        if (open == null) {
            return;
        }
        if (open.activities.onData(data.area(), data.data())) {
            if (open.section == Section.ACTIVITIES) {
                open.rebuild();
            }
        } else if (open.world.onData(data.area(), data.data())) {
            if (open.section == Section.WORLD) {
                open.world.refresh();
            }
        } else if (open.serverSections.onData(data.area(), data.data())) {
            Section shown = switch (data.area()) {
                case net.ledok.eden_ld.hub.moderation.AdminTools.GUILDS -> Section.GUILDS;
                case net.ledok.eden_ld.hub.moderation.AdminTools.PARTIES -> Section.PARTIES;
                default -> Section.SERVER;
            };
            if (open.section == shown) {
                open.rebuild();
            }
        } else if (open.section == Section.PLAYERS) {
            open.punish.onAdminData(data);
        }
    }

    /** The Activities section's live poll answer (markers, the picked activity's status and history). */
    public static void onActivityLive(HubPayloads.ActivityLive live) {
        if (open != null) {
            open.activities.onLive(live.data(), open.section == Section.ACTIVITIES);
        }
    }

    public static void onNotice(HubPayloads.ModNotice notice) {
        if (!notice.ok()) {
            // A refused Admin screen opens nothing: don't follow the next screen the admin opens.
            ModerationReturn.disarm();
        }
        if (open != null) {
            Toast.text(open.canvas(), notice.message(), notice.ok() ? GOOD : BAD);
        }
    }

    /** Where the window sits on screen (EMI keeps its panels beside it). */
    public int[] screenBounds() {
        int w = Math.round(WIDTH * fit);
        int h = Math.round(HEIGHT * fit);
        return new int[]{Math.round((width - w) / 2f + offsetX), (height - h) / 2, w, h};
    }

    /** An item dragged from EMI onto a ghost slot (screen coordinates); false when it isn't over one. */
    public boolean emiDrop(double screenX, double screenY, ItemStack stack) {
        int[] bounds = screenBounds();
        float x = (float) ((screenX - bounds[0]) / fit);
        float y = (float) ((screenY - bounds[1]) / fit);
        for (GhostSlot slot : slots) {
            Rect r = slot.rootBounds();
            if (r != null && x >= r.x() && x < r.right() && y >= r.y() && y < r.bottom()) {
                slot.set(stack.copyWithCount(1), 1);
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- layout

    private void rebuild() {
        root.clear();
        root.item(sidebar());
        main.clear();
        slots.clear();
        sendButton = null;
        if (info == null) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
        } else {
            switch (section) {
                case MAIL -> buildComposer();
                case LOG -> buildLog();
                case PLAYERS -> punish.buildPlayers(main);
                case PUNISH_LOG -> punish.buildLog(main);
                case NEWS -> buildNews();
                case CUSTOMIZATION -> customization.build(main, info.groups(),
                        (info.staff() & HomeData.STAFF_BACKGROUNDS) != 0, (info.staff() & HomeData.STAFF_THEMES) != 0);
                case ACTIVITIES -> activities.build(main);
                case GUILDS -> serverSections.buildGroups(main, net.ledok.eden_ld.hub.moderation.AdminTools.GUILDS);
                case PARTIES -> serverSections.buildGroups(main, net.ledok.eden_ld.hub.moderation.AdminTools.PARTIES);
                case WORLD -> world.build(main);
                case SERVER -> serverSections.buildServer(main);
            }
        }
        root.item(main);
        root.layoutIn(WIDTH, HEIGHT);
    }

    /** Where Esc goes from this window. */
    @Nullable
    Screen parent() {
        return parent;
    }

    /** The admin's rights ({@code HomeData.STAFF_*} bits). */
    int staff() {
        return info == null ? 0 : info.staff();
    }

    /** Builds the open section again (after a change inside it). */
    void rebuildNow() {
        rebuild();
    }

    /** Lays the window out again after a part of it was refilled. */
    void relayout() {
        if (root.canvas() != null) {
            root.layoutIn(WIDTH, HEIGHT);
        }
    }

    /** From the punishment log: that player's page in the Players section. */
    void openPlayer(java.util.UUID player, String name) {
        section = Section.PLAYERS;
        punish.showPlayer(player, name);
        rebuild();
    }

    private boolean allowed(Section target) {
        int staff = info == null ? 0 : info.staff();
        return switch (target) {
            case MAIL -> (staff & HomeData.STAFF_MAIL) != 0;
            case LOG -> (staff & (HomeData.STAFF_MAIL | HomeData.STAFF_MAIL_LOG)) != 0;
            case PLAYERS -> (staff & HomeData.STAFF_PLAYER_TABS) != 0;
            case PUNISH_LOG -> (staff & HomeData.STAFF_PUNISH_LOG) != 0;
            case NEWS -> (staff & HomeData.STAFF_NEWS) != 0;
            case CUSTOMIZATION -> (staff & (HomeData.STAFF_BACKGROUNDS | HomeData.STAFF_THEMES)) != 0;
            case ACTIVITIES -> (staff & HomeData.STAFF_DUNGEONS) != 0;
            case GUILDS -> (staff & HomeData.STAFF_GUILDS) != 0;
            case PARTIES -> (staff & HomeData.STAFF_PARTIES) != 0;
            case WORLD -> (staff & HomeData.STAFF_WORLD) != 0;
            case SERVER -> (staff & (HomeData.STAFF_EMOJIS | HomeData.STAFF_COMMANDS)) != 0;
        };
    }

    private void show(Section target) {
        saveNow();
        section = target;
        switch (target) {
            case LOG -> ClientPlayNetworking.send(new HubPayloads.MailLogRequest(logEveryone, 0));
            case PLAYERS -> punish.requestResults();
            case PUNISH_LOG -> ClientPlayNetworking.send(HubPayloads.PunishLogRequest.INSTANCE);
            case ACTIVITIES -> activities.request();
            case GUILDS -> serverSections.request(net.ledok.eden_ld.hub.moderation.AdminTools.GUILDS);
            case PARTIES -> serverSections.request(net.ledok.eden_ld.hub.moderation.AdminTools.PARTIES);
            case WORLD -> world.request();
            case SERVER -> serverSections.request(net.ledok.eden_ld.hub.moderation.AdminTools.SERVER);
            default -> {
            }
        }
        rebuild();
    }

    private Flex sidebar() {
        Flex side = Flex.column().gap(3).padding(Insets.of(6, 6, 6, 6));
        side.sizing(Sizing.fixed(SIDEBAR), Sizing.fill());
        side.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL));
        side.item(text(Component.translatable("gui.eden_ld.moderation.title"), TEXT));
        side.item(gap(4));
        // Only what this staff member may use.
        for (Section target : Section.values()) {
            if (!allowed(target)) {
                continue;
            }
            if ((target == Section.PLAYERS || target == Section.NEWS || target == Section.GUILDS)
                    && side.children().size() > 2) {
                side.item(gap(6));
            }
            side.item(sectionButton(target, true));
        }
        return side;
    }

    private Button sectionButton(Section target, boolean allowed) {
        Button button = new Button(SIDEBAR - 12, 16,
                Component.translatable("gui.eden_ld.moderation.section." + target.name().toLowerCase(Locale.ROOT)),
                () -> show(target));
        button.enabled(allowed);
        if (target == section) {
            button.style(SELECTED);
        }
        return button;
    }

    // ---------------------------------------------------------------- mail composer

    private void buildComposer() {
        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.fill());
        Flex form = Flex.column().gap(4);
        form.sizing(Sizing.fixed(FORM), Sizing.fill());
        form.item(draftRow());
        form.item(audienceRow());
        form.item(recipients());
        form.item(detailsRow());
        form.item(languageRow());
        MailText current = draft.text(editing);
        TextField title = new TextField(FORM, current.title(), value -> {
            draft.setTitle(editing, value);
            changed();
        });
        title.size(FORM, 16);
        title.hint(Component.translatable("gui.eden_ld.moderation.title_hint").getString());
        title.maxLength(128);
        form.item(title);
        form.item(textBlock(current));
        form.item(slotRows());
        columns.item(form);

        Flex right = Flex.column().gap(4);
        right.sizing(Sizing.fixed(RIGHT), Sizing.fill());
        right.item(text(Component.translatable("gui.eden_ld.moderation.inventory"), DIM, (int) RIGHT));
        right.item(inventory());
        Flex spacer = Flex.column();
        spacer.sizing(Sizing.fill(), Sizing.expand());
        right.item(spacer);
        sendButton = new Button(RIGHT, 18, Component.translatable("gui.eden_ld.moderation.send"), this::checkSend);
        sendButton.sizing(Sizing.fill(), Sizing.fixed(18));
        right.item(sendButton);
        columns.item(right);
        main.item(columns);
    }

    /**
     * The mail's text, as far as it fits (placeholders filled like the preview), with the button to
     * the editor screen — clicking the text opens it too.
     */
    private Flex textBlock(MailText current) {
        Flex box = Flex.column().gap(2);
        box.sizing(Sizing.fixed(FORM), Sizing.expand());
        Flex head = Flex.row().gap(4).alignItems(Align.CENTER);
        head.sizing(Sizing.fill(), Sizing.content());
        head.item(text(Component.translatable("gui.eden_ld.moderation.text"), DIM));
        Flex spacer = Flex.row();
        spacer.sizing(Sizing.expand(), Sizing.fixed(1));
        head.item(spacer);
        head.item(small("gui.eden_ld.moderation.edit_text", 60, this::openTextEditor));
        box.item(head);
        MailView shown = draft.preview(editing, info);
        Component body = shown.texts().get(0).body().isBlank()
                ? Component.translatable("gui.eden_ld.moderation.no_text").withStyle(s -> s.withColor(DIM & 0xFFFFFF))
                : net.ledok.vectorlib.text.MarkdownText.parse(shown.texts().get(0).body());
        Flex excerpt = Flex.column().padding(Insets.of(3, 4, 3, 4));
        excerpt.sizing(Sizing.fill(), Sizing.expand());
        excerpt.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(0xFF14161A).stroke(Stroke.of(LINE, 1)));
        ScrollPanel scroll = new ScrollPanel(FORM - 8, 30, TextNode.of(body).color(TEXT).shadow(false).maxWidth(MailTab.TEXT_WIDTH));
        scroll.sizing(Sizing.fill(), Sizing.fill());
        excerpt.item(scroll);
        box.item(excerpt);
        return box;
    }

    /** A mail as the Mail tab shows it, in its parchment frame, {@code width} wide. */
    static Flex mailPreview(MailView mail) {
        // The Mail tab's open-mail panel, same width and padding, so lines wrap exactly as there.
        Flex framed = Flex.column().padding(Insets.of(4, 6, 4, 6));
        framed.sizing(Sizing.fixed(MailTab.DETAIL_WIDTH), Sizing.fill());
        framed.background(net.ledok.eden_ld.client.EdenParchment.framed(net.ledok.eden_ld.client.EdenParchment.ROW_BG,
                net.ledok.eden_ld.client.EdenParchment.HAIRLINE));
        ScrollPanel scroll = new ScrollPanel(MailTab.DETAIL_WIDTH - 12, 200, MailTab.content(mail, MailTab.TEXT_WIDTH));
        scroll.sizing(Sizing.fill(), Sizing.fill());
        framed.item(scroll);
        return framed;
    }

    /** The news panel's width on the Home tab (text, scroll lane, padding). */
    static final float NEWS_PANEL = HubScreen.NEWS_WIDTH + 6 + 16;

    /** The title and text of the language being edited, on the editor screen. */
    private void openTextEditor() {
        saveNow();
        String language = editing;
        MailText current = draft.text(language);
        Minecraft.getInstance().setScreen(new MailTextScreen(
                Component.translatable("gui.eden_ld.moderation.text_heading", languageName(language)),
                current.title(), current.body(), this::placeholders,
                MailTab.DETAIL_WIDTH, (title, body, width) -> mailPreview(draft.preview(language, info, title, body)),
                (title, body) -> {
                    draft.setTitle(language, title);
                    draft.setBody(language, body);
                    dirty = true;
                    changedAt = System.currentTimeMillis();
                },
                this::autosave,
                () -> {
                    Minecraft.getInstance().setScreen(this);
                    rebuild();
                }));
    }

    /** The admin's drafts, newest first, with the one being edited always in the list. */
    private List<Long> draftIds() {
        List<Long> ids = new ArrayList<>();
        ids.add(draft.id);
        for (MailDraftData d : info.drafts()) {
            if (d.id() != draft.id) {
                ids.add(d.id());
            }
        }
        return ids;
    }

    /** A draft's name in the list: the one given, else its title, else when it was started. */
    private Component draftLabel(long id) {
        if (id == draft.id) {
            return draft.label();
        }
        return info.drafts().stream().filter(d -> d.id() == id).findFirst().map(Draft::of).map(Draft::label)
                .orElse(Component.translatable("gui.eden_ld.moderation.untitled"));
    }

    private Flex draftRow() {
        Flex row = Flex.row().gap(3).alignItems(Align.CENTER);
        row.item(text(Component.translatable("gui.eden_ld.moderation.draft"), DIM));
        float listWidth = FORM - 3 * 17 - font.width(Component.translatable("gui.eden_ld.moderation.draft")) - 3;
        draftList = new Dropdown<>(listWidth, draftIds(), draft.id, this::draftLabel, id -> {
            if (id == draft.id) {
                return;
            }
            saveNow();
            info.drafts().stream().filter(d -> d.id() == id).findFirst().ifPresent(d -> {
                draft = Draft.of(d);
                editing = draft.defaultLanguage;
            });
            rebuild();
        });
        draftList.size(listWidth, 16);
        row.item(draftList);
        Button rename = icon("✎", this::renameDraft);
        HubIcons.button(rename, "rename");
        rename.tooltip(Component.translatable("gui.eden_ld.moderation.rename_draft"));
        row.item(rename);
        Button fresh = icon("+", () -> {
            saveNow();
            draft = Draft.fresh(info);
            editing = draft.defaultLanguage;
            rebuild();
        });
        fresh.tooltip(Component.translatable("gui.eden_ld.moderation.new_draft"));
        row.item(fresh);
        Button delete = icon("✕", this::confirmDeleteDraft);
        delete.tooltip(Component.translatable("gui.eden_ld.moderation.delete_draft"));
        row.item(delete);
        return row;
    }

    private void renameDraft() {
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(220), Sizing.content());
        dialog.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL).stroke(Stroke.of(LINE, 1)));
        dialog.item(text(Component.translatable("gui.eden_ld.moderation.rename_draft"), TEXT, 200));
        TextField name = new TextField(200, draft.name, value -> {
        });
        name.size(200, 16);
        name.maxLength(48);
        name.hint(Component.translatable("gui.eden_ld.moderation.draft_name_hint").getString());
        dialog.item(name);
        Modal[] modal = new Modal[1];
        Runnable apply = () -> {
            draft.name = name.text().strip();
            changed();
            saveNow();
            modal[0].close();
        };
        name.onSubmit(apply);
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        buttons.item(new Button(70, 16, Component.translatable("gui.eden_ld.moderation.save"), apply));
        buttons.item(new Button(60, 16, Component.translatable("gui.eden_ld.moderation.cancel"), () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(canvas(), dialog);
        modal[0].closeOnClickOutside(true);
        canvas().focus(name);
    }

    private void confirmDeleteDraft() {
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(220), Sizing.content());
        dialog.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL).stroke(Stroke.of(LINE, 1)));
        dialog.item(text(Component.translatable("gui.eden_ld.moderation.delete_draft_confirm", draft.label()), TEXT, 200));
        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        buttons.item(new Button(70, 16, Component.translatable("gui.eden_ld.moderation.delete"), () -> {
            ClientPlayNetworking.send(new HubPayloads.DraftSave(draft.toData(), true));
            dirty = false;
            draft = Draft.fresh(info);
            editing = draft.defaultLanguage;
            ClientPlayNetworking.send(HubPayloads.ModOpen.INSTANCE);
            modal[0].close();
        }));
        buttons.item(new Button(60, 16, Component.translatable("gui.eden_ld.moderation.cancel"), () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    private Flex audienceRow() {
        Flex row = Flex.row().gap(3).alignItems(Align.CENTER);
        row.item(text(Component.translatable("gui.eden_ld.moderation.to"), DIM));
        String[] names = {"players", "all", "groups"};
        for (int i = 0; i < names.length; i++) {
            int audience = i;
            Button button = small("gui.eden_ld.moderation.audience." + names[i], 60, () -> {
                draft.audience = audience;
                changed();
                rebuild();
            });
            if (draft.audience == i) {
                button.style(SELECTED);
            }
            row.item(button);
        }
        return row;
    }

    private Flex recipients() {
        Flex box = Flex.column().gap(3);
        box.sizing(Sizing.fixed(FORM), Sizing.content());
        if (draft.audience == MailDraftData.TO_ALL) {
            box.item(text(Component.translatable("gui.eden_ld.moderation.audience.all_hint"), DIM, (int) FORM));
            return box;
        }
        List<Button> chips = new ArrayList<>();
        if (draft.audience == MailDraftData.TO_GROUPS) {
            if (info.groups().isEmpty()) {
                box.item(text(Component.translatable("gui.eden_ld.moderation.no_groups"), DIM, (int) FORM));
            }
            for (String group : info.groups()) {
                boolean on = draft.groups.contains(group);
                Button chip = new Button(Minecraft.getInstance().font.width(group) + 14, 14,
                        Component.literal((on ? "✔ " : "") + group), () -> {
                            if (!draft.groups.remove(group)) {
                                draft.groups.add(group);
                            }
                            changed();
                            rebuild();
                        });
                if (on) {
                    chip.style(SELECTED);
                }
                chips.add(chip);
            }
            box.item(wrapped(chips, FORM, 3));
            return box;
        }
        Flex add = Flex.row().gap(3).alignItems(Align.CENTER);
        Button addButton = small("gui.eden_ld.moderation.add", 42, () -> {
        });
        float nameWidth = FORM - addButton.width() - 3;
        TextField name = new TextField(nameWidth, "", value -> {
        });
        name.size(nameWidth, 16);
        name.hint(Component.translatable("gui.eden_ld.moderation.player_hint").getString());
        name.maxLength(16);
        Runnable addName = () -> {
            String value = name.text().trim();
            if (!value.isEmpty() && draft.players.stream().noneMatch(p -> p.equalsIgnoreCase(value))) {
                draft.players.add(value);
                changed();
                rebuild();
            }
        };
        name.onSubmit(addName);
        addButton.onClick(addName);
        add.item(name);
        add.item(addButton);
        box.item(add);
        for (String player : draft.players) {
            Button chip = new Button(Minecraft.getInstance().font.width(player) + 16, 14,
                    Component.literal(player + " ✕"), () -> {
                        draft.players.remove(player);
                        changed();
                        rebuild();
                    });
            chips.add(chip);
        }
        if (chips.isEmpty()) {
            return box;
        }
        // Many names scroll in two rows' height instead of pushing the form down.
        Flex list = wrapped(chips, FORM - 8, 3);
        float rows = list.children().size();
        float height = Math.min(2, rows) * 14 + (Math.min(2, rows) - 1) * 3;
        ScrollPanel scroll = new ScrollPanel(FORM, height, list);
        scroll.sizing(Sizing.fixed(FORM), Sizing.fixed(height));
        box.item(scroll);
        if (rows > 2) {
            box.item(text(Component.translatable("gui.eden_ld.moderation.recipient_count", draft.players.size()), DIM));
        }
        return box;
    }

    private Flex detailsRow() {
        Flex row = Flex.row().gap(3).alignItems(Align.CENTER);
        TextField sender = new TextField(90, draft.sender, value -> {
            draft.sender = value;
            changed();
        });
        sender.size(90, 16);
        sender.maxLength(32);
        sender.tooltip(Component.translatable("gui.eden_ld.moderation.sender"));
        row.item(sender);
        row.item(text(Component.translatable("gui.eden_ld.moderation.days"), DIM));
        NumberField days = new NumberField(34, draft.expiryDays, value -> {
            draft.expiryDays = (int) value;
            changed();
        });
        days.range(1, info.maxExpiryDays());
        days.size(34, 16);
        row.item(days);
        row.item(text(Component.translatable("gui.eden_ld.moderation.coins"), DIM));
        // Whatever the labels (their length differs per language) leave, at least 40.
        var font = Minecraft.getInstance().font;
        float coinsWidth = Math.max(40, FORM - 90 - 34 - 5 * 3
                - font.width(Component.translatable("gui.eden_ld.moderation.days"))
                - font.width(Component.translatable("gui.eden_ld.moderation.coins")));
        NumberField coins = new NumberField(coinsWidth, draft.coins, value -> {
            draft.coins = value;
            changed();
        });
        coins.range(0, 1_000_000_000L).step(100);
        coins.size(coinsWidth, 16);
        row.item(coins);
        return row;
    }

    private Flex languageRow() {
        return languageRow(draft.texts.keySet(), editing, draft.defaultLanguage, FORM,
                language -> {
                    editing = language;
                    rebuild();
                },
                language -> {
                    draft.texts.put(language, new String[]{"", ""});
                    editing = language;
                    changed();
                    rebuild();
                },
                () -> {
                    draft.defaultLanguage = editing;
                    changed();
                    rebuild();
                },
                () -> {
                    draft.texts.remove(editing);
                    if (editing.equals(draft.defaultLanguage)) {
                        draft.defaultLanguage = draft.texts.keySet().iterator().next();
                    }
                    editing = draft.defaultLanguage;
                    changed();
                    rebuild();
                });
    }

    /**
     * Language tabs (★ marks the default), + Language, make-default and remove — for a mail's texts
     * and the news alike.
     */
    private Flex languageRow(java.util.Set<String> languages, String current, String defaultLanguage, float width,
                             java.util.function.Consumer<String> select, java.util.function.Consumer<String> add,
                             Runnable makeDefault, Runnable remove) {
        List<Widget> row = new ArrayList<>();
        for (String language : languages) {
            boolean isDefault = language.equals(defaultLanguage);
            Button tab = new Button(Minecraft.getInstance().font.width(language) + (isDefault ? 20 : 12), 14,
                    Component.literal((isDefault ? "★ " : "") + language), () -> select.accept(language));
            tab.tooltip(languageName(language));
            if (language.equals(current)) {
                tab.enabled(false);
            }
            row.add(tab);
        }
        List<String> codes = new ArrayList<>(Minecraft.getInstance().getLanguageManager().getLanguages().keySet());
        codes.removeAll(languages);
        if (!codes.isEmpty()) {
            codes.add(0, "");
            Dropdown<String> adder = new Dropdown<>(76, codes, "", code -> code.isEmpty()
                    ? Component.translatable("gui.eden_ld.moderation.add_language")
                    : Component.literal(code + " — ").append(languageName(code)), code -> {
                if (!code.isEmpty()) {
                    add.accept(code);
                }
            });
            adder.searchable(true);
            adder.size(76, 14);
            row.add(adder);
        }
        Button star = icon("★", makeDefault);
        star.tooltip(Component.translatable("gui.eden_ld.moderation.make_default"));
        star.enabled(!current.equals(defaultLanguage));
        row.add(star);
        Button cross = icon("✕", remove);
        cross.tooltip(Component.translatable("gui.eden_ld.moderation.remove_language"));
        cross.enabled(languages.size() > 1);
        row.add(cross);
        return wrapped(row, width, 2);
    }

    private Flex slotRows() {
        Flex box = Flex.column().gap(2);
        box.item(text(Component.translatable("gui.eden_ld.moderation.items", info.maxItems()), DIM, (int) FORM));
        int perRow = (int) (FORM / (SLOT + 2));
        Flex row = null;
        for (int i = 0; i < info.maxItems(); i++) {
            if (i % perRow == 0) {
                row = Flex.row().gap(2);
                box.item(row);
            }
            GhostSlot slot = new GhostSlot(i);
            slots.add(slot);
            row.item(slot);
        }
        return box;
    }

    /** The player's inventory: click an item to attach a copy (hotbar last, like vanilla). */
    private Flex inventory() {
        Flex grid = Flex.column().gap(1);
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return grid;
        }
        int[][] rows = {{9, 18}, {18, 27}, {27, 36}, {0, 9}};
        for (int[] range : rows) {
            Flex row = Flex.row().gap(1);
            for (int i = range[0]; i < range[1]; i++) {
                row.item(new InventorySlot(player.getInventory().items.get(i)));
            }
            grid.item(row);
        }
        return grid;
    }

    private List<Placeholder> placeholders() {
        List<Placeholder> list = new ArrayList<>();
        if (info != null) {
            for (HubPayloads.PlaceholderInfo entry : info.placeholders()) {
                list.add(Placeholder.of(entry.token(), entry.token(), entry.value().isEmpty() ? null : entry.value()));
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- sending

    private void checkSend() {
        saveNow();
        ClientPlayNetworking.send(new HubPayloads.MailSend(draft.toData(), false));
    }

    private void confirmSend(HubPayloads.MailSendCheck check) {
        if (check.draftId() != draft.id) {
            return;
        }
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(240), Sizing.content());
        dialog.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL).stroke(Stroke.of(LINE, 1)));
        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        if (!check.problems().isEmpty()) {
            dialog.item(text(Component.translatable("gui.eden_ld.moderation.cant_send"), BAD, 220));
            for (String problem : check.problems()) {
                dialog.item(text(Component.literal("• ").append(Component.translatable(problem)), TEXT, 220));
            }
        } else {
            dialog.item(text(check.recipients() < 0
                    ? Component.translatable("gui.eden_ld.moderation.confirm_all")
                    : Component.translatable("gui.eden_ld.moderation.confirm", check.recipients()), TEXT, 220));
            buttons.item(new Button(70, 16, Component.translatable("gui.eden_ld.moderation.send"), () -> {
                ClientPlayNetworking.send(new HubPayloads.MailSend(draft.toData(), true));
                dirty = false;
                sentDraft = draft.id;
                modal[0].close();
            }));
        }
        if (!check.unknownNames().isEmpty()) {
            dialog.item(text(Component.translatable("gui.eden_ld.moderation.unknown",
                    String.join(", ", check.unknownNames())), BAD, 220));
        }
        buttons.item(new Button(60, 16, Component.translatable("gui.eden_ld.moderation.cancel"), () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    // ---------------------------------------------------------------- mail log

    private void buildLog() {
        Flex header = Flex.row().gap(4).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        header.item(text(Component.translatable("gui.eden_ld.moderation.section.log"), TEXT));
        Flex spacer = Flex.row();
        spacer.sizing(Sizing.expand(), Sizing.fixed(1));
        header.item(spacer);
        if ((info.staff() & HomeData.STAFF_MAIL_LOG) != 0) {
            for (boolean everyone : new boolean[]{false, true}) {
                Button button = small(everyone ? "gui.eden_ld.moderation.log_everyone" : "gui.eden_ld.moderation.log_mine",
                        60, () -> {
                            logEveryone = everyone;
                            ClientPlayNetworking.send(new HubPayloads.MailLogRequest(everyone, 0));
                        });
                button.enabled(logEveryone != everyone);
                header.item(button);
            }
        }
        main.item(header);
        if (log == null) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.expand());
        Flex list = Flex.column().gap(2);
        if (log.mails().isEmpty()) {
            list.item(text(Component.translatable("gui.eden_ld.moderation.log_empty"), DIM, 200));
        }
        HubPayloads.SentMailView open = null;
        for (HubPayloads.SentMailView sent : log.mails()) {
            if (logSelected < 0) {
                logSelected = sent.mail().id();
            }
            if (sent.mail().id() == logSelected) {
                open = sent;
            }
            list.item(new LogRow(sent));
        }
        // Narrow enough to leave the mail its real Mail-tab width beside it.
        float listWidth = WIDTH - SIDEBAR - 16 - 8 - MailTab.DETAIL_WIDTH;
        ScrollPanel panel = new ScrollPanel(listWidth, 100, list);
        panel.sizing(Sizing.fixed(listWidth), Sizing.fill());
        columns.item(panel);
        Flex detail = Flex.column().gap(4);
        detail.sizing(Sizing.expand(), Sizing.fill());
        if (open != null) {
            HubPayloads.SentMailView shown = open;
            detail.item(text(Component.translatable("gui.eden_ld.moderation.log_to", shown.toAll()
                    ? Component.translatable("gui.eden_ld.moderation.audience.all") : Component.literal(shown.audienceNote())),
                    DIM, 200));
            detail.item(text(Component.translatable("gui.eden_ld.moderation.log_stats", shown.recipients(), shown.read(),
                    shown.claimed()), DIM, 200));
            Flex framed = Flex.column();
            framed.sizing(Sizing.fixed(MailTab.DETAIL_WIDTH), Sizing.expand());
            framed.item(mailPreview(shown.mail()));
            detail.item(framed);
            Button recall = new Button(80, 16, Component.translatable("gui.eden_ld.moderation.recall"), () -> confirmRecall(shown));
            detail.item(recall);
        }
        columns.item(detail);
        main.item(columns);
    }

    private void confirmRecall(HubPayloads.SentMailView sent) {
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(220), Sizing.content());
        dialog.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL).stroke(Stroke.of(LINE, 1)));
        dialog.item(text(Component.translatable("gui.eden_ld.moderation.recall_confirm"), TEXT, 200));
        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        buttons.item(new Button(70, 16, Component.translatable("gui.eden_ld.moderation.recall"), () -> {
            ClientPlayNetworking.send(new HubPayloads.MailLogRequest(logEveryone, sent.mail().id()));
            logSelected = -1;
            modal[0].close();
        }));
        buttons.item(new Button(60, 16, Component.translatable("gui.eden_ld.moderation.cancel"), () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    // ---------------------------------------------------------------- news

    private void loadNews(HubPayloads.NewsView news) {
        newsTexts.clear();
        for (MailText text : news.texts()) {
            newsTexts.put(text.language(), text.body());
        }
        if (newsTexts.isEmpty()) {
            newsTexts.put(Minecraft.getInstance().getLanguageManager().getSelected(), "");
        }
        newsDefault = newsTexts.containsKey(news.defaultLanguage()) ? news.defaultLanguage() : newsTexts.keySet().iterator().next();
        if (!newsTexts.containsKey(newsEditing)) {
            newsEditing = newsDefault;
        }
    }

    private HubPayloads.NewsView newsView() {
        List<MailText> texts = new ArrayList<>();
        newsTexts.forEach((language, body) -> texts.add(new MailText(language, "", body)));
        return new HubPayloads.NewsView(newsDefault, texts, 0, "");
    }

    private void newsChanged() {
        newsDirty = true;
        rebuild();
    }

    /**
     * The Home tab's news: one text per language, edited on the text screen, previewed as the
     * Home tab shows it (in the language picked here), and published to everyone with Publish.
     */
    private void buildNews() {
        float width = WIDTH - SIDEBAR - 16;
        Flex header = Flex.row().gap(6).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        header.item(text(Component.translatable("gui.eden_ld.moderation.section.news"), TEXT));
        HubPayloads.NewsView published = info.news();
        Component state = newsDirty ? Component.translatable("gui.eden_ld.moderation.news_unpublished")
                : published.updatedAt() > 0
                ? Component.translatable("gui.eden_ld.moderation.news_published_by", published.updatedBy(),
                DATES.format(published.updatedAt() * 1000L))
                : Component.translatable("gui.eden_ld.moderation.news_never");
        header.item(TextNode.of(state).color(newsDirty ? 0xFFE0C850 : DIM).shadow(false).maxWidth((int) width - 150).ellipsis(true));
        Flex spacer = Flex.row();
        spacer.sizing(Sizing.expand(), Sizing.fixed(1));
        header.item(spacer);
        Button revert = small("gui.eden_ld.moderation.news_revert", 40, () -> {
            newsDirty = false;
            loadNews(info.news());
            rebuild();
        });
        revert.enabled(newsDirty);
        header.item(revert);
        Button publish = small("gui.eden_ld.moderation.news_publish", 60, this::confirmPublish);
        publish.enabled(newsDirty);
        header.item(publish);
        main.item(header);

        Flex languages = Flex.row().gap(6).alignItems(Align.CENTER);
        languages.sizing(Sizing.fill(), Sizing.content());
        languages.item(languageRow(newsTexts.keySet(), newsEditing, newsDefault, width - 90,
                language -> {
                    newsEditing = language;
                    rebuild();
                },
                language -> {
                    newsTexts.put(language, "");
                    newsEditing = language;
                    newsChanged();
                },
                () -> {
                    newsDefault = newsEditing;
                    newsChanged();
                },
                () -> {
                    newsTexts.remove(newsEditing);
                    if (newsEditing.equals(newsDefault)) {
                        newsDefault = newsTexts.keySet().iterator().next();
                    }
                    newsEditing = newsDefault;
                    newsChanged();
                }));
        Flex push = Flex.row();
        push.sizing(Sizing.expand(), Sizing.fixed(1));
        languages.item(push);
        languages.item(small("gui.eden_ld.moderation.edit_text", 80, this::openNewsEditor));
        main.item(languages);

        main.item(text(Component.translatable("gui.eden_ld.moderation.news_preview", languageName(newsEditing)), DIM));
        String editingText = newsTexts.getOrDefault(newsEditing, "");
        HubPayloads.NewsView shown = new HubPayloads.NewsView(newsEditing,
                List.of(new MailText(newsEditing, "", fillPlaceholders(editingText))), 0, "");
        main.item(newsPanel(shown, "moderation/news"));
    }

    /** The Home tab's news panel at its real width, so the preview wraps exactly as there. */
    static Flex newsPanel(HubPayloads.NewsView news, String scrollKey) {
        Flex holder = Flex.column();
        holder.sizing(Sizing.fixed(NEWS_PANEL), Sizing.expand());
        holder.item(HubScreen.news(news, HubScreen.NEWS_WIDTH, scrollKey));
        return holder;
    }

    /** The admin's own values for the placeholders, as the previews show them. */
    private String fillPlaceholders(String text) {
        for (HubPayloads.PlaceholderInfo placeholder : info.placeholders()) {
            if (!placeholder.value().isEmpty()) {
                text = text.replace(placeholder.token(), placeholder.value());
            }
        }
        return text;
    }

    private void openNewsEditor() {
        String language = newsEditing;
        Minecraft.getInstance().setScreen(new MailTextScreen(
                Component.translatable("gui.eden_ld.moderation.news_heading", languageName(language)),
                null, newsTexts.getOrDefault(language, ""), this::placeholders, NEWS_PANEL,
                (title, body, width) -> newsPanel(new HubPayloads.NewsView(language,
                        List.of(new MailText(language, "", fillPlaceholders(body))), 0, ""), "moderation/news_edit"),
                (title, body) -> {
                    newsTexts.put(language, body);
                    newsDirty = true;
                },
                () -> {
                },
                () -> {
                    Minecraft.getInstance().setScreen(this);
                    rebuild();
                }));
    }

    private void confirmPublish() {
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(220), Sizing.content());
        dialog.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL).stroke(Stroke.of(LINE, 1)));
        dialog.item(text(Component.translatable("gui.eden_ld.moderation.news_confirm"), TEXT, 200));
        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        buttons.item(new Button(70, 16, Component.translatable("gui.eden_ld.moderation.news_publish"), () -> {
            HubPayloads.NewsView view = newsView();
            ClientPlayNetworking.send(new HubPayloads.NewsSave(view.defaultLanguage(), view.texts()));
            newsDirty = false;
            ClientPlayNetworking.send(HubPayloads.ModOpen.INSTANCE);
            modal[0].close();
        }));
        buttons.item(new Button(60, 16, Component.translatable("gui.eden_ld.moderation.cancel"), () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    // ---------------------------------------------------------------- small helpers

    static TextNode text(Component value, int color) {
        return TextNode.of(value).color(color).shadow(false);
    }

    static TextNode text(Component value, int color, int wrap) {
        return TextNode.of(value).color(color).shadow(false).maxWidth(wrap);
    }

    /** Widgets in rows no wider than {@code width} (Flex doesn't wrap). */
    static Flex wrapped(List<? extends net.ledok.vectorlib.client.canvas.widget.Widget> widgets, float width, float gap) {
        Flex column = Flex.column().gap(gap);
        column.sizing(Sizing.fixed(width), Sizing.content());
        Flex row = null;
        float used = 0;
        for (net.ledok.vectorlib.client.canvas.widget.Widget widget : widgets) {
            float w = widget.width();
            if (row == null || used + w > width) {
                row = Flex.row().gap(gap).alignItems(Align.CENTER);
                column.item(row);
                used = 0;
            }
            row.item(widget);
            used += w + gap;
        }
        return column;
    }

    static Flex gap(float height) {
        Flex spacer = Flex.column();
        spacer.sizing(Sizing.fixed(1), Sizing.fixed(height));
        return spacer;
    }

    static Button small(String key, float minWidth, Runnable action) {
        Component label = Component.translatable(key);
        float width = Math.max(minWidth, Minecraft.getInstance().font.width(label) + 10);
        Button button = new Button(width, 14, label, action);
        button.sizing(Sizing.fixed(width), Sizing.fixed(14));
        return button;
    }

    /** A square button with a symbol (its meaning goes in the tooltip). */
    static Button icon(String symbol, Runnable action) {
        Button button = new Button(14, 14, Component.literal(symbol), action);
        button.sizing(Sizing.fixed(14), Sizing.fixed(14));
        return button;
    }

    private static Component languageName(String code) {
        var info = Minecraft.getInstance().getLanguageManager().getLanguage(code);
        return info == null ? Component.literal(code) : info.toComponent();
    }

    // ---------------------------------------------------------------- the draft being edited

    /** The draft as the composer edits it; {@link #toData()} is what's saved and sent. */
    private static final class Draft {
        long id;
        String name = "";
        int audience;
        final List<String> players = new ArrayList<>();
        final List<String> groups = new ArrayList<>();
        String sender;
        int expiryDays;
        long coins;
        String defaultLanguage;
        final Map<String, String[]> texts = new LinkedHashMap<>();
        final ItemStack[] items = new ItemStack[54];
        final int[] counts = new int[54];

        static Draft fresh(HubPayloads.ModInfo info) {
            Draft d = new Draft();
            d.id = System.currentTimeMillis();
            d.audience = MailDraftData.TO_PLAYERS;
            d.sender = "Administration";
            d.expiryDays = Math.min(7, info.maxExpiryDays());
            String language = Minecraft.getInstance().getLanguageManager().getSelected();
            d.defaultLanguage = language;
            d.texts.put(language, new String[]{"", ""});
            return d;
        }

        static Draft of(MailDraftData data) {
            Draft d = new Draft();
            d.id = data.id();
            d.name = data.name();
            d.audience = data.audience();
            d.players.addAll(data.players());
            d.groups.addAll(data.groups());
            d.sender = data.sender();
            d.expiryDays = data.expiryDays();
            d.coins = data.coins();
            for (MailText text : data.texts()) {
                d.texts.put(text.language(), new String[]{text.title(), text.body()});
            }
            if (d.texts.isEmpty()) {
                d.texts.put("en_us", new String[]{"", ""});
            }
            d.defaultLanguage = d.texts.containsKey(data.defaultLanguage()) ? data.defaultLanguage()
                    : d.texts.keySet().iterator().next();
            for (int i = 0; i < data.items().size() && i < d.items.length; i++) {
                d.items[i] = data.items().get(i);
                d.counts[i] = i < data.counts().size() ? data.counts().get(i) : 1;
            }
            return d;
        }

        /** Its name in the draft list: the one given, else the first title, else when it was started. */
        Component label() {
            if (!name.isBlank()) {
                return Component.literal(name);
            }
            String title = texts.containsKey(defaultLanguage) ? texts.get(defaultLanguage)[0] : "";
            if (title.isBlank()) {
                title = texts.values().stream().map(t -> t[0]).filter(t -> !t.isBlank()).findFirst().orElse("");
            }
            return title.isBlank()
                    ? Component.translatable("gui.eden_ld.moderation.draft_from", DATES.format(id))
                    : Component.literal(title);
        }

        MailText text(String language) {
            String[] text = texts.computeIfAbsent(language, l -> new String[]{"", ""});
            return new MailText(language, text[0], text[1]);
        }

        void setTitle(String language, String title) {
            texts.computeIfAbsent(language, l -> new String[]{"", ""})[0] = title;
        }

        void setBody(String language, String body) {
            texts.computeIfAbsent(language, l -> new String[]{"", ""})[1] = body;
        }

        MailDraftData toData() {
            List<MailText> list = new ArrayList<>();
            texts.forEach((language, text) -> list.add(new MailText(language, text[0], text[1])));
            List<ItemStack> stacks = new ArrayList<>();
            List<Integer> amounts = new ArrayList<>();
            for (int i = 0; i < items.length; i++) {
                if (items[i] != null && !items[i].isEmpty()) {
                    stacks.add(items[i].copyWithCount(1));
                    amounts.add(Math.max(1, counts[i]));
                }
            }
            return new MailDraftData(id, name, audience, List.copyOf(players), List.copyOf(groups), sender, expiryDays, coins,
                    defaultLanguage, list, stacks, amounts, System.currentTimeMillis());
        }

        /** How the Mail tab will show it, in one language — placeholders filled with the admin's own values. */
        MailView preview(String language, @Nullable HubPayloads.ModInfo info) {
            MailText raw = text(language);
            return preview(language, info, raw.title(), raw.body());
        }

        /** As {@link #preview(String, HubPayloads.ModInfo)}, with this title and text. */
        MailView preview(String language, @Nullable HubPayloads.ModInfo info, String title, String body) {
            if (info != null) {
                for (HubPayloads.PlaceholderInfo placeholder : info.placeholders()) {
                    if (!placeholder.value().isEmpty()) {
                        title = title.replace(placeholder.token(), placeholder.value());
                        body = body.replace(placeholder.token(), placeholder.value());
                    }
                }
            }
            MailText text = new MailText(language, title, body);
            MailDraftData data = toData();
            long now = System.currentTimeMillis() / 1000L;
            return new MailView(0, 2, sender.isBlank() ? "Administration" : sender, false, language, List.of(text),
                    "", "", List.of(), data.items(), data.counts(), coins, now, now + expiryDays * 86_400L, true, false);
        }
    }

    // ---------------------------------------------------------------- slots

    /** An attachment slot: shows a copy of an item; right-click clears it, scrolling changes the count. */
    private final class GhostSlot extends Widget {
        private final int index;

        GhostSlot(int index) {
            super(SLOT, SLOT);
            this.index = index;
            sizing(Sizing.fixed(SLOT), Sizing.fixed(SLOT));
            tooltip(() -> {
                ItemStack stack = draft.items[index];
                if (stack == null || stack.isEmpty()) {
                    return List.of(Component.translatable("gui.eden_ld.moderation.slot_hint"));
                }
                List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), stack));
                lines.add(Component.translatable("gui.eden_ld.moderation.slot_controls").withStyle(s -> s.withColor(DIM & 0xFFFFFF)));
                return lines;
            });
        }

        void set(ItemStack stack, int count) {
            draft.items[index] = stack;
            draft.counts[index] = count;
            changed();
            refresh();
        }

        Rect rootBounds() {
            float[] origin = localToRoot(0, 0);
            return new Rect(origin[0], origin[1], width, height);
        }

        @Override
        protected void rebuild() {
            add(Shapes.rect(0, 0, width, height).fill(0xFF14161A).stroke(Stroke.of(hovered ? ACCENT : LINE, 1)));
            ItemStack stack = draft.items[index];
            if (stack != null && !stack.isEmpty()) {
                add(ItemStackNode.of(stack.copyWithCount(Math.max(1, draft.counts[index])), 1, 1, 16).stackTooltip(false));
            }
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button == 1 && draft.items[index] != null) {
                UiSounds.click();
                set(null, 0);
                return true;
            }
            return false;
        }

        @Override
        public boolean onScroll(float x, float y, double delta) {
            ItemStack stack = draft.items[index];
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            int step = Screen.hasShiftDown() ? 10 : 1;
            draft.counts[index] = Math.max(1, Math.min(99_999, draft.counts[index] + (delta > 0 ? step : -step)));
            changed();
            refresh();
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }

    /** One of the player's own inventory slots: a click attaches a copy (the item stays with the player). */
    private final class InventorySlot extends Widget {
        private final ItemStack stack;

        InventorySlot(ItemStack stack) {
            super(SLOT, SLOT);
            this.stack = stack;
            sizing(Sizing.fixed(SLOT), Sizing.fixed(SLOT));
        }

        @Override
        protected void rebuild() {
            add(Shapes.rect(0, 0, width, height).fill(0xFF14161A).stroke(Stroke.of(hovered && !stack.isEmpty() ? ACCENT : LINE, 1)));
            if (!stack.isEmpty()) {
                add(ItemStackNode.of(stack, 1, 1, 16));
            }
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0 || stack.isEmpty()) {
                return false;
            }
            for (GhostSlot slot : slots) {
                if (draft.items[slot.index] == null || draft.items[slot.index].isEmpty()) {
                    UiSounds.click();
                    slot.set(stack.copyWithCount(1), stack.getCount());
                    return true;
                }
            }
            Toast.text(canvas(), Component.translatable("gui.eden_ld.moderation.slots_full"), BAD);
            return true;
        }

        @Override
        public Cursor cursor() {
            return stack.isEmpty() ? Cursor.DEFAULT : Cursor.HAND;
        }
    }

    /** A sent mail in the log. */
    private final class LogRow extends Widget {
        private final HubPayloads.SentMailView sent;

        LogRow(HubPayloads.SentMailView sent) {
            super(WIDTH - SIDEBAR - 16 - 8 - MailTab.DETAIL_WIDTH - 6, 24);
            this.sent = sent;
            sizing(Sizing.fixed(width), Sizing.fixed(24));
        }

        @Override
        protected void rebuild() {
            boolean selected = sent.mail().id() == logSelected;
            add(Shapes.rect(0, 0, width, height).fill(selected ? PANEL : 0xFF22252A)
                    .stroke(Stroke.of(selected ? ACCENT : hovered ? DIM : LINE, 1)));
            add(TextNode.of(MailTab.title(sent.mail())).at(5, 3).maxWidth((int) width - 10).ellipsis(true).color(TEXT).shadow(false));
            add(TextNode.of(Component.translatable("gui.eden_ld.moderation.log_row", sent.mail().sender(), sent.read(),
                            sent.recipients() < 1 ? "?" : String.valueOf(sent.recipients()), sent.claimed()))
                    .at(5, 13).maxWidth((int) width - 10).ellipsis(true).color(DIM).shadow(false));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            logSelected = sent.mail().id();
            rebuild();
            ModerationScreen.this.rebuild();
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }

    /** Short local dates for untitled drafts. */
    private static final class DateTimeFormatterHolder {
        private final java.time.format.DateTimeFormatter format =
                java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(java.time.ZoneId.systemDefault());

        String format(long epochMillis) {
            return format.format(java.time.Instant.ofEpochMilli(epochMillis));
        }
    }
}
