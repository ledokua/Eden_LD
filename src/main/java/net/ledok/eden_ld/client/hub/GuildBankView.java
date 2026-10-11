package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads.BankAction;
import net.ledok.eden_ld.hub.HubPayloads.BankAction.Action;
import net.ledok.eden_ld.hub.HubPayloads.BankData;
import net.ledok.eden_ld.hub.HubPayloads.BankLog;
import net.ledok.eden_ld.hub.HubPayloads.BankTab;
import net.ledok.eden_ld.hub.HubPayloads.BankView;
import net.ledok.eden_ld.hub.HubPayloads.GuildData;
import net.ledok.eden_ld.hub.HubPayloads.RankRightsView;
import net.ledok.eden_ld.hub.guild.GuildBank;
import net.ledok.eden_ld.hub.guild.GuildRank;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasRenderContext;
import net.ledok.vectorlib.client.canvas.Interactive;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.ShapeNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Grid;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Dropdown;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The Guild tab's bank sub-view: balance, vault tabs and upgrades (left), the viewed vault tab
 * over the player's inventory (center), plus a log dialog and the leader's permissions dialog.
 * Items move by server-side clicks only (left = stack, right or shift = one item); every action
 * is answered with fresh {@link BankData}, which rebuilds the view.
 */
final class GuildBankView {

    private static final float SLOT = 18;
    private static final float GRID = SLOT * 9;
    private static final float GAP = 8;
    private static final float LANE = 6;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Vault tab the player looks at (-1 = none chosen yet); survives rebuilds and sub-view switches. */
    private static int viewedTab = -1;
    // BankView goes out on user actions and once per hub screen / GuildData instance (tab re-entry
    // re-requests guild data) — never per rebuild, since every BankData arrival rebuilds.
    private static WeakReference<HubScreen> lastScreen = new WeakReference<>(null);
    private static String amountText = "";
    private static TextField amountField;
    private static boolean refocusAmount;
    /** The open log window and its list (refilled as bank pushes land). */
    private static net.ledok.vectorlib.client.canvas.widget.Window logWindow;
    private static Flex logList;

