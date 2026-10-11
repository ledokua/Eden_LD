package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.vectorlib.client.canvas.ImageNode;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.presentation.CanvasScreen;
import net.ledok.vectorlib.client.presentation.Placement;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The hub shell: bookmark tab bar over one parchment panel. Reopens on the tab the
 * player last used this session ({@link HubState#lastTab}); Home after a fresh join.
 * <p>
 * The player can drag the panel's sides and bottom to resize the hub, or maximize it from the tab
 * row; the size is kept on this computer ({@link HubWindow}). Tabs build at {@link #innerWidth()} and
 * are rebuilt when it changes.
 */
public class HubScreen extends CanvasScreen {

    /** The hub's smallest size, and its size until the player resizes it. */
    static final float MIN_WIDTH = 440;
    static final float MIN_HEIGHT = 262;
    private static final float TAB_HEIGHT = 20;
    private static final float TAB_HEIGHT_ACTIVE = 24;
    private static final Insets PAGE_PADDING = Insets.of(8, 12, 10, 12);
    /** While a resize drag runs, the open tab rebuilds at most this often. */
    private static final long RELAYOUT_MS = 60;

    /** The open tab's width: the page's inner width at the hub's current size. */
    private static float innerWidth = MIN_WIDTH - PAGE_PADDING.horizontal();

    private final Flex root;
    private final Flex tabRow;
    private final Flex content;
    /** The panel around the open tab: its title on top, then {@link #content}. */
    private final Flex page;
    /** The page heading: the open tab's name, centered between two thin lines. */
    private final Flex pageTitle;
    /** The tab content was built once (the first size is known); later sizes rebuild it. */
    private boolean built;
    private boolean relayoutPending;
    /** The pending rebuild comes from a drag still going on (a split divider): keep it to a few a second. */
    private boolean relayoutLive;
    private long lastRelayout;
    /** The theme's decorations, behind and in front of the hub (may reach outside the canvas). */
    private final net.ledok.vectorlib.client.canvas.CanvasGroup decorationsBehind = new net.ledok.vectorlib.client.canvas.CanvasGroup();
    private final net.ledok.vectorlib.client.canvas.CanvasGroup decorationsFront = new net.ledok.vectorlib.client.canvas.CanvasGroup();
    /** Hover cards (a friend's mini profile): over everything, never clickable. */
    private final net.ledok.vectorlib.client.canvas.CanvasGroup hoverLayer = new net.ledok.vectorlib.client.canvas.CanvasGroup();
    /** Opened from the inventory: closing the hub goes back there (else back to the game). */
    private final boolean backToInventory;

    /**
     * Opens the hub. From the inventory (its hub button, {@link InventoryHubButton}) the inventory closes
     * first, as E closes it — its crafting grid and held item go back, and nothing of it stays open behind
     * the hub for EMI to draw on — and closing the hub goes back to it. From the game (the key) closing the
     * hub goes back to the game. Opened again over itself (the server asking for a tab), it keeps where it
     * goes back to.
     */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        open(minecraft.screen instanceof HubScreen hub ? hub.backToInventory
                : minecraft.screen instanceof InventoryScreen && closeInventory(minecraft));
    }

    static void open(boolean backToInventory) {
        Minecraft.getInstance().setScreen(new HubScreen(backToInventory));
    }

    /** Opens on a tab by name ("profile" = the player's own profile); "" = the last one used. */
    public static void open(String tab) {
        selectTab(tab);
        open();
    }

    /** Opens on a tab by name; closing it goes back to the inventory or the game. */
    static void open(String tab, boolean backToInventory) {
        selectTab(tab);
        open(backToInventory);
    }

    private static void selectTab(String tab) {
        for (HubTab candidate : HubTab.values()) {
            if (candidate.name().equalsIgnoreCase(tab)) {
                if (candidate == HubTab.PROFILE) {
                    HubState.profileTarget = null;
                }
                HubState.lastTab = candidate;
            }
        }
    }

    /** The server closes the player's inventory as it does for E; the hub's {@code setScreen} then removes its screen. */
    private static boolean closeInventory(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player != null) {
            player.connection.send(new ServerboundContainerClosePacket(player.containerMenu.containerId));
        }
        return true;
    }

    boolean backToInventory() {
        return backToInventory;
    }

    private HubScreen(boolean backToInventory) {
        super(Component.translatable("gui.eden_ld.hub.title"),
                VectorCanvas.create(MIN_WIDTH, MIN_HEIGHT), Placement.Screen.center());
        this.backToInventory = backToInventory;
        // The hub's own language (Settings → Visual) before anything is built.
        HubLanguage.apply();
        canvas().theme(EdenParchment.THEME);
        // The panel's sides and bottom resize it (the tab row's top stays clickable); a theme's
        // decorations may run off the window's edge rather than shrink every theme's text.
        resizable(MIN_WIDTH, MIN_HEIGHT);
        windowMargin(Insets.of(4));
        resizeEdges(EDGE_LEFT | EDGE_RIGHT | EDGE_BOTTOM);
        resizeFrame(Insets.of(TAB_HEIGHT_ACTIVE, 0, 0, 0));
        if (HubWindow.width() > 0 && HubWindow.height() > 0) {
            canvasSize(HubWindow.width(), HubWindow.height());
        }
        maximized(HubWindow.maximized());

        // Literal tabs: each sized to its own label, adjacent with no gaps,
        // bottom-aligned so the taller active tab pops up out of the row.
        tabRow = Flex.row().gap(0).alignItems(Align.END);
        tabRow.sizing(Sizing.fill(), Sizing.fixed(TAB_HEIGHT_ACTIVE));
        tabRow.id(HubSpotlights.TABS);

        // The page: the open tab's name as a title, then the tab's own content.
        page = Flex.column().gap(5).padding(PAGE_PADDING);
        page.sizing(Sizing.fill(), Sizing.expand());
        page.background(EdenParchment.panel());
        page.id(HubSpotlights.PAGE);
        pageTitle = Flex.row().gap(6).alignItems(Align.CENTER);
        pageTitle.sizing(Sizing.fill(), Sizing.content());
        page.item(pageTitle);
        content = Flex.column().gap(6);
        content.sizing(Sizing.fill(), Sizing.expand());
        page.item(content);

        root = Flex.column();
        // Pin the root to the canvas: a content-sized root arranges at its measured
        // (smaller) size and the panel ends up off-center inside the canvas.
        root.sizing(Sizing.fill(), Sizing.fill());
        root.item(tabRow);
        root.item(page);
        decorationsBehind.pickable(false);
        decorationsFront.pickable(false);
        canvas().add(decorationsBehind);
        canvas().add(root);
        canvas().add(decorationsFront);
        hoverLayer.pickable(false);
        canvas().add(hoverLayer);
        // The tabs are built on the first init(), once the hub's size on this window is known.
    }

    @Override
    protected void init() {
        // Back from a screen of its own (the game's language came back when the hub was left).
        HubLanguage.apply();
        // Sizes the canvas for this window (the saved size, maximized, or scaled down to fit when the GUI
        // scale leaves less room than the smallest hub — at GUI scale 4 a 1080p window is 270 GUI px tall).
        super.init();
    }

    /** The open tab's width at the hub's current size (416 at the smallest). */
    static float innerWidth() {
        return innerWidth;
    }

    @Override
    protected void onCanvasResized(float width, float height) {
        innerWidth = width - PAGE_PADDING.horizontal();
        decorate();
        if (!built) {
            built = true;
            select(HubState.lastTab);
            requestHomeData();
            return;
        }
        if (!isResizing()) {
            // The game window changed: the maximize button shows only when there's room to grow.
            rebuildTabRow();
        }
        relayoutPending = true;
    }

    @Override
    protected void onResizeFinished(float width, float height, boolean maximized) {
        HubWindow.save(chosenWidth(), chosenHeight(), maximized);
        relayoutPending = true;
        rebuildTabRow();
    }

    @Override
    protected void onCanvasFrame(float partialTick) {
        // A drag resizes every frame: the open tab follows a few times a second, and once more on release.
        long now = System.currentTimeMillis();
        if (relayoutPending && (!(isResizing() || relayoutLive) || now - lastRelayout >= RELAYOUT_MS)) {
            relayoutPending = false;
            relayoutLive = false;
            lastRelayout = now;
            relayout();
        }
    }

    /**
     * Rebuild the open tab at its current size on the next frame — throttled while {@code live} (a divider
     * still being dragged), right away otherwise.
     */
    void relayoutSoon(boolean live) {
        relayoutPending = true;
        relayoutLive = live;
    }

    /**
     * Two panes side by side with a divider the player drags; the left one keeps its size when the hub
     * resizes and the tab rebuilds as the divider moves. {@code key} keeps the divider for the session.
     * Lay the panes out at {@code split.sizesFor(width)}.
     */
    net.ledok.vectorlib.client.canvas.widget.SplitView splitLeft(String key, float first, float minFirst,
                                                                  float minSecond, float gap) {
        return split(net.ledok.vectorlib.client.canvas.widget.SplitView.row().first(first), key, minFirst, minSecond, gap);
    }

    /** As {@link #splitLeft}, but the right pane keeps its size. */
    net.ledok.vectorlib.client.canvas.widget.SplitView splitRight(String key, float second, float minFirst,
                                                                   float minSecond, float gap) {
        return split(net.ledok.vectorlib.client.canvas.widget.SplitView.row().second(second), key, minFirst, minSecond, gap);
    }

    /**
     * A window over the hub in the theme's look: panel background, a row-styled title bar, kept on the
     * panel below the tab row (the tabs stay clickable). {@code key} keeps its place and size for the session.
     */
    net.ledok.vectorlib.client.canvas.widget.Window window(Component title, float width, float height, String key) {
        var canvas = canvas();
        return new net.ledok.vectorlib.client.canvas.widget.Window(title, width, height)
                .minSize(160, 90)
                .background(EdenParchment.panel())
                .titleBackground(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE))
                // More room at the bottom on themes whose tiled border art is thick.
                .border(Insets.of(7, 8, EdenParchment.tileEdges() ? 11 : 7, 8))
                .area(() -> new net.ledok.vectorlib.client.canvas.Rect(0, TAB_HEIGHT_ACTIVE, canvas.width(),
                        canvas.height() - TAB_HEIGHT_ACTIVE))
                .remember("eden_ld/" + key);
    }

    /** Sets up any split the same way (minimums, divider, remembered, rebuilding the tab as it moves). */
    net.ledok.vectorlib.client.canvas.widget.SplitView split(net.ledok.vectorlib.client.canvas.widget.SplitView split,
                                                             String key, float minFirst, float minSecond, float gap) {
        split.min(minFirst, minSecond).divider(gap).remember("eden_ld/" + key)
                .onMove(size -> relayoutSoon(true))
                .onRelease(() -> relayoutSoon(false));
        split.sizing(Sizing.fill(), Sizing.expand());
        return split;
    }

    /**
     * The resize grip on the parchment just inside the frame's corner (the frame's dark border would hide
     * it), in the theme's line color.
     */
    @Override
    protected void drawResizeGrip(net.minecraft.client.gui.GuiGraphics graphics, float right, float bottom) {
        int inset = EdenParchment.tileEdges() ? 9 : 6;
        int r = (int) Math.floor(right) - inset, b = (int) Math.floor(bottom) - inset;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3 - row; col++) {
                int x = r - col * 2, y = b - row * 2;
                graphics.fill(x, y, x + 1, y + 1, EdenParchment.HAIRLINE);
            }
        }
    }

    /** Rebuilds the open tab at the new size from the data it already has (no new requests). */
    private void relayout() {
        content.clear();
        content.id(HubSpotlights.page(HubState.lastTab));
        switch (HubState.lastTab) {
            case HOME -> buildHome();
            case PROFILE -> ProfileTab.build(this, content);
            case PARTY -> PartyTab.build(this, content);
            case GUILD -> GuildTab.build(this, content);
            case FRIENDS -> FriendsTab.build(this, content);
            case MAIL -> MailTab.build(this, content);
            case DAILIES -> DailiesTab.build(this, content);
            case WAYSTONES -> WaystonesTab.build(this, content);
            case ACTIVITIES -> ActivitiesTab.build(this, content);
            case WARDROBE -> WardrobeTab.build(this, content);
            case SETTINGS -> SettingsTab.build(this, content);
            default -> rebuildContent();
        }
        // The help window's ▶ follows the sub-tab or filter just shown.
        HubHelp.refreshTour();
    }

    /** The tab-row button that maximizes the hub or puts it back (only when the window has room to grow). */
    private void maximizeButton() {
        if (!canGrow()) {
            return;
        }
        boolean max = maximized();
        HubIconButton button = new HubIconButton(max ? "tab_restore" : "tab_maximize", max ? "❐" : "□", 24, TAB_HEIGHT,
                Component.translatable(max ? "gui.eden_ld.hub.restore" : "gui.eden_ld.hub.maximize"),
                () -> maximized(!maximized()));
        button.id(HubSpotlights.MAXIMIZE);
        tabRow.item(button);
    }

    @Override
    public void tick() {
        super.tick();
        if (HubState.lastTab == HubTab.WAYSTONES) {
            WaystonesTab.tick(this);
        } else if (HubState.lastTab == HubTab.DAILIES) {
            DailiesTab.tick();
        } else if (HubState.lastTab == HubTab.GUILD && HubState.guildView == GuildView.QUESTS) {
            GuildQuestsView.tick();
        } else if (HubState.lastTab == HubTab.ACTIVITIES && ActivitiesTab.showingDuels()) {
            DuelsTab.tick();
        }
    }

    /** Back where the hub was opened from: the inventory (its hub button) or the game. */
    @Override
    public void onClose() {
        if (backToInventory && minecraft != null && minecraft.player != null) {
            // As E opens it (in creative it becomes the creative inventory).
            minecraft.setScreen(new InventoryScreen(minecraft.player));
            return;
        }
        super.onClose();
    }

    @Override
    public void removed() {
        super.removed();
        HubLanguage.restore();
        closeBankView();
        if (HubState.lastTab == HubTab.ACTIVITIES && ActivitiesTab.showingDuels()) {
            DuelsTab.left();
        }
        net.ledok.eden_ld.client.chat.GifViewer.close();
    }

    // A [GIF] or [Sticker] clicked in party or guild chat opens the chat's viewer over the hub.

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        dimAroundDialog(graphics);
        if (net.ledok.eden_ld.client.chat.GifViewer.isOpen()) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 500);
            net.ledok.eden_ld.client.chat.GifViewer.render(graphics, font, width, height, mouseX, mouseY);
            graphics.pose().popPose();
        }
    }

    /**
     * A dialog's scrim covers only the hub's canvas, which left its empty tab-row corner as a dark
     * slab over the world: dim the rest of the screen the same way, so everything darkens evenly.
     */
    private void dimAroundDialog(net.minecraft.client.gui.GuiGraphics graphics) {
        // Follows the dialog's own fade-in, so the two halves of the dim never differ.
        float opacity = canvas().popups().children().stream()
                .filter(node -> node instanceof net.ledok.vectorlib.client.canvas.widget.Modal)
                .map(net.ledok.vectorlib.client.canvas.CanvasNode::opacity).max(Float::compare).orElse(0f);
        if (opacity <= 0) {
            return;
        }
        float scale = placement.scale();
        float[] topLeft = placement.resolve(width, height, canvas().width(), canvas().height());
        int x0 = Math.round(topLeft[0]);
        int y0 = Math.round(topLeft[1]);
        int x1 = Math.round(topLeft[0] + canvas().width() * scale);
        int y1 = Math.round(topLeft[1] + canvas().height() * scale);
        int color = Math.round(0x88 * opacity) << 24;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        graphics.fill(0, 0, width, y0, color);
        graphics.fill(0, y1, width, height, color);
        graphics.fill(0, y0, x0, y1, color);
        graphics.fill(x1, y0, width, y1, color);
        graphics.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (net.ledok.eden_ld.client.chat.GifViewer.click(this, mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && net.ledok.eden_ld.client.chat.GifViewer.isOpen()) {
            net.ledok.eden_ld.client.chat.GifViewer.close();
            return true;
        }
        // Tab in a party/guild chat field completes "@name" instead of moving the focus.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB && ChatMentions.tab()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Tells the server the bank isn't on screen any more (it stops pushing bank updates). */
    private static void closeBankView() {
        if (HubState.lastTab == HubTab.GUILD && HubState.guildView == GuildView.BANK) {
            ClientPlayNetworking.send(new HubPayloads.BankView(HubPayloads.BankView.CLOSED));
            GuildBankView.closeLog();
        }
    }

    private void select(HubTab tab) {
        if (tab != HubState.lastTab) {
            closeBankView();
            if (tab == HubTab.ACTIVITIES) {
                ActivitiesTab.entered();
            }
            if (HubState.lastTab == HubTab.ACTIVITIES && ActivitiesTab.showingDuels()) {
                DuelsTab.left();
            }
        }
        if (!available(tab)) {
            tab = HubTab.HOME;
        }
        HubState.lastTab = tab;
        HubNotifications.markSeen(tab);
        rebuildTabRow();
        rebuildContent();
        HubHelp.follow(this, tab);
    }

    private void rebuildTabRow() {
        HubTab active = HubState.lastTab;
        tabRow.clear();
        for (HubTab candidate : HubTab.values()) {
            if (candidate == HubTab.SETTINGS || !available(candidate)) {
                continue;
            }
            tabRow.item(tabButton(candidate, active));
        }
        tabRow.spacer();
        maximizeButton();
        // "?" explains the open tab.
        HubIconButton help = new HubIconButton("tab_help", "?", 24, TAB_HEIGHT,
                Component.translatable("gui.eden_ld.hub.help.button"), () -> HubHelp.open(this, HubState.lastTab));
        help.id(HubSpotlights.HELP);
        tabRow.item(help);
        tabRow.item(tabButton(HubTab.SETTINGS, active));
        // Close sits at the far right, right next to Settings.
        HubIconButton close = new HubIconButton("close_clear", "✖", 24, TAB_HEIGHT,
                Component.translatable("gui.eden_ld.hub.close"), this::onClose);
        close.id(HubSpotlights.CLOSE);
        tabRow.item(close);
    }

    /** Tabs backed by optional mods hide until the server confirms the mod is present. */
    private static boolean available(HubTab tab) {
        return switch (tab) {
            case FRIENDS -> HubState.friendsAvailable();
            case WAYSTONES -> HubState.waystonesAvailable();
            case MAIL -> HubState.mailData != null;
            case MODERATION -> HubState.homeData != null
                    && (HubState.homeData.staff() & HubPayloads.HomeData.STAFF_MODERATION) != 0;
            default -> true;
        };
    }

    /** An icon bookmark; the open one also shows its name and sits taller. */
    private TabBookmark tabButton(HubTab candidate, HubTab active) {
        boolean isActive = candidate == active;
        TabBookmark bookmark = new TabBookmark(candidate, isActive, isActive ? TAB_HEIGHT_ACTIVE : TAB_HEIGHT, () -> {
            if (candidate == HubTab.MODERATION) {
                HubNotifications.markSeen(candidate);
                ModerationScreen.openFrom(this);
                return;
            }
            if (candidate == HubTab.PROFILE) {
                // The bookmark is always the player's own profile.
                showOwnProfile();
            }
            select(candidate);
        }, EdenParchment.TAB_ACTIVE);
        bookmark.id(HubSpotlights.tab(candidate));
        return bookmark;
    }

    /** Public tab switch, e.g. from a Home attention card. */
    public void selectTab(HubTab tab) {
        select(tab);
    }

    /** Shows a hover card (canvas coordinates), replacing any other. */
    void showHoverCard(net.ledok.vectorlib.client.canvas.CanvasNode card) {
        hoverLayer.clear();
        hoverLayer.add(card);
    }

    /** Hides this hover card (a newer one stays). */
    void hideHoverCard(net.ledok.vectorlib.client.canvas.CanvasNode card) {
        hoverLayer.remove(card);
    }

    /** One of the thin lines either side of the page heading. */
    private static Flex titleLine() {
        Flex line = Flex.column();
        line.sizing(Sizing.expand(), Sizing.fixed(1));
        line.backgroundFill(EdenParchment.HAIRLINE);
        return line;
    }

    /** Shows a player's profile on the Profile tab; null = the player's own. */
    public void openProfile(@org.jetbrains.annotations.Nullable java.util.UUID target) {
        Minecraft mc = Minecraft.getInstance();
        if (target != null && mc.player != null && target.equals(mc.player.getUUID())) {
            target = null;
        }
        HubState.profileTarget = target;
        forgetOtherProfile();
        select(HubTab.PROFILE);
    }

    private static void showOwnProfile() {
        HubState.profileTarget = null;
        forgetOtherProfile();
    }

    /** A page for someone else than who's asked for now isn't shown while the right one loads. */
    private static void forgetOtherProfile() {
        java.util.UUID subject = HubState.profileSubject();
        if (HubState.profileData != null && !HubState.profileData.uuid().equals(subject)) {
            HubState.profileData = null;
        }
    }

    /** Called when a profile page lands (tab open, background change, an admin unlock). */
    public void onProfileData() {
        if (HubState.lastTab == HubTab.PROFILE) {
            content.clear();
            ProfileTab.build(this, content);
        }
    }

    private void rebuildContent() {
        content.clear();
        HubTab tab = HubState.lastTab;
        content.id(HubSpotlights.page(tab));
        pageTitle.clear();
        pageTitle.item(titleLine());
        pageTitle.item(EdenParchment.text(tab.displayName(), EdenParchment.INK_MID));
        pageTitle.item(titleLine());
        if (!tab.implemented) {
            buildPlaceholder(tab);
        } else if (tab == HubTab.HOME) {
            buildHome();
        } else if (tab == HubTab.PROFILE) {
            java.util.UUID subject = HubState.profileSubject();
            if (subject != null) {
                ClientPlayNetworking.send(new HubPayloads.ProfileRequest(subject));
            }
            ProfileTab.build(this, content);
        } else if (tab == HubTab.PARTY) {
            requestPartyData();
            PartyTab.build(this, content);
        } else if (tab == HubTab.GUILD) {
            ClientPlayNetworking.send(HubPayloads.GuildDataRequest.INSTANCE);
            ClientPlayNetworking.send(HubPayloads.GuildQuestsRequest.INSTANCE);
            if (HubState.guildView == GuildView.BANK) {
                GuildBankView.resubscribe(this);
            }
            GuildTab.build(this, content);
        } else if (tab == HubTab.FRIENDS) {
            // Every visit asks; the server throttles the platform re-fetch.
            ClientPlayNetworking.send(HubPayloads.FriendsDataRequest.INSTANCE);
            FriendsTab.build(this, content);
        } else if (tab == HubTab.MAIL) {
            MailTab.build(this, content);
        } else if (tab == HubTab.DAILIES) {
            ClientPlayNetworking.send(HubPayloads.DailiesDataRequest.INSTANCE);
            DailiesTab.build(this, content);
        } else if (tab == HubTab.WAYSTONES) {
            HubState.waystoneMessage = null;
            ClientPlayNetworking.send(HubPayloads.WaystonesDataRequest.INSTANCE);
            WaystonesTab.build(this, content);
        } else if (tab == HubTab.ACTIVITIES) {
            ClientPlayNetworking.send(HubPayloads.ActivitiesRequest.INSTANCE);
            if (ActivitiesTab.showingDuels()) {
                // Duels is a filter of Activities: live duel data while it's shown.
                DuelsTab.entered();
            }
            ActivitiesTab.build(this, content);
        } else if (tab == HubTab.WARDROBE) {
            ClientPlayNetworking.send(HubPayloads.WardrobeRequest.INSTANCE);
            WardrobeTab.build(this, content);
        } else if (tab == HubTab.SETTINGS) {
            ClientPlayNetworking.send(HubPayloads.SettingsDataRequest.INSTANCE);
            SettingsTab.build(this, content);
        }
    }

    private void buildPlaceholder(HubTab tab) {
        Flex center = Flex.column().gap(4).justify(Justify.CENTER).alignItems(Align.CENTER);
        center.sizing(Sizing.fill(), Sizing.expand());
        center.item(EdenParchment.text(tab.label(), EdenParchment.INK_MID));
        center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.wip"), EdenParchment.INK_DIM));
        content.item(center);
    }

    // ------------------------------------------------------------------ Home

    private void buildHome() {
        HubPayloads.HomeData data = HubState.homeData;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"),
                    EdenParchment.INK_DIM));
            content.item(center);
            return;
        }

        content.item(playerStrip(data));
        content.item(EdenParchment.spacer(2));
        content.item(attention(data));
        content.item(EdenParchment.spacer(2));
        content.item(news(data));
    }

    /**
     * The player's card: the Minestar level ring on the left, head, name and group, then balance,
     * dailies and playtime — over the player's profile background (like their profile page).
     */
    private Flex playerStrip(HubPayloads.HomeData data) {
        Minecraft mc = Minecraft.getInstance();
        // Light text over a picked background; the theme's card look without one.
        boolean pictured = net.ledok.eden_ld.client.profile.ProfileBackgrounds.shown(data.background());
        var palette = net.ledok.eden_ld.client.profile.ProfileBackgrounds.palette(data.background());
        int text = pictured ? palette.text() : EdenParchment.INK;
        int dim = pictured ? palette.textDim() : EdenParchment.INK_DIM;
        boolean shadow = pictured ? palette.shadow() : EdenParchment.TEXT_SHADOW;
        Flex strip = Flex.row().gap(8).padding(Insets.of(5, 8, 5, 8)).alignItems(Align.CENTER);
        strip.sizing(Sizing.fill(), Sizing.content());
        strip.background(pictured ? ProfileTab.picture(data.background(), ProfileTab.Surface.HOME)
                : EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));

        if (data.hasLevel()) {
            float progress = data.levelXpNeeded() > 0 ? (float) data.levelXp() / data.levelXpNeeded() : 0f;
            LevelRing ring = new LevelRing(28, 3, data.level(), progress, text,
                    pictured ? (text & 0xFFFFFF) | 0x50000000 : EdenParchment.BAR_TRACK, false);
            ring.tooltip(Component.translatable("gui.eden_ld.hub.home.level", data.level(), data.levelXp(),
                    data.levelXpNeeded()));
            strip.item(ring);
        }
        if (mc.player != null) {
            ResourceLocation skin = mc.player.getSkin().texture();
            CanvasGroup face = new CanvasGroup();
            face.add(ImageNode.of(skin, 0, 0, 22, 22, 64, 64).region(8, 8, 8, 8));
            face.add(ImageNode.of(skin, 0, 0, 22, 22, 64, 64).region(40, 8, 8, 8));
            strip.item(face);
        }

        Flex names = Flex.column().gap(2);
        String playerName = mc.player != null ? mc.player.getGameProfile().getName() : "";
        Component decorated = mc.player != null ? PlayerNames.of(mc.player.getUUID(), playerName)
                : Component.literal(playerName);
        // No separate prime badge: the LuckPerms prefix already says it.
        names.item(net.ledok.vectorlib.client.canvas.TextNode.of(decorated).color(text).shadow(shadow));
        if (!data.group().isEmpty()) {
            names.item(net.ledok.vectorlib.client.canvas.TextNode.of(
                    Component.translatable("gui.eden_ld.hub.home.group", data.group())).color(dim).shadow(shadow));
        }
        strip.item(names);

        strip.spacer();

        Flex stats = Flex.column().gap(2).alignItems(Align.END);
        if (data.hasEconomy()) {
            stats.item(net.ledok.vectorlib.client.canvas.TextNode.of(
                    Component.translatable("gui.eden_ld.hub.home.balance", data.balance())).color(text).shadow(shadow)
                    .id(HubSpotlights.BALANCE));
        }
        int[] dailies = HubState.dailiesProgress();
        if (dailies[1] > 0) {
            stats.item(net.ledok.vectorlib.client.canvas.TextNode.of(
                    Component.translatable("gui.eden_ld.hub.home.dailies", dailies[0], dailies[1]))
                    .color(dailies[0] == dailies[1] ? (pictured ? palette.good() : EdenParchment.GOOD) : dim).shadow(shadow));
        }
        strip.item(stats);
        return strip;
    }

    private Flex attention(HubPayloads.HomeData data) {
        Flex box = Flex.column().gap(4);
        box.sizing(Sizing.fill(), Sizing.content());
        // Live count from the pushed party data (the HomeData snapshot goes stale while
        // the screen is open); fall back to the snapshot until the first push lands.
        int partyInvites = HubState.partyData != null
                ? HubState.partyData.myInvites().size()
                : data.partyInvites();
        int friendRequests = HubState.friendsAvailable() ? HubState.friendRequestCount() : 0;
        int dailiesClaimable = HubState.dailiesClaimable();
        int guildInvites = HubState.guildInvites();
        if (guildInvites > 0) {
            box.item(attentionCard(
                    Component.translatable("gui.eden_ld.hub.home.guild_invites", guildInvites),
                    Component.translatable("gui.eden_ld.hub.home.view"),
                    () -> selectTab(HubTab.GUILD)));
        }
        int unreadMail = HubState.mailData != null
                ? (int) HubState.mailData.mails().stream().filter(m -> !m.read()).count() : 0;
        if (unreadMail > 0) {
            box.item(attentionCard(
                    Component.translatable("gui.eden_ld.hub.home.unread_mail", unreadMail),
                    Component.translatable("gui.eden_ld.hub.home.view"),
                    () -> selectTab(HubTab.MAIL)));
        }
        java.util.List<Component> unlocks = HubNotifications.enabled(HubNotifications.Kind.ACTIVITIES)
                ? HubState.activityUnlocks : java.util.List.of();
        if (!unlocks.isEmpty()) {
            box.item(attentionCard(ActivitiesTab.homeLine(unlocks),
                    Component.translatable("gui.eden_ld.hub.home.view"),
                    () -> selectTab(HubTab.ACTIVITIES)));
        }
        HubPayloads.DuelsStatus duels = HubNotifications.enabled(HubNotifications.Kind.DUELS) ? HubState.duelsStatus : null;
        boolean searching = duels != null && duels.searching();
        if (searching) {
            box.item(duelsCard(duels));
        }
        boolean anyCard = data.shopPending() > 0 || partyInvites > 0 || friendRequests > 0 || dailiesClaimable > 0
                || guildInvites > 0 || unreadMail > 0 || !unlocks.isEmpty() || searching;
        if (dailiesClaimable > 0) {
            box.item(attentionCard(
                    Component.translatable("gui.eden_ld.hub.home.dailies_claimable", dailiesClaimable),
                    Component.translatable("gui.eden_ld.hub.home.view"),
                    () -> selectTab(HubTab.DAILIES)));
        }
        if (friendRequests > 0) {
            box.item(attentionCard(
                    Component.translatable("gui.eden_ld.hub.home.friend_requests", friendRequests),
                    Component.translatable("gui.eden_ld.hub.home.view"),
                    () -> selectTab(HubTab.FRIENDS)));
        }
        if (partyInvites > 0) {
            Flex row = Flex.row().gap(8).padding(Insets.of(4, 6, 4, 6)).alignItems(Align.CENTER);
            row.sizing(Sizing.fill(), Sizing.content());
            row.background(EdenParchment.framed(EdenParchment.ROW_BG_ALT, EdenParchment.HAIRLINE));
            Label text = Label.of(innerWidth - CARD_TEXT_INSET,
                    Component.translatable("gui.eden_ld.hub.home.party_invites", partyInvites));
            text.color(EdenParchment.INK);
            row.item(text);
            row.spacer();
            row.item(EdenParchment.fitButton(
                    Component.translatable("gui.eden_ld.hub.home.view"), 60, 14,
                    () -> selectTab(HubTab.PARTY)));
            box.item(row);
        }
        if (data.shopPending() > 0) {
            Flex row = Flex.row().gap(8).padding(Insets.of(4, 6, 4, 6)).alignItems(Align.CENTER);
            row.sizing(Sizing.fill(), Sizing.content());
            row.background(EdenParchment.framed(EdenParchment.ROW_BG_ALT, EdenParchment.HAIRLINE));
            Label text = Label.of(innerWidth - CARD_TEXT_INSET,
                    Component.translatable("gui.eden_ld.hub.home.shop_pending", data.shopPending()));
            text.color(EdenParchment.INK);
            row.item(text);
            row.spacer();
            row.item(EdenParchment.fitButton(
                    Component.translatable("gui.eden_ld.hub.home.claim"), 60, 14, () -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                            mc.player.connection.sendCommand("shop-receive");
                        }
                        requestHomeData();
                    }));
            box.item(row);
        }
        if (!anyCard) {
            box.item(EdenParchment.text(
                    Component.translatable("gui.eden_ld.hub.home.caught_up"), EdenParchment.INK_DIM));
        }
        return box;
    }

    /** "Searching for a 1v1 duel · 0:42" (or "match found") with Leave queue — while the player is in a queue. */
    private Flex duelsCard(HubPayloads.DuelsStatus status) {
        Component mode = Component.translatable("gui.eden_ld.hub.duels.mode."
                + (status.oneVsOne() && status.twoVsTwo() ? "both" : status.twoVsTwo() ? "2v2" : "1v1"));
        Component text;
        if (status.pending()) {
            text = Component.translatable("gui.eden_ld.hub.home.duel_pending");
        } else {
            long seconds = Math.max(0, (status.searchingMillis() + System.currentTimeMillis() - HubState.duelsStatusAt) / 1000);
            text = Component.translatable("gui.eden_ld.hub.home.duel_searching", mode,
                    String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
        }
        Flex row = attentionCard(text, Component.translatable("gui.eden_ld.hub.duels.leave"),
                () -> ClientPlayNetworking.send(new HubPayloads.DuelsQueue(-1, false)));
        return row;
    }

    /** Fresh Wardrobe data (tab opened, something worn, bought or given), or a filter change. */
    public void onWardrobeData() {
        if (HubState.lastTab == HubTab.WARDROBE) {
            content.clear();
            WardrobeTab.build(this, content);
        }
    }

    /** The duel queue changed (joined, left, matched): the Home card follows. */
    public void onDuelsStatus() {
        if (HubState.lastTab == HubTab.HOME) {
            rebuildContent();
        }
    }

    /** An attention card's text is the tab's width less this (260 at the smallest hub): padding and the button. */
    private static final float CARD_TEXT_INSET = MIN_WIDTH - PAGE_PADDING.horizontal() - 260;

    private Flex attentionCard(Component text, Component action, Runnable onClick) {
        Flex row = Flex.row().gap(8).padding(Insets.of(4, 6, 4, 6)).alignItems(Align.CENTER);
        row.sizing(Sizing.fill(), Sizing.content());
        row.background(EdenParchment.framed(EdenParchment.ROW_BG_ALT, EdenParchment.HAIRLINE));
        Label label = Label.of(innerWidth - CARD_TEXT_INSET, text);
        label.color(EdenParchment.INK);
        row.item(label);
        row.spacer();
        row.item(EdenParchment.fitButton(action, 60, 14, onClick));
        return row;
    }

    private Flex news(HubPayloads.HomeData data) {
        return news(data.news(), (int) innerWidth - NEWS_INSET, "home/news");
    }

    /**
     * A tab with nothing to show yet: its icon, a short title and one helpful line, centered —
     * instead of a lone dim sentence in a big empty panel.
     */
    static Flex emptyState(HubTab tab, Component title, Component line) {
        Flex center = Flex.column().gap(5).justify(Justify.CENTER).alignItems(Align.CENTER);
        center.sizing(Sizing.fill(), Sizing.expand());
        HubIcons.Icon icon = HubIcons.tab(tab);
        if (icon != null) {
            Flex art = Flex.column();
            art.sizing(Sizing.fixed(32), Sizing.fixed(32));
            art.item(icon.node(32));
            center.item(art);
        }
        center.item(EdenParchment.text(title, EdenParchment.INK_MID));
        center.item(EdenParchment.text(line, EdenParchment.INK_DIM, 260));
        return center;
    }

    /** The news panel's text width at the smallest hub (the Moderation preview's width). */
    static final int NEWS_WIDTH = 380;
    /** How much narrower the news text is than the tab: the box's padding and scroll lane. */
    private static final int NEWS_INSET = (int) (MIN_WIDTH - PAGE_PADDING.horizontal()) - NEWS_WIDTH;

    /**
     * The Home tab's news panel: the Markdown in the reader's language (else the default), scrolling
     * when long. The Moderation window's News preview draws the same panel.
     */
    static Flex news(HubPayloads.NewsView news, int textWidth, String scrollKey) {
        String text = news.text(HubLanguage.code());
        Flex box = Flex.column().gap(3).padding(Insets.of(6, 8, 6, 8));
        // No news: just the heading and one line, not a tall empty box.
        box.sizing(Sizing.fill(), text.isBlank() ? Sizing.content() : Sizing.expand());
        box.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        box.item(EdenParchment.text(
                Component.translatable("gui.eden_ld.hub.home.news"), EdenParchment.INK_MID));
        box.item(EdenParchment.hairline());
        Component body = text.isBlank()
                ? Component.translatable("gui.eden_ld.hub.home.no_news")
                : net.ledok.vectorlib.text.MarkdownText.parse(text);
        Flex lines = Flex.column();
        // No theme shadow: it doubles colored and dark Markdown text.
        lines.item(EdenParchment.text(body, text.isBlank() ? EdenParchment.INK_DIM : EdenParchment.INK, textWidth).shadow(false));
        if (text.isBlank()) {
            box.item(lines);
            return box;
        }
        net.ledok.vectorlib.client.canvas.widget.ScrollPanel scroll = HubScroll.panel(scrollKey, textWidth + 6, 60, lines);
        scroll.sizing(Sizing.fill(), Sizing.expand());
        box.item(scroll);
        return box;
    }

    /** Called by the client payload receiver when fresh Home data lands. */
    public void onHomeData() {
        // Home data carries Friends availability and the request count badge.
        rebuildTabRow();
        if (HubState.lastTab == HubTab.HOME) {
            rebuildContent();
        }
    }

    /** Fresh guild level/quest data: only the sub-tabs that show it rebuild (not the chat). */
    public void onGuildQuests() {
        if (HubState.lastTab == HubTab.GUILD
                && (HubState.guildView == GuildView.QUESTS || HubState.guildView == GuildView.LEVEL)) {
            content.clear();
            GuildTab.build(this, content);
        }
    }

    /** Called when fresh guild data or a guild chat line lands. */
    public void onGuildData() {
        if (HubState.lastTab == HubTab.GUILD) {
            content.clear();
            GuildTab.build(this, content);
            // Joining or leaving a guild, or another sub-tab: the help window's ▶ follows.
            HubHelp.refreshTour();
        } else if (HubState.lastTab == HubTab.HOME) {
            rebuildContent();
        }
    }

    /** Called when fresh daily quest data lands (tab open, progress, claims, rerolls). */
    /** The mailbox changed (new mail, a claim, a read): the tab bar (badge, a first-time Mail tab) and the tab. */
    public void onMailData() {
        rebuildTabRow();
        if (HubState.lastTab == HubTab.MAIL) {
            content.clear();
            MailTab.build(this, content);
        } else if (HubState.lastTab == HubTab.HOME) {
            rebuildContent();
        }
    }

    public void onDailiesData() {
        if (HubState.lastTab == HubTab.DAILIES) {
            content.clear();
            DailiesTab.build(this, content);
        } else if (HubState.lastTab == HubTab.HOME) {
            rebuildContent();
        }
    }

    /** Called when waystone data or a refused teleport lands. */
    public void onWaystonesData() {
        if (HubState.lastTab == HubTab.WAYSTONES) {
            content.clear();
            WaystonesTab.build(this, content);
        }
    }

    /** Fresh Activities data (tab opened), or a filter/sort change. */
    public void onActivitiesData() {
        if (HubState.lastTab == HubTab.ACTIVITIES) {
            content.clear();
            ActivitiesTab.build(this, content);
            // Duels picked or left: the help window's ▶ follows.
            HubHelp.refreshTour();
        }
    }

    /** Fresh Duels data (the Duels filter opened, a queue or match change), or a view switch. */
    public void onDuelsData() {
        if (HubState.lastTab == HubTab.ACTIVITIES && ActivitiesTab.showingDuels()) {
            content.clear();
            ActivitiesTab.build(this, content);
        }
    }

    /** New unlocks seen or arrived: the tab badge, and the Home card. */
    public void onActivityUnlocks() {
        rebuildTabRow();
        if (HubState.lastTab == HubTab.HOME) {
            rebuildContent();
        }
    }

    /** A new hub theme was applied: reskin the canvas and rebuild everything visible. */
    /** The hub language changed (Settings → Visual): everything is drawn again in it. */
    public void onLanguageChanged() {
        HubLanguage.apply();
        onThemeChanged();
    }

    public void onThemeChanged() {
        canvas().theme(EdenParchment.THEME);
        page.background(EdenParchment.panel());
        decorate();
        rebuildTabRow();
        onNameStyles();
    }

    // ------------------------------------------------------------------ theme decorations

    /** The panel under the tabs, in canvas units: x, y, w, h. */
    private float[] panelBox() {
        return new float[]{0, TAB_HEIGHT_ACTIVE, canvas().width(), canvas().height() - TAB_HEIGHT_ACTIVE};
    }

    /** Places the active theme's decorations around the panel. */
    private void decorate() {
        decorationsBehind.clear();
        decorationsFront.clear();
        net.ledok.eden_ld.client.theme.HubTheme theme = net.ledok.eden_ld.client.theme.HubThemes
                .get(net.ledok.eden_ld.client.theme.HubThemes.selected());
        if (theme == null) {
            return;
        }
        for (net.ledok.eden_ld.client.theme.HubTheme.Decoration decoration : theme.decorations()) {
            float[] at = decorationPosition(decoration);
            net.ledok.eden_ld.client.theme.HubTheme.Image image = decoration.image();
            net.ledok.vectorlib.client.canvas.ImageNode node = net.ledok.vectorlib.client.canvas.ImageNode.of(
                    image.texture(), at[0], at[1], decoration.width(), decoration.height(), image.width(), image.height());
            node.pickable(false);
            (decoration.front() ? decorationsFront : decorationsBehind).add(node);
        }
    }

    /** Top-left corner of a decoration: its anchor point on the panel's same point, plus its offset. */
    private float[] decorationPosition(net.ledok.eden_ld.client.theme.HubTheme.Decoration decoration) {
        float[] box = panelBox();
        String anchor = decoration.anchor();
        float fx = anchor.endsWith("left") ? 0 : anchor.endsWith("right") ? 1 : 0.5f;
        float fy = anchor.startsWith("top") ? 0 : anchor.startsWith("bottom") ? 1 : 0.5f;
        float x = box[0] + box[2] * fx - decoration.width() * fx + decoration.x();
        float y = box[1] + box[3] * fy - decoration.height() * fy + decoration.y();
        return new float[]{x, y};
    }

    /** Names restyled (LuckPerms prefixes changed, or someone joined/left): redraw the open tab. */
    public void onNameStyles() {
        content.clear();
        switch (HubState.lastTab) {
            case HOME -> buildHome();
            case PARTY -> PartyTab.build(this, content);
            case GUILD -> GuildTab.build(this, content);
            case FRIENDS -> FriendsTab.build(this, content);
            default -> rebuildContent();
        }
    }

    /** Called when fresh settings land (tab open, or the server's echo of a change). */
    public void onSettingsData() {
        if (HubState.lastTab == HubTab.SETTINGS) {
            content.clear();
            SettingsTab.build(this, content);
        }
    }

    /** Called when fresh friends data lands (tab open, friend actions, players joining/leaving). */
    public void onFriendsData() {
        rebuildTabRow();
        if (HubState.lastTab == HubTab.FRIENDS) {
            content.clear();
            FriendsTab.build(this, content);
        } else if (HubState.lastTab == HubTab.HOME) {
            rebuildContent();
        }
    }

    /** Called when the ignore list changes: badges, list order, chat panels and Settings → Privacy follow. */
    public void onIgnoreData() {
        rebuildTabRow();
        rebuildContent();
    }

    /** Called when fresh party data (or a chat line) lands. */
    public void onPartyData() {
        if (HubState.lastTab == HubTab.PARTY) {
            content.clear();
            PartyTab.build(this, content);
        } else if (HubState.lastTab == HubTab.FRIENDS) {
            // Party-invite buttons on friend rows follow the party candidate list.
            content.clear();
            FriendsTab.build(this, content);
        } else if (HubState.lastTab == HubTab.HOME) {
            // The Home attention cards show the party invite count — keep it live.
            rebuildContent();
        }
    }

    private void requestHomeData() {
        ClientPlayNetworking.send(HubPayloads.HomeDataRequest.INSTANCE);
    }

    private boolean partyDataRequested;

    private void requestPartyData() {
        if (!partyDataRequested) {
            partyDataRequested = true;
            ClientPlayNetworking.send(HubPayloads.PartyDataRequest.INSTANCE);
        }
    }
}
