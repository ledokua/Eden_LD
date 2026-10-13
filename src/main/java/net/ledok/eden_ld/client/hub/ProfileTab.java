package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.profile.ProfileBackgrounds;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.profile.LevelPerks;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.Interactive;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.ShapeNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Grid;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The Profile tab: a player's page on their chosen background, laid out like the Minestar site's
 * profile — avatar with the activity level, name, prime and group, member since; total playtime
 * (all Minestar servers and this one); the level ring with XP to the next level; earned rewards;
 * and on the player's own page what the level gives (level rewards) plus the background picker.
 * Other players' pages open from Profile buttons (Friends, party and guild) when their privacy
 * setting allows it.
 */
final class ProfileTab {

    private static final int PRIME = 0xFFC084FC;
    /**
     * The page's colors: light over a picked background, the hub theme's own without one
     * (set at the start of each {@link #build}).
     */
    private static int TEXT;
    private static int TEXT_DIM;
    private static int TEXT_FAINT;
    private static int REACHED;
    private static int TRACK;
    private static boolean SHADOW;
    private static boolean pictured;
    /** Page content width: the tab's width − page padding 16 − scroll lane 6 (set as the tab builds). */
    private static float inner = HubScreen.MIN_WIDTH - 24 - 16 - 6;
    /** A level-track chip's narrowest width before the track drops a column. */
    private static final float CHIP_MIN = 200;
    private static final float REWARD = 22;

