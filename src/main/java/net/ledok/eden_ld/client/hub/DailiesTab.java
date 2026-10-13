package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.DailiesData;
import net.ledok.eden_ld.hub.HubPayloads.DailyAction;
import net.ledok.eden_ld.hub.HubPayloads.DailyEntry;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Dailies tab: reset countdown and free rerolls in the header, one card per quest
 * (icon, name, progress bar, "n / m · rewards", claim at 100%, reroll), and the
 * all-quests bonus row pinned at the bottom. Every action is a payload; the server
 * answers with fresh {@link DailiesData}.
 */
final class DailiesTab {

    /** The claimed check icon's size: its 16 × 16 art at 1 pixel per unit. */
    private static final float CHECK = 16;

    /** ScrollPanel scrollbar lane (default bar 5 + 1). */
    private static final float LANE = 6;
    /** The tab's width and a quest card's (less the scroll lane), set as the tab builds. */
    private static float width = HubScreen.MIN_WIDTH - 24;
    private static float cardWidth = width - LANE;
    /** Tall enough for the "12 / 20" label inside the bar. */
    private static final float BAR_HEIGHT = 12;
    private static final float ICON = 20;
    private static final float REWARD_ICON = 16;

    private static Label resetLabel;
    /** The first ↻ of a build has the tours' id. */
    private static boolean rerollNamed;
    private static boolean refreshRequested;

