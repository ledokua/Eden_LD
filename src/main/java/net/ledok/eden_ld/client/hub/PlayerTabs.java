package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.profile.ProfileBackgrounds;
import net.ledok.eden_ld.client.theme.HubTheme;
import net.ledok.eden_ld.client.theme.HubThemes;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.moderation.AdminTools;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.NumberField;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.ledok.eden_ld.client.hub.ModerationScreen.BAD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.GOOD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.LINE;
import static net.ledok.eden_ld.client.hub.ModerationScreen.PANEL;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.small;
import static net.ledok.eden_ld.client.hub.ModerationScreen.text;

/**
 * The player page's admin tabs besides Punish: <b>Unlocks</b> (themes and profile backgrounds),
 * <b>Dailies</b> (today's quests, extra rerolls, reset), <b>Dungeons</b> (unlock, lock, record a
 * run) and <b>Emojis</b> (bonus favorite-emoji and sticker slots). Built from {@code AdminTools}'
 * data; every button sends an action and the server answers with a notice and fresh data.
 */
final class PlayerTabs {

    private static final int WARN = 0xFFE0C850;

    private PlayerTabs() {
    }

    static void action(String area, UUID target, String action, CompoundTag args) {
        ClientPlayNetworking.send(new HubPayloads.AdminAction(area, target, action, args));
    }

    static void request(String area, UUID target) {
        ClientPlayNetworking.send(new HubPayloads.AdminRequest(area, target));
    }

    static Component json(String json) {
        try {
            Component parsed = Component.Serializer.fromJson(json, Minecraft.getInstance().level == null
                    ? net.minecraft.core.RegistryAccess.EMPTY : Minecraft.getInstance().level.registryAccess());
            return parsed == null ? Component.empty() : parsed;
        } catch (RuntimeException e) {
            return Component.literal(json);
        }
    }

