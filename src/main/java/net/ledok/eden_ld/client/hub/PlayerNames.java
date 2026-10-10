package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.chat.LegacyText;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player names as the hub shows them: LuckPerms prefix + name + suffix, for players online
 * on this server (the server syncs {@link HubPayloads.NameStyles}). Prefixes use the usual
 * legacy codes — {@code &c}, {@code §l}, {@code &#ff8800} — parsed into real styles.
 */
public final class PlayerNames {

    private record Decoration(Component prefix, Component suffix, Component guildTag, int nameColor) {
    }

    private static final Map<UUID, Decoration> DECORATIONS = new ConcurrentHashMap<>();

    private PlayerNames() {
    }

    public static void onStyles(HubPayloads.NameStyles styles) {
        DECORATIONS.clear();
        for (HubPayloads.NameStyle style : styles.styles()) {
            Component tag = style.guildTag().isEmpty() ? Component.empty()
                    : Component.literal("[" + style.guildTag() + "] ")
                            .withStyle(s -> s.withColor(style.guildColor()));
            DECORATIONS.put(style.uuid(), new Decoration(parseLegacy(style.prefix()), parseLegacy(style.suffix()), tag,
                    style.nameColor()));
        }
    }

    public static void reset() {
        DECORATIONS.clear();
    }

    /** "prefix name suffix"; just the name for players without a style (or offline). */
    public static Component of(UUID uuid, String name) {
        Decoration decoration = uuid == null ? null : DECORATIONS.get(uuid);
        if (decoration == null) {
            return Component.literal(name);
        }
        // The name itself resets to the label's own color, as in chat.
        return Component.empty().append(decoration.prefix())
                .append(Component.literal(name).withStyle(Style.EMPTY))
                .append(decoration.suffix());
    }

    /**
     * A sender in party/guild chat: prefix, the name in its group color (as in chat), suffix. A
     * click on the name mentions them in the chat field ({@link ChatMentions}).
     */
    public static Component chatName(UUID uuid, String name) {
        Decoration decoration = uuid == null ? null : DECORATIONS.get(uuid);
        Style style = Style.EMPTY
                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                        net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, "@" + name + " "))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("gui.eden_ld.hub.chat.mention", name)));
        if (decoration != null && decoration.nameColor() >= 0) {
            style = style.withColor(TextColor.fromRgb(decoration.nameColor()));
        }
        MutableComponent shown = Component.literal(name).withStyle(style);
        if (decoration == null) {
            return shown;
        }
        return Component.empty().append(decoration.prefix()).append(shown).append(decoration.suffix());
    }

    /** The colored "[TAG] " a player chose to show, or empty. */
    public static Component guildTag(UUID uuid) {
        Decoration decoration = uuid == null ? null : DECORATIONS.get(uuid);
        return decoration == null ? Component.empty() : decoration.guildTag();
    }

    /** Legacy formatting to a component (see {@link LegacyText}). */
    static Component parseLegacy(String text) {
        return LegacyText.parse(text);
    }
}
