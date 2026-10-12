package net.ledok.eden_ld.chat;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.chat.emoji.EmojiRegistry;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How Eden's chat looks (it replaces Styled Chat). A player's display name everywhere the
 * server names them (chat, death and join messages, advancements) is
 * {@code prefix + name + suffix}: LuckPerms prefix/suffix, the name in its group's color
 * (meta {@code eden_ld.name_color}, else config). A chat line is
 * {@code [channel] [TAG] displayName » message} (the channel tag switches channel on click);
 * the message takes {@code eden_ld.chat_color}, and {@code :name:} Discord emojis
 * and gets clickable links and "@name" mentions.
 */
public final class ChatStyle {

    public static final String META_NAME_COLOR = "eden_ld.name_color";
    public static final String META_CHAT_COLOR = "eden_ld.chat_color";
    public static final String META_SILENT_JOIN = "eden_ld.silent_join";

    /**
     * Groups: 1-5 an emoji's image link (FakeNitro style; see EmojiRegistry), 6 other links,
     * 7 "@name", 8 ":name:", 9 an emoji's character, 10-12 a sticker link (tried before group 6
     * by {@link #content}: a sticker link is a link too).
     */
    private static final Pattern TOKENS = Pattern.compile(EmojiRegistry.cdnPattern().pattern()
            + "|(https?://[^\\s]+)|@([A-Za-z0-9_]{3,16})|" + EmojiRegistry.SHORTCODE.pattern()
            + "|" + EmojiRegistry.CHARACTER.pattern() + "|" + ChatLinks.STICKER.pattern());

    /** A message ready to show, and who was mentioned in it. */
    public record Content(Component component, boolean decorated, List<ServerPlayer> mentioned) {
    }

    private ChatStyle() {
    }

    /** The name color from the player's groups, else the config default. */
    @Nullable
    public static TextColor nameColor(ServerPlayer player) {
        TextColor meta = LegacyText.color(LuckPermsCompat.metaString(player, META_NAME_COLOR));
        return meta != null ? meta : LegacyText.color(EdenLD.CONFIG.chat.default_name_color);
    }

    /**
     * {@code prefix + name + suffix}. The vanilla display name keeps its hover card, click and
     * insertion; a scoreboard team color (Duels_LD matches) wins over the group color.
     */
    public static Component displayName(ServerPlayer player, Component vanilla) {
        String[] parts = LuckPermsCompat.prefixSuffix(player);
        MutableComponent name = vanilla.copy();
        TextColor color = nameColor(player);
        if (color != null && vanilla.getStyle().getColor() == null) {
            name.withStyle(style -> style.withColor(color));
        }
        if (parts[0].isEmpty() && parts[1].isEmpty()) {
            return name;
        }
        return Component.empty().append(LegacyText.parse(parts[0])).append(name).append(LegacyText.parse(parts[1]));
    }

    /**
     * Everything before the message: channel tag, opt-in guild tag (not in guild chat, where
     * everyone shares it), display name, separator.
     */
    public static Component senderLine(ServerPlayer sender, ChatChannels.Channel channel) {
        MutableComponent line = Component.empty();
        // Invisible: tells Eden's client the line's channel (Settings → Chat may hide it).
        line.append(ChatMarker.marker(ChatMarker.Kind.valueOf(channel.name()), false, sender.getUUID()));
        line.append(channelTag(channel)).append(" ");
        MinecraftServer server = sender.getServer();
        if (server != null && channel != ChatChannels.Channel.GUILD) {
            Component tag = guildTag(server, sender);
            if (tag != null) {
                line.append(tag);
            }
        }
        line.append(sender.getDisplayName());
        line.append(LegacyText.parse(EdenLD.CONFIG.chat.separator));
        return line;
    }

    /**
     * The colored "[TAG] " of the player's guild, when they chose to show it (Settings → Show
     * guild tag); null otherwise. Chat and the tab list both use it.
     */
    @Nullable
    public static Component guildTag(MinecraftServer server, ServerPlayer player) {
        if (!HubSettings.showGuildTag(server, player.getUUID())) {
            return null;
        }
        Guild guild = GuildManager.get(server).guildOf(player.getUUID());
        return guild == null ? null
                : Component.literal("[" + guild.tag + "] ").withStyle(style -> style.withColor(guild.color));
    }

