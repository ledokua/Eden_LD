package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.theme.HubTheme;
import net.ledok.eden_ld.client.theme.HubThemes;
import net.ledok.eden_ld.client.theme.ThemePreview;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.settings.Option;
import net.ledok.vectorlib.client.canvas.settings.Settings;
import net.ledok.vectorlib.client.canvas.settings.SettingsView;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Settings tab: per-player preferences, stored and enforced server side. Declared as a VectorLib
 * {@link Settings} page — sections (Privacy, Chat, Notifications, Visual) as sub-tabs, each a list of
 * "label + description | control" rows under headings, a search over all of them, and a reset button on
 * any setting moved off its default. Every change goes to the server, whose echo rebuilds the tab (the
 * page remembers its section, search and scroll).
 */
final class SettingsTab {

    private SettingsTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.settings." + key, args);
    }

    private static void send(String key, int value) {
        ClientPlayNetworking.send(new HubPayloads.SettingChange(key, value));
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    static void build(HubScreen screen, Flex content) {
        HubPayloads.SettingsData data = HubState.settingsData;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"),
                    EdenParchment.INK_DIM));
            content.item(center);
            return;
        }

        Settings settings = new Settings();
        privacy(settings.section("privacy", tr("tab.privacy")), data);
        chat(settings.section("chat", tr("tab.chat")), data);
        notifications(settings.section("notifications", tr("tab.notifications")));
        visual(screen, settings.section("visual", tr("tab.visual")), data);

        SettingsView view = new SettingsView(settings, HubScreen.innerWidth())
                .tabs(bar -> {
                    EdenParchment.styleTabs(bar, 50);
                    bar.id(HubSpotlights.SETTINGS_SECTIONS);
                })
                .rowBackground(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE))
                .searchable(true)
                .remember("eden_ld/settings");
        view.sizing(Sizing.fill(), Sizing.expand());
        if (HubState.openSettingsSection != null) {
            // Asked for a section (the chat channel menu's "Chat settings").
            view.section(HubState.openSettingsSection);
            HubState.openSettingsSection = null;
        }
        content.item(view);
        shownView = new java.lang.ref.WeakReference<>(view);
    }

    /** The Settings page's view, for the tours' locator. */
    private static java.lang.ref.WeakReference<SettingsView> shownView = new java.lang.ref.WeakReference<>(null);

    /**
     * {@link HubSpotlights#SETTINGS_LIST}: the open section's options are a panel {@link SettingsView} builds inside
     * itself (no id of its own), the last thing in it — under the tabs, the search and the line. Client init.
     */
    static void registerLocator() {
        net.ledok.vectorlib.client.presentation.Spotlight.registerLocator((screen, id) -> {
            if (!id.equals(HubSpotlights.SETTINGS_LIST) || !(screen instanceof HubScreen hub)) {
                return null;
            }
            SettingsView view = shownView.get();
            if (view == null || view.canvas() != hub.canvas() || !view.isVisible() || view.children().isEmpty()) {
                return null;
            }
            var list = view.children().get(view.children().size() - 1);
            var bounds = list.bounds();
            if (bounds == null) {
                return null;
            }
            float[] from = list.localToRoot(bounds.x(), bounds.y());
            float[] to = list.localToRoot(bounds.right(), bounds.bottom());
            float[] a = hub.canvasToScreen(from[0], from[1]);
            float[] b = hub.canvasToScreen(to[0], to[1]);
            return new net.ledok.vectorlib.client.canvas.Rect(a[0], a[1], b[0] - a[0], b[1] - a[1]);
        });
    }

    // ---------------------------------------------------------------- sections

    private static void privacy(Settings settings, HubPayloads.SettingsData data) {
        settings.heading(tr("section.party"));
        settings.add(whoMay("invites_from", HubSettings.PARTY_INVITES_FROM, data.partyInvitesFrom(), data));
        settings.heading(tr("section.guild"));
        settings.add(guildTag(data));
        settings.heading(tr("section.profile"));
        settings.add(whoMay("profile_visible_to", HubSettings.PROFILE_VISIBLE_TO, data.profileVisibleTo(), data));
        ignored(settings);
    }

    /** Everyone the player ignores, each with Stop ignoring — the only way back to someone offline. */
    private static void ignored(Settings settings) {
        settings.heading(tr("section.ignored", HubState.ignored.size()));
        if (HubState.ignored.isEmpty()) {
            settings.add(Option.custom(tr("ignored.none"), width -> new net.ledok.vectorlib.client.canvas.CanvasGroup())
                    .hint(tr("ignored.none.hint")).keywords("ignore", "mute", "block"));
            return;
        }
        HubState.ignored.forEach((uuid, name) -> settings.add(
                Option.action(Component.literal(name), tr("ignored.stop"), () -> Ignoring.stop(uuid))
                        .keywords("ignore", "mute", "block")));
    }

    private static void chat(Settings settings, HubPayloads.SettingsData data) {
        if (HubState.chatFlagKnown(HubPayloads.ChatFlags.NEW_LOOK)) {
            // Only when the server sent the chat flags (Eden 1.3.0+): an older one can't keep the choice.
            settings.heading(tr("section.look"));
            settings.add(Option.toggle(tr("chat_new_look"), HubState.chatNewLook(),
                            on -> send(HubSettings.CHAT_NEW_LOOK, on ? 1 : 0))
                    .text(tr("channel.focus.toggle")).hint(tr("chat_new_look.hint")).defaultValue(true)
                    .keywords("chat", "look", "style", "classic", "vanilla", "old"));
            // The closed chat's lines: only the new look draws strips, and only a server that knows the switch keeps it.
            if (HubState.chatNewLook() && HubState.chatFlagKnown(HubPayloads.ChatFlags.HUD_STRIPS)) {
                settings.add(Option.choice(tr("chat_hud_style"), List.of(true, false), HubState.chatHudStrips(),
                                strips -> tr(strips ? "chat_hud_style.strips" : "chat_hud_style.classic"),
                                strips -> send(HubSettings.CHAT_HUD_STYLE, strips ? 1 : 0))
                        .hint(tr("chat_hud_style.hint")).defaultValue(true)
                        .keywords("chat", "hud", "lines", "background", "classic", "strips"));
            }
        }
        settings.heading(tr("section.messages"));
        settings.add(messages(HubSettings.CHAT_DEATHS, "deaths", data.chatDeaths()));
        settings.add(messages(HubSettings.CHAT_ADVANCEMENTS, "advancements", data.chatAdvancements()));
        settings.heading(tr("section.notifications"));
        settings.add(mentionVolume(data));
        settings.heading(tr("section.channels"));
        settings.add(Option.toggle(tr("channel.focus"), data.chatFocus(),
                        on -> send(HubSettings.CHAT_FOCUS, on ? 1 : 0))
                .text(tr("channel.focus.toggle")).hint(tr("channel.focus.hint")).defaultValue(false));
        // A channel shown or hidden in the game's chat (the player's own lines always show).
        String[] keys = {HubSettings.CHAT_HIDE_GLOBAL, HubSettings.CHAT_HIDE_LOCAL, HubSettings.CHAT_HIDE_PARTY,
                HubSettings.CHAT_HIDE_GUILD, HubSettings.CHAT_HIDE_DISCORD};
        String[] names = {"global", "local", "party", "guild", "discord"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            boolean hidden = (data.chatHidden() & (1 << i)) != 0;
            settings.add(Option.toggle(tr("channel." + names[i]), !hidden, on -> send(key, on ? 0 : 1))
                    .text(tr("channel.toggle")).hint(tr("channel." + names[i] + ".hint")).defaultValue(true));
        }
        // Per channel, as on its pip's right-click menu: notifications, and a mute while one runs.
        settings.heading(tr("section.channel_notify"));
        for (net.ledok.eden_ld.chat.ChatChannels.Channel channel : net.ledok.eden_ld.chat.ChatChannels.Channel.values()) {
            String name = lower(channel);
            int shift = channel.ordinal() * 2;
            settings.add(Option.choice(Component.translatable("chat.eden_ld.channel." + name),
                            List.of(HubSettings.ChannelNotify.ALL_MESSAGES, HubSettings.ChannelNotify.ONLY_MENTIONS,
                                    HubSettings.ChannelNotify.NOTHING),
                            HubSettings.ChannelNotify.of(data.chatNotify(), channel.ordinal()),
                            net.ledok.eden_ld.client.chat.ChannelMenu::notifyLabel,
                            level -> send(HubSettings.CHAT_NOTIFY,
                                    data.chatNotify() & ~(3 << shift) | level.ordinal() << shift))
                    // The explanation once, under the first channel.
                    .hint(channel.ordinal() == 0 ? tr("channel_notify.hint") : null)
                    .defaultValue(HubSettings.ChannelNotify.ONLY_MENTIONS)
                    .keywords("notifications", "mute", "ping", "mention"));
            Component muted = net.ledok.eden_ld.client.chat.ChannelMenu.mutedLine(channel);
            if (muted != null) {
                String key = HubSettings.CHAT_MUTE.get(channel.ordinal());
                settings.add(Option.action(tr("channel_muted", Component.translatable("chat.eden_ld.channel." + name)),
                                tr("unmute"), () -> send(key, 0))
                        .hint(muted).keywords("mute", "unmute"));
            }
        }
    }

    private static void notifications(Settings settings) {
        settings.heading(tr("section.notify_kinds"));
        for (HubNotifications.Kind kind : HubNotifications.Kind.values()) {
            String name = lower(kind);
            settings.add(Option.toggle(tr("notify." + name), HubNotifications.enabled(kind),
                            on -> HubNotifications.setEnabled(kind, on))
                    .text(tr("channel.focus.toggle")).hint(tr("notify." + name + ".hint")).defaultValue(true));
        }
    }

    private static void visual(HubScreen screen, Settings settings, HubPayloads.SettingsData data) {
        settings.heading(tr("section.look"));
        settings.add(language(data));
        settings.add(theme(screen, data));
        settings.add(Option.toggle(tr("theme_icons"), !data.themeIconsOff(),
                        on -> send(HubSettings.THEME_ICONS_OFF, on ? 0 : 1))
                .text(tr("theme_icons.toggle")).hint(tr("theme_icons.hint")).defaultValue(true));
        settings.heading(tr("section.notify_look"));
        settings.add(Option.choice(tr("notify_style"), List.of(HubSettings.NotifyStyle.values()),
                        HubSettings.NotifyStyle.of(data.notifyStyle()), style -> tr("notify_style." + lower(style)),
                        style -> send(HubSettings.NOTIFY_STYLE, style.ordinal()))
                .hint(tr("notify_style.hint")).defaultValue(HubSettings.NotifyStyle.BADGE));
        settings.add(notifyColor(data));
    }

    // ---------------------------------------------------------------- options

    /**
     * Who may send party invites / open the profile: anyone, friends, or no one. The "friends" choice needs
     * Minestar friendships, so it's left out where there are none.
     */
    private static Option<?, ?> whoMay(String name, String key, int value, HubPayloads.SettingsData data) {
        List<HubSettings.InvitesFrom> choices = new ArrayList<>();
        for (HubSettings.InvitesFrom choice : HubSettings.InvitesFrom.values()) {
            if (choice != HubSettings.InvitesFrom.FRIENDS || data.friendsAvailable()) {
                choices.add(choice);
            }
        }
        HubSettings.InvitesFrom current = HubSettings.InvitesFrom.of(value);
        if (!choices.contains(current)) {
            current = HubSettings.InvitesFrom.ANYONE;
        }
        return Option.choice(tr(name), choices, current, who -> tr(name + "." + lower(who)), who -> send(key, who.ordinal()))
                .hint(tr(name + ".hint")).defaultValue(HubSettings.InvitesFrom.ANYONE);
    }

    /** Opt-in: the player's guild tag in chat, the tab list, and above their head (only in a guild). */
    private static Option<?, ?> guildTag(HubPayloads.SettingsData data) {
        boolean inGuild = HubState.guildData != null ? HubState.guildData.inGuild() : data.inGuild();
        var option = Option.toggle(tr("guild_tag"), data.showGuildTag(), on -> send(HubSettings.SHOW_GUILD_TAG, on ? 1 : 0))
                .text(tr("guild_tag.toggle")).hint(tr("guild_tag.hint")).defaultValue(false);
        return inGuild ? option : option.disabled(tr("guild_tag.no_guild"));
    }

    /** Whose deaths / advancements show in the player's chat: everyone, party & guild, or nobody. */
    private static Option<?, ?> messages(String key, String name, int value) {
        return Option.choice(tr(name), List.of(HubSettings.MessagesFrom.values()), HubSettings.MessagesFrom.of(value),
                        from -> tr("messages_from." + lower(from)), from -> send(key, from.ordinal()))
                .hint(tr(name + ".hint")).defaultValue(HubSettings.MessagesFrom.EVERYONE);
    }

    /** Mention ping volume, 0-100% (0 = off); letting go saves it and plays a test ping. */
    private static Option<?, ?> mentionVolume(HubPayloads.SettingsData data) {
        return Option.slider(tr("mention_sound"), 0, 100, 5, data.mentionVolume(), value -> {
                    int volume = (int) Math.round(value);
                    send(HubSettings.MENTION_VOLUME, volume);
                    net.ledok.eden_ld.client.chat.MentionSound.play(volume);
                })
                .formatter(v -> (int) Math.round(v) == 0 ? tr("mention_sound.off") : Component.literal((int) Math.round(v) + "%"))
                .hint(tr("mention_sound.hint")).defaultValue((double) HubSettings.DEFAULT_MENTION_VOLUME);
    }

    /** The hub's own language: the game's, English or Ukrainian (names in their own language). */
    private static Option<?, ?> language(HubPayloads.SettingsData data) {
        return Option.choice(tr("hub_language"), List.of(0, 1, 2), Math.max(0, Math.min(2, data.hubLanguage())),
                        choice -> switch (choice) {
                            case 1 -> Component.literal("English");
                            case 2 -> Component.literal("Українська");
                            default -> tr("hub_language.game");
                        },
                        choice -> send(HubSettings.HUB_LANGUAGE, choice))
                .hint(tr("hub_language.hint")).defaultValue(0);
    }

    /** The badge / glow color: the theme's own, or one of the fixed ones (each with a swatch). */
    private static Option<?, ?> notifyColor(HubPayloads.SettingsData data) {
        List<Integer> choices = new ArrayList<>();
        for (int i = 0; i <= HubNotifications.COLORS.length; i++) {
            choices.add(i);
        }
        int current = data.notifyColor() >= 0 && data.notifyColor() <= HubNotifications.COLORS.length ? data.notifyColor() : 0;
        return Option.choice(tr("notify_color"), choices, current, SettingsTab::colorLabel,
                        choice -> send(HubSettings.NOTIFY_COLOR, choice))
                .hint(tr("notify_color.hint")).defaultValue(0);
    }

    private static Component colorLabel(int choice) {
        int rgb = (choice == 0 ? EdenParchment.NOTIFY : HubNotifications.COLORS[choice - 1]) & 0xFFFFFF;
        Component name = tr(choice == 0 ? "notify_color.theme" : "notify_color." + HubNotifications.COLOR_NAMES[choice - 1]);
        // Only the swatch takes the color (an appended name would inherit it).
        return Component.empty().append(Component.literal("■ ").withStyle(style -> style.withColor(rgb))).append(name);
    }

    /**
     * The player's hub themes: the ones the server lets them use (open, their groups', unlocked or
     * bought) that this client also has (a resource pack may be missing one), then the locked ones
     * the server shows (🔒, with the price when for sale). Picking a locked one offers to buy it.
     */
    private static Option<?, ?> theme(HubScreen screen, HubPayloads.SettingsData data) {
        List<HubTheme> choices = new ArrayList<>();
        for (String id : data.themes()) {
            HubTheme theme = theme(id);
            if (theme != null) {
                choices.add(theme);
            }
        }
        java.util.Map<HubTheme, Long> offers = new java.util.LinkedHashMap<>();
        for (HubPayloads.LookOffer offer : data.themeOffers()) {
            HubTheme theme = theme(offer.id());
            if (theme != null && !choices.contains(theme)) {
                offers.put(theme, offer.price());
                choices.add(theme);
            }
        }
        HubTheme current = HubThemes.get(HubThemes.selected());
        if (current == null || !choices.contains(current) || offers.containsKey(current)) {
            current = choices.isEmpty() ? HubTheme.PARCHMENT : choices.get(0);
        }
        java.util.function.Function<HubTheme, Component> label = theme -> {
            Long price = offers.get(theme);
            if (price == null) {
                return theme.name();
            }
            net.minecraft.network.chat.MutableComponent text = Component.literal("🔒 ").append(theme.name());
            return price > 0 ? text.append(" · " + price + " " + net.ledok.eden_ld.client.HubGlyphs.LC_TEXT) : text;
        };
        // Wide enough for the longest entry ("🔒 Name · 500 LC" included), never narrower than the others.
        float width = 120;
        for (HubTheme theme : choices) {
            width = Math.max(width, net.minecraft.client.Minecraft.getInstance().font.width(label.apply(theme)) + 22);
        }
        var option = Option.choice(tr("theme"), choices, current, label, theme -> {
            Long price = offers.get(theme);
            if (price == null) {
                ClientPlayNetworking.send(new HubPayloads.ThemeSelect(theme.id().toString()));
                return;
            }
            // A locked theme isn't picked: the dropdown goes back, and it's offered for sale.
            screen.onSettingsData();
            if (price > 0) {
                confirmThemeBuy(screen, theme, price);
            } else {
                net.ledok.vectorlib.client.canvas.widget.Toast.text(screen.canvas(), tr("theme.locked"),
                        EdenParchment.DANGER);
            }
        }).hint(offers.isEmpty() ? tr("theme.hint") : tr("theme.hint_shop")).controlWidth(width);
        return choices.contains(HubTheme.PARCHMENT) ? option.defaultValue(HubTheme.PARCHMENT) : option;
    }

    @org.jetbrains.annotations.Nullable
    private static HubTheme theme(String id) {
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.tryParse(id);
        return location == null ? null : HubThemes.get(location);
    }

    /** "Spend N on the NAME theme?" with a preview of it and the player's balance; Buy asks the server. */
    private static void confirmThemeBuy(HubScreen screen, HubTheme theme, long price) {
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(300), Sizing.content());
        dialog.background(EdenParchment.panel());
        dialog.item(EdenParchment.text(tr("theme.buy_title"), EdenParchment.INK));
        Flex framed = Flex.column();
        framed.sizing(Sizing.fixed(280), Sizing.fixed(110));
        framed.item(ThemePreview.node(theme, 280, 110));
        dialog.item(framed);
        dialog.item(EdenParchment.text(tr("theme.buy_question", price, theme.name()), EdenParchment.INK, 280));
        HubPayloads.HomeData home = HubState.homeData;
        boolean known = home != null && home.hasEconomy();
        boolean affordable = !known || home.balance() >= price;
        if (known) {
            dialog.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.profile.background.buy_balance",
                    home.balance()), affordable ? EdenParchment.INK_DIM : EdenParchment.DANGER, 280));
        }
        net.ledok.vectorlib.client.canvas.widget.Modal[] modal = new net.ledok.vectorlib.client.canvas.widget.Modal[1];
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        net.ledok.vectorlib.client.canvas.widget.Button buy = EdenParchment.fitButton(tr("theme.buy"), 60, 14, () -> {
            ClientPlayNetworking.send(new HubPayloads.LookBuy(true, theme.id().toString()));
            ClientPlayNetworking.send(HubPayloads.HomeDataRequest.INSTANCE);
            modal[0].close();
        });
        buy.enabled(affordable);
        buttons.item(buy);
        buttons.item(EdenParchment.fitButton(Component.translatable("gui.eden_ld.hub.profile.close"), 60, 14,
                () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = net.ledok.vectorlib.client.canvas.widget.Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }
}
