package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.emoji.EmojiRegistry;
import net.ledok.eden_ld.client.chat.ChannelMenu;
import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.ledok.eden_ld.client.chat.EmojiFont;
import net.ledok.eden_ld.client.chat.EmojiTabs;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Eden's additions to the chat screen:
 * <ul>
 *   <li>channel pips left of the input (docs/PLAN-chat-activity.md) — one per channel the player can use, the one
 *   the message goes to with its name (a typed "!l " prefix previews its channel), a dot for lines not seen yet,
 *   @N for mentions; a click switches (also to a channel switched off in Settings), Ctrl+Tab (Shift: back) cycles
 *   the others, a right-click opens the channel's menu (mute, notifications); "!x message" is sent once to that
 *   channel with the prefix stripped client side; "↓ Mentions" jumps to the oldest unseen mention;</li>
 *   <li>for players with {@code eden_ld.emoji_picker}, an emoji button right of the input that
 *   opens tabs — favorites, the server's (Minestar) emojis, Discord's categories; a click inserts the emoji,
 *   a right-click (also on emojis in chat, guests included) stars or unstars it;</li>
 *   <li>a completed {@code :name:} turns into the emoji as you type;</li>
 *   <li>the GIF viewer (a [GIF] link clicked — ScreenMixin) sits over the chat.</li>
 * </ul>
 * Only on servers running Eden (nothing shows elsewhere).
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {

    @Unique
    private static final int EMOJI_BUTTON = 14;
    @Unique
    private static final int CELL = 12;
    @Unique
    private static final int COLUMNS = 10;

    @Shadow
    protected EditBox input;

    /** The room the channel pips take left of the input (0 = none on this server). */
    @Unique
    private int eden_ld$badge;
    @Unique
    private static final int PIP_GAP = 1;
    @Unique
    private boolean eden_ld$emojiButton;
    @Unique
    private boolean eden_ld$pickerOpen;
    @Unique
    private int eden_ld$pickerScroll;
    /** Set while the input is rewritten (setValue calls onEdited again). */
    @Unique
    private boolean eden_ld$rewriting;
    /** Eden's new chat look on this screen (decided at init: an Eden server, and the player hasn't turned it off). */
    @Unique
    private boolean eden_ld$newLook;
    /** Its host, made the first time this screen draws itself (a screen built and never shown gets none). */
    @Unique
    private net.ledok.eden_ld.client.chat.ui.EdenChatUi eden_ld$ui;

    @Shadow
    net.minecraft.client.gui.components.CommandSuggestions commandSuggestions;

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void eden_ld$makeRoom(CallbackInfo ci) {
        eden_ld$badge = 0;
        eden_ld$newLook = net.ledok.eden_ld.client.chat.ui.ChatLook.enabled();
        eden_ld$emojiButton = ClientChatChannel.emojiPicker();
        if (!eden_ld$emojiButton) {
            eden_ld$pickerOpen = false;
        }
        if (ClientChatChannel.current() == null) {
            return;
        }
        eden_ld$badge = eden_ld$pipsWidth();
        input.setX(input.getX() + eden_ld$badge);
        // The new look keeps the line inside its plate: clear of the emoji button, and of the plate's right edge.
        int rightRoom = eden_ld$newLook ? (eden_ld$emojiButton ? EMOJI_BUTTON + 4 : 4) : (eden_ld$emojiButton ? EMOJI_BUTTON + 2 : 0);
        input.setWidth(input.getWidth() - eden_ld$badge - rightRoom);
        if (eden_ld$emojiButton) {
            eden_ld$search = new EditBox(font, 0, 0, COLUMNS * CELL - 2, 12, Component.translatable("chat.eden_ld.emoji.search"));
            eden_ld$search.setHint(Component.translatable("chat.eden_ld.emoji.search").withStyle(ChatFormatting.DARK_GRAY));
            eden_ld$search.setMaxLength(40);
            eden_ld$search.setValue(eden_ld$query);
            eden_ld$search.setResponder(query -> {
                eden_ld$query = query;
                eden_ld$pickerScroll = 0;
            });
            eden_ld$search.setVisible(eden_ld$pickerOpen);
            addWidget(eden_ld$search);
        }
    }

    // ---------------------------------------------------------------- geometry

    /** The channels with a pip: those the player can use (Party and Guild only while in one). */
    @Unique
    private static List<Channel> eden_ld$pipChannels() {
        List<Channel> channels = new java.util.ArrayList<>();
        for (Channel channel : Channel.values()) {
            if (ClientChatChannel.open(channel)) {
                channels.add(channel);
            }
        }
        return channels;
    }

    /** A pip's label: the channel's name on the one typed into, else its letter. */
    @Unique
    private static Component eden_ld$pipLabel(Channel channel, boolean active) {
        return active ? ClientChatChannel.badgeLabel(channel)
                : Component.translatable("chat.eden_ld.pip." + channel.name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The pips' room as they are now (the channel typed into shows its name): the input starts right after them, and
     * moves when the channel changes.
     */
    @Unique
    private int eden_ld$pipsWidth() {
        Channel current = ClientChatChannel.current();
        int width = 0;
        for (Channel channel : eden_ld$pipChannels()) {
            width += font.width(eden_ld$pipLabel(channel, channel == current)) + 6 + PIP_GAP;
        }
        width += font.width(eden_ld$systemLabel(ClientChatChannel.systemView())) + 6 + PIP_GAP;
        return width + 1;
    }

    /** The System pip's label: its name while the System view is on, else its letter. */
    @Unique
    private static Component eden_ld$systemLabel(boolean on) {
        return Component.translatable(on ? "chat.eden_ld.badge.system" : "chat.eden_ld.pip.system");
    }

    /** The System pip, after the channels' (x0, x1). */
    @Unique
    private int[] eden_ld$systemPip() {
        Channel current = ClientChatChannel.current();
        List<int[]> channels = eden_ld$pipBounds(current);
        int x0 = channels.isEmpty() ? 2 : channels.get(channels.size() - 1)[2] + PIP_GAP;
        return new int[]{x0, x0 + font.width(eden_ld$systemLabel(ClientChatChannel.systemView())) + 6};
    }

    @Unique
    private boolean eden_ld$overSystemPip(double mouseX, double mouseY) {
        if (eden_ld$badge == 0 || ClientChatChannel.current() == null || mouseY < height - 14 || mouseY >= height - 2) {
            return false;
        }
        int[] pip = eden_ld$systemPip();
        return mouseX >= pip[0] && mouseX < pip[1];
    }

    /** Each pip's channel and left edge, then its right edge (x0, x1 pairs, in pip order). */
    @Unique
    private List<int[]> eden_ld$pipBounds(Channel active) {
        List<int[]> bounds = new java.util.ArrayList<>();
        int x = 2;
        for (Channel channel : eden_ld$pipChannels()) {
            int w = font.width(eden_ld$pipLabel(channel, channel == active)) + 6;
            bounds.add(new int[]{channel.ordinal(), x, x + w});
            x += w + PIP_GAP;
        }
        return bounds;
    }

    /** The channel whose pip is under the mouse, or null. */
    @Unique
    private Channel eden_ld$pipAt(double mouseX, double mouseY) {
        Channel current = ClientChatChannel.current();
        if (eden_ld$badge == 0 || current == null || mouseY < height - 14 || mouseY >= height - 2) {
            return null;
        }
        for (int[] pip : eden_ld$pipBounds(current)) {
            if (mouseX >= pip[1] && mouseX < pip[2]) {
                return Channel.of(pip[0]);
            }
        }
        return null;
    }

    /** "↓ Mentions: N" right above the input's right end, while there are mentions not seen yet. */
    @Unique
    private int[] eden_ld$pill() {
        int count = net.ledok.eden_ld.client.chat.ChatActivity.mentionsTotal();
        // The new look has its own, in the history plate.
        if (eden_ld$badge == 0 || count == 0 || eden_ld$host() != null) {
            return null;
        }
        int w = font.width(Component.translatable("chat.eden_ld.mentions.jump", count)) + 8;
        int x1 = width - 2;
        return new int[]{x1 - w, height - 27, x1, height - 16};
    }

    @Unique
    private boolean eden_ld$overEmojiButton(double mouseX, double mouseY) {
        int x1 = eden_ld$emojiRight();
        return eden_ld$emojiButton && mouseX >= x1 - EMOJI_BUTTON && mouseX < x1
                && mouseY >= height - 14 && mouseY < height - 2;
    }

    /** The emoji button's right edge: inside the new look's plate (2 in from its rounded end), else vanilla's row end. */
    @Unique
    private int eden_ld$emojiRight() {
        return eden_ld$newLook ? width - 4 : width - 2;
    }

    /** The new look's host while this screen is the one showing, else null. */
    @Unique
    private net.ledok.eden_ld.client.chat.ui.EdenChatUi eden_ld$host() {
        return eden_ld$ui != null && minecraft != null && minecraft.screen == (Object) this ? eden_ld$ui : null;
    }

    // ---------------------------------------------------------------- the new look's frame

    @Inject(method = "render", at = @At("HEAD"))
    private void eden_ld$lookFrame(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (eden_ld$newLook && eden_ld$ui == null && minecraft != null && minecraft.screen == (Object) this) {
            eden_ld$ui = new net.ledok.eden_ld.client.chat.ui.EdenChatUi((ChatScreen) (Object) this);
        }
        if (eden_ld$host() != null) {
            eden_ld$ui.frame();
        }
        // What sits over the history (either look): the lines under the picker or the GIF viewer aren't hovered or
        // clicked through it, and a hovered line's toolbar stays clear of it.
        net.ledok.eden_ld.client.chat.panel.ChatViews.main().cover(
                eden_ld$pickerOpen && eden_ld$emojiButton ? eden_ld$panel() : null,
                net.ledok.eden_ld.client.chat.GifViewer.bounds(width, height));
    }

    /** Vanilla's input strip: the new look draws its plate there instead (before the screen's own widgets). */
    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private boolean eden_ld$inputStrip(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        var host = eden_ld$host();
        if (host == null) {
            return true;
        }
        host.drawInputPlate(graphics, !eden_ld$searching());
        return false;
    }

    /** The line itself stays vanilla's; in the new look it's in the theme's ink and comes in with the panels. */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/EditBox;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
    private void eden_ld$inputLine(EditBox box, GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                   com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var host = eden_ld$host();
        if (host == null) {
            original.call(box, graphics, mouseX, mouseY, partialTick);
            return;
        }
        host.styleInput(box);
        graphics.pose().pushPose();
        graphics.pose().translate(0, host.rise(), 0);
        original.call(box, graphics, mouseX, mouseY, partialTick);
        graphics.pose().popPose();
    }

    /** Picker header: the tab strip (favorites, the server's emojis, Discord's categories), then search. */
    @Unique
    private static final int TAB_STRIP = 13;
    @Unique
    private static final int HEADER = TAB_STRIP + 14;
    /** Tabs shown at once; more scroll with the mouse wheel over the strip. */
    @Unique
    private static final int TAB_SLOTS = COLUMNS;
    @Unique
    private int eden_ld$tabScroll;

    /** The open tab (see EmojiTabs). */
    @Unique
    private int eden_ld$tab;
    /** The picker's search field (drawn and placed by hand over the panel), and what's typed in it. */
    @Unique
    private EditBox eden_ld$search;
    @Unique
    private String eden_ld$query = "";

    /**
     * The picker's rows: favorites first (their own rows), then every listed emoji. Null
     * entries pad the last favorites row.
     */
    @Unique
    private List<EmojiFont.Entry[]> eden_ld$gridRows() {
        List<EmojiFont.Entry[]> rows = new java.util.ArrayList<>();
        eden_ld$addRows(rows, EmojiTabs.entries(eden_ld$tab, eden_ld$query));
        return rows;
    }

    @Unique
    private static void eden_ld$addRows(List<EmojiFont.Entry[]> rows, List<EmojiFont.Entry> entries) {
        for (int i = 0; i < entries.size(); i += COLUMNS) {
            EmojiFont.Entry[] row = new EmojiFont.Entry[COLUMNS];
            for (int c = 0; c < COLUMNS && i + c < entries.size(); c++) {
                row[c] = entries.get(i + c);
            }
            rows.add(row);
        }
    }

    /** The tab under the mouse in the strip, or -1. */
    @Unique
    private int eden_ld$tabAt(double mouseX, double mouseY) {
        if (!eden_ld$pickerOpen) {
            return -1;
        }
        int[] p = eden_ld$panel();
        int slot = (int) Math.floor((mouseX - p[0] - 2) / CELL);
        boolean inStrip = mouseY >= p[1] + 1 && mouseY < p[1] + 1 + CELL && slot >= 0 && slot < TAB_SLOTS;
        int tab = slot + eden_ld$tabScroll;
        return inStrip && tab < EmojiTabs.count() ? tab : -1;
    }

    /** The picker opens on favorites, or on the server's emojis while there are none. */
    @Unique
    private void eden_ld$openPicker() {
        eden_ld$pickerOpen = true;
        eden_ld$pickerScroll = 0;
        eden_ld$tab = EmojiTabs.initial();
        eden_ld$tabScroll = 0;
        if (eden_ld$search != null) {
            eden_ld$search.setVisible(true);
        }
    }

    /** Closes the picker; typing goes back to the chat line. */
    @Unique
    private void eden_ld$closePicker() {
        eden_ld$pickerOpen = false;
        if (eden_ld$search != null) {
            eden_ld$search.setVisible(false);
            eden_ld$search.setFocused(false);
        }
        setFocused(input);
    }

    @Unique
    private boolean eden_ld$searching() {
        return eden_ld$pickerOpen && eden_ld$search != null && eden_ld$search.isFocused();
    }

    /** Puts an emoji in the chat line (the picker stays open with Shift). */
    @Unique
    private void eden_ld$insert(EmojiFont.Entry entry) {
        input.insertText(Character.toString(entry.codepoint()));
        if (Screen.hasShiftDown()) {
            return;
        }
        eden_ld$closePicker();
    }

    /** The Stickers tab's cells: four a row, as wide as the emoji grid. */
    @Unique
    private static final int STICKER_COLUMNS = 4;
    @Unique
    private static final int STICKER_CELL = COLUMNS * CELL / STICKER_COLUMNS;

    /** Whether the grid shows saved stickers (the Stickers tab, no search). */
    @Unique
    private boolean eden_ld$stickerMode() {
        return EmojiTabs.stickers(eden_ld$tab, eden_ld$query);
    }

    @Unique
    private int eden_ld$cell() {
        return eden_ld$stickerMode() ? STICKER_CELL : CELL;
    }

    /** Every grid row (emoji rows, or sticker rows). */
    @Unique
    private int eden_ld$totalRows() {
        return eden_ld$stickerMode() ? (EmojiFont.stickers().size() + STICKER_COLUMNS - 1) / STICKER_COLUMNS
                : eden_ld$gridRows().size();
    }

    /** Visible picker rows: as many as the grid needs, capped to the space above the input. */
    @Unique
    private int eden_ld$rows() {
        int cell = eden_ld$cell();
        return Math.max(1, Math.min(eden_ld$totalRows(), (height - 14 - 8 - HEADER - CELL) / cell));
    }

    /** Picker panel {x0, y0, x1, y1}: right-aligned, just above the input line. */
    @Unique
    private int[] eden_ld$panel() {
        int x1 = width - 2;
        int x0 = x1 - COLUMNS * CELL - 4;
        int y1 = height - 16;
        int y0 = y1 - eden_ld$rows() * eden_ld$cell() - 4 - HEADER;
        return new int[]{x0, y0, x1, y1};
    }

    /** The emoji under the mouse in the open picker, or null. */
    @Unique
    private EmojiFont.Entry eden_ld$emojiAt(double mouseX, double mouseY) {
        if (!eden_ld$pickerOpen || eden_ld$stickerMode()) {
            return null;
        }
        int[] p = eden_ld$panel();
        int column = (int) Math.floor((mouseX - p[0] - 2) / CELL);
        int row = (int) Math.floor((mouseY - p[1] - 2 - HEADER) / CELL);
        if (column < 0 || column >= COLUMNS || row < 0 || row >= eden_ld$rows()) {
            return null;
        }
        List<EmojiFont.Entry[]> rows = eden_ld$gridRows();
        int index = row + eden_ld$pickerScroll;
        return index < rows.size() ? rows.get(index)[column] : null;
    }

    /** The saved sticker under the mouse on the Stickers tab, or null. */
    @Unique
    private net.ledok.eden_ld.chat.ChatLinks.Sticker eden_ld$stickerAt(double mouseX, double mouseY) {
        if (!eden_ld$pickerOpen || !eden_ld$stickerMode()) {
            return null;
        }
        int[] p = eden_ld$panel();
        int column = (int) Math.floor((mouseX - p[0] - 2) / STICKER_CELL);
        int row = (int) Math.floor((mouseY - p[1] - 2 - HEADER) / STICKER_CELL);
        if (column < 0 || column >= STICKER_COLUMNS || row < 0 || row >= eden_ld$rows()) {
            return null;
        }
        List<net.ledok.eden_ld.chat.ChatLinks.Sticker> stickers = EmojiFont.stickers();
        int index = (row + eden_ld$pickerScroll) * STICKER_COLUMNS + column;
        return index < stickers.size() ? stickers.get(index) : null;
    }

    /** Right-click on a sticker (in chat or the picker): save or drop it; a full list says so. */
    @Unique
    private void eden_ld$toggleSticker(net.ledok.eden_ld.chat.ChatLinks.Sticker sticker) {
        if (!EmojiFont.toggleSticker(sticker) && minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable("chat.eden_ld.sticker.full",
                    EmojiFont.stickersLimit()).withStyle(ChatFormatting.RED), true);
        }
    }

    @Unique
    private boolean eden_ld$overPanel(double mouseX, double mouseY) {
        if (!eden_ld$pickerOpen) {
            return false;
        }
        int[] p = eden_ld$panel();
        return mouseX >= p[0] && mouseX < p[2] && mouseY >= p[1] && mouseY < p[3];
    }

    /** Right-click on an emoji: star or unstar it; a full favorites list says so. */
    @Unique
    private void eden_ld$toggleFavorite(EmojiFont.Entry entry) {
        if (!EmojiFont.toggleFavorite(entry.id()) && minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable("chat.eden_ld.emoji.favorites_full",
                    EmojiFont.favoritesLimit()).withStyle(ChatFormatting.RED), true);
        }
    }

    // ---------------------------------------------------------------- drawing

    @Inject(method = "render", at = @At("TAIL"))
    private void eden_ld$drawExtras(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (net.ledok.eden_ld.client.chat.GifViewer.isOpen() && eden_ld$pickerOpen) {
            // Both sit bottom right: the GIF someone just clicked wins.
            eden_ld$closePicker();
        }
        // The chat draws its text at z 100 (ChatView; the font a little nearer): the picker and the GIF viewer go
        // above it (150), or chat lines under them would show through (command suggestions stay on top, 200).
        var host = eden_ld$host();
        if (host != null) {
            host.drawPills(graphics, font, mouseX, mouseY, commandSuggestions != null && commandSuggestions.isVisible(),
                    ChannelMenu.isOpen() || net.ledok.eden_ld.client.chat.MessageMenu.isOpen(),
                    eden_ld$pickerOpen && eden_ld$emojiButton ? eden_ld$panel() : null,
                    net.ledok.eden_ld.client.chat.GifViewer.bounds(width, height));
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 150.0F);
        net.ledok.eden_ld.client.chat.GifViewer.render(graphics, font, width, height, mouseX, mouseY);
        if (eden_ld$badge > 0) {
            eden_ld$drawPips(graphics, mouseX, mouseY);
        }
        if (eden_ld$emojiButton) {
            eden_ld$drawEmojiButton(graphics, mouseX, mouseY);
        }
        if (eden_ld$pickerOpen && eden_ld$emojiButton) {
            eden_ld$drawPicker(graphics, mouseX, mouseY);
        }
        ChannelMenu.render(graphics, font, mouseX, mouseY, width, height);
        net.ledok.eden_ld.client.chat.MessageMenu.render(graphics, font, mouseX, mouseY, width, height);
        net.ledok.eden_ld.client.chat.panel.ChatPanel.Tool tool = ChannelMenu.isOpen()
                || net.ledok.eden_ld.client.chat.MessageMenu.isOpen() ? null
                : net.ledok.eden_ld.client.chat.panel.ChatPanel.toolAt(mouseX, mouseY);
        if (tool != null) {
            graphics.renderTooltip(font, Component.translatable("chat.eden_ld.action."
                    + tool.name().toLowerCase(java.util.Locale.ROOT)), mouseX, mouseY);
        }
        graphics.pose().popPose();
    }

    @Unique
    private void eden_ld$drawPips(GuiGraphics graphics, int mouseX, int mouseY) {
        Channel current = ClientChatChannel.current();
        // Where this message goes: a typed "!l " prefix lights that pip (without moving the input).
        Channel target = ClientChatChannel.effective(input.getValue());
        if (current == null || target == null) {
            return;
        }
        // The channel changed, or a party or guild was joined or left: the input follows the pips.
        int room = eden_ld$pipsWidth();
        if (room != eden_ld$badge) {
            input.setX(input.getX() + room - eden_ld$badge);
            input.setWidth(input.getWidth() - room + eden_ld$badge);
            eden_ld$badge = room;
        }
        boolean command = input.getValue().startsWith("/");
        int y0 = height - 14;
        int y1 = height - 2;
        Channel hovered = ChannelMenu.isOpen() ? null : eden_ld$pipAt(mouseX, mouseY);
        int accent = net.ledok.eden_ld.client.hub.HubNotifications.color();
        var host = eden_ld$host();
        if (host != null) {
            eden_ld$drawChips(graphics, mouseX, mouseY, host, current, target, command, hovered);
            return;
        }
        for (int[] pip : eden_ld$pipBounds(current)) {
            Channel channel = Channel.of(pip[0]);
            int x0 = pip[1];
            int x1 = pip[2];
            // The System view lights its own pip; the channels keep the typed-into one's name.
            boolean on = !ClientChatChannel.systemView() && channel == target;
            boolean switchedOff = net.ledok.eden_ld.client.chat.ChatFilter.channelSwitchedOff(channel);
            boolean muted = net.ledok.eden_ld.client.chat.ChatActivity.muted(channel);
            int color = command && on ? 0xFF808080 : ClientChatChannel.color(channel);
            graphics.fill(x0, y0, x1, y1, channel == hovered ? 0xE0383838 : on ? 0xE0202020 : 0xC0000000);
            if (on) {
                graphics.fill(x0, y1 - 1, x1, y1, color);
            }
            // The others dimmed; switched off or muted dimmer still.
            int text = on ? color : (switchedOff || muted ? 0x70000000 : 0xB0000000) | color & 0xFFFFFF;
            Component label = eden_ld$pipLabel(channel, channel == current);
            graphics.drawString(font, label, x0 + 3, y0 + 2, text, false);
            if (net.ledok.eden_ld.client.chat.ChatActivity.unseen(channel)) {
                graphics.fill(x1 - 3, y0 + 1, x1 - 1, y0 + 3, 0xFFFFFFFF);
            }
            int mentions = net.ledok.eden_ld.client.chat.ChatActivity.mentions(channel);
            if (mentions > 0) {
                String count = "@" + (mentions > 9 ? "9+" : mentions);
                int w = font.width(count) + 3;
                int bx1 = x1 + 2;
                int by0 = y0 - 7;
                graphics.pose().pushPose();
                graphics.pose().translate(0, 0, 10);
                graphics.fill(bx1 - w, by0, bx1, by0 + 9, 0xFF000000 | accent);
                graphics.drawString(font, count, bx1 - w + 2, by0 + 1, 0xFFFFFFFF, false);
                graphics.pose().popPose();
            }
        }
        int[] system = eden_ld$systemPip();
        boolean systemOn = ClientChatChannel.systemView();
        boolean systemHovered = !ChannelMenu.isOpen() && eden_ld$overSystemPip(mouseX, mouseY);
        graphics.fill(system[0], y0, system[1], y1, systemHovered ? 0xE0383838 : systemOn ? 0xE0202020 : 0xC0000000);
        if (systemOn) {
            graphics.fill(system[0], y1 - 1, system[1], y1, 0xFFAAAAAA);
        }
        graphics.drawString(font, eden_ld$systemLabel(systemOn), system[0] + 3, y0 + 2, systemOn ? 0xFFAAAAAA : 0xB0AAAAAA, false);
        if (!systemOn && net.ledok.eden_ld.client.chat.ChatActivity.unseenSystem()) {
            graphics.fill(system[1] - 3, y0 + 1, system[1] - 1, y0 + 3, 0xFFFFFFFF);
        }
        if (systemHovered) {
            graphics.renderComponentTooltip(font, List.of(Component.translatable("chat.eden_ld.pip.system.name"),
                    Component.translatable("chat.eden_ld.pip.system.hint").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
        }
        int[] pill = eden_ld$pill();
        if (pill != null) {
            boolean over = !ChannelMenu.isOpen() && mouseX >= pill[0] && mouseX < pill[2] && mouseY >= pill[1] && mouseY < pill[3]
                    && !eden_ld$overPanel(mouseX, mouseY);
            graphics.fill(pill[0], pill[1], pill[2], pill[3], over ? 0xFF000000 | accent : 0xD0000000 | accent & 0xFFFFFF);
            graphics.drawString(font, Component.translatable("chat.eden_ld.mentions.jump",
                    net.ledok.eden_ld.client.chat.ChatActivity.mentionsTotal()), pill[0] + 4, pill[1] + 2, 0xFFFFFFFF, false);
            if (over) {
                graphics.renderTooltip(font, Component.translatable("chat.eden_ld.mentions.jump.hint"), mouseX, mouseY);
            }
        }
        if (hovered != null) {
            graphics.renderComponentTooltip(font, eden_ld$pipTooltip(hovered, current), mouseX, mouseY);
        }
    }

    /**
     * The new look's pips: rounded chips inside the input plate (rows h-13..h-3), rising and fading in with it — the
     * channel typed into lit with a foot line in its color, a dot for unseen lines, the @N badge ringed by the plate.
     */
    @Unique
    private void eden_ld$drawChips(GuiGraphics graphics, int mouseX, int mouseY,
                                   net.ledok.eden_ld.client.chat.ui.EdenChatUi host, Channel current, Channel target,
                                   boolean command, Channel hovered) {
        var theme = net.ledok.eden_ld.client.chat.ui.ChatTheme.current();
        float fade = net.ledok.eden_ld.client.chat.ui.ChatLook.chrome();
        int y0 = height - 13;
        int chipHeight = 10;
        int badgeColor = net.ledok.eden_ld.client.hub.HubNotifications.color();
        graphics.pose().pushPose();
        graphics.pose().translate(0, host.rise(), 0);
        for (int[] pip : eden_ld$pipBounds(current)) {
            Channel channel = Channel.of(pip[0]);
            int x0 = pip[1];
            int x1 = pip[2];
            boolean on = !ClientChatChannel.systemView() && channel == target;
            boolean dim = net.ledok.eden_ld.client.chat.ChatFilter.channelSwitchedOff(channel)
                    || net.ledok.eden_ld.client.chat.ChatActivity.muted(channel);
            int color = command && on ? 0xFF808080 : ClientChatChannel.color(channel);
            net.ledok.vectorlib.client.canvas.RoundedBox.draw(graphics, x0, y0, x1 - x0, chipHeight,
                    channel == hovered ? theme.pipHover : on ? theme.pipLit : theme.pip, fade, false);
            if (on) {
                net.ledok.vectorlib.client.canvas.RoundedBox.draw(graphics, x0 + 2, y0 + chipHeight - 1, x1 - x0 - 4, 1,
                        theme.fill(color | 0xFF000000), fade, false);
            }
            if (net.ledok.eden_ld.client.chat.ChatActivity.unseen(channel)) {
                net.ledok.vectorlib.client.canvas.RoundedBox.draw(graphics, x1 - 4, y0 + 1, 3, 3, theme.dot, fade, false);
            }
            int text = on ? color : (dim ? 0x70000000 : 0xB0000000) | color & 0xFFFFFF;
            text = net.ledok.eden_ld.client.chat.ui.ChatLook.textColor(text, fade);
            if (text != 0) {
                graphics.drawString(font, eden_ld$pipLabel(channel, channel == current), x0 + 3, height - 12, text, false);
            }
            int mentions = net.ledok.eden_ld.client.chat.ChatActivity.mentions(channel);
            if (mentions > 0) {
                // Above the label (h-12), border included.
                eden_ld$drawBadge(graphics, theme, badgeColor, "@" + (mentions > 9 ? "9+" : mentions), x1 + 2, y0 - 9, fade);
            }
        }
        int[] system = eden_ld$systemPip();
        boolean systemOn = ClientChatChannel.systemView();
        boolean systemHovered = !ChannelMenu.isOpen() && eden_ld$overSystemPip(mouseX, mouseY);
        net.ledok.vectorlib.client.canvas.RoundedBox.draw(graphics, system[0], y0, system[1] - system[0], chipHeight,
                systemHovered ? theme.pipHover : systemOn ? theme.pipLit : theme.pip, fade, false);
        if (systemOn) {
            net.ledok.vectorlib.client.canvas.RoundedBox.draw(graphics, system[0] + 2, y0 + chipHeight - 1,
                    system[1] - system[0] - 4, 1, theme.fill(0xFFAAAAAA), fade, false);
        } else if (net.ledok.eden_ld.client.chat.ChatActivity.unseenSystem()) {
            net.ledok.vectorlib.client.canvas.RoundedBox.draw(graphics, system[1] - 4, y0 + 1, 3, 3, theme.dot, fade, false);
        }
        int systemText = net.ledok.eden_ld.client.chat.ui.ChatLook.textColor(systemOn ? 0xFFAAAAAA : 0xB0AAAAAA, fade);
        if (systemText != 0) {
            graphics.drawString(font, eden_ld$systemLabel(systemOn), system[0] + 3, height - 12, systemText, false);
        }
        graphics.pose().popPose();
        if (systemHovered) {
            graphics.renderComponentTooltip(font, List.of(Component.translatable("chat.eden_ld.pip.system.name"),
                    Component.translatable("chat.eden_ld.pip.system.hint").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
        }
        if (hovered != null) {
            graphics.renderComponentTooltip(font, eden_ld$pipTooltip(hovered, current), mouseX, mouseY);
        }
    }

    /** "@N" in the notification color (text black or white, whichever reads), its right end at {@code right}. */
    @Unique
    private void eden_ld$drawBadge(GuiGraphics graphics, net.ledok.eden_ld.client.chat.ui.ChatTheme theme, int color,
                                   String text, int right, int top, float fade) {
        int w = font.width(text) + 4;
        // On the first chip it would start left of the screen: kept on it, its ring included.
        int x0 = Math.max(1, right - w);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 10);
        net.ledok.vectorlib.client.canvas.RoundedBox.draw(graphics, x0, top, w, 9, theme.badge(color), fade, false);
        int ink = net.ledok.eden_ld.client.chat.ui.ChatLook.textColor(
                net.ledok.eden_ld.client.chat.ui.ChatLook.textOn(color), fade);
        if (ink != 0) {
            graphics.drawString(font, text, x0 + 2, top + 1, ink, false);
        }
        graphics.pose().popPose();
    }

    @Unique
    private static List<Component> eden_ld$pipTooltip(Channel channel, Channel active) {
        List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.translatable("chat.eden_ld.channel." + channel.name().toLowerCase(java.util.Locale.ROOT))
                .withColor(ClientChatChannel.color(channel) & 0xFFFFFF));
        if (channel == active) {
            lines.add(Component.translatable("chat.eden_ld.pip.typing").withStyle(ChatFormatting.GRAY));
        }
        int mentions = net.ledok.eden_ld.client.chat.ChatActivity.mentions(channel);
        if (mentions > 0) {
            lines.add(Component.translatable("chat.eden_ld.pip.mentions", mentions).withStyle(ChatFormatting.GRAY));
        } else if (net.ledok.eden_ld.client.chat.ChatActivity.unseen(channel)) {
            lines.add(Component.translatable("chat.eden_ld.pip.unseen").withStyle(ChatFormatting.GRAY));
        }
        Component muted = ChannelMenu.mutedLine(channel);
        if (muted != null) {
            lines.add(muted.copy().withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("chat.eden_ld.pip.notify",
                ChannelMenu.notifyLabel(net.ledok.eden_ld.client.chat.ChatActivity.notify(channel))).withStyle(ChatFormatting.GRAY));
        if (net.ledok.eden_ld.client.chat.ChatFilter.channelSwitchedOff(channel)) {
            lines.add(Component.translatable("chat.eden_ld.pip.switched_off").withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("chat.eden_ld.pip.hint").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    @Unique
    private void eden_ld$drawEmojiButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x0 = eden_ld$emojiRight() - EMOJI_BUTTON;
        int y0 = height - 14;
        boolean hover = eden_ld$overEmojiButton(mouseX, mouseY);
        Component icon = EmojiTabs.icon(EmojiTabs.SERVER);
        var host = eden_ld$host();
        if (host != null) {
            var theme = net.ledok.eden_ld.client.chat.ui.ChatTheme.current();
            float fade = net.ledok.eden_ld.client.chat.ui.ChatLook.chrome();
            graphics.pose().pushPose();
            graphics.pose().translate(0, host.rise(), 0);
            net.ledok.vectorlib.client.canvas.RoundedBox.draw(graphics, x0, y0 + 1, EMOJI_BUTTON, 10,
                    hover || eden_ld$pickerOpen ? theme.buttonHover : theme.button, fade, false);
            int color = net.ledok.eden_ld.client.chat.ui.ChatLook.textColor(0xFFFFFFFF, fade);
            if (color != 0) {
                graphics.drawString(font, icon, x0 + (EMOJI_BUTTON - font.width(icon)) / 2 + 1, y0 + 2, color, false);
            }
            graphics.pose().popPose();
        } else {
            graphics.fill(x0, y0, eden_ld$emojiRight(), height - 2, hover || eden_ld$pickerOpen ? 0xE0303030 : 0xC0000000);
            graphics.drawString(font, icon, x0 + (EMOJI_BUTTON - font.width(icon)) / 2 + 1, y0 + 2, 0xFFFFFFFF, false);
        }
        if (hover) {
            graphics.renderTooltip(font, Component.translatable("chat.eden_ld.emoji.button"), mouseX, mouseY);
        }
    }

    @Unique
    private void eden_ld$drawPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        int[] p = eden_ld$panel();
        graphics.fill(p[0], p[1], p[2], p[3], 0xE0101010);
        graphics.renderOutline(p[0], p[1], p[2] - p[0], p[3] - p[1], 0xFF505050);
        int hoveredTab = eden_ld$tabAt(mouseX, mouseY);
        for (int slot = 0; slot < TAB_SLOTS && slot + eden_ld$tabScroll < EmojiTabs.count(); slot++) {
            int tab = slot + eden_ld$tabScroll;
            int x = p[0] + 2 + slot * CELL;
            if (tab == eden_ld$tab) {
                graphics.fill(x, p[1] + 1, x + CELL, p[1] + 1 + CELL, 0x80FFFFFF);
            } else if (tab == hoveredTab) {
                graphics.fill(x, p[1] + 1, x + CELL, p[1] + 1 + CELL, 0x40FFFFFF);
            }
            Component icon = EmojiTabs.icon(tab);
            graphics.drawString(font, icon, x + (CELL - font.width(icon)) / 2 + 1, p[1] + 3, 0xFFFFFFFF, false);
        }
        graphics.fill(p[0] + 1, p[1] + TAB_STRIP, p[2] - 1, p[1] + TAB_STRIP + 1, 0xFF505050);
        if (eden_ld$search != null) {
            eden_ld$search.setX(p[0] + 3);
            eden_ld$search.setY(p[1] + TAB_STRIP + 2);
            eden_ld$search.render(graphics, mouseX, mouseY, 0);
        }
        if (eden_ld$stickerMode()) {
            eden_ld$drawStickers(graphics, p, mouseX, mouseY, hoveredTab);
            return;
        }
        List<EmojiFont.Entry[]> rows = eden_ld$gridRows();
        EmojiFont.Entry hovered = eden_ld$emojiAt(mouseX, mouseY);
        int top = p[1] + 2 + HEADER;
        for (int visible = 0; visible < eden_ld$rows(); visible++) {
            int index = visible + eden_ld$pickerScroll;
            if (index >= rows.size()) {
                break;
            }
            int y = top + visible * CELL;
            EmojiFont.Entry[] row = rows.get(index);
            for (int column = 0; column < COLUMNS; column++) {
                EmojiFont.Entry entry = row[column];
                if (entry == null) {
                    continue;
                }
                int x = p[0] + 2 + column * CELL;
                if (entry == hovered) {
                    graphics.fill(x, y, x + CELL, y + CELL, 0x60FFFFFF);
                }
                Component glyph = EmojiTabs.glyph(entry);
                graphics.drawString(font, glyph, x + (CELL - font.width(glyph)) / 2 + 1, y + 2, 0xFFFFFFFF, false);
            }
        }
        if (hoveredTab >= 0) {
            graphics.renderTooltip(font, EmojiTabs.name(hoveredTab), mouseX, mouseY);
        } else if (hovered != null) {
            graphics.renderComponentTooltip(font, List.of(Component.literal(":" + hovered.name() + ":"),
                    Component.translatable(EmojiFont.isFavorite(hovered.id())
                            ? "chat.eden_ld.emoji.unfavorite" : "chat.eden_ld.emoji.favorite")
                            .withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
        }
    }

    /** The Stickers tab: saved stickers as images (loading on first sight), or how to save one. */
    @Unique
    private void eden_ld$drawStickers(GuiGraphics graphics, int[] p, int mouseX, int mouseY, int hoveredTab) {
        List<net.ledok.eden_ld.chat.ChatLinks.Sticker> stickers = EmojiFont.stickers();
        int top = p[1] + 2 + HEADER;
        if (stickers.isEmpty()) {
            graphics.drawWordWrap(font, Component.translatable("chat.eden_ld.sticker.empty"), p[0] + 4, top + 3,
                    p[2] - p[0] - 8, 0xFF909090);
        }
        net.ledok.eden_ld.chat.ChatLinks.Sticker hovered = eden_ld$stickerAt(mouseX, mouseY);
        for (int visible = 0; visible < eden_ld$rows(); visible++) {
            for (int column = 0; column < STICKER_COLUMNS; column++) {
                int index = (visible + eden_ld$pickerScroll) * STICKER_COLUMNS + column;
                if (index >= stickers.size()) {
                    break;
                }
                net.ledok.eden_ld.chat.ChatLinks.Sticker sticker = stickers.get(index);
                int x = p[0] + 2 + column * STICKER_CELL;
                int y = top + visible * STICKER_CELL;
                if (sticker.equals(hovered)) {
                    graphics.fill(x, y, x + STICKER_CELL, y + STICKER_CELL, 0x60FFFFFF);
                }
                net.ledok.eden_ld.client.chat.StickerImages.Image image =
                        net.ledok.eden_ld.client.chat.StickerImages.get(sticker, null);
                net.minecraft.resources.ResourceLocation texture = net.ledok.eden_ld.client.chat.StickerImages.texture(image);
                if (texture == null) {
                    graphics.drawString(font, "…", x + STICKER_CELL / 2 - 3, y + STICKER_CELL / 2 - 4, 0xFF909090, false);
                    continue;
                }
                int side = STICKER_CELL - 4;
                float scale = (float) side / Math.max(image.width(), image.height());
                int w = Math.max(1, Math.round(image.width() * scale));
                int h = Math.max(1, Math.round(image.height() * scale));
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                graphics.blit(texture, x + (STICKER_CELL - w) / 2, y + (STICKER_CELL - h) / 2, w, h, 0, 0,
                        image.width(), image.height(), image.width(), image.height());
                com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            }
        }
        if (hoveredTab >= 0) {
            graphics.renderTooltip(font, EmojiTabs.name(hoveredTab), mouseX, mouseY);
        } else if (hovered != null) {
            graphics.renderComponentTooltip(font, List.of(Component.literal(hovered.name()),
                    Component.translatable("chat.eden_ld.sticker.drop").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
        }
    }

    // ---------------------------------------------------------------- input

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void eden_ld$keys(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && (ChannelMenu.isOpen() || net.ledok.eden_ld.client.chat.MessageMenu.isOpen())) {
            ChannelMenu.close();
            net.ledok.eden_ld.client.chat.MessageMenu.close();
            cir.setReturnValue(true);
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && net.ledok.eden_ld.client.chat.GifViewer.isOpen()) {
            net.ledok.eden_ld.client.chat.GifViewer.close();
            cir.setReturnValue(true);
            return;
        }
        if (eden_ld$searching()) {
            // The search field has the keyboard: Enter picks the first match, Esc closes.
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                List<EmojiFont.Entry> found = EmojiFont.search(eden_ld$query);
                if (!found.isEmpty()) {
                    eden_ld$insert(found.get(0));
                }
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                eden_ld$closePicker();
            } else {
                eden_ld$search.keyPressed(keyCode, scanCode, modifiers);
            }
            cir.setReturnValue(true);
            return;
        }
        if (eden_ld$badge > 0 && keyCode == GLFW.GLFW_KEY_TAB && Screen.hasControlDown()) {
            ClientChatChannel.cycle(Screen.hasShiftDown());
            cir.setReturnValue(true);
        } else if (eden_ld$pickerOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // Esc closes the picker first, the chat second.
            eden_ld$closePicker();
            cir.setReturnValue(true);
        }
    }

    @Shadow
    private net.minecraft.network.chat.Style getComponentStyleAt(double mouseX, double mouseY) {
        throw new AssertionError();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void eden_ld$clicks(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (ChannelMenu.isOpen()) {
            ChannelMenu.click(mouseX, mouseY);
            cir.setReturnValue(true);
            return;
        }
        if (net.ledok.eden_ld.client.chat.MessageMenu.isOpen()) {
            net.ledok.eden_ld.client.chat.MessageMenu.click(mouseX, mouseY);
            cir.setReturnValue(true);
            return;
        }
        var host = eden_ld$host();
        if (host != null && host.clicked(mouseX, mouseY, button)) {
            cir.setReturnValue(true);
            return;
        }
        if (eden_ld$overSystemPip(mouseX, mouseY) && (button == 0 || button == 1)) {
            if (button == 0) {
                ClientChatChannel.showSystem();
            }
            cir.setReturnValue(true);
            return;
        }
        Channel pip = eden_ld$pipAt(mouseX, mouseY);
        if (pip != null && (button == 0 || button == 1)) {
            if (button == 1) {
                for (int[] bounds : eden_ld$pipBounds(ClientChatChannel.current())) {
                    if (bounds[0] == pip.ordinal()) {
                        ChannelMenu.open(pip, bounds[1], height - 14);
                    }
                }
            } else {
                // Its channel — even one switched off in Settings (Ctrl+Tab skips those) — and its oldest unseen mention.
                ClientChatChannel.select(pip);
                net.ledok.eden_ld.client.chat.ChatActivity.jumpToMention(pip);
            }
            cir.setReturnValue(true);
            return;
        }
        int[] pill = eden_ld$pill();
        if (pill != null && button == 0 && mouseX >= pill[0] && mouseX < pill[2] && mouseY >= pill[1] && mouseY < pill[3]
                && !eden_ld$overPanel(mouseX, mouseY)) {
            net.ledok.eden_ld.client.chat.ChatActivity.jumpToMention(null);
            cir.setReturnValue(true);
            return;
        }
        // The hovered message's toolbar: reply, copy, the full menu.
        net.ledok.eden_ld.client.chat.panel.ChatPanel.Tool tool = button == 0
                ? net.ledok.eden_ld.client.chat.panel.ChatPanel.toolAt(mouseX, mouseY) : null;
        if (tool != null) {
            net.ledok.eden_ld.client.chat.panel.ChatEntry entry =
                    net.ledok.eden_ld.client.chat.panel.ChatPanel.toolEntryAt(mouseX, mouseY);
            switch (tool) {
                case REPLY -> net.ledok.eden_ld.client.chat.MessageActions.reply(entry);
                case COPY -> net.ledok.eden_ld.client.chat.MessageActions.copy(entry);
                case MORE -> net.ledok.eden_ld.client.chat.MessageMenu.open(entry, (int) mouseX, (int) mouseY);
            }
            cir.setReturnValue(true);
            return;
        }
        if (net.ledok.eden_ld.client.chat.GifViewer.click(this, mouseX, mouseY)) {
            cir.setReturnValue(true);
            return;
        }
        if (button == 1) {
            // Right-click: favorite the emoji (or save the sticker) under the mouse — in the
            // picker, or in a chat message.
            net.ledok.eden_ld.chat.ChatLinks.Sticker sticker = eden_ld$stickerAt(mouseX, mouseY);
            EmojiFont.Entry entry = eden_ld$emojiAt(mouseX, mouseY);
            if (sticker == null && entry == null && eden_ld$emojiButton && !eden_ld$overPanel(mouseX, mouseY)) {
                net.minecraft.network.chat.Style style = getComponentStyleAt(mouseX, mouseY);
                String insertion = style == null ? null : style.getInsertion();
                if (insertion != null && insertion.codePointCount(0, insertion.length()) == 1) {
                    entry = EmojiFont.byCodepoint(insertion.codePointAt(0));
                }
                net.minecraft.network.chat.ClickEvent click = style == null ? null : style.getClickEvent();
                if (click != null && click.getAction() == net.minecraft.network.chat.ClickEvent.Action.OPEN_URL) {
                    sticker = net.ledok.eden_ld.chat.ChatLinks.stickerOf(click.getValue());
                }
            }
            if (sticker != null) {
                eden_ld$toggleSticker(sticker);
                cir.setReturnValue(true);
                return;
            }
            if (entry != null) {
                eden_ld$toggleFavorite(entry);
                cir.setReturnValue(true);
                return;
            }
            // Anywhere else on a message: its menu (reply, copy, profile, ignore, moderate).
            net.ledok.eden_ld.client.chat.panel.ChatEntry message = eden_ld$pickerOpen && eden_ld$overPanel(mouseX, mouseY)
                    ? null : net.ledok.eden_ld.client.chat.panel.ChatPanel.entryAt(mouseX, mouseY);
            if (message != null) {
                net.ledok.eden_ld.client.chat.MessageMenu.open(message, (int) mouseX, (int) mouseY);
                cir.setReturnValue(true);
            }
            return;
        }
        if (button != 0) {
            return;
        }
        if (eden_ld$overEmojiButton(mouseX, mouseY)) {
            if (eden_ld$pickerOpen) {
                eden_ld$closePicker();
            } else {
                eden_ld$openPicker();
            }
            cir.setReturnValue(true);
        } else if (eden_ld$pickerOpen && eden_ld$search != null && eden_ld$search.isMouseOver(mouseX, mouseY)) {
            eden_ld$search.mouseClicked(mouseX, mouseY, button);
            setFocused(eden_ld$search);
            cir.setReturnValue(true);
        } else if (eden_ld$tabAt(mouseX, mouseY) >= 0) {
            eden_ld$tab = eden_ld$tabAt(mouseX, mouseY);
            eden_ld$pickerScroll = 0;
            cir.setReturnValue(true);
        } else if (eden_ld$overPanel(mouseX, mouseY)) {
            EmojiFont.Entry entry = eden_ld$emojiAt(mouseX, mouseY);
            net.ledok.eden_ld.chat.ChatLinks.Sticker sticker = eden_ld$stickerAt(mouseX, mouseY);
            if (entry != null) {
                eden_ld$insert(entry);
            } else if (sticker != null) {
                // One character, drawn as the sticker; it's sent as its FakeNitro link.
                input.insertText(EmojiFont.stickerCharacter(sticker));
                if (!Screen.hasShiftDown()) {
                    eden_ld$closePicker();
                }
            }
            cir.setReturnValue(true);
        } else if (eden_ld$pickerOpen) {
            eden_ld$closePicker();
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void eden_ld$closeViewer(CallbackInfo ci) {
        net.ledok.eden_ld.client.chat.GifViewer.close();
        ChannelMenu.close();
        net.ledok.eden_ld.client.chat.MessageMenu.close();
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void eden_ld$scrollPicker(double mouseX, double mouseY, double scrollX, double scrollY,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (eden_ld$tabAt(mouseX, mouseY) >= 0 || (eden_ld$pickerOpen && eden_ld$overPanel(mouseX, mouseY)
                && mouseY < eden_ld$panel()[1] + TAB_STRIP)) {
            // Over the tab strip: scroll the tabs.
            int maxTabScroll = Math.max(0, EmojiTabs.count() - TAB_SLOTS);
            eden_ld$tabScroll = Math.max(0, Math.min(maxTabScroll, eden_ld$tabScroll - (int) Math.signum(scrollY)));
            cir.setReturnValue(true);
            return;
        }
        if (eden_ld$overPanel(mouseX, mouseY)) {
            int totalRows = eden_ld$totalRows();
            int maxScroll = Math.max(0, totalRows - eden_ld$rows());
            eden_ld$pickerScroll = Math.max(0, Math.min(maxScroll, eden_ld$pickerScroll - (int) Math.signum(scrollY)));
            cir.setReturnValue(true);
        }
    }

    /**
     * A completed {@code :name:} of a known emoji turns into the emoji's character as you type
     * (drawn as the image — FontSetMixin); one Backspace removes it. The server reads the
     * character as {@code :name:}.
     */
    @Inject(method = "onEdited", at = @At("TAIL"))
    private void eden_ld$emojiAsYouType(String value, CallbackInfo ci) {
        if (eden_ld$rewriting || ClientChatChannel.current() == null) {
            return;
        }
        // Emojis from the system's emoji keyboard (😂 👍🏽 🇺🇦) become one character each too.
        String converted = net.ledok.eden_ld.chat.emoji.UnicodeEmoji.toCharacters(value);
        if (!converted.equals(value)) {
            int cursorFromEnd = value.length() - input.getCursorPosition();
            eden_ld$rewriting = true;
            try {
                input.setValue(converted);
                input.setCursorPosition(Math.max(0, converted.length() - cursorFromEnd));
                input.setHighlightPos(input.getCursorPosition());
            } finally {
                eden_ld$rewriting = false;
            }
            value = converted;
        }
        if (value.indexOf(':') < 0) {
            return;
        }
        java.util.regex.Matcher matcher = EmojiRegistry.SHORTCODE.matcher(value);
        StringBuilder out = new StringBuilder();
        int cursor = input.getCursorPosition();
        int newCursor = cursor;
        int last = 0;
        while (matcher.find()) {
            EmojiFont.Entry entry = EmojiFont.byName(matcher.group(1));
            if (entry == null) {
                entry = EmojiFont.standardByName(matcher.group(1));
            }
            if (entry == null) {
                continue;
            }
            out.append(value, last, matcher.start());
            String glyph = Character.toString(entry.codepoint());
            out.append(glyph);
            if (cursor >= matcher.end()) {
                newCursor -= matcher.group().length() - glyph.length();
            }
            last = matcher.end();
        }
        if (last == 0) {
            return;
        }
        out.append(value.substring(last));
        eden_ld$rewriting = true;
        try {
            input.setValue(out.toString());
            input.setCursorPosition(Math.max(0, Math.min(newCursor, out.length())));
            input.setHighlightPos(input.getCursorPosition());
        } finally {
            eden_ld$rewriting = false;
        }
    }

    /** Sticker characters go out as their links (the chat history keeps the short form). */
    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "handleChatInput", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;sendChat(Ljava/lang/String;)V"))
    private String eden_ld$stickerLinks(String message) {
        return EmojiFont.expandStickers(message, 256);
    }

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "handleChatInput", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;sendCommand(Ljava/lang/String;)V"))
    private String eden_ld$stickerLinksInCommands(String command) {
        return EmojiFont.expandStickers(command, 255);
    }

    @ModifyVariable(method = "handleChatInput", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String eden_ld$oneShot(String message) {
        return ClientChatChannel.takePrefix(message);
    }
}