    static List<String> strings(CompoundTag tag, String key) {
        List<String> values = new ArrayList<>();
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            values.add(list.getString(i));
        }
        return values;
    }

    static ItemStack icon(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return new ItemStack(location == null ? Items.PAPER : BuiltInRegistries.ITEM.getOptional(location).orElse(Items.PAPER));
    }

    /** A dark row, {@code w} wide, with the given content laid left to right. */
    static Flex row(float w) {
        Flex row = Flex.row().gap(5).alignItems(Align.CENTER).padding(Insets.of(2, 4, 2, 4));
        row.sizing(Sizing.fixed(w), Sizing.content());
        row.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(0xFF22252A).stroke(Stroke.of(LINE, 1)));
        return row;
    }

    static Flex spacer() {
        Flex spacer = Flex.row();
        spacer.sizing(Sizing.expand(), Sizing.fixed(1));
        return spacer;
    }

    static void confirm(ModerationScreen screen, Component question, String buttonKey, Runnable onYes) {
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(230), Sizing.content());
        dialog.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL).stroke(Stroke.of(LINE, 1)));
        dialog.item(text(question, TEXT, 210));
        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        buttons.item(small(buttonKey, 60, () -> {
            onYes.run();
            modal[0].close();
        }));
        buttons.item(small("gui.eden_ld.moderation.cancel", 50, () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    // ---------------------------------------------------------------- unlocks

    static void unlocks(Flex box, CompoundTag data, UUID target, float w) {
        List<String> themeOpen = strings(data, "theme_open");
        List<String> themeMine = strings(data, "theme_unlocked");
        List<String> bgOpen = strings(data, "background_open");
        List<String> bgMine = strings(data, "background_unlocked");
        if (data.getBoolean("themes")) {
            box.item(text(Component.translatable("gui.eden_ld.moderation.admin.themes"), TEXT));
            for (HubTheme theme : HubThemes.all()) {
                String id = theme.id().toString();
                box.item(unlockRow(w, null, theme.name(), id, themeOpen.contains(id), themeMine.contains(id),
                        on -> {
                            CompoundTag args = new CompoundTag();
                            args.putString("id", id);
                            args.putBoolean("on", on);
                            action(AdminTools.UNLOCKS, target, "theme", args);
                        }));
            }
            box.item(ModerationScreen.gap(4));
        }
        if (data.getBoolean("backgrounds")) {
            box.item(text(Component.translatable("gui.eden_ld.moderation.admin.backgrounds"), TEXT));
            for (ProfileBackgrounds.Background background : ProfileBackgrounds.all()) {
                String id = background.id().toString();
                box.item(unlockRow(w, id, background.name(), id, bgOpen.contains(id), bgMine.contains(id), on -> {
                    CompoundTag args = new CompoundTag();
                    args.putString("id", id);
                    args.putBoolean("on", on);
                    action(AdminTools.UNLOCKS, target, "background", args);
                }));
            }
        }
    }

    /**
     * One theme or background: open (free, by rule or group — nothing to do here), unlocked for
     * this player (Lock takes it back), or locked (Unlock gives it).
     */
    private static Flex unlockRow(float w, String picture, Component name, String id, boolean open, boolean mine,
                                  java.util.function.Consumer<Boolean> toggle) {
        Flex row = row(w);
        if (picture != null) {
            CanvasGroup thumb = new CanvasGroup();
            thumb.add(ProfileBackgrounds.thumbnail(picture, 36, 18, 0));
            Flex holder = Flex.column();
            holder.sizing(Sizing.fixed(36), Sizing.fixed(18));
            holder.item(thumb);
            row.item(holder);
        }
        row.item(TextNode.of(name).color(TEXT).shadow(false).maxWidth((int) (w * 0.4f)).ellipsis(true));
        row.item(spacer());
        boolean freeOrRule = open && !mine;
        Component state = Component.translatable(freeOrRule ? "gui.eden_ld.moderation.admin.state_open"
                : mine ? "gui.eden_ld.moderation.admin.state_unlocked" : "gui.eden_ld.moderation.admin.state_locked");
        row.item(text(state, freeOrRule ? DIM : mine ? GOOD : BAD));
        if (!freeOrRule) {
            Button button = small(mine ? "gui.eden_ld.moderation.admin.lock" : "gui.eden_ld.moderation.admin.unlock",
                    54, () -> toggle.accept(!mine));
            row.item(button);
        }
        row.tooltip(Component.literal(id));
        return row;
    }

    // ---------------------------------------------------------------- dailies

    static void dailies(ModerationScreen screen, Flex box, CompoundTag data, UUID target, float w, String name) {
        long hours = data.getLong("reset_in") / 3_600_000L;
        long minutes = data.getLong("reset_in") / 60_000L % 60;
        Component rerolls = data.getInt("free_left") >= 0
                ? Component.translatable("gui.eden_ld.moderation.admin.rerolls_online", data.getInt("free_left"),
                data.getInt("paid_used"), data.getInt("extra"))
                : Component.translatable("gui.eden_ld.moderation.admin.rerolls_offline", data.getInt("free_used"),
                data.getInt("paid_used"), data.getInt("extra"));
        box.item(text(rerolls, DIM, (int) w));
        box.item(text(Component.translatable("gui.eden_ld.moderation.admin.dailies_reset_in", hours, minutes), DIM));
        ListTag slots = data.getList("slots", Tag.TAG_COMPOUND);
        if (slots.isEmpty()) {
            box.item(text(Component.translatable("gui.eden_ld.moderation.none"), DIM));
        }
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag slot = slots.getCompound(i);
            Flex row = row(w);
            row.item(ItemStackNode.of(icon(slot.getString("icon")), 0, 0, 16).decorations(false).stackTooltip(false));
            row.item(TextNode.of(json(slot.getString("title"))).color(TEXT).shadow(false).maxWidth((int) (w - 110))
                    .ellipsis(true));
            row.item(spacer());
            boolean claimed = slot.getBoolean("claimed");
            row.item(text(claimed ? Component.translatable("gui.eden_ld.moderation.admin.claimed")
                    : Component.literal(slot.getInt("progress") + " / " + slot.getInt("count")),
                    claimed ? GOOD : slot.getInt("progress") >= slot.getInt("count") ? WARN : DIM));
            box.item(row);
        }
        box.item(text(Component.translatable(data.getBoolean("bonus_claimed")
                ? "gui.eden_ld.moderation.admin.bonus_claimed" : "gui.eden_ld.moderation.admin.bonus_open"), DIM));

        Flex actions = Flex.row().gap(4).alignItems(Align.CENTER);
        actions.item(text(Component.translatable("gui.eden_ld.moderation.admin.give_rerolls_label"), TEXT));
        NumberField amount = new NumberField(36, 1, null);
        amount.range(1, 100);
        amount.size(36, 14);
        actions.item(amount);
        actions.item(small("gui.eden_ld.moderation.admin.give", 40, () -> {
            CompoundTag args = new CompoundTag();
            args.putInt("amount", (int) amount.value());
            action(AdminTools.DAILIES, target, "rerolls", args);
        }));
        box.item(actions);
        // Its own line: the rerolls row is already as wide as the tab in Ukrainian.
        box.item(small("gui.eden_ld.moderation.admin.reset_dailies", 60, () -> confirm(screen,
                Component.translatable("gui.eden_ld.moderation.admin.reset_confirm", name),
                "gui.eden_ld.moderation.admin.reset_dailies",
                () -> action(AdminTools.DAILIES, target, "reset", new CompoundTag()))));
    }

    // ---------------------------------------------------------------- dungeons

    static void dungeons(ModerationScreen screen, Flex box, CompoundTag data, UUID target, float w, String name) {
        // The player's activity level (Pufferfish Skills needs them online to read it).
        int level = data.getInt("level");
        box.item(text(!data.getBoolean("gated") ? Component.translatable("gui.eden_ld.moderation.admin.activities_not_gated")
                : level < 0 ? Component.translatable("gui.eden_ld.moderation.admin.activities_offline")
                : Component.translatable("gui.eden_ld.moderation.admin.activities_level", level), DIM, (int) w));
        ListTag list = data.getList("dungeons", Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            box.item(text(Component.translatable("gui.eden_ld.moderation.admin.no_dungeons"), DIM, (int) w));
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag activity = list.getCompound(i);
            String id = activity.getString("id");
            Component title = json(activity.getString("name"));
            ListTag difficulties = activity.getList("difficulties", Tag.TAG_COMPOUND);
            Flex row = row(w);
            Flex lines = Flex.column().gap(1);
            lines.item(TextNode.of(Component.translatable("gui.eden_ld.hub.activities.type." + activity.getInt("type"))
                            .append(" · ").append(title))
                    .color(TEXT).shadow(false).maxWidth((int) (w - 112)).ellipsis(true));
            // One line each (the unlock level, then every difficulty), so nothing is cut by the buttons.
            java.util.List<Component> stats = new java.util.ArrayList<>();
            net.minecraft.network.chat.MutableComponent opens = Component.translatable(
                    "gui.eden_ld.moderation.admin.activity_unlock", activity.getInt("unlock_level"));
            if (difficulties.isEmpty()) {
                opens.append(" · ").append(Component.translatable("gui.eden_ld.moderation.admin.dungeon_runs",
                        activity.getInt("clears")));
            }
            if (activity.getInt("wave") > 0) {
                opens.append(" · ").append(Component.translatable("gui.eden_ld.moderation.admin.activity_wave",
                        activity.getInt("wave")));
            }
            stats.add(opens);
            for (int d = 0; d < difficulties.size(); d++) {
                CompoundTag difficulty = difficulties.getCompound(d);
                net.minecraft.network.chat.MutableComponent line = Component.translatable(
                        "gui.eden_ld.moderation.admin.activity_difficulty",
                        difficulty.getString("id"), difficulty.getInt("level"), difficulty.getInt("clears"));
                long best = difficulty.getLong("best");
                if (best > 0) {
                    line.append(" (" + String.format("%d:%02d", best / 60_000, best / 1000 % 60) + ")");
                }
                stats.add(line);
            }
            for (Component line : stats) {
                lines.item(TextNode.of(line).color(DIM).shadow(false).maxWidth((int) (w - 112)).ellipsis(true));
            }
            row.item(lines);
            row.item(spacer());
            String first = difficulties.isEmpty() ? "" : difficulties.getCompound(0).getString("id");
            Button complete = small("gui.eden_ld.moderation.admin.complete", 48, () -> confirm(screen,
                    Component.translatable("gui.eden_ld.moderation.admin.complete_confirm", title, name),
                    "gui.eden_ld.moderation.admin.complete", () -> {
                        CompoundTag args = new CompoundTag();
                        args.putString("id", id);
                        args.putString("difficulty", first);
                        action(AdminTools.DUNGEONS, target, "complete", args);
                    }));
            complete.tooltip(Component.translatable("gui.eden_ld.moderation.admin.complete_hint"));
            row.item(complete);
            row.item(small("gui.eden_ld.moderation.admin.activity_reset", 48, () -> confirm(screen,
                    Component.translatable("gui.eden_ld.moderation.admin.activity_reset_confirm", title, name),
                    "gui.eden_ld.moderation.admin.activity_reset", () -> {
                        CompoundTag args = new CompoundTag();
                        args.putString("id", id);
                        action(AdminTools.DUNGEONS, target, "reset", args);
                    })));
            java.util.List<Component> tip = new java.util.ArrayList<>(stats);
            tip.add(0, Component.literal(id));
            row.tooltip(tip.toArray(Component[]::new));
            box.item(row);
        }
    }

    // ---------------------------------------------------------------- emojis

    /** Kind picked in the Emojis tab's add form ("emojis" or "stickers"). */
    private static String emojiKind = "emojis";

    static void emojis(ModerationScreen screen, Flex box, CompoundTag data, UUID target, float w) {
        if (data.getInt("emoji_limit") >= 0) {
            box.item(text(Component.translatable("gui.eden_ld.moderation.admin.emoji_limits", data.getInt("emoji_limit"),
                    data.getInt("sticker_limit")), DIM, (int) w));
        } else {
            box.item(text(Component.translatable("gui.eden_ld.moderation.admin.emoji_limits_offline"), DIM, (int) w));
        }
        box.item(text(Component.translatable("gui.eden_ld.moderation.admin.bonuses"), TEXT));
        ListTag grants = data.getList("grants", Tag.TAG_COMPOUND);
        if (grants.isEmpty()) {
            box.item(text(Component.translatable("gui.eden_ld.moderation.none"), DIM));
        }
        for (int i = 0; i < grants.size(); i++) {
            CompoundTag grant = grants.getCompound(i);
            Flex row = row(w);
            int amount = grant.getInt("amount");
            row.item(text(Component.translatable("gui.eden_ld.moderation.admin.kind_" + grant.getString("kind")), TEXT));
            row.item(text(Component.literal((amount > 0 ? "+" : "") + amount), amount > 0 ? GOOD : BAD));
            row.item(spacer());
            long expires = grant.getLong("expires");
            row.item(text(expires == 0 ? Component.translatable("gui.eden_ld.moderation.admin.permanent")
                    : Component.translatable("gui.eden_ld.moderation.admin.ends_in",
                    PunishSection.length(Math.max(0, (expires - System.currentTimeMillis()) / 1000))), DIM));
            box.item(row);
        }

        Flex add = Flex.row().gap(4).alignItems(Align.CENTER);
        add.sizing(Sizing.fill(), Sizing.content());
        for (String kind : new String[]{"emojis", "stickers"}) {
            Button button = small("gui.eden_ld.moderation.admin.kind_" + kind, 44, () -> {
                emojiKind = kind;
                screen.rebuildNow();
            });
            if (kind.equals(emojiKind)) {
                button.style(ModerationScreen.SELECTED);
            }
            add.item(button);
        }
        NumberField amount = new NumberField(40, 1, null);
        amount.range(-1000, 1000);
        amount.size(40, 14);
        amount.tooltip(Component.translatable("gui.eden_ld.moderation.admin.amount_hint"));
        add.item(amount);
        TextField duration = new TextField(50, "", value -> {
        });
        duration.size(50, 14);
        duration.hint("30d");
        duration.maxLength(12);
        duration.tooltip(Component.translatable("gui.eden_ld.moderation.admin.duration_hint"));
        add.item(duration);
        add.item(small("gui.eden_ld.moderation.admin.give", 40, () -> {
            CompoundTag args = new CompoundTag();
            args.putString("kind", emojiKind);
            args.putInt("amount", (int) amount.value());
            args.putString("duration", duration.text());
            action(AdminTools.EMOJIS, target, "add", args);
        }));
        box.item(add);
        Flex clear = Flex.row().gap(4).alignItems(Align.CENTER);
        for (String kind : new String[]{"emojis", "stickers"}) {
            clear.item(small("gui.eden_ld.moderation.admin.clear_" + kind, 60, () -> {
                CompoundTag args = new CompoundTag();
                args.put("kind", StringTag.valueOf(kind));
                action(AdminTools.EMOJIS, target, "clear", args);
            }));
        }
        box.item(clear);
    }
}