    private ProfileTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.profile." + key, args);
    }

    static void build(HubScreen screen, Flex content) {
        HubPayloads.ProfileData data = HubState.profileData;
        inner = HubScreen.innerWidth() - 16 - 6;
        UUID subject = HubState.profileSubject();
        boolean ready = data != null && subject != null && data.uuid().equals(subject);
        String background = ready && data.access() == HubPayloads.ProfileData.SHOWN
                ? data.background() : HubSettings.DEFAULT_BACKGROUND;
        usePalette(background);

        Flex page = Flex.column().gap(6).padding(Insets.of(8, 8, 8, 8));
        page.sizing(Sizing.fill(), Sizing.expand());
        if (pictured) {
            page.background(picture(background, Surface.PAGE));
        }
        content.item(page);

        if (!ready) {
            message(page, tr("loading"));
            return;
        }
        if (data.access() == HubPayloads.ProfileData.PRIVATE) {
            message(page, tr("private", data.name()));
            return;
        }
        if (data.access() == HubPayloads.ProfileData.UNKNOWN) {
            message(page, tr("unknown"));
            return;
        }

        page.item(header(screen, data));
        Flex body = Flex.column().gap(6);
        body.sizing(Sizing.fixed(inner), Sizing.content());
        body.item(statsRow(data));
        body.item(rewardsCard(data));
        if (data.self() && data.platform() && !data.perks().isEmpty()) {
            body.item(perksCard(data));
        }
        ScrollPanel scroll = HubScroll.panel("profile/" + data.uuid(), inner + 6, 100, body);
        scroll.sizing(Sizing.fill(), Sizing.expand());
        page.item(scroll);
    }

    private static void usePalette(String background) {
        pictured = ProfileBackgrounds.shown(background);
        ProfileBackgrounds.Palette palette = ProfileBackgrounds.palette(background);
        TEXT = pictured ? palette.text() : EdenParchment.INK;
        TEXT_DIM = pictured ? palette.textDim() : EdenParchment.INK_MID;
        TEXT_FAINT = pictured ? palette.textFaint() : EdenParchment.INK_DIM;
        REACHED = pictured ? palette.good() : EdenParchment.GOOD;
        TRACK = pictured ? (TEXT & 0xFFFFFF) | 0x40000000 : EdenParchment.BAR_TRACK;
        SHADOW = pictured ? palette.shadow() : EdenParchment.TEXT_SHADOW;
        lightText = light(TEXT);
    }

    /** Light text sits on dark cards over the picture; dark text (a light picture) on light ones. */
    private static boolean lightText;

    static boolean light(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return r * 299 + g * 587 + b * 114 > 128_000;
    }

    /** Where a background is drawn: each has its own image in the background's definition. */
    enum Surface { PAGE, CARD, HOME }

    /** The background as a panel's background, darkened as its definition says, framed. */
    static Function<Rect, CanvasNode> picture(String background, Surface surface) {
        return rect -> {
            CanvasGroup group = new CanvasGroup();
            group.add(switch (surface) {
                case PAGE -> ProfileBackgrounds.page(background, rect.width(), rect.height());
                case CARD -> ProfileBackgrounds.card(background, rect.width(), rect.height());
                case HOME -> ProfileBackgrounds.homeCard(background, rect.width(), rect.height());
            });
            group.add(Shapes.rect(0, 0, rect.width(), rect.height()).stroke(EdenParchment.HAIRLINE, 1));
            return group;
        };
    }

    /** A centered notice (loading, private, unknown); the Profile bookmark leads back to your own page. */
    private static void message(Flex page, Component text) {
        Flex center = Flex.column().gap(6).justify(Justify.CENTER).alignItems(Align.CENTER);
        center.sizing(Sizing.fill(), Sizing.expand());
        center.item(TextNode.of(text).color(TEXT).shadow(SHADOW).maxWidth(300));
        page.item(center);
    }

    // ---------------------------------------------------------------- header

    private static Flex header(HubScreen screen, HubPayloads.ProfileData data) {
        Flex header = Flex.row().gap(8).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        header.item(avatar(data));

        Flex names = Flex.column().gap(3);
        if (pictured) {
            // A soft plate under the name block: a picture can be bright exactly where the light text
            // sits (or dark under dark text), and the name, badges and "member since" must read on any.
            names.padding(Insets.of(3, 5, 3, 5));
            int plate = lightText ? 0x70000000 : 0xA0FFFFFF;
            names.background(rect -> Shapes.roundRect(0, 0, rect.width(), rect.height(), 4).fill(plate));
        }
        Flex nameLine = Flex.row().gap(5).alignItems(Align.CENTER);
        // The plain name: Prime, the group and the guild are badges right here, so the chat prefix
        // ("[Prime] name") would only repeat them.
        nameLine.item(TextNode.of(Component.literal(data.name())).color(TEXT).shadow(SHADOW).scale(1.5f));
        if (data.prime()) {
            nameLine.item(pill(tr("prime"), PRIME));
        }
        names.item(nameLine);
        Flex tags = Flex.row().gap(4).alignItems(Align.CENTER);
        boolean anyTag = false;
        if (!data.group().isEmpty()) {
            tags.item(pill(Component.literal(data.group()), data.groupColor() >= 0 ? 0xFF000000 | data.groupColor() : TEXT_DIM));
            anyTag = true;
        }
        if (!data.guildTag().isEmpty()) {
            tags.item(pill(Component.literal("[" + data.guildTag() + "] " + data.guildName()),
                    0xFF000000 | data.guildColor()));
            anyTag = true;
        }
        if (anyTag) {
            names.item(tags);
        }
        if (data.registeredAt() > 0) {
            String date = HubDates.full(data.registeredAt());
            names.item(TextNode.of(tr("member_since", date)).color(TEXT_DIM).shadow(SHADOW));
        }
        // No buttons here: backgrounds are picked in the Wardrobe, and the Profile bookmark always
        // leads back to your own page.
        header.item(names);
        return header;
    }

    /** The head in a frame, with the level on a badge under it (like the site's "LVL 12"). */
    private static CanvasNode avatar(HubPayloads.ProfileData data) {
        float box = 36;
        float badge = data.platform() ? 6 : 0;
        CanvasGroup avatar = new CanvasGroup() {
            @Override
            public Rect bounds() {
                return new Rect(0, 0, box, box + badge);
            }
        };
        int frame = data.prime() ? PRIME : LevelRing.COLOR;
        avatar.add(Shapes.roundRect(0, 0, box, box, 3).fill(0x90000000).stroke(frame, 1.5f));
        CanvasNode face = PlayerFace.of(data.uuid(), data.name(), 32);
        face.at(2, 2);
        avatar.add(face);
        if (data.platform()) {
            Component text = tr("lvl_badge", data.level());
            float width = net.minecraft.client.Minecraft.getInstance().font.width(text) * 0.75f + 8;
            avatar.add(Shapes.roundRect((box - width) / 2, box - 5, width, 10, 5).fill(0xFF1B1530).stroke(frame, 1));
            TextNode label = TextNode.of(text).color(0xFFFFFFFF).scale(0.75f).align(TextNode.Align.CENTER);
            label.at(box / 2, box - 2.5f);
            avatar.add(label);
        }
        return avatar;
    }

    private static Flex pill(Component text, int color) {
        Flex pill = Flex.row().padding(Insets.of(2, 4, 1, 4)).alignItems(Align.CENTER);
        pill.sizing(Sizing.content(), Sizing.content());
        int fill = !pictured || lightText ? 0x70000000 : 0x90FFFFFF;
        pill.background(rect -> Shapes.roundRect(0, 0, rect.width(), rect.height(), 4).fill(fill).stroke(color, 1));
        pill.item(TextNode.of(text).color(color));
        return pill;
    }

    // ---------------------------------------------------------------- cards

    private static Flex card() {
        Flex card = Flex.column().gap(4).padding(Insets.of(6, 8, 6, 8));
        card.background(!pictured ? EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE)
                : lightText ? rect -> Shapes.roundRect(0, 0, rect.width(), rect.height(), 3).fill(0xC0151222).stroke(0x50FFFFFF, 1)
                : rect -> Shapes.roundRect(0, 0, rect.width(), rect.height(), 3).fill(0xB0FFFFFF).stroke(0x50000000, 1));
        return card;
    }

    private static TextNode caption(Component text) {
        return TextNode.of(text.copy().withStyle(style -> style.withBold(true))).color(TEXT_FAINT).scale(0.75f);
    }

    private static Flex statsRow(HubPayloads.ProfileData data) {
        Flex row = Flex.row().gap(6).alignItems(Align.STRETCH);
        row.sizing(Sizing.fill(), Sizing.content());

        Flex time = card();
        time.id(HubSpotlights.PROFILE_PLAYTIME);
        time.sizing(Sizing.expand(), Sizing.content());
        time.item(caption(tr("total_time")));
        Flex big = Flex.row().gap(4).alignItems(Align.END);
        long serverHours = data.serverPlaytimeTicks() < 0 ? -1 : data.serverPlaytimeTicks() / 72000L;
        long hours = data.platform() ? data.playtimeSeconds() / 3600L : Math.max(0, serverHours);
        big.item(TextNode.of(Component.literal(grouped(hours))).color(TEXT).shadow(SHADOW).scale(2f));
        big.item(TextNode.of(tr("hours_unit")).color(TEXT_DIM));
        time.item(big);
        // Own lines, wrapped: the Ukrainian wording is long for a half-width card.
        int wrap = (int) (inner / 2 - 3 - 16);
        time.item(TextNode.of(data.platform() ? tr("hours_all") : tr("hours_here")).color(TEXT_DIM).maxWidth(wrap));
        if (data.platform() && serverHours >= 0) {
            time.item(TextNode.of(tr("this_server", grouped(serverHours))).color(TEXT_DIM).maxWidth(wrap));
        }
        row.item(time);

        Flex level = card();
        level.id(HubSpotlights.PROFILE_LEVEL);
        level.sizing(Sizing.expand(), Sizing.content());
        level.item(caption(tr("activity")));
        if (!data.platform()) {
            level.item(TextNode.of(tr("no_platform")).color(TEXT_FAINT).maxWidth(170));
        } else {
            Flex line = Flex.row().gap(8).alignItems(Align.CENTER);
            float progress = data.levelXpNeeded() > 0 ? (float) data.levelXp() / data.levelXpNeeded() : 0f;
            line.item(new LevelRing(40, 4, data.level(), progress, TEXT, TRACK, true));
            Flex numbers = Flex.column().gap(3);
            numbers.item(TextNode.of(tr("xp", grouped(data.levelXp()), grouped(data.levelXpNeeded())))
                    .color(TEXT).shadow(SHADOW));
            numbers.item(TextNode.of(tr("to_next", data.level() + 1, grouped(data.xpToNextLevel()))).color(TEXT_DIM));
            line.item(numbers);
            level.item(line);
        }
        row.item(level);
        return row;
    }

    private static Flex rewardsCard(HubPayloads.ProfileData data) {
        Flex card = card();
        card.id(HubSpotlights.PROFILE_REWARDS);
        card.sizing(Sizing.fill(), Sizing.content());
        card.item(caption(tr("rewards", data.rewards().size())));
        if (!data.platform()) {
            card.item(TextNode.of(tr("no_platform")).color(TEXT_FAINT));
        } else if (data.rewards().isEmpty()) {
            card.item(TextNode.of(tr("no_rewards")).color(TEXT_FAINT));
        } else {
            int columns = (int) ((inner - 16 + 3) / (REWARD + 3));
            Grid grid = Grid.of(columns).gap(3).cell(REWARD, REWARD);
            for (HubPayloads.ProfileReward reward : data.rewards()) {
                grid.item(new RewardIcon(reward, REWARD));
            }
            card.item(grid);
        }
        return card;
    }

    /** What the level gives now, what's next, and a track of the perk levels around the player's. */
    private static Flex perksCard(HubPayloads.ProfileData data) {
        List<LevelPerks.Rule> rules = data.perks().stream()
                .map(rule -> new LevelPerks.Rule(rule.perk(), rule.from(), rule.every(), rule.amount(), rule.until()))
                .toList();
        int level = data.level();
        Flex card = card();
        card.id(HubSpotlights.PROFILE_TRACK);
        card.sizing(Sizing.fill(), Sizing.content());
        card.item(caption(tr("perks")));
        Map<LevelPerks.Kind, Integer> totals = LevelPerks.totals(rules, level);
        card.item(TextNode.of(totals.isEmpty() ? tr("perks.none") : tr("perks.current", LevelPerks.describe(totals)))
                .color(TEXT).shadow(SHADOW).maxWidth((int) (inner - 16)));
        int next = LevelPerks.nextPerkLevel(rules, level);
        card.item(TextNode.of(next > 0 ? tr("perks.next", next, describeAt(rules, next)) : tr("perks.max"))
                .color(TEXT_DIM).maxWidth((int) (inner - 16)));

        // The last two perk levels reached, then the next six.
        List<Integer> reached = new ArrayList<>();
        for (int at = level; at >= 1 && reached.size() < 2; at--) {
            if (!LevelPerks.at(rules, at).isEmpty()) {
                reached.add(0, at);
            }
        }
        List<Integer> track = new ArrayList<>(reached);
        for (int at = next; at > 0 && track.size() < reached.size() + 6; at = LevelPerks.nextPerkLevel(rules, at)) {
            track.add(at);
        }
        if (!track.isEmpty()) {
            int chipColumns = Math.max(2, (int) ((inner - 16 + 4) / (CHIP_MIN + 4)));
            float chip = (inner - 16 - 4 * (chipColumns - 1)) / chipColumns;
            Grid grid = Grid.of(chipColumns).gap(4, 3).cell(chip, 13);
            for (int at : track) {
                grid.item(trackChip(rules, at, at <= level, at == next, chip));
            }
            card.item(grid);
        }
        return card;
    }

    private static Component describeAt(List<LevelPerks.Rule> rules, int level) {
        Map<LevelPerks.Kind, Integer> gains = new java.util.EnumMap<>(LevelPerks.Kind.class);
        LevelPerks.at(rules, level).forEach(gain -> gains.merge(gain.kind(), gain.amount(), Integer::sum));
        return LevelPerks.describe(gains);
    }

    private static Flex trackChip(List<LevelPerks.Rule> rules, int level, boolean reached, boolean next, float width) {
        int border = reached ? REACHED : next ? LevelRing.COLOR : pictured ? (TEXT & 0xFFFFFF) | 0x50000000 : EdenParchment.HAIRLINE;
        Flex chip = Flex.row().gap(4).padding(Insets.of(2, 4, 1, 4)).alignItems(Align.CENTER);
        chip.background(rect -> Shapes.roundRect(0, 0, rect.width(), rect.height(), 3)
                .fill(reached ? 0x3040C050 : 0x30000000).stroke(border, 1));
        Component text = (reached ? Component.literal("✔ ") : Component.empty()).copy()
                .append(tr("perks.level", level)).append(" · ").append(describeAt(rules, level));
        Label label = Label.of(width - 8, text);
        label.color(reached ? REACHED : next ? TEXT : TEXT_DIM);
        label.tooltipWhenCut(true);
        chip.item(label);
        return chip;
    }

    /** "1 234 567" — spaces between thousands, like the Minestar site. */
    private static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
