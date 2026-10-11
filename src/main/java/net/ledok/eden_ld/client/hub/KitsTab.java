package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.KitItem;
import net.ledok.eden_ld.hub.HubPayloads.KitView;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Grid;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Kits tab ({@code docs/PLAN-kits.md}): one card per Kits_LD kit the player sees — icon, name, status (ready, the
 * wait counting down, claimed for good, or locked with the kit's own message), Preview and Claim. Preview shows the
 * kit shaped like the inventory it lands in. Claims are requests; the server answers with a toast and fresh data.
 */
final class KitsTab {

    /** ScrollPanel scrollbar lane (default bar 5 + 1). */
    private static final float LANE = 6;
    private static final float ICON = 20;
    private static final float CELL = 18;

    /** The cards' status lines, updated every tick while waits count down ({@code waiting}: when the card was built). */
    private record Status(KitView kit, Label label, boolean waiting) {
    }

    private static final List<Status> STATUSES = new ArrayList<>();

    private KitsTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.kits." + key, args);
    }

    static void build(HubScreen screen, Flex content) {
        STATUSES.clear();
        HubPayloads.KitsData data = HubState.kitsData;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
            content.item(center);
            return;
        }
        if (data.kits().isEmpty()) {
            content.item(HubScreen.emptyState(HubTab.KITS, tr("none_title"), tr("none")));
            return;
        }
        float width = HubScreen.innerWidth();
        float cardWidth = width - LANE;
        Flex list = Flex.column().gap(3);
        boolean claimMarked = false;
        for (int i = 0; i < data.kits().size(); i++) {
            KitView kit = data.kits().get(i);
            boolean markClaim = !claimMarked && claimable(kit);
            claimMarked |= markClaim;
            list.item(card(screen, kit, cardWidth, i == 0, markClaim));
        }
        ScrollPanel panel = HubScroll.panel("kits", width, 100, list);
        panel.sizing(Sizing.fill(), Sizing.expand());
        panel.id(HubSpotlights.KITS_LIST);
        content.item(panel);
    }

    /** Every client tick while open: the waits count down; one running out makes its card claimable. */
    static void tick(HubScreen screen) {
        boolean turnedReady = false;
        for (Status status : STATUSES) {
            if (status.waiting) {
                if (HubState.kitRemaining(status.kit) == 0) {
                    turnedReady = true;
                } else {
                    status.label.text(statusText(status.kit));
                }
            }
        }
        if (turnedReady) {
            // The card turns claimable, and the tab's badge counts it.
            screen.onKitsData();
        }
    }

    private static Flex card(HubScreen screen, KitView kit, float cardWidth, boolean first, boolean markClaim) {
        boolean ready = claimable(kit);
        Flex card = Flex.row().gap(6).padding(Insets.of(4, 6, 4, 6)).alignItems(Align.CENTER);
        card.sizing(Sizing.fixed(cardWidth), Sizing.content());
        card.background(EdenParchment.framed(ready ? EdenParchment.ROW_BG : EdenParchment.ROW_BG_ALT,
                EdenParchment.HAIRLINE));
        if (first) {
            card.id(HubSpotlights.KITS_FIRST);
        }
        card.item(ItemStackNode.of(kit.icon(), 0, 0, ICON).decorations(false).stackTooltip(false));

        Component previewLabel = tr("preview");
        Component claimLabel = tr("claim");
        float previewWidth = EdenParchment.fitWidth(previewLabel, 50);
        float claimWidth = EdenParchment.fitWidth(claimLabel, 50);
        float textWidth = cardWidth - 12 - ICON - previewWidth - claimWidth - 6 * 4;

        Flex text = Flex.column().gap(2);
        Label name = Label.of(textWidth, kit.name());
        name.color(ready ? EdenParchment.INK : EdenParchment.INK_MID);
        name.tooltipWhenCut(true);
        text.item(name);
        Label status = Label.of(textWidth, statusText(kit));
        status.color(statusColor(kit));
        status.tooltipWhenCut(true);
        text.item(status);
        STATUSES.add(new Status(kit, status, !kit.locked() && HubState.kitRemaining(kit) > 0));
        card.item(text);
        card.spacer();

        Button preview = EdenParchment.fitButton(previewLabel, 50, 14, () -> openPreview(screen, kit));
        if (first) {
            preview.id(HubSpotlights.KITS_PREVIEW);
        }
        card.item(preview);
        Button claim = EdenParchment.fitButton(claimLabel, 50, 14, () -> claim(kit));
        claim.enabled(ready);
        if (!ready) {
            // Live: the wait counts down here too.
            claim.tooltip(() -> List.of(statusText(kit)));
        }
        if (markClaim) {
            claim.id(HubSpotlights.KITS_CLAIM);
        }
        card.item(claim);
        return card;
    }

    /** Not locked and no wait left (counted down here: the data may say "wait" from before). */
    private static boolean claimable(KitView kit) {
        return !kit.locked() && HubState.kitRemaining(kit) == 0;
    }

    private static void claim(KitView kit) {
        ClientPlayNetworking.send(new HubPayloads.KitClaim(kit.id()));
    }

    // ---------------------------------------------------------------- status

    /** "Ready · every 1 d", "Ready in 2 h 5 min", "Claimed (one-time kit)", or why it's locked. */
    private static Component statusText(KitView kit) {
        if (kit.locked()) {
            return kit.lockMessage().isEmpty() ? tr("locked") : Component.literal(kit.lockMessage());
        }
        long remaining = HubState.kitRemaining(kit);
        if (remaining == -1L) {
            return tr("claimed_once");
        }
        if (remaining > 0) {
            return tr("ready_in", duration(remaining));
        }
        return kit.cooldownSeconds() < 0 ? tr("ready_once")
                : kit.cooldownSeconds() > 0 ? tr("ready_every", duration(kit.cooldownSeconds() * 1000L))
                : tr("ready");
    }

    private static int statusColor(KitView kit) {
        if (kit.locked()) {
            return EdenParchment.DANGER;
        }
        long remaining = HubState.kitRemaining(kit);
        return remaining == -1L ? EdenParchment.INK_DIM : remaining > 0 ? EdenParchment.WARN : EdenParchment.GOOD;
    }

    /** "2d 4h", "3h 15m", "1m 30s", "40s" — the two coarsest parts (a zero second part left out). */
    static Component duration(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        long days = seconds / 86400;
        long hours = seconds / 3600 % 24;
        long minutes = seconds / 60 % 60;
        long rest = seconds % 60;
        if (days > 0) {
            return hours > 0 ? tr("days_hours", days, hours) : tr("days", days);
        }
        if (hours > 0) {
            return minutes > 0 ? tr("hours_minutes", hours, minutes) : tr("hours", hours);
        }
        if (minutes > 0) {
            return rest > 0 ? tr("minutes_seconds", minutes, rest) : tr("minutes", minutes);
        }
        return tr("seconds", seconds);
    }

    // ---------------------------------------------------------------- preview

    /**
     * The kit as it lands: armor (head to feet) and the offhand on the left, the three inventory rows and the hotbar
     * beside them, trinkets underneath. Every item has its vanilla tooltip.
     */
    private static void openPreview(HubScreen screen, KitView kit) {
        Modal[] modal = new Modal[1];
        ItemStack[] main = new ItemStack[36];
        ItemStack[] armor = new ItemStack[4];
        ItemStack offhand = ItemStack.EMPTY;
        List<KitItem> trinkets = new ArrayList<>();
        for (KitItem item : kit.items()) {
            switch (item.area()) {
                case KitItem.MAIN -> {
                    if (item.slot() >= 0 && item.slot() < 36) {
                        main[item.slot()] = item.stack();
                    }
                }
                case KitItem.ARMOR -> {
                    if (item.slot() >= 0 && item.slot() < 4) {
                        armor[item.slot()] = item.stack();
                    }
                }
                case KitItem.OFFHAND -> offhand = item.stack();
                default -> trinkets.add(item);
            }
        }

        float gridWidth = 9 * CELL;
        float innerWidth = CELL + 8 + gridWidth;
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.content(), Sizing.content());
        dialog.background(EdenParchment.panel());
        dialog.id(HubSpotlights.KITS_PREVIEW_WINDOW);
        dialog.item(EdenParchment.text(kit.name(), EdenParchment.INK, (int) innerWidth));
        dialog.item(EdenParchment.text(kit.cooldownSeconds() < 0 ? tr("preview.once")
                : kit.cooldownSeconds() > 0 ? tr("preview.every", duration(kit.cooldownSeconds() * 1000L))
                : tr("preview.anytime"), EdenParchment.INK_MID, (int) innerWidth));

        Flex body = Flex.row().gap(8).alignItems(Align.START);
        Flex gear = Flex.column().gap(0);
        for (int slot = 3; slot >= 0; slot--) {
            gear.item(cell(armor[slot]));
        }
        gear.item(EdenParchment.spacer(4));
        gear.item(cell(offhand));
        body.item(gear);
        Flex inventory = Flex.column().gap(4);
        Grid rows = Grid.of(9).cell(CELL, CELL);
        for (int slot = 9; slot < 36; slot++) {
            rows.item(cell(main[slot]));
        }
        inventory.item(rows);
        Grid hotbar = Grid.of(9).cell(CELL, CELL);
        for (int slot = 0; slot < 9; slot++) {
            hotbar.item(cell(main[slot]));
        }
        inventory.item(hotbar);
        body.item(inventory);
        dialog.item(body);

        if (!trinkets.isEmpty()) {
            dialog.item(EdenParchment.text(tr("preview.trinkets"), EdenParchment.INK_MID));
            Grid grid = Grid.of(9).cell(CELL, CELL);
            for (KitItem trinket : trinkets) {
                CanvasNode node = cell(trinket.stack());
                grid.item(node);
            }
            dialog.item(grid);
        }
        if (kit.locked() || HubState.kitRemaining(kit) != 0) {
            dialog.item(EdenParchment.text(statusText(kit), statusColor(kit), (int) innerWidth));
        }

        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fixed(innerWidth), Sizing.content());
        if (claimable(kit)) {
            buttons.item(EdenParchment.fitButton(tr("claim"), 50, 14, () -> {
                claim(kit);
                modal[0].close();
            }));
        }
        buttons.item(EdenParchment.fitButton(Component.translatable("gui.eden_ld.hub.profile.close"), 50, 14,
                () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    /** A slot as in the inventory: its frame, and the stack (count, durability, tooltip) when there is one. */
    private static CanvasNode cell(ItemStack stack) {
        return new Cell(stack == null ? ItemStack.EMPTY : stack);
    }

    private static final class Cell extends CanvasGroup {
        Cell(ItemStack stack) {
            add(Shapes.rect(0.5f, 0.5f, CELL - 1, CELL - 1)
                    .fill(0x30000000 | (EdenParchment.INK & 0xFFFFFF)).stroke(EdenParchment.HAIRLINE, 1));
            if (!stack.isEmpty()) {
                add(ItemStackNode.of(stack, 1, 1));
            }
        }

        @Override
        public Rect bounds() {
            return new Rect(0, 0, CELL, CELL);
        }
    }
}