    private GuildBankView() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.guild." + key, args);
    }

    private static void send(BankAction action) {
        ClientPlayNetworking.send(action);
    }

    private static void request(HubScreen screen, int tab) {
        ClientPlayNetworking.send(new BankView(tab));
        lastScreen = new WeakReference<>(screen);
    }

    /** Called when the Guild tab is entered (or the hub reopens on it) with the bank view up. */
    static void resubscribe(HubScreen screen) {
        request(screen, viewedTab);
    }

    /** The Bank sub-tab opened: the server starts pushing bank updates. */
    static void opened(HubScreen screen) {
        request(screen, viewedTab);
    }

    /** The Bank sub-tab closed: the server stops pushing (and the log window, which no longer updates, goes). */
    static void closed() {
        ClientPlayNetworking.send(new BankView(BankView.CLOSED));
        HubState.bankData = null;
        closeLog();
    }

    /** Closes the log window (the bank is leaving the screen; the log would stop updating). */
    static void closeLog() {
        if (logWindow != null) {
            logWindow.close();
        }
    }

    static void build(HubScreen screen, Flex container, GuildData data, float width) {
        if (lastScreen.get() != screen) {
            logWindow = null;
            logList = null;
        }
        if (lastScreen.get() != screen) {
            request(screen, viewedTab);
        }
        float leftWidth = width - GRID - GAP;
        BankData bank = HubState.bankData;
        Flex left = Flex.column().gap(5);
        left.sizing(Sizing.fixed(leftWidth), Sizing.fill());
        Flex top = Flex.row().gap(3).alignItems(Align.CENTER);
        top.sizing(Sizing.fill(), Sizing.fixed(14));
        left.item(top);
        container.item(left);

        if (bank == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fixed(GRID), Sizing.fill());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"),
                    EdenParchment.INK_DIM));
            container.item(center);
            return;
        }

        if (viewedTab >= bank.tabs().size()) {
            viewedTab = -1;
        }
        if (viewedTab < 0) {
            // First visit (or first tab just bought): open the first tab the rank may see, once.
            for (int i = 0; i < bank.tabs().size(); i++) {
                if (bank.tabs().get(i).access() >= GuildBank.Access.VIEW.ordinal()) {
                    viewedTab = i;
                    request(screen, i);
                    break;
                }
            }
        }

        // Leader and vice-leader: purchases and the permissions editor.
        boolean leader = bank.myRank() >= GuildRank.VICE_LEADER.level;
        Button log = EdenParchment.fitButton(tr("bank.log"), 34, 14, () -> openLog(screen));
        log.id(HubSpotlights.GUILD_BANK_LOG);
        Button permissions = leader
                ? EdenParchment.fitButton(tr("bank.permissions"), 50, 14, () -> openPermissions(screen, bank)) : null;
        // The open storage tab's status leads the row (the vault and inventory need the right column's height).
        float buttons = log.bounds().width() + (permissions == null ? 0 : permissions.bounds().width() + 3);
        Label status = tabStatus(bank, leftWidth - buttons - 6);
        if (status != null) {
            top.item(status);
        }
        top.spacer();
        top.item(log);
        if (permissions != null) {
            top.item(permissions);
        }
        left.item(balanceBox(bank, leftWidth - 8));
        left.item(tabsBox(screen, bank, leftWidth - 8, leader));
        left.item(upgradesBox(screen, bank, leftWidth - 10, leader));
        container.item(centerColumn(bank));

        if (logWindow != null && logList != null) {
            fillLog(logList, bank, logRowWidth(logWindow));
        }
        if (refocusAmount) {
            refocusAmount = false;
            screen.canvas().focus(amountField);
        }
    }

    // ---------------------------------------------------------------- balance

    private static Flex balanceBox(BankData bank, float inner) {
        Flex box = Flex.column().gap(3).padding(Insets.of(4, 4, 4, 4));
        box.id(HubSpotlights.GUILD_BANK_BALANCE);
        box.sizing(Sizing.fill(), Sizing.content());
        box.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));

        // One line each: big balances side by side would run out of the box.
        Label balance = Label.of(inner, tr("bank.balance", bank.balance()));
        balance.color(EdenParchment.INK);
        balance.tooltipWhenCut(true);
        box.item(balance);
        if (bank.myBalance() >= 0) {
            // What the player can deposit, right above the field they type it into.
            Label mine = Label.of(inner, tr("bank.my_balance", bank.myBalance()));
            mine.color(EdenParchment.INK_DIM);
            mine.tooltipWhenCut(true);
            box.item(mine);
        }
        if (!bank.economy()) {
            box.item(EdenParchment.text(tr("bank.no_economy"), EdenParchment.DANGER));
        }

        Flex row = Flex.row().gap(3).alignItems(Align.CENTER);
        row.sizing(Sizing.fixed(inner), Sizing.content());
        boolean hadFocus = amountField != null && amountField.isFocused();
        TextField amount = new TextField(80, amountText, text -> amountText = text);
        amount.sizing(Sizing.expand(), Sizing.fixed(16));
        amount.maxLength(12);
        amount.filter(text -> text.chars().allMatch(Character::isDigit));
        amount.hint(tr("bank.amount_hint").getString());
        row.item(amount);

        Button deposit = EdenParchment.fitButton(tr("bank.deposit"), 40, 16, () -> sendLc(Action.DEPOSIT_LC));
        deposit.enabled(bank.economy());
        deposit.tooltip(bank.economy() ? tr("bank.deposit_tip") : tr("bank.no_economy"));
        row.item(deposit);

        Button withdraw = EdenParchment.fitButton(tr("bank.withdraw"), 40, 16, () -> sendLc(Action.WITHDRAW_LC));
        withdraw.enabled(bank.economy() && bank.lcLeft() != 0);
        withdraw.tooltip(!bank.economy() ? tr("bank.no_economy")
                : bank.lcLeft() < 0 ? tr("bank.withdraw_unlimited")
                : bank.lcLeft() == 0 ? tr("bank.withdraw_none")
                : tr("bank.withdraw_left", bank.lcLeft()));
        row.item(withdraw);
        box.item(row);

        amountField = amount;
        refocusAmount = hadFocus;
        return box;
    }

    private static void sendLc(Action action) {
        long amount;
        try {
            amount = Long.parseLong(amountText.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (amount > 0) {
            send(BankAction.amount(action, amount));
            amountText = "";
            if (amountField != null) {
                amountField.text("");
            }
        }
    }

    // ---------------------------------------------------------------- tabs

    private static Flex tabsBox(HubScreen screen, BankData bank, float inner, boolean leader) {
        Flex box = Flex.column().gap(3).padding(Insets.of(4, 4, 4, 4));
        box.id(HubSpotlights.GUILD_BANK_TABS);
        box.sizing(Sizing.fill(), Sizing.expand());
        box.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));

        Flex header = Flex.row().gap(3).alignItems(Align.CENTER);
        header.sizing(Sizing.fixed(inner), Sizing.content());
        header.item(EdenParchment.text(tr("bank.tabs"), EdenParchment.INK_MID));
        header.spacer();
        long cost = bank.nextTabCost();
        if (leader && cost >= 0) {
            int number = bank.tabs().size() + 1;
            Button buy = EdenParchment.fitButton(tr("bank.buy_tab", cost), 40, 12,
                    () -> confirm(screen, tr("bank.confirm_buy_tab", number, cost), tr("bank.buy"),
                            () -> send(BankAction.of(Action.BUY_TAB))));
            buy.enabled(bank.balance() >= cost);
            if (bank.balance() < cost) {
                buy.tooltip(tr("bank.bank_short", cost));
            }
            header.item(buy);
        }
        box.item(header);

        if (bank.tabs().isEmpty()) {
            return box;
        }
        float cell = (inner - LANE - 4) / 3;
        Grid grid = Grid.of(3).gap(2).cell(cell, 13);
        for (int i = 0; i < bank.tabs().size(); i++) {
            BankTab tab = bank.tabs().get(i);
            int index = i;
            Button button = new Button(cell, 13, tabName(index, tab), () -> {
                if (index != viewedTab) {
                    viewedTab = index;
                    request(screen, index);
                    screen.onGuildData();
                }
            });
            button.tooltipWhenCut(true);
            if (index == viewedTab) {
                button.style(EdenParchment.TAB_ACTIVE);
            }
            if (tab.access() <= GuildBank.Access.NONE.ordinal()) {
                button.enabled(false);
                button.tooltip(tr("bank.tab_no_access"));
            }
            grid.item(button);
        }
        ScrollPanel panel = HubScroll.panel("guild/bank_tabs", inner, 40, grid);
        panel.sizing(Sizing.fixed(inner), Sizing.expand());
        box.item(panel);
        return box;
    }

    private static Component tabName(int index, BankTab tab) {
        return tab == null || tab.name().isBlank() ? tr("bank.tab_default", index + 1) : Component.literal(tab.name());
    }

    private static Component tabName(BankData bank, int index) {
        return tabName(index, index >= 0 && index < bank.tabs().size() ? bank.tabs().get(index) : null);
    }

    // ---------------------------------------------------------------- upgrades

    private static Flex upgradesBox(HubScreen screen, BankData bank, float inner, boolean leader) {
        Flex box = Flex.column().gap(3).padding(Insets.of(4, 5, 4, 5));
        box.id(HubSpotlights.GUILD_BANK_UPGRADES);
        box.sizing(Sizing.fill(), Sizing.content());
        box.background(EdenParchment.framed(EdenParchment.ROW_BG_ALT, EdenParchment.HAIRLINE));

        // The rows stop short of the frame so the Buy buttons sit clear of it.
        float rowWidth = inner - 6;
        long memberCost = bank.nextMemberCost();
        box.item(upgradeRow(bank, rowWidth, leader,
                tr("bank.members_upgrade", bank.memberLevel() * bank.memberStep()),
                memberCost < 0 ? tr("bank.maxed") : tr("bank.next_members", bank.memberStep(), memberCost),
                memberCost, () -> confirm(screen, tr("bank.confirm_members", bank.memberStep(), memberCost),
                        tr("bank.buy"), () -> send(BankAction.of(Action.BUY_MEMBERS)))));

        long claimCost = bank.nextClaimCost();
        box.item(upgradeRow(bank, rowWidth, leader,
                tr("bank.claims_upgrade", bank.claimLevel(), bank.claimStep()),
                claimCost < 0 ? tr("bank.maxed") : tr("bank.next_claims", claimCost),
                claimCost, () -> confirm(screen, tr("bank.confirm_claims", bank.claimLevel() + 1, claimCost),
                        tr("bank.buy"), () -> send(BankAction.of(Action.BUY_CLAIMS)))));
        // With OPAC set up for guilds: live usage; otherwise why the upgrade does nothing yet.
        box.item(bank.claimsLimit() >= 0
                ? EdenParchment.text(tr("bank.claims_usage", bank.claimsUsed(), bank.claimsLimit()),
                        bank.claimsUsed() >= bank.claimsLimit() ? EdenParchment.WARN : EdenParchment.INK_MID, (int) inner)
                : EdenParchment.text(tr("bank.claims_note"), EdenParchment.INK_DIM, (int) inner));
        return box;
    }

    /** Title + dim "next" line, and the leader's Buy (or Max) button on the right. */
    private static Flex upgradeRow(BankData bank, float inner, boolean leader, Component title, Component next,
                                   long cost, Runnable buy) {
        Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
        row.sizing(Sizing.fixed(inner), Sizing.content());
        Button button = null;
        if (leader) {
            button = EdenParchment.fitButton(cost < 0 ? tr("bank.max") : tr("bank.buy"), 34, 14, buy);
            button.enabled(cost >= 0 && bank.balance() >= cost);
            if (cost >= 0 && bank.balance() < cost) {
                button.tooltip(tr("bank.bank_short", cost));
            }
        }
        float textWidth = inner - (button == null ? 0 : button.bounds().width() + 4);
        Flex text = Flex.column().gap(1);
        Label titleLabel = Label.of(textWidth, title);
        titleLabel.color(EdenParchment.INK);
        titleLabel.tooltipWhenCut(true);
        text.item(titleLabel);
        Label nextLabel = Label.of(textWidth, next);
        nextLabel.color(cost >= 0 && bank.balance() < cost ? EdenParchment.WARN : EdenParchment.INK_DIM);
        nextLabel.tooltipWhenCut(true);
        text.item(nextLabel);
        row.item(text);
        if (button != null) {
            row.spacer();
            row.item(button);
        }
        return row;
    }

    // ---------------------------------------------------------------- vault + inventory

    /**
     * "Tab 1 · Items left: 5" for the open storage tab — warning-colored when no withdrawals are left — with
     * the click hints on hover. Null without an open tab.
     */
    private static Label tabStatus(BankData bank, float width) {
        BankTab tab = viewedTab >= 0 && viewedTab < bank.tabs().size() ? bank.tabs().get(viewedTab) : null;
        if (tab == null || width < 20) {
            return null;
        }
        int access = tab.access();
        Label status = Label.of(width, headerText(viewedTab, tab));
        status.color(access == GuildBank.Access.WITHDRAW.ordinal() && tab.itemsLeft() == 0
                ? EdenParchment.WARN : EdenParchment.INK_MID);
        status.pickable(true);
        if (access == GuildBank.Access.WITHDRAW.ordinal()) {
            status.tooltip(headerText(viewedTab, tab), tr("bank.items_left_hint"), tr("bank.click_hint"),
                    tr("bank.click_hint_stack"), tr("bank.drag_hint"), tr("bank.click_hint_amount"), tr("bank.inventory_hint"));
        } else {
            status.tooltip(headerText(viewedTab, tab), tr("bank.click_hint"),
                    tr("bank.click_hint_stack"), tr("bank.drag_hint"), tr("bank.click_hint_amount"), tr("bank.inventory_hint"));
        }
        return status;
    }

    private static Flex centerColumn(BankData bank) {
        // The vault, then the player's inventory and hotbar: 6 + 3 + 1 rows, which just fit the smallest hub
        // under the Guild header (the open tab's status sits in the left column's top row).
        Flex column = Flex.column();
        column.sizing(Sizing.fixed(GRID), Sizing.fill());
        BankTab tab = viewedTab >= 0 && viewedTab < bank.tabs().size() ? bank.tabs().get(viewedTab) : null;
        int access = tab == null ? 0 : tab.access();
        boolean shown = tab != null && access >= GuildBank.Access.VIEW.ordinal() && bank.viewTab() == viewedTab
                && bank.contents().size() >= GuildBank.TAB_SIZE;

        if (shown) {
            boolean canTake = access == GuildBank.Access.WITHDRAW.ordinal() && tab.itemsLeft() != 0;
            int tabIndex = bank.viewTab();
            int itemsLeft = tab.itemsLeft();
            Grid vault = Grid.of(9).cell(SLOT, SLOT);
            vault.id(HubSpotlights.GUILD_BANK_VAULT);
            for (int slot = 0; slot < GuildBank.TAB_SIZE; slot++) {
                ItemStack stack = bank.contents().get(slot);
                int index = slot;
                SlotNode node = new SlotNode(() -> stack, canTake, itemsLeft,
                        count -> send(BankAction.item(Action.WITHDRAW_ITEM, tabIndex, index, count)),
                        tr("bank.amount_take"), true);
                if (!canTake) {
                    node.tooltip(access == GuildBank.Access.WITHDRAW.ordinal() ? tr("bank.items_left", 0)
                            : tr("bank.view_only"));
                }
                vault.item(node);
            }
            column.item(vault);
            // A stack dragged here from the inventory goes in whole (where the player may deposit).
            boolean deposit = access >= GuildBank.Access.DEPOSIT.ordinal();
            dropZone(vault, drag -> !drag.fromVault() && deposit, drag -> drag.move().accept(0));
        } else {
            Component message = bank.tabs().isEmpty() ? tr("bank.no_tabs")
                    : tab != null && access < GuildBank.Access.VIEW.ordinal() ? tr("bank.no_view")
                    : Component.translatable("gui.eden_ld.hub.home.loading");
            Flex box = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER).padding(Insets.of(6, 8, 6, 8));
            box.sizing(Sizing.fixed(GRID), Sizing.fixed(SLOT * 6));
            box.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
            box.item(EdenParchment.text(message, EdenParchment.INK_DIM, (int) GRID - 16));
            column.item(box);
        }

        boolean canPut = shown && access >= GuildBank.Access.DEPOSIT.ordinal();
        column.item(EdenParchment.spacer(3));
        Flex inventory = Flex.column().gap(2);
        inventory.id(HubSpotlights.GUILD_BANK_INVENTORY);
        Grid main = Grid.of(9).cell(SLOT, SLOT);
        for (int i = 9; i < 36; i++) {
            main.item(inventorySlot(i, canPut));
        }
        inventory.item(main);
        Grid hotbar = Grid.of(9).cell(SLOT, SLOT);
        for (int i = 0; i < 9; i++) {
            hotbar.item(inventorySlot(i, canPut));
        }
        inventory.item(hotbar);
        column.item(inventory);
        // A stack dragged here from the vault comes out whole (the server keeps to today's limit).
        dropZone(inventory, BankDrag::fromVault, drag -> drag.move().accept(0));
        return column;
    }

    /** A stack picked up from a slot: from the vault or the inventory, and how to move it ({@code 0} = all of it). */
    private record BankDrag(boolean fromVault, java.util.function.IntConsumer move, ItemStack stack) {
    }

    /** Takes stacks dragged from the other grid, outlined in the accent while one would land here. */
    private static void dropZone(net.ledok.vectorlib.client.canvas.CanvasNode zone,
                                 java.util.function.Predicate<BankDrag> accepts,
                                 java.util.function.Consumer<BankDrag> drop) {
        net.ledok.vectorlib.client.canvas.DragDrop.zone(zone, payload -> payload instanceof BankDrag drag && accepts.test(drag),
                payload -> drop.accept((BankDrag) payload));
    }

    private static Component headerText(int index, BankTab tab) {
        MutableComponent text = tabName(index, tab).copy().append(" · ");
        if (tab.access() == GuildBank.Access.WITHDRAW.ordinal()) {
            return text.append(tr("bank.items_left", tab.itemsLeft() < 0 ? "∞" : String.valueOf(tab.itemsLeft())));
        }
        return text.append(tr(tab.access() == GuildBank.Access.DEPOSIT.ordinal() ? "bank.deposit_only"
                : "bank.view_only"));
    }

    /** A live inventory slot: the server syncs slot changes after the bank data, so read it every frame. */
    private static SlotNode inventorySlot(int index, boolean canPut) {
        return new SlotNode(() -> {
            var player = Minecraft.getInstance().player;
            return player == null ? ItemStack.EMPTY : player.getInventory().items.get(index);
        }, canPut, -1, count -> send(BankAction.item(Action.DEPOSIT_ITEM, viewedTab, index, count)),
                tr("bank.amount_put"), false);
    }

    /**
     * One 18×18 slot with its item. A left click (on release) moves one item, shift + left click the whole
     * stack, and dragging the stack onto the other grid moves all of it; right or middle click opens an
     * amount picker. {@code cap} limits the picker (-1 = the stack).
     */
    private static final class SlotNode extends CanvasGroup implements Interactive,
            net.ledok.vectorlib.client.canvas.DragDrop.Source {
        private final Supplier<ItemStack> stack;
        private final boolean enabled;
        private final int cap;
        private final java.util.function.IntConsumer move;
        private final Component verb;
        private final boolean fromVault;
        private final ShapeNode frame;
        private final ItemStackNode item;
        private boolean pressed;

        SlotNode(Supplier<ItemStack> stack, boolean enabled, int cap, java.util.function.IntConsumer move,
                 Component verb, boolean fromVault) {
            this.stack = stack;
            this.enabled = enabled;
            this.cap = cap;
            this.move = move;
            this.verb = verb;
            this.fromVault = fromVault;
            frame = add(Shapes.rect(0.5f, 0.5f, SLOT - 1, SLOT - 1));
            item = add(ItemStackNode.of(stack.get(), 1, 1));
            paint(false);
        }

        private boolean live() {
            return enabled && !stack.get().isEmpty();
        }

        private void paint(boolean hovered) {
            int ink = EdenParchment.INK & 0xFFFFFF;
            if (!enabled) {
                frame.fill(0x14000000 | ink).stroke(EdenParchment.INK_DIM, 1);
            } else if (hovered && live()) {
                frame.fill(0x55000000 | (EdenParchment.ACCENT & 0xFFFFFF)).stroke(EdenParchment.ACCENT, 1);
            } else {
                frame.fill(0x30000000 | ink).stroke(EdenParchment.HAIRLINE, 1);
            }
        }

        @Override
        public Rect bounds() {
            return new Rect(0, 0, SLOT, SLOT);
        }

        @Override
        protected void draw(CanvasRenderContext ctx) {
            item.stack(stack.get());
            super.draw(ctx);
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (!live() || button > 2) {
                return false;
            }
            if (button != 0) {
                ItemStack current = stack.get();
                int max = cap > 0 ? Math.min(cap, current.getCount()) : current.getCount();
                if (Minecraft.getInstance().screen instanceof HubScreen screen) {
                    openAmount(screen, current, max, verb, move);
                }
                return true;
            }
            // Moves on release, so a press that turns into a drag moves nothing here.
            pressed = true;
            return true;
        }

        @Override
        public void onMouseUp(float x, float y, int button) {
            if (button != 0 || !pressed) {
                return;
            }
            pressed = false;
            if (live() && bounds().contains(x, y)) {
                // 0 = the whole stack (the server caps it to today's limit).
                move.accept(Screen.hasShiftDown() ? 0 : 1);
            }
        }

        @Override
        public Object payload() {
            return live() ? new BankDrag(fromVault, move, stack.get().copy()) : null;
        }

        @Override
        public net.ledok.vectorlib.client.canvas.CanvasNode ghost(Object payload) {
            return ItemStackNode.of(((BankDrag) payload).stack(), 0, 0, 16);
        }

        @Override
        public void onHoverChanged(boolean hovered) {
            paint(hovered);
        }

        @Override
        public Cursor cursor() {
            return live() ? Cursor.HAND : Cursor.DEFAULT;
        }
    }

    /** Right/middle-click picker: slider and number field kept in sync, 1..max. */
    private static void openAmount(HubScreen screen, ItemStack stack, int max, Component verb,
                                   java.util.function.IntConsumer move) {
        if (max <= 1) {
            move.accept(1);
            return;
        }
        float inner = 180;
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(inner + 20), Sizing.content());
        dialog.background(EdenParchment.panel());
        Flex title = Flex.row().gap(4).alignItems(Align.CENTER);
        title.item(ItemStackNode.of(stack, 0, 0, 16).decorations(false));
        Label name = Label.of(inner - 20, verb.copy().append(" ").append(stack.getHoverName()));
        name.color(EdenParchment.INK);
        name.tooltipWhenCut(true);
        title.item(name);
        dialog.item(title);

        int start = Math.max(1, max / 2);
        net.ledok.vectorlib.client.canvas.widget.Slider[] slider = new net.ledok.vectorlib.client.canvas.widget.Slider[1];
        net.ledok.vectorlib.client.canvas.widget.NumberField number =
                new net.ledok.vectorlib.client.canvas.widget.NumberField(50, start, value -> {
                    if (slider[0] != null) {
                        slider[0].value(value);
                    }
                });
        number.range(1, max).step(1);
        slider[0] = new net.ledok.vectorlib.client.canvas.widget.Slider(inner - 56, 1, max, 1, start,
                value -> number.value(Math.round(value)));
        Flex row = Flex.row().gap(6).alignItems(Align.CENTER);
        row.item(slider[0]);
        row.item(number);
        dialog.item(row);
        dialog.item(EdenParchment.text(tr("bank.amount_max", max), EdenParchment.INK_DIM));

        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6).justify(Justify.CENTER);
        buttons.sizing(Sizing.fixed(inner), Sizing.content());
        buttons.item(EdenParchment.fitButton(tr("bank.amount_ok"), 60, 14, () -> {
            move.accept((int) number.value());
            modal[0].close();
        }));
        buttons.item(EdenParchment.fitButton(tr("cancel"), 60, 14, () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    // ---------------------------------------------------------------- log dialog

    /**
     * The bank's log as a window beside the vault: it stays open (and live) while the player deposits and
     * withdraws, and can be moved and resized. The Log button again closes it.
     */
    private static void openLog(HubScreen screen) {
        BankData bank = HubState.bankData;
        if (bank == null) {
            return;
        }
        if (logWindow != null && logWindow.canvas() == screen.canvas()) {
            logWindow.close();
            return;
        }
        net.ledok.vectorlib.client.canvas.widget.Window window = screen.window(tr("bank.log_title"), 300, 190, "bank_log");
        Flex list = Flex.column().gap(2);
        ScrollPanel scroll = HubScroll.panel("guild/bank_log", 284, 100, list);
        scroll.sizing(Sizing.fill(), Sizing.expand());
        window.body().item(scroll);
        window.onResize((width, height) -> {
            if (HubState.bankData != null) {
                fillLog(list, HubState.bankData, logRowWidth(window));
            }
        });
        window.onClose(() -> {
            if (logWindow == window) {
                logWindow = null;
                logList = null;
            }
        });
        fillLog(list, bank, logRowWidth(window));
        logWindow = window;
        logList = list;
        window.open(screen.canvas());
    }

    /** The log's rows: the window less its border and the scroll lane. */
    private static float logRowWidth(net.ledok.vectorlib.client.canvas.widget.Window window) {
        return window.width() - 16 - LANE;
    }

    private static void fillLog(Flex list, BankData bank, float rowWidth) {
        list.clear();
        if (bank.log().isEmpty()) {
            list.item(EdenParchment.text(tr("bank.log_empty"), EdenParchment.INK_DIM, (int) rowWidth));
            return;
        }
        LocalDate today = LocalDate.now();
        for (int i = bank.log().size() - 1; i >= 0; i--) {
            BankLog entry = bank.log().get(i);
            Flex row = Flex.row().gap(3);
            LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.time()), ZoneId.systemDefault());
            Label stamp = Label.of(26, time.toLocalDate().equals(today) ? time.format(TIME) : HubDates.dayMonth(time));
            stamp.color(EdenParchment.INK_DIM);
            row.item(stamp);
            float textWidth = rowWidth - 26 - 3;
            if (!entry.stack().isEmpty()) {
                row.item(ItemStackNode.of(entry.stack(), 0, 0, 9).decorations(false));
                textWidth -= 12;
            }
            row.item(EdenParchment.text(logText(bank, entry), EdenParchment.INK, (int) textWidth));
            list.item(row);
        }
    }

    private static Component logText(BankData bank, BankLog entry) {
        GuildBank.LogType[] types = GuildBank.LogType.values();
        GuildBank.LogType type = types[Math.floorMod(entry.type(), types.length)];
        String name = entry.name();
        return switch (type) {
            case DEPOSIT_ITEM -> tr("bank.log.deposit_item", name, entry.amount(), entry.stack().getHoverName(),
                    tabName(bank, entry.tab()));
            case WITHDRAW_ITEM -> tr("bank.log.withdraw_item", name, entry.amount(), entry.stack().getHoverName(),
                    tabName(bank, entry.tab()));
            case DEPOSIT_LC -> tr("bank.log.deposit_lc", name, entry.amount());
            case WITHDRAW_LC -> tr("bank.log.withdraw_lc", name, entry.amount());
            case BUY_TAB -> tr("bank.log.buy_tab", name, tabName(bank, entry.tab()), entry.amount());
            case BUY_UPGRADE -> tr("CLAIMS".equals(entry.detail()) ? "bank.log.buy_claims" : "bank.log.buy_members",
                    name, entry.amount());
            case QUEST_REWARD -> tr("bank.log.quest_reward", entry.amount());
            case RIGHTS -> {
                Component rank = rankName(entry.detail());
                if (rank == null) {
                    yield tr("bank.log.rights", name);
                }
                yield entry.tab() >= 0 ? tr("bank.log.rights_tab", name, rank, tabName(bank, entry.tab()))
                        : tr("bank.log.rights_lc", name, rank,
                        entry.amount() < 0 ? tr("bank.unlimited") : Component.literal(entry.amount() + " " + net.ledok.eden_ld.client.HubGlyphs.LC_TEXT));
            }
        };
    }

    private static Component rankName(String name) {
        try {
            return tr("rank." + GuildRank.valueOf(name).name().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- permissions dialog (leader)

    private static void openPermissions(HubScreen screen, BankData bank) {
        float inner = 300;
        float rowWidth = inner - LANE;
        Flex dialog = Flex.column().gap(5).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(inner + 20), Sizing.content());
        dialog.background(EdenParchment.panel());
        dialog.item(EdenParchment.text(tr("bank.permissions_title"), EdenParchment.INK));
        dialog.item(EdenParchment.text(tr("bank.permissions_hint"), EdenParchment.INK_DIM, (int) inner));
        dialog.item(EdenParchment.hairline());

        List<Runnable> commits = new ArrayList<>();
        Flex body = Flex.column().gap(3);
        if (!bank.tabs().isEmpty()) {
            body.item(EdenParchment.text(tr("bank.tab_names"), EdenParchment.INK_MID));
            for (int t = 0; t < bank.tabs().size(); t++) {
                BankTab tab = bank.tabs().get(t);
                int index = t;
                Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
                Label label = Label.of(100, tr("bank.tab_default", t + 1));
                label.color(EdenParchment.INK_DIM);
                row.item(label);
                TextField name = new TextField(rowWidth - 104, tab.name(), null);
                name.sizing(Sizing.fixed(rowWidth - 104), Sizing.fixed(16));
                name.maxLength(16);
                name.hint(tr("bank.tab_default", t + 1).getString());
                row.item(name);
                body.item(row);
                commits.add(() -> {
                    String text = name.text().trim();
                    if (!text.equals(tab.name())) {
                        send(BankAction.renameTab(index, text));
                    }
                });
            }
        }

        // One rank at a time (a dropdown picks it) so the list stays short as ranks grow.
        // Every rank's section is built up front, so edits survive switching ranks.
        List<Flex> sections = new ArrayList<>();
        for (RankRightsView view : bank.rights()) {
            Flex section = Flex.column().gap(3);
            Flex lcRow = Flex.row().gap(4).alignItems(Align.CENTER);
            lcRow.sizing(Sizing.fixed(rowWidth), Sizing.content());
            lcRow.item(EdenParchment.text(tr("bank.lc_per_day"), EdenParchment.INK_DIM));
            lcRow.spacer();
            TextField lc = limitField(60, view.lcPerDay());
            lcRow.item(lc);
            section.item(lcRow);
            commits.add(() -> {
                long value = parseLimit(lc.text());
                if (value != view.lcPerDay()) {
                    send(BankAction.lcLimit(view.rank(), value));
                }
            });

            if (!bank.tabs().isEmpty()) {
                Flex columns = Flex.row().gap(4);
                columns.item(dimLabel(100, tr("bank.col_tab")));
                columns.item(dimLabel(110, tr("bank.col_access")));
                columns.item(dimLabel(60, tr("bank.col_items")));
                section.item(columns);
            }
            for (int t = 0; t < bank.tabs().size(); t++) {
                int index = t;
                int oldAccess = t < view.access().size() ? view.access().get(t) : 0;
                long oldItems = t < view.items().size() ? view.items().get(t) : 0;
                int[] access = {oldAccess};
                Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
                Label label = Label.of(100, tabName(bank, t));
                label.color(EdenParchment.INK_MID);
                label.tooltipWhenCut(true);
                row.item(label);
                TextField items = limitField(60, oldItems);
                items.enabled(oldAccess == GuildBank.Access.WITHDRAW.ordinal());
                Dropdown<Integer> dropdown = new Dropdown<>(110, List.of(0, 1, 2, 3), oldAccess,
                        a -> tr("bank.access." + a), selected -> {
                    access[0] = selected;
                    items.enabled(selected == GuildBank.Access.WITHDRAW.ordinal());
                });
                dropdown.sizing(Sizing.fixed(110), Sizing.fixed(16));
                row.item(dropdown);
                row.item(items);
                section.item(row);
                commits.add(() -> {
                    boolean withdraw = access[0] == GuildBank.Access.WITHDRAW.ordinal();
                    long value = withdraw ? parseLimit(items.text()) : oldItems;
                    if (access[0] != oldAccess || value != oldItems) {
                        send(BankAction.tabRights(view.rank(), index, access[0], value));
                    }
                });
            }
            sections.add(section);
        }
        Flex rankBody = Flex.column();
        if (!sections.isEmpty()) {
            List<Integer> ranks = new ArrayList<>();
            for (int i = 0; i < bank.rights().size(); i++) {
                ranks.add(i);
            }
            Flex rankRow = Flex.row().gap(4).alignItems(Align.CENTER);
            rankRow.item(EdenParchment.text(tr("bank.rank_pick"), EdenParchment.INK));
            Dropdown<Integer> rankPick = new Dropdown<>(120, ranks, 0,
                    i -> tr("rank." + GuildRank.of(bank.rights().get(i).rank()).name().toLowerCase(Locale.ROOT)),
                    i -> {
                        rankBody.clear();
                        rankBody.item(sections.get(i));
                    });
            rankPick.sizing(Sizing.fixed(120), Sizing.fixed(16));
            rankRow.item(rankPick);
            dialog.item(rankRow);
            rankBody.item(sections.get(0));
        }
        body.item(EdenParchment.spacer(2));
        body.item(rankBody);
        dialog.item(new ScrollPanel(inner, 140, body));

        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6).justify(Justify.CENTER);
        buttons.sizing(Sizing.fixed(inner), Sizing.content());
        buttons.item(EdenParchment.fitButton(tr("save"), 70, 14, () -> {
            commits.forEach(Runnable::run);
            modal[0].close();
        }));
        buttons.item(EdenParchment.fitButton(tr("cancel"), 70, 14, () -> modal[0].close()));
        dialog.item(buttons);

        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    /** Blank = unlimited (-1), otherwise a non-negative daily limit. */
    private static TextField limitField(float width, long value) {
        TextField field = new TextField(width, value < 0 ? "" : String.valueOf(value), null);
        field.sizing(Sizing.fixed(width), Sizing.fixed(16));
        field.maxLength(9);
        field.filter(text -> text.matches("-?\\d*"));
        field.hint("∞");
        return field;
    }

    private static long parseLimit(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("-")) {
            return -1;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Label dimLabel(float width, Component text) {
        Label label = Label.of(width, text);
        label.color(EdenParchment.INK_DIM);
        return label;
    }

    /** Small yes/cancel dialog (purchases spend the guild's LC). */
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
}
