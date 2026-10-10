package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.emoji.EmojiRegistry;
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
 *   <li>a channel badge left of the input — where the message goes (a typed "!l " prefix
 *   previews its channel); Ctrl+Tab (Shift: back) or a click switches; "!x message" is sent
 *   once to that channel with the prefix stripped client side;</li>
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

    /** Badge width (0 = no badge on this server). */
    @Unique
    private int eden_ld$badge;
    @Unique
    private boolean eden_ld$emojiButton;
    @Unique
    private boolean eden_ld$pickerOpen;
    @Unique
    private int eden_ld$pickerScroll;
    /** Set while the input is rewritten (setValue calls onEdited again). */
    @Unique
    private boolean eden_ld$rewriting;

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void eden_ld$makeRoom(CallbackInfo ci) {
        eden_ld$badge = 0;
        eden_ld$emojiButton = ClientChatChannel.emojiPicker();
        if (!eden_ld$emojiButton) {
            eden_ld$pickerOpen = false;
        }
        if (ClientChatChannel.current() == null) {
            return;
        }
        int widest = 0;
        for (Channel channel : Channel.values()) {
            widest = Math.max(widest, font.width(ClientChatChannel.badgeLabel(channel)));
        }
        eden_ld$badge = widest + 8;
        input.setX(input.getX() + eden_ld$badge);
        input.setWidth(input.getWidth() - eden_ld$badge - (eden_ld$emojiButton ? EMOJI_BUTTON + 2 : 0));
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

    @Unique
    private boolean eden_ld$overBadge(double mouseX, double mouseY) {
        return eden_ld$badge > 0 && mouseX >= 2 && mouseX < 2 + eden_ld$badge
                && mouseY >= height - 14 && mouseY < height - 2;
    }

    @Unique
    private boolean eden_ld$overEmojiButton(double mouseX, double mouseY) {
        return eden_ld$emojiButton && mouseX >= width - 2 - EMOJI_BUTTON && mouseX < width - 2
                && mouseY >= height - 14 && mouseY < height - 2;
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
        // The chat draws its text 50 units up (vanilla): the picker and the GIF viewer go above
        // it, or chat lines under them would show through (command suggestions stay on top, 200).
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 100.0F);
        net.ledok.eden_ld.client.chat.GifViewer.render(graphics, font, width, height, mouseX, mouseY);
        if (eden_ld$badge > 0) {
            eden_ld$drawBadge(graphics, mouseX, mouseY);
        }
        if (eden_ld$emojiButton) {
            eden_ld$drawEmojiButton(graphics, mouseX, mouseY);
        }
        if (eden_ld$pickerOpen && eden_ld$emojiButton) {
            eden_ld$drawPicker(graphics, mouseX, mouseY);
        }
        graphics.pose().popPose();
    }

    @Unique
    private void eden_ld$drawBadge(GuiGraphics graphics, int mouseX, int mouseY) {
        Channel channel = ClientChatChannel.effective(input.getValue());
        if (channel == null) {
            return;
        }
        int x0 = 2;
        int y0 = height - 14;
        int x1 = x0 + eden_ld$badge - 2;
        int y1 = height - 2;
        boolean command = input.getValue().startsWith("/");
        int color = command ? 0xFF808080 : ClientChatChannel.color(channel);
        graphics.fill(x0, y0, x1, y1, 0xC0000000);
        graphics.fill(x0, y1 - 1, x1, y1, color);
        Component label = ClientChatChannel.badgeLabel(channel);
        graphics.drawString(font, label, x0 + (x1 - x0 - font.width(label)) / 2, y0 + 2, color, false);
        if (eden_ld$overBadge(mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, List.of(
                    Component.translatable("chat.eden_ld.badge.tooltip", ClientChatChannel.badgeLabel(channel)),
                    Component.translatable("chat.eden_ld.badge.hint").withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
        }
    }

    @Unique
    private void eden_ld$drawEmojiButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x0 = width - 2 - EMOJI_BUTTON;
        int y0 = height - 14;
        boolean hover = eden_ld$overEmojiButton(mouseX, mouseY);
        graphics.fill(x0, y0, width - 2, height - 2, hover || eden_ld$pickerOpen ? 0xE0303030 : 0xC0000000);
        Component icon = EmojiTabs.icon(EmojiTabs.SERVER);
        graphics.drawString(font, icon, x0 + (EMOJI_BUTTON - font.width(icon)) / 2 + 1, y0 + 2, 0xFFFFFFFF, false);
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
            }
            return;
        }
        if (button != 0) {
            return;
        }
        if (eden_ld$overBadge(mouseX, mouseY)) {
            ClientChatChannel.cycle(Screen.hasShiftDown());
            cir.setReturnValue(true);
        } else if (eden_ld$overEmojiButton(mouseX, mouseY)) {
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
