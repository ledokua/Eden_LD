package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads.GuildQuestsData;
import net.ledok.eden_ld.hub.guild.GuildPerks;
import net.ledok.eden_ld.hub.profile.LevelPerks;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Grid;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Guild tab's Level sub-tab: the level ring and XP toward the next level, where guild XP
 * comes from, and what levels give (now, next, and a track of every level that gives something).
 */
final class GuildLevelView {

    private static final float LANE = 6;
    /** A perk chip's narrowest width before the track drops a column. */
    private static final float CHIP_MIN = 200;
    /** The cards' width: the tab's width less the scroll lane (set as the view builds). */
    private static float inner = HubScreen.MIN_WIDTH - 24 - LANE;

    private GuildLevelView() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.guild.level." + key, args);
    }

    static void build(Flex content) {
        GuildQuestsData data = HubState.guildQuests;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
            content.item(center);
            return;
        }
        float width = HubScreen.innerWidth();
        inner = width - LANE;
        Flex body = Flex.column().gap(6);
        body.item(levelCard(data));
        body.item(perksCard(data));
        ScrollPanel panel = HubScroll.panel("guild/level", width, 100, body);
        panel.sizing(Sizing.fill(), Sizing.expand());
        content.item(panel);
    }

    private static Flex card() {
        Flex card = Flex.column().gap(4).padding(Insets.of(6, 8, 6, 8));
        card.sizing(Sizing.fixed(inner), Sizing.content());
        card.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        return card;
    }

    private static Flex levelCard(GuildQuestsData data) {
        Flex card = card();
        Flex line = Flex.row().gap(10).alignItems(Align.CENTER);
        boolean max = data.xpNeeded() <= 0;
        float progress = max ? 1f : (float) data.xpInto() / data.xpNeeded();
        line.item(new LevelRing(40, 4, data.level(), progress, EdenParchment.INK, EdenParchment.BAR_TRACK, true));
        Flex numbers = Flex.column().gap(3);
        numbers.item(EdenParchment.text(tr("title", data.level(), data.maxLevel()), EdenParchment.INK));
        float barWidth = inner - 16 - 40 - 10;
        numbers.item(EdenParchment.progressBar(barWidth, 12, progress,
                max ? tr("max") : tr("xp", grouped(data.xpInto()), grouped(data.xpNeeded())), max)
                .remember("eden_ld/guild/level"));
        numbers.item(EdenParchment.text(tr("sources"), EdenParchment.INK_DIM, (int) barWidth));
        line.item(numbers);
        card.item(line);
        return card;
    }

    private static Flex perksCard(GuildQuestsData data) {
        List<LevelPerks.Rule> rules = data.perks().stream()
                .map(rule -> new LevelPerks.Rule(rule.perk(), rule.from(), rule.every(), rule.amount(), rule.until()))
                .toList();
        int level = data.level();
        Flex card = card();
        card.item(EdenParchment.text(tr("perks"), EdenParchment.INK_MID));
        if (rules.isEmpty()) {
            card.item(EdenParchment.text(tr("perks.none_configured"), EdenParchment.INK_DIM, (int) (inner - 16)));
            return card;
        }
        Map<GuildPerks.Kind, Integer> totals = GuildPerks.totals(rules, level);
        card.item(EdenParchment.text(totals.isEmpty() ? tr("perks.none") : tr("perks.current", GuildPerks.describe(totals)),
                EdenParchment.INK, (int) (inner - 16)));
        int next = nextPerkLevel(rules, level, data.maxLevel());
        card.item(EdenParchment.text(next > 0 ? tr("perks.next", next, GuildPerks.describe(GuildPerks.at(rules, next)))
                : tr("perks.max"), EdenParchment.INK_DIM, (int) (inner - 16)));

        // Every level up to the top that gives something: two chips a line, more in a wide hub.
        int columns = Math.max(2, (int) ((inner - 16 + 4) / (CHIP_MIN + 4)));
        float chip = (inner - 16 - 4 * (columns - 1)) / columns;
        Grid grid = Grid.of(columns).gap(4, 3).cell(chip, 13);
        boolean any = false;
        for (int at = 2; at <= data.maxLevel(); at++) {
            Map<GuildPerks.Kind, Integer> gains = GuildPerks.at(rules, at);
            if (!gains.isEmpty()) {
                grid.item(chip(at, gains, at <= level, at == next, chip));
                any = true;
            }
        }
        if (any) {
            card.item(grid);
        }
        return card;
    }

    private static int nextPerkLevel(List<LevelPerks.Rule> rules, int level, int max) {
        for (int at = level + 1; at <= max; at++) {
            if (!GuildPerks.at(rules, at).isEmpty()) {
                return at;
            }
        }
        return -1;
    }

    private static Flex chip(int level, Map<GuildPerks.Kind, Integer> gains, boolean reached, boolean next, float width) {
        int border = reached ? EdenParchment.GOOD : next ? LevelRing.COLOR : EdenParchment.HAIRLINE;
        Flex chip = Flex.row().gap(4).padding(Insets.of(2, 4, 1, 4)).alignItems(Align.CENTER);
        chip.background(rect -> Shapes.roundRect(0, 0, rect.width(), rect.height(), 3)
                .fill(reached ? 0x3040C050 : 0x18000000).stroke(border, 1));
        Component text = (reached ? Component.literal("✔ ") : Component.empty()).copy()
                .append(tr("perks.level", level)).append(" · ").append(GuildPerks.describe(gains));
        Label label = Label.of(width - 8, text);
        label.color(reached ? EdenParchment.GOOD : next ? EdenParchment.INK : EdenParchment.INK_DIM);
        label.tooltipWhenCut(true);
        chip.item(label);
        return chip;
    }

    private static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
