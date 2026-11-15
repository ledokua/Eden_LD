package net.ledok.eden_ld.client.poll;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * A staff poll on screen ({@code hub.poll.Polls}): a framed panel at the right middle, over the game and over every
 * GUI, until the poll ends. The question on top, a bar of the votes so far (one color per answer, the track for those
 * who haven't voted), a thin timer bar running out, and the answers as buttons. In a GUI the buttons are clicked; in
 * the game the first two answers have keys ({@link #KEY_FIRST}, {@link #KEY_SECOND}; the chat line's [answers] cover
 * the rest). After voting the buttons give way to «Ви проголосували: …» and the panel stays small until the end,
 * shows the result for a few seconds, and goes. × hides it (in a GUI); the vote can still be cast from chat.
 */
public final class PollOverlay {

    private static final int WIDTH = 136;
    private static final int PAD = 6;
    private static final int BUTTON = 14;
    private static final int MARGIN = 4;
    private static final long RESULT_MILLIS = 6000;
    private static final int[] ANSWER_COLORS = {0xFF4E8A3A, 0xFFA83A2E, 0xFF3A6EA5, 0xFFB58A1E};

    public static KeyMapping KEY_FIRST;
    public static KeyMapping KEY_SECOND;

    @Nullable
    private static HubPayloads.PollData data;
    private static long endsAt;
    private static long goneAt;
    private static boolean hidden;
    /** The panel's place when last drawn (clicks are tested against it). */
    private static int panelX, panelY, panelHeight, buttonsY;

    private PollOverlay() {
    }

    public static void initialize() {
        KEY_FIRST = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.eden_ld.poll_first",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "category.eden_ld.main"));
        KEY_SECOND = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.eden_ld.poll_second",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.eden_ld.main"));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.PollData.TYPE,
                (payload, context) -> onData(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> data = null);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean first = false;
            boolean second = false;
            while (KEY_FIRST.consumeClick()) {
                first = true;
            }
            while (KEY_SECOND.consumeClick()) {
                second = true;
            }
            if (client.screen == null && votable()) {
                if (first) {
                    vote(0);
                } else if (second) {
                    vote(1);
                }
            }
        });
        HudRenderCallback.EVENT.register((graphics, tick) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen == null && !minecraft.options.hideGui) {
                render(graphics, graphics.guiWidth(), graphics.guiHeight(), -1, -1, false);
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenEvents.afterRender(screen).register((shown, graphics, mouseX, mouseY, delta) ->
                    render(graphics, shown.width, shown.height, mouseX, mouseY, true));
            ScreenMouseEvents.allowMouseClick(screen).register((shown, mouseX, mouseY, button) ->
                    !click(mouseX, mouseY, button));
        });
    }

    private static void onData(HubPayloads.PollData payload) {
        boolean fresh = data == null || data.poll() != payload.poll();
        if (fresh) {
            hidden = false;
            if (!payload.ended()) {
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.2f, 0.6f));
            }
        }
        data = payload;
        long now = System.currentTimeMillis();
        endsAt = now + payload.secondsLeft() * 1000L;
        goneAt = payload.ended() ? now + RESULT_MILLIS : Long.MAX_VALUE;
    }

    /** The poll to draw, or null: none, hidden, gone after its result, or its end missed (a lost packet). */
    @Nullable
    private static HubPayloads.PollData shown() {
        HubPayloads.PollData poll = data;
        if (poll == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now >= goneAt || !poll.ended() && now > endsAt + 5000) {
            data = null;
            return null;
        }
        return hidden ? null : poll;
    }

    private static boolean votable() {
        HubPayloads.PollData poll = shown();
        return poll != null && !poll.ended() && poll.myVote() < 0;
    }

    private static void vote(int answer) {
        HubPayloads.PollData poll = data;
        if (poll == null || poll.ended() || answer < 0 || answer >= poll.answers().size()) {
            return;
        }
        ClientPlayNetworking.send(new HubPayloads.PollVote(poll.poll(), answer));
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    // ---------------------------------------------------------------- drawing

    private static void render(GuiGraphics graphics, int screenWidth, int screenHeight, int mouseX, int mouseY,
                               boolean interactive) {
        HubPayloads.PollData poll = shown();
        if (poll == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int inner = WIDTH - PAD * 2;
        List<FormattedCharSequence> title = font.split(Component.literal(poll.question()), inner - (interactive ? 10 : 0));
        boolean voting = !poll.ended() && poll.myVote() < 0;
        List<FormattedCharSequence> status = voting ? List.of() : font.split(statusLine(poll), inner);

        int height = PAD + title.size() * 10 + 3 + 6 + 3 + 2 + 5
                + (voting ? poll.answers().size() * (BUTTON + 2) - 2 : status.size() * 10) + PAD;
        int x = screenWidth - WIDTH - MARGIN;
        int y = Math.max(MARGIN, (screenHeight - height) / 2);
        panelX = x;
        panelY = y;
        panelHeight = height;

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        // The frame: a dark edge, a light inner line, the parchment.
        graphics.fill(x, y, x + WIDTH, y + height, EdenParchment.HAIRLINE_HI);
        graphics.fill(x + 1, y + 1, x + WIDTH - 1, y + height - 1, EdenParchment.HAIRLINE);
        graphics.fill(x + 2, y + 2, x + WIDTH - 2, y + height - 2, EdenParchment.PANEL);

        int cy = y + PAD;
        for (FormattedCharSequence line : title) {
            graphics.drawString(font, line, x + PAD, cy, EdenParchment.INK, false);
            cy += 10;
        }
        if (interactive) {
            boolean over = mouseX >= x + WIDTH - 13 && mouseX < x + WIDTH - 3 && mouseY >= y + 3 && mouseY < y + 13;
            graphics.drawString(font, "×", x + WIDTH - 11, y + PAD - 1, over ? EdenParchment.DANGER : EdenParchment.INK_DIM, false);
        }
        cy += 3;

        // The votes so far: one part per answer, the track for those who haven't voted.
        int barX = x + PAD;
        graphics.fill(barX, cy, barX + inner, cy + 6, EdenParchment.HAIRLINE);
        graphics.fill(barX + 1, cy + 1, barX + inner - 1, cy + 5, EdenParchment.BAR_TRACK);
        int voted = 0;
        for (int count : poll.counts()) {
            voted += count;
        }
        int total = Math.max(1, Math.max(poll.voters(), voted));
        int usable = inner - 2;
        int drawn = 0;
        int cumulative = 0;
        for (int i = 0; i < poll.counts().size(); i++) {
            cumulative += poll.counts().get(i);
            int end = Math.round(usable * (cumulative / (float) total));
            if (end > drawn) {
                graphics.fill(barX + 1 + drawn, cy + 1, barX + 1 + end, cy + 5, ANSWER_COLORS[i % ANSWER_COLORS.length]);
                drawn = end;
            }
        }
        cy += 6 + 3;

        // The time left, running out to the left.
        float left = poll.ended() ? 0 : Math.max(0f, Math.min(1f,
                (endsAt - System.currentTimeMillis()) / (poll.totalSeconds() * 1000f)));
        graphics.fill(barX, cy, barX + inner, cy + 2, EdenParchment.BAR_TRACK);
        graphics.fill(barX, cy, barX + Math.round(inner * left), cy + 2, left < 0.2f ? EdenParchment.DANGER : EdenParchment.ACCENT);
        cy += 2 + 5;

        buttonsY = cy;
        if (voting) {
            for (int i = 0; i < poll.answers().size(); i++) {
                int top = cy + i * (BUTTON + 2);
                boolean over = interactive && mouseX >= barX && mouseX < barX + inner && mouseY >= top && mouseY < top + BUTTON;
                graphics.fill(barX, top, barX + inner, top + BUTTON, EdenParchment.HAIRLINE);
                graphics.fill(barX + 1, top + 1, barX + inner - 1, top + BUTTON - 1,
                        over ? EdenParchment.PANEL_2 : EdenParchment.ROW_BG);
                graphics.fill(barX + 1, top + 1, barX + 4, top + BUTTON - 1, ANSWER_COLORS[i % ANSWER_COLORS.length]);
                Component label = poll.answers().get(i);
                if (!interactive && i < 2) {
                    // In the game there's no cursor: the answer's key.
                    label = Component.literal("[").append((i == 0 ? KEY_FIRST : KEY_SECOND).getTranslatedKeyMessage())
                            .append("] ").append(label);
                }
                String text = font.plainSubstrByWidth(label.getString(), inner - 12);
                graphics.drawString(font, text, barX + 8, top + 3, over ? EdenParchment.BUTTON_TEXT_HOVER
                        : EdenParchment.BUTTON_TEXT, false);
            }
        } else {
            for (FormattedCharSequence line : status) {
                graphics.drawString(font, line, barX, cy, poll.ended() ? EdenParchment.INK : EdenParchment.INK_MID, false);
                cy += 10;
            }
        }
        graphics.pose().popPose();
    }

    /** «Ви проголосували: Так.» while it runs; the result (the leading answer and its votes) once it ended. */
    private static Component statusLine(HubPayloads.PollData poll) {
        if (!poll.ended()) {
            return Component.translatable("gui.eden_ld.poll.voted", poll.answers().get(poll.myVote()));
        }
        int best = -1;
        boolean tie = false;
        for (int i = 0; i < poll.counts().size(); i++) {
            if (best < 0 || poll.counts().get(i) > poll.counts().get(best)) {
                best = i;
                tie = false;
            } else if (poll.counts().get(i).equals(poll.counts().get(best))) {
                tie = true;
            }
        }
        if (best < 0 || poll.counts().get(best) == 0) {
            return Component.translatable("gui.eden_ld.poll.ended_none");
        }
        return tie ? Component.translatable("gui.eden_ld.poll.ended_tie")
                : Component.translatable("gui.eden_ld.poll.ended", poll.answers().get(best), poll.counts().get(best));
    }

    // ---------------------------------------------------------------- clicks (in a GUI)

    /** True when the click was the panel's (it never reaches the screen under it). */
    private static boolean click(double mouseX, double mouseY, int button) {
        HubPayloads.PollData poll = shown();
        if (poll == null || mouseX < panelX || mouseX >= panelX + WIDTH || mouseY < panelY || mouseY >= panelY + panelHeight) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        if (mouseX >= panelX + WIDTH - 13 && mouseY < panelY + 13) {
            hidden = true;
            return true;
        }
        if (!poll.ended() && poll.myVote() < 0) {
            int inner = WIDTH - PAD * 2;
            if (mouseX >= panelX + PAD && mouseX < panelX + PAD + inner && mouseY >= buttonsY) {
                int index = (int) ((mouseY - buttonsY) / (BUTTON + 2));
                boolean onButton = (mouseY - buttonsY) - index * (BUTTON + 2) < BUTTON;
                if (onButton && index < poll.answers().size()) {
                    vote(index);
                }
            }
        }
        return true;
    }
}