    /**
     * The channel's colored tag. Clicking it switches the reader to that channel ({@code /ch}),
     * so answering in the same channel is one click.
     */
    public static Component channelTag(ChatChannels.Channel channel) {
        String id = channel.name().toLowerCase(java.util.Locale.ROOT);
        MutableComponent tag = switch (channel) {
            case GLOBAL -> Component.translatable("chat.eden_ld.tag.global").withStyle(ChatFormatting.GRAY);
            case LOCAL -> Component.translatable("chat.eden_ld.tag.local").withStyle(ChatFormatting.YELLOW);
            case PARTY -> Component.translatable("message.eden_ld.party.chat_prefix").withStyle(ChatFormatting.GREEN);
            case GUILD -> Component.translatable("message.eden_ld.guild.chat_prefix").withStyle(ChatFormatting.AQUA);
        };
        return tag.withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ch " + id))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(
                        "chat.eden_ld.tag.hover", Component.translatable("chat.eden_ld.channel." + id)))));
    }

    /** The message formatting the server allows (config {@code chat.markdown} / {@code chat.color_tags}). */
    public static ChatFormat.Options formatOptions() {
        return new ChatFormat.Options(EdenLD.CONFIG.chat.markdown, EdenLD.CONFIG.chat.color_tags);
    }

    /**
     * The message as shown: the sender's chat color, markdown and tags ({@link ChatFormat}),
     * links clickable, "@name" of online players highlighted. {@code decorated} is false when
     * nothing changed (the signed text shows as is).
     */
    public static Content content(ServerPlayer sender, String typed) {
        // Standard emojis (😂 👍🏽 🇺🇦, from keyboards or Discord) become one character each.
        String text = net.ledok.eden_ld.chat.emoji.UnicodeEmoji.toCharacters(typed);
        TextColor base = LegacyText.color(LuckPermsCompat.metaString(sender, META_CHAT_COLOR));
        Style baseStyle = base == null ? Style.EMPTY : Style.EMPTY.withColor(base);
        MutableComponent out = Component.empty().withStyle(baseStyle);
        List<ServerPlayer> mentioned = new ArrayList<>();
        List<ChatFormat.Span> spans = ChatFormat.parse(text, formatOptions());
        boolean[] decorated = {base != null || !text.equals(typed) || ChatFormat.changed(spans, text)};
        ChatFormat.build(out, spans, (part, run) -> {
            if (appendTokens(part, run, sender, mentioned)) {
                decorated[0] = true;
            }
        });
        return new Content(out, decorated[0], mentioned);
    }

    // ---------------------------------------------------------------- :item: and :pos:

    /**
     * {@code :item:} — the held item as "[Name]" (rarity color; hover shows the item, as in
     * vanilla's item links), with "×count" for a stack; {@code :pos:} — the block position,
     * click to copy, dimension on hover. Null for other names, or {@code :item:} with an empty
     * hand (the text stays).
     */
    @Nullable
    private static Component shortcut(String name, ServerPlayer sender) {
        switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "item" -> {
                net.minecraft.world.item.ItemStack stack = sender.getMainHandItem();
                if (stack.isEmpty()) {
                    return null;
                }
                MutableComponent item = Component.empty().append(stack.getDisplayName());
                if (stack.getCount() > 1) {
                    item.append(Component.literal(" ×" + stack.getCount()).withStyle(ChatFormatting.GRAY));
                }
                return item;
            }
            case "pos" -> {
                String position = position(sender);
                return Component.literal(position).withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, position))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(
                                "chat.eden_ld.pos.hover", sender.level().dimension().location().toString()))));
            }
            default -> {
                return null;
            }
        }
    }

    private static String position(ServerPlayer sender) {
        net.minecraft.core.BlockPos pos = sender.blockPosition();
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /**
     * {@code :item:} and {@code :pos:} as plain text — for Discord and the hub's chat panels,
     * which get text, not components: "[Diamond Sword ×3]", "12 64 -300". Formatting characters
     * in item names are escaped, so a name never formats the message.
     */
    public static String expandShortcuts(ServerPlayer sender, String text) {
        if (text.indexOf(':') < 0) {
            return text;
        }
        Matcher matcher = EmojiRegistry.SHORTCODE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = null;
            String name = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            if (name.equals("item") && !sender.getMainHandItem().isEmpty()) {
                net.minecraft.world.item.ItemStack stack = sender.getMainHandItem();
                replacement = "[" + escape(stack.getHoverName().getString())
                        + (stack.getCount() > 1 ? " ×" + stack.getCount() : "") + "]";
            } else if (name.equals("pos")) {
                replacement = position(sender);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Formatting characters kept as text ({@code \*}), when the server formats messages at all. */
    private static String escape(String text) {
        if (!formatOptions().any()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("*_~|`<\\".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Links, emojis and mentions in one run of the message; true when any was found. */
    private static boolean appendTokens(MutableComponent out, String text, ServerPlayer sender, List<ServerPlayer> mentioned) {
        TextColor mentionColor = LegacyText.color(EdenLD.CONFIG.chat.mention_color);
        MinecraftServer server = sender.getServer();
        boolean decorated = false;
        Matcher matcher = TOKENS.matcher(text);
        int last = 0;
        while (matcher.find()) {
            if (matcher.group(3) != null || matcher.group(5) != null) {
                // A pasted emoji image link: the emoji (fetched as a guest before delivery).
                EmojiRegistry.Emoji emoji = EmojiRegistry.byId(matcher.group(3) != null ? matcher.group(3) : matcher.group(5));
                out.append(text.substring(last, matcher.start()));
                out.append(emoji != null ? EmojiRegistry.glyph(emoji)
                        : ChatLinks.link(matcher.group(2) != null ? matcher.group(2) : matcher.group(4)));
                last = matcher.end();
                decorated = true;
            } else if (matcher.group(6) != null) {
                out.append(text.substring(last, matcher.start()));
                out.append(ChatLinks.isSticker(matcher.group(6)) ? ChatLinks.sticker(null, matcher.group(6))
                        : ChatLinks.link(matcher.group(6)));
                last = matcher.end();
                decorated = true;
            } else if (matcher.group(11) != null || matcher.group(12) != null) {
                // A FakeNitro sticker: "[Sticker: name]", opening in the chat's viewer.
                out.append(text.substring(last, matcher.start()));
                out.append(ChatLinks.sticker(matcher.group(10), matcher.group(11) != null ? matcher.group(11) : matcher.group(12)));
                last = matcher.end();
                decorated = true;
            } else if (matcher.group(8) != null || matcher.group(9) != null) {
                // ":item:" / ":pos:", ":name:" (the server's emojis, then the standard ones), or an emoji's character
                // (Eden clients type that in its place; standard emojis arrive as one too).
                Component emoji = matcher.group(8) != null ? shortcut(matcher.group(8), sender) : null;
                if (emoji == null) {
                    emoji = matcher.group(8) != null ? EmojiRegistry.glyphForName(matcher.group(8), sender)
                            : EmojiRegistry.glyphForCharacter(matcher.group(9).codePointAt(0), sender);
                }
                if (emoji == null) {
                    continue;
                }
                out.append(text.substring(last, matcher.start()));
                out.append(emoji);
                last = matcher.end();
                decorated = true;
            } else {
                ServerPlayer target = server == null ? null : server.getPlayerList().getPlayerByName(matcher.group(7));
                if (target == null) {
                    continue;
                }
                out.append(text.substring(last, matcher.start()));
                out.append(Component.literal("@" + target.getGameProfile().getName())
                        .withStyle(style -> style.withColor(mentionColor)));
                // Who it is, for their client (the highlight and the channel's @ count).
                out.append(ChatMarker.mention(target.getUUID()));
                if (!mentioned.contains(target)) {
                    mentioned.add(target);
                }
                last = matcher.end();
                decorated = true;
            }
        }
        out.append(text.substring(last));
        return decorated;
    }
}
