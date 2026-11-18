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

import java.util.ArrayList;
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

    private static final int MIN_WIDTH = 136;
    private static final int MAX_WIDTH = 180;
    private static final int PAD = 6;
    private static final int LINE = 10;
    private static final int BUTTON_PAD = 4;
    private static final int BARS_HEIGHT = 3 + 6 + 3 + 2 + 5;
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
            } else if (client.screen == null && (first || second) && dismissable()) {
                // Voted (or over): the same keys hide the panel; the result still comes in chat.
                hidden = true;
            }
        });
        HudRenderCallback.EVENT.register((graphics, tick) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen == null && !minecraft.options.hideGui) {
                render(graphics, graphics.guiWidth(), graphics.guiHeight(), -1, -1, false);
            }
        });
        // Clicks are claimed in BEFORE_INIT: registered ahead of other mods' per-screen listeners (EMI's item list
        // took the click and opened a recipe), and Fabric fires allowMouseClick before the screen's own handling.
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) ->
                ScreenMouseEvents.allowMouseClick(screen).register((shown, mouseX, mouseY, button) ->
                        !click(shown.width, shown.height, mouseX, mouseY, button)));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.afterRender(screen).register((shown, graphics, mouseX, mouseY, delta) ->
                        render(graphics, shown.width, shown.height, mouseX, mouseY, true)));
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

    /** Voted, or over: the panel can be hidden (× in a GUI, a vote key in the game). */
    private static boolean dismissable() {
        HubPayloads.PollData poll = shown();
        return poll != null && (poll.ended() || poll.myVote() >= 0);
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

    // ---------------------------------------------------------------- layout

    /**
     * Where the panel is on a screen of this size: {x, y, width, height}, or null when no poll shows. The same for
     * drawing, clicks and EMI's exclusion area ({@code EdenEmiPlugin}), so EMI's item list keeps clear of it.
     */
    @Nullable
    public static int[] bounds(int screenWidth, int screenHeight) {
        HubPayloads.PollData poll = shown();
        if (poll == null) {
            return null;
        }
        Layout layout = layout(poll, screenWidth, screenHeight, true);
        return new int[]{layout.x(), layout.y(), (int) Math.ceil(layout.width() * layout.scale()),
                (int) Math.ceil(layout.height() * layout.scale())};
    }

    private record Answer(List<FormattedCharSequence> lines) {
        int height() {
            return lines.size() * LINE + BUTTON_PAD;
        }
    }

    private record Layout(int x, int y, int width, int height, float scale, List<FormattedCharSequence> title,
                          List<FormattedCharSequence> status, List<Answer> answers) {
        int bodyTop() {
            return PAD + title.size() * LINE + BARS_HEIGHT;
        }

        boolean closeAt(double x, double y) {
            return x >= width - 16 && x < width - 2 && y >= 2 && y < 16;
        }
    }

    /** Draw, hit-test and reserve the same rectangle, including any multi-line answers. */
    private static Layout layout(HubPayloads.PollData poll, int screenWidth, int screenHeight, boolean interactive) {
        Font font = Minecraft.getInstance().font;
        int width = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, screenWidth / 3));
        int inner = width - PAD * 2;
        List<FormattedCharSequence> title = font.split(Component.literal(poll.question()), inner - (interactive ? 12 : 0));
        boolean voting = !poll.ended() && poll.myVote() < 0;
        List<FormattedCharSequence> status = voting ? List.of() : font.split(statusLine(poll, interactive), inner);
        List<Answer> answers = new ArrayList<>();
        int body = status.size() * LINE;
        if (voting) {
            for (int i = 0; i < poll.answers().size(); i++) {
                Component label = poll.answers().get(i);
                if (!interactive && i < 2) {
                    label = Component.literal("[").append((i == 0 ? KEY_FIRST : KEY_SECOND).getTranslatedKeyMessage())
                            .append("] ").append(label);
                }
                Answer answer = new Answer(font.split(label, inner - 12));
                answers.add(answer);
                body += answer.height() + (i == 0 ? 0 : 2);
            }
        }
        int height = PAD + title.size() * LINE + BARS_HEIGHT + body + PAD;
        float scale = Math.min(1f, Math.min(Math.max(1, screenWidth - 2 * MARGIN) / (float) width,
                Math.max(1, screenHeight - 2 * MARGIN) / (float) height));
        int x = screenWidth - MARGIN - (int) Math.ceil(width * scale);
        int y = (screenHeight - (int) Math.ceil(height * scale)) / 2;
        return new Layout(x, y, width, height, scale, title, status, answers);
    }

    // ---------------------------------------------------------------- drawing

    private static void render(GuiGraphics graphics, int screenWidth, int screenHeight, int mouseX, int mouseY,
                               boolean interactive) {
        HubPayloads.PollData poll = shown();
        if (poll == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Layout layout = layout(poll, screenWidth, screenHeight, interactive);
        int width = layout.width();
        int height = layout.height();
        int inner = width - PAD * 2;
        float mx = (mouseX - layout.x()) / layout.scale();
        float my = (mouseY - layout.y()) / layout.scale();

        graphics.pose().pushPose();
        graphics.pose().translate(layout.x(), layout.y(), 400);
        graphics.pose().scale(layout.scale(), layout.scale(), 1);
        graphics.fill(0, 0, width, height, EdenParchment.HAIRLINE_HI);
        graphics.fill(1, 1, width - 1, height - 1, EdenParchment.HAIRLINE);
        graphics.fill(2, 2, width - 2, height - 2, EdenParchment.PANEL);

        int cy = PAD;
        for (FormattedCharSequence line : layout.title()) {
            graphics.drawString(font, line, PAD, cy, EdenParchment.INK, false);
            cy += LINE;
        }
        if (interactive) {
            boolean over = layout.closeAt(mx, my);
            graphics.drawString(font, "×", width - 12, PAD - 1, over ? EdenParchment.DANGER : EdenParchment.INK_DIM, false);
        }
        cy += 3;

        // The votes so far: one part per answer, the track for those who haven't voted.
        int barX = PAD;
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

        for (int i = 0; i < layout.answers().size(); i++) {
            Answer answer = layout.answers().get(i);
            int bottom = cy + answer.height();
            boolean over = interactive && mx >= barX && mx < barX + inner && my >= cy && my < bottom;
            graphics.fill(barX, cy, barX + inner, bottom, EdenParchment.HAIRLINE);
            graphics.fill(barX + 1, cy + 1, barX + inner - 1, bottom - 1,
                    over ? EdenParchment.PANEL_2 : EdenParchment.ROW_BG);
            graphics.fill(barX + 1, cy + 1, barX + 4, bottom - 1, ANSWER_COLORS[i % ANSWER_COLORS.length]);
            int labelY = cy + BUTTON_PAD / 2 + 1;
            for (FormattedCharSequence line : answer.lines()) {
                graphics.drawString(font, line, barX + 8, labelY,
                        over ? EdenParchment.BUTTON_TEXT_HOVER : EdenParchment.BUTTON_TEXT, false);
                labelY += LINE;
            }
            cy = bottom + 2;
        }
        for (FormattedCharSequence line : layout.status()) {
            graphics.drawString(font, line, barX, cy, poll.ended() ? EdenParchment.INK : EdenParchment.INK_MID, false);
            cy += LINE;
        }
        graphics.pose().popPose();
    }

    /** «Ви проголосували: Так.» while it runs; the result (the leading answer and its votes) once it ended. */
    private static Component statusLine(HubPayloads.PollData poll, boolean interactive) {
        // In the game there's no cursor for ×: say which key hides it.
        Component hint = interactive ? Component.empty() : Component.literal("\n").append(
                Component.translatable("gui.eden_ld.poll.hide_hint", KEY_FIRST.getTranslatedKeyMessage()));
        if (!poll.ended()) {
            return Component.translatable("gui.eden_ld.poll.voted", poll.answers().get(poll.myVote())).append(hint);
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
            return Component.translatable("gui.eden_ld.poll.ended_none").append(hint);
        }
        return (tie ? Component.translatable("gui.eden_ld.poll.ended_tie")
                : Component.translatable("gui.eden_ld.poll.ended", poll.answers().get(best), poll.counts().get(best)))
                .append(hint);
    }

    // ---------------------------------------------------------------- clicks (in a GUI)

    /** True when the click was the panel's (it never reaches the screen under it, nor EMI). */
    private static boolean click(int screenWidth, int screenHeight, double mouseX, double mouseY, int button) {
        HubPayloads.PollData poll = shown();
        if (poll == null) {
            return false;
        }
        Layout layout = layout(poll, screenWidth, screenHeight, true);
        double x = (mouseX - layout.x()) / layout.scale();
        double y = (mouseY - layout.y()) / layout.scale();
        if (x < 0 || x >= layout.width() || y < 0 || y >= layout.height()) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        if (layout.closeAt(x, y)) {
            hidden = true;
            return true;
        }
        if (x >= PAD && x < layout.width() - PAD) {
            int top = layout.bodyTop();
            for (int i = 0; i < layout.answers().size(); i++) {
                int bottom = top + layout.answers().get(i).height();
                if (y >= top && y < bottom) {
                    vote(i);
                    return true;
                }
                top = bottom + 2;
            }
        }
        return true;
    }
}