    private DailiesTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.dailies." + key, args);
    }

    private static void send(DailyAction.Action action, int slot) {
        ClientPlayNetworking.send(DailyAction.of(action, slot));
    }

    static void build(HubScreen screen, Flex content) {
        DailiesData data = HubState.dailiesData;
        width = HubScreen.innerWidth();
        cardWidth = width - LANE;
        resetLabel = null;
        rerollNamed = false;
        refreshRequested = false;
        if (data == null || data.quests().isEmpty()) {
            if (data == null) {
                Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
                center.sizing(Sizing.fill(), Sizing.expand());
                center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
                content.item(center);
                return;
            }
            Flex center = HubScreen.emptyState(HubTab.DAILIES, tr("none_title"), tr("none"));
            content.item(center);
            return;
        }

        Flex header = Flex.row().gap(8).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        header.spacer();
        header.item(EdenParchment.text(tr("free_rerolls", data.freeRerollsLeft()), EdenParchment.INK_DIM));
        // As wide as the longest countdown ("… 23 h 59 min"), so it never cuts and never jumps per tick.
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        float resetWidth = Math.max(font.width(tr("resets_hours", 23, 59)), font.width(tr("resets_minutes", 59))) + 2;
        resetLabel = Label.of(resetWidth, Component.empty());
        resetLabel.color(EdenParchment.INK_DIM);
        resetLabel.align(net.ledok.vectorlib.client.canvas.TextNode.Align.RIGHT);
        resetLabel.id(HubSpotlights.DAILIES_RESET);
        header.item(resetLabel);
        content.item(header);
        updateReset();

        // Sized so the default four quests fill the list without a scrollbar.
        Flex list = Flex.column().gap(3);
        for (DailyEntry quest : data.quests()) {
            list.item(card(quest, data));
        }
        ScrollPanel panel = HubScroll.panel("dailies", width, 100, list);
        panel.id(HubSpotlights.DAILIES_LIST);
        panel.sizing(Sizing.fill(), Sizing.expand());
        content.item(panel);

        Flex bonus = bonusRow(data);
        bonus.id(HubSpotlights.DAILIES_BONUS);
        content.item(bonus);
    }

    /** Every client tick while open: the reset countdown; at zero, ask for the new day. */
    static void tick() {
        DailiesData data = HubState.dailiesData;
        if (data == null || resetLabel == null) {
            return;
        }
        if (millisLeft(data) <= 0 && !refreshRequested) {
            refreshRequested = true;
            ClientPlayNetworking.send(HubPayloads.DailiesDataRequest.INSTANCE);
        }
        updateReset();
    }

    private static long millisLeft(DailiesData data) {
        return data.resetMillis() - (System.currentTimeMillis() - HubState.dailiesReceivedAt);
    }

    private static void updateReset() {
        DailiesData data = HubState.dailiesData;
        if (resetLabel == null || data == null) {
            return;
        }
        long seconds = Math.max(0, millisLeft(data) / 1000);
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        resetLabel.text(hours > 0
                ? tr("resets_hours", hours, minutes)
                : tr("resets_minutes", Math.max(1, (seconds + 59) / 60)));
    }

    private static Flex card(DailyEntry quest, DailiesData data) {
        boolean complete = quest.progress() >= quest.count();
        // 38 units tall: four cards fill the list exactly (rewards on the title line, 16 px icons).
        Flex card = Flex.row().gap(6).padding(Insets.of(4, 6, 4, 6)).alignItems(Align.CENTER);
        card.sizing(Sizing.fixed(cardWidth), Sizing.content());
        card.background(EdenParchment.framed(quest.claimed() ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG,
                EdenParchment.HAIRLINE));

        ItemStack icon = new ItemStack(BuiltInRegistries.ITEM.getOptional(quest.icon()).orElse(Items.PAPER));
        card.item(ItemStackNode.of(icon, 0, 0, ICON).decorations(false).stackTooltip(false));

        // One action slot: ↻ reroll while in progress, Claim once complete, ✔ after claiming.
        Component claimLabel = tr("claim");
        // Wide enough for either state: "Claim" and "Claimed" differ in length per language.
        float actionWidth = Math.max(EdenParchment.fitWidth(claimLabel, 50), EdenParchment.fitWidth(tr("claimed"), 50));
        float textWidth = cardWidth - 12 - ICON - actionWidth - 6 * 3;

        Flex text = Flex.column().gap(2);
        // Line 1: the title, the whole width (long translations). Line 2: the bar (count inside),
        // rewards at its right end.
        Label name = Label.of(textWidth, quest.title().getString().isEmpty() ? tr("removed") : quest.title());
        name.color(quest.claimed() ? EdenParchment.INK_DIM : EdenParchment.INK);
        name.tooltipWhenCut(true);
        text.item(name);
        if (!quest.objective().getString().isEmpty()) {
            Label objective = Label.of(textWidth, quest.objective());
            objective.color(EdenParchment.INK_MID);
            objective.tooltipWhenCut(true);
            text.item(objective);
        }
        // Guild XP is part of the rewards for players in a guild (0 otherwise, not shown).
        boolean hasRewards = !quest.rewards().isEmpty() || quest.guildXp() > 0;
        // The bar can give up room: it only needs its "123 / 456"; the rewards get the rest.
        float barMin = Math.max(40, net.minecraft.client.Minecraft.getInstance().font
                .width(tr("progress", quest.count(), quest.count())) + 10);
        float rewardsMax = Math.max(textWidth * 0.62f, textWidth - 6 - barMin);
        float rewardsWidth = hasRewards ? rewardsWidth(quest.rewards(), quest.guildXp(), rewardsMax) : 0;
        Flex bottom = Flex.row().gap(6).alignItems(Align.CENTER);
        bottom.sizing(Sizing.fixed(textWidth), Sizing.content());
        bottom.item(EdenParchment.progressBar(textWidth - (hasRewards ? rewardsWidth + 6 : 0), BAR_HEIGHT,
                quest.count() == 0 ? 1 : (float) quest.progress() / quest.count(),
                tr("progress", quest.progress(), quest.count()), quest.claimed())
                .remember("eden_ld/daily/" + quest.slot() + "/" + quest.title().getString()));
        if (hasRewards) {
            bottom.item(rewardsRow(quest.rewards(), quest.guildXp(), rewardsMax));
        }
        text.item(bottom);
        card.item(text);
        card.spacer();

        if (quest.claimed()) {
            card.item(claimedMark(actionWidth));
        } else if (complete) {
            card.item(new Button(actionWidth, 14, claimLabel, () -> send(DailyAction.Action.CLAIM, quest.slot())));
        } else {
            card.item(rerollButton(quest, data, actionWidth));
        }
        return card;
    }

    /** Right-aligned ↻ in the action slot; greyed out with the reason when not affordable. */
    private static Flex rerollButton(DailyEntry quest, DailiesData data, float slotWidth) {
        Button reroll = new Button(18, 14, Component.literal("↻"),
                () -> send(DailyAction.Action.REROLL, quest.slot()));
        boolean free = data.freeRerollsLeft() > 0 || data.nextRerollCost() <= 0;
        boolean affordable = free || data.economy() && data.balance() >= data.nextRerollCost();
        reroll.enabled(affordable);
        if (!rerollNamed) {
            reroll.id(HubSpotlights.DAILIES_REROLL);
            rerollNamed = true;
        }
        reroll.tooltip(free ? tr("reroll_free", data.freeRerollsLeft())
                : !data.economy() ? tr("reroll_none")
                : affordable ? tr("reroll_cost", data.nextRerollCost())
                : tr("reroll_poor", data.nextRerollCost(), Math.max(0, data.balance())));
        Flex slot = Flex.row().justify(Justify.END);
        slot.sizing(Sizing.fixed(slotWidth), Sizing.content());
        slot.item(reroll);
        return slot;
    }

    /**
     * Reward item icons (count on the icon, vanilla tooltip on hover — in the player's own
     * language), then "50 XP · 25 LC · note" as text.
     */
    private static Flex rewardsRow(HubPayloads.RewardView rewards, int guildXp, float width) {
        Flex row = Flex.row().gap(3).alignItems(Align.CENTER);
        // Say what the icons are: "Rewards: [icons] 50 XP".
        Component label = tr("rewards");
        row.item(EdenParchment.text(label, EdenParchment.INK_DIM));
        float used = net.minecraft.client.Minecraft.getInstance().font.width(label) + 3;
        for (ItemStack stack : rewards.items()) {
            row.item(ItemStackNode.of(stack, 0, 0, REWARD_ICON));
            used += REWARD_ICON + 3;
        }
        Component line = rewardText(rewards, guildXp);
        if (!line.getSiblings().isEmpty()) {
            float textWidth = Math.min(net.minecraft.client.Minecraft.getInstance().font.width(line),
                    Math.max(20, width - used));
            Label text = Label.of(textWidth, line);
            text.color(EdenParchment.INK_DIM);
            text.tooltipWhenCut(true);
            row.item(text);
        }
        return row;
    }

    /** Width {@link #rewardsRow} takes: icons plus the (capped) text. */
    private static float rewardsWidth(HubPayloads.RewardView rewards, int guildXp, float width) {
        float used = net.minecraft.client.Minecraft.getInstance().font.width(tr("rewards")) + 3
                + rewards.items().size() * (REWARD_ICON + 3);
        Component line = rewardText(rewards, guildXp);
        if (!line.getSiblings().isEmpty()) {
            used += Math.min(net.minecraft.client.Minecraft.getInstance().font.width(line), Math.max(20, width - used));
        } else if (used > 0) {
            used -= 3;
        }
        return used;
    }

    private static Component rewardText(HubPayloads.RewardView rewards, int guildXp) {
        net.minecraft.network.chat.MutableComponent line = Component.empty();
        if (rewards.xp() > 0) {
            line.append(tr("reward_xp", rewards.xp()));
        }
        if (guildXp > 0) {
            if (!line.getSiblings().isEmpty()) {
                line.append(" · ");
            }
            line.append(tr("reward_guild_xp", guildXp));
        }
        if (rewards.lc() > 0) {
            if (!line.getSiblings().isEmpty()) {
                line.append(" · ");
            }
            line.append(tr("reward_lc", rewards.lc()));
        }
        if (!rewards.note().getString().isEmpty()) {
            if (!line.getSiblings().isEmpty()) {
                line.append(" · ");
            }
            line.append(rewards.note());
        }
        return line;
    }

    private static Flex bonusRow(DailiesData data) {
        int total = data.quests().size();
        int claimed = (int) data.quests().stream().filter(DailyEntry::claimed).count();
        boolean ready = claimed == total && !data.bonusClaimed();

        Flex row = Flex.row().gap(8).padding(Insets.of(3, 6, 3, 6)).alignItems(Align.CENTER);
        row.sizing(Sizing.fill(), Sizing.content());
        row.background(EdenParchment.framed(ready ? EdenParchment.ROW_BG : EdenParchment.ROW_BG_ALT,
                EdenParchment.HAIRLINE));

        Component claimLabel = tr("claim");
        // Wide enough for either state: "Claim" and "Claimed" differ in length per language.
        float actionWidth = Math.max(EdenParchment.fitWidth(claimLabel, 50), EdenParchment.fitWidth(tr("claimed"), 50));
        // One line — title left, rewards right — so the quest list keeps its height. The title
        // takes what the padding (6 + 6), the rewards, the button and the 8-unit gaps leave.
        float inner = width - 12;
        float rewardsMax = (inner - actionWidth) / 2;
        float rewardsWidth = data.bonusRewards().isEmpty() ? 0 : rewardsWidth(data.bonusRewards(), 0, rewardsMax) + 8;
        row.item(Label.of(inner - actionWidth - 8 - rewardsWidth, tr("bonus", claimed, total))
                .color(EdenParchment.INK).tooltipWhenCut(true));
        if (!data.bonusRewards().isEmpty()) {
            row.item(rewardsRow(data.bonusRewards(), 0, rewardsMax));
        }
        if (data.bonusClaimed()) {
            row.item(claimedMark(actionWidth));
        } else {
            Button claim = new Button(actionWidth, 14, claimLabel, () -> send(DailyAction.Action.CLAIM_BONUS, 0));
            claim.enabled(ready);
            if (!ready) {
                claim.tooltip(tr("bonus_locked"));
            }
            row.item(claim);
        }
        return row;
    }

    /** "Claimed": the check icon centered where the Claim button was ("Claimed ✔" until the icon exists). */
    private static net.ledok.vectorlib.client.canvas.CanvasNode claimedMark(float width) {
        HubIcons.Icon check = HubIcons.get("check");
        if (check == null) {
            return Label.of(width, tr("claimed")).color(EdenParchment.GOOD);
        }
        Flex mark = Flex.row().justify(net.ledok.vectorlib.client.canvas.layout.Justify.CENTER).alignItems(Align.CENTER);
        mark.sizing(Sizing.fixed(width), Sizing.fixed(CHECK));
        Flex icon = Flex.row();
        icon.sizing(Sizing.fixed(CHECK), Sizing.fixed(CHECK));
        icon.item(check.node(CHECK));
        mark.item(icon);
        mark.pickable(true);
        mark.tooltip(tr("claimed"));
        return mark;
    }
}
