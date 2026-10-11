package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.GuildQuestEntry;
import net.ledok.eden_ld.hub.HubPayloads.GuildQuestsData;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Grid;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The Guild tab's Quests sub-tab: the daily and the weekly board, each with its reset countdown,
 * as a two-column grid of cards (icon, name, shared progress, rewards, the viewer's share). Quests
 * complete on their own; the server pushes the board while members make progress.
 */
final class GuildQuestsView {

    private static final float LANE = 6;
    private static final float GAP = 4;
    /** A card's narrowest width before a row drops a column (two at the smallest hub). */
    private static final float CARD_MIN = 200;
    /** Set as the view builds, from the tab's width. */
    private static float width = HubScreen.MIN_WIDTH - 24;
    private static int columns = 2;
    private static float cardWidth = (width - LANE - GAP) / 2;
    private static final float ICON = 18;
    private static final float REWARD_ICON = 12;
    /** Title, bar (12, tall enough for its "12 / 40"), rewards line, padding. */
    private static final float CARD_HEIGHT = 50;

    private static Label dailyReset;
    private static Label weeklyReset;
    private static boolean refreshRequested;

    private GuildQuestsView() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.guild.quests." + key, args);
    }

    static void build(Flex content) {
        GuildQuestsData data = HubState.guildQuests;
        dailyReset = null;
        weeklyReset = null;
        refreshRequested = false;
        if (data == null || data.quests().isEmpty()) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(data == null
                    ? Component.translatable("gui.eden_ld.hub.home.loading") : tr("none"), EdenParchment.INK_DIM));
            content.item(center);
            return;
        }
        width = HubScreen.innerWidth();
        columns = Math.max(2, (int) ((width - LANE + GAP) / (CARD_MIN + GAP)));
        cardWidth = (width - LANE - GAP * (columns - 1)) / columns;
        Flex list = Flex.column().gap(4);
        dailyReset = section(list, data, 0, tr("daily"));
        weeklyReset = section(list, data, 1, tr("weekly"));
        ScrollPanel panel = HubScroll.panel("guild/quests", width, 100, list);
        panel.id(HubSpotlights.GUILD_QUESTS);
        panel.sizing(Sizing.fill(), Sizing.expand());
        content.item(panel);
        updateResets();
    }

    /** A period's heading ("Daily guild quests · 2 / 5 done … resets in 5 h") and its cards. */
    private static Label section(Flex list, GuildQuestsData data, int period, Component title) {
        List<GuildQuestEntry> quests = data.quests().stream().filter(q -> q.period() == period).toList();
        if (quests.isEmpty()) {
            return null;
        }
        long done = quests.stream().filter(GuildQuestEntry::done).count();
        Flex heading = Flex.row().gap(6).alignItems(Align.CENTER);
        heading.sizing(Sizing.fixed(width - LANE), Sizing.content());
        heading.item(EdenParchment.text(title, EdenParchment.INK_MID));
        heading.item(EdenParchment.text(tr("done_count", done, quests.size()), EdenParchment.INK_DIM));
        heading.spacer();
        var font = Minecraft.getInstance().font;
        float width = Math.max(font.width(tr("resets_days", 6, 23)), font.width(tr("resets_hours", 23, 59))) + 2;
        Label reset = Label.of(width, Component.empty());
        reset.color(EdenParchment.INK_DIM);
        reset.align(TextNode.Align.RIGHT);
        heading.item(reset);
        list.item(heading);
        Grid grid = Grid.of(columns).gap(GAP, GAP).cell(cardWidth, CARD_HEIGHT);
        for (GuildQuestEntry quest : quests) {
            grid.item(card(quest));
        }
        list.item(grid);
        return reset;
    }

    private static Flex card(GuildQuestEntry quest) {
        Flex card = Flex.row().gap(5).padding(Insets.of(4, 5, 4, 5)).alignItems(Align.CENTER);
        card.sizing(Sizing.fixed(cardWidth), Sizing.fixed(CARD_HEIGHT));
        card.background(EdenParchment.framed(quest.done() ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG,
                EdenParchment.HAIRLINE));
        ItemStack icon = new ItemStack(BuiltInRegistries.ITEM.getOptional(quest.icon()).orElse(Items.PAPER));
        card.item(ItemStackNode.of(icon, 0, 0, ICON).decorations(false).stackTooltip(false));

        float textWidth = cardWidth - 10 - ICON - 5;
        Flex text = Flex.column().gap(2);
        Label name = Label.of(textWidth, quest.title());
        name.color(quest.done() ? EdenParchment.INK_DIM : EdenParchment.INK);
        name.tooltipWhenCut(true);
        if (!quest.objective().getString().isEmpty()) {
            name.tooltip(quest.title(), quest.objective());
        }
        text.item(name);
        var bar = EdenParchment.progressBar(textWidth, 12,
                quest.goal() <= 0 ? 1 : (float) quest.progress() / quest.goal(),
                quest.done() ? tr("done") : tr("progress", quest.progress(), quest.goal()), quest.done())
                .remember("eden_ld/guild-quest/" + quest.period() + "/" + quest.title().getString());
        bar.pickable(true);
        bar.tooltip(contribution(quest));
        text.item(bar);
        text.item(rewards(quest, textWidth));
        card.item(text);
        return card;
    }

    /** "You: 12 · Most: Name (40)". */
    private static Component contribution(GuildQuestEntry quest) {
        MutableComponent line = tr("yours", quest.mine()).copy();
        if (quest.topAmount() > 0) {
            line.append(" · ").append(tr("top", quest.topName(), quest.topAmount()));
        }
        return line;
    }

    /** "+100 guild XP · 1000 LC" then the mailed item icons. */
    private static Flex rewards(GuildQuestEntry quest, float width) {
        Flex row = Flex.row().gap(3).alignItems(Align.CENTER);
        row.sizing(Sizing.fixed(width), Sizing.fixed(REWARD_ICON));
        MutableComponent line = Component.empty();
        if (quest.guildXp() > 0) {
            line.append(tr("reward_xp", quest.guildXp()));
        }
        if (quest.lc() > 0) {
            if (!line.getSiblings().isEmpty()) {
                line.append(" · ");
            }
            line.append(tr("reward_lc", quest.lc()));
        }
        float iconsWidth = quest.items().size() * (REWARD_ICON + 3);
        if (!line.getSiblings().isEmpty()) {
            float textWidth = Math.min(Minecraft.getInstance().font.width(line), Math.max(20, width - iconsWidth));
            Label label = Label.of(textWidth, line);
            label.color(EdenParchment.INK_DIM);
            label.pickable(true);
            label.tooltip(rewardTooltip(quest));
            row.item(label);
        }
        for (ItemStack stack : quest.items()) {
            row.item(ItemStackNode.of(stack, 0, 0, REWARD_ICON));
        }
        if (!quest.items().isEmpty()) {
            Label mailed = Label.of(10, Component.literal("✉"));
            mailed.color(EdenParchment.INK_DIM);
            mailed.pickable(true);
            mailed.tooltip(tr("items_mailed"));
            row.item(mailed);
        }
        return row;
    }

    private static Component[] rewardTooltip(GuildQuestEntry quest) {
        java.util.List<Component> lines = new java.util.ArrayList<>();
        if (quest.guildXp() > 0) {
            lines.add(tr("reward_xp_hint", quest.guildXp()));
        }
        if (quest.lc() > 0) {
            lines.add(tr("reward_lc_hint", quest.lc()));
        }
        if (!quest.items().isEmpty()) {
            lines.add(tr("items_mailed"));
        }
        return lines.toArray(Component[]::new);
    }

    /** Every client tick on the sub-tab: the countdowns; at zero, ask for the new board. */
    static void tick() {
        GuildQuestsData data = HubState.guildQuests;
        if (data == null || dailyReset == null && weeklyReset == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - HubState.guildQuestsReceivedAt;
        if (!refreshRequested && (data.dailyResetMillis() - elapsed <= 0 || data.weeklyResetMillis() - elapsed <= 0)) {
            refreshRequested = true;
            ClientPlayNetworking.send(HubPayloads.GuildQuestsRequest.INSTANCE);
        }
        updateResets();
    }

    private static void updateResets() {
        GuildQuestsData data = HubState.guildQuests;
        if (data == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - HubState.guildQuestsReceivedAt;
        if (dailyReset != null) {
            dailyReset.text(countdown(data.dailyResetMillis() - elapsed));
        }
        if (weeklyReset != null) {
            weeklyReset.text(countdown(data.weeklyResetMillis() - elapsed));
        }
    }

    private static Component countdown(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long days = seconds / 86_400;
        long hours = seconds / 3600 % 24;
        long minutes = seconds / 60 % 60;
        if (days > 0) {
            return tr("resets_days", days, hours);
        }
        return hours > 0 ? tr("resets_hours", hours, minutes) : tr("resets_minutes", Math.max(1, (seconds + 59) / 60));
    }
}
