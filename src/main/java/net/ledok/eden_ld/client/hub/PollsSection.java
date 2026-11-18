package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.hub.moderation.AdminTools;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.GOOD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.small;
import static net.ledok.eden_ld.client.hub.ModerationScreen.text;

/**
 * The Moderation window's <b>Polls</b> section ({@code eden_ld.admin.poll}): a Discord-style form to start a poll for
 * every online player (the question, one field per answer, the duration), stop the running one, and see the running
 * or last poll's votes with who voted what (players only ever see the counts). {@code hub.poll.Polls}.
 */
final class PollsSection {

    /** The panel's answer colors, in order ({@code PollOverlay}): each answer's dot matches its part of the vote bar. */
    private static final int[] ANSWER_COLORS = {0xFF4E8A3A, 0xFFA83A2E, 0xFF3A6EA5, 0xFFB58A1E};
    private static final int MAX_QUESTION = 120;
    private static final int MAX_ANSWER = 24;

    private final ModerationScreen screen;
    private final float width;
    @Nullable
    private CompoundTag data;
    /** The form as typed (kept while the window is open). */
    private String question = "";
    private final java.util.List<String> answers = new java.util.ArrayList<>();
    private int seconds = 60;

    PollsSection(ModerationScreen screen, float width) {
        this.screen = screen;
        this.width = width;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.moderation.polls." + key, args);
    }

    void request() {
        PlayerTabs.request(AdminTools.POLL, Util.NIL_UUID);
    }

    /** Returns whether it was this section's data. */
    boolean onData(String area, CompoundTag tag) {
        if (!area.equals(AdminTools.POLL)) {
            return false;
        }
        data = tag;
        return true;
    }

    /**
     * The new-poll form, Discord-style: the question with a counter, one field per answer (its color dot, ✕ to remove),
     * «+ Додати відповідь» up to the limit, the duration, and «Опублікувати». It starts with Так / Ні.
     */
    private void buildForm(Flex main) {
        int max = Math.max(2, data.getInt("max_answers"));
        if (answers.isEmpty()) {
            answers.add(Component.translatable("gui.eden_ld.poll.yes").getString());
            answers.add(Component.translatable("gui.eden_ld.poll.no").getString());
        }
        main.item(text(tr("question"), TEXT));
        net.ledok.vectorlib.client.canvas.widget.Label counter = net.ledok.vectorlib.client.canvas.widget.Label.of(50,
                Component.literal(question.length() + "/" + MAX_QUESTION));
        counter.color(DIM);
        counter.align(net.ledok.vectorlib.client.canvas.TextNode.Align.RIGHT);
        TextField ask = new TextField(width - 56, question, value -> {
            question = value;
            counter.text(Component.literal(value.length() + "/" + MAX_QUESTION));
        });
        ask.size(width - 56, 16);
        ask.hint(tr("question_hint").getString());
        ask.maxLength(MAX_QUESTION);
        Flex questionRow = Flex.row().gap(6).alignItems(Align.CENTER);
        questionRow.item(ask);
        questionRow.item(counter);
        main.item(questionRow);

        main.item(text(tr("answers"), TEXT));
        for (int i = 0; i < answers.size(); i++) {
            int index = i;
            Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
            row.item(text(Component.literal("●"), ANSWER_COLORS[i % ANSWER_COLORS.length]));
            TextField field = new TextField(width - 40, answers.get(i), value -> answers.set(index, value.replace("|", "")));
            field.size(width - 40, 16);
            field.hint(tr("answer_hint", i + 1).getString());
            field.maxLength(MAX_ANSWER);
            row.item(field);
            var remove = ModerationScreen.icon("✕", () -> {
                answers.remove(index);
                screen.rebuild();
            });
            remove.tooltip(tr("remove_answer"));
            // A poll needs two answers.
            remove.enabled(answers.size() > 2);
            row.item(remove);
            main.item(row);
        }
        Flex addRow = Flex.row().gap(6).alignItems(Align.CENTER);
        addRow.sizing(Sizing.fill(), Sizing.content());
        addRow.item(PlayerTabs.spacer());
        var add = small("gui.eden_ld.moderation.polls.add_answer", 110, () -> {
            answers.add("");
            screen.rebuild();
        });
        add.enabled(answers.size() < max);
        addRow.item(add);
        main.item(addRow);

        Flex bottom = Flex.row().gap(6).alignItems(Align.CENTER);
        bottom.sizing(Sizing.fill(), Sizing.content());
        bottom.item(text(tr("duration"), TEXT));
        // Any length, in seconds (the server's range); its minutes next to it ("= 1 хв 30 с").
        int min = data.getInt("min_seconds");
        int maxSeconds = data.getInt("max_seconds");
        net.ledok.vectorlib.client.canvas.widget.Label readable = net.ledok.vectorlib.client.canvas.widget.Label.of(70,
                readable(seconds));
        readable.color(DIM);
        net.ledok.vectorlib.client.canvas.widget.NumberField length =
                new net.ledok.vectorlib.client.canvas.widget.NumberField(50, seconds, value -> {
                    int clamped = (int) Math.max(min, Math.min(maxSeconds, value));
                    readable.text(readable(clamped));
                });
        length.range(min, maxSeconds).step(10);
        length.onCommit(value -> seconds = (int) value);
        length.tooltip(tr("duration_tip", min, maxSeconds));
        bottom.item(length);
        bottom.item(text(tr("seconds_unit"), DIM));
        bottom.item(readable);
        bottom.item(PlayerTabs.spacer());
        bottom.item(small("gui.eden_ld.moderation.polls.start", 90, () -> {
            CompoundTag args = new CompoundTag();
            args.putString("question", question);
            StringBuilder joined = new StringBuilder();
            for (String answer : answers) {
                if (!answer.isBlank()) {
                    joined.append(joined.isEmpty() ? "" : " | ").append(answer.strip());
                }
            }
            args.putString("answers", joined.toString());
            seconds = (int) length.value();
            args.putInt("seconds", seconds);
            PlayerTabs.action(AdminTools.POLL, Util.NIL_UUID, "start", args);
        }));
        main.item(bottom);
    }

    /** "= 1 хв 30 с" for 90; "" under a minute. */
    private static Component readable(int seconds) {
        if (seconds < 60) {
            return Component.empty();
        }
        int minutes = seconds / 60;
        int rest = seconds % 60;
        return rest == 0 ? tr("minutes", minutes) : tr("minutes_seconds", minutes, rest);
    }

    void build(Flex main) {
        Flex header = Flex.row().gap(4).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        header.item(text(Component.translatable("gui.eden_ld.moderation.section.polls"), TEXT));
        header.item(PlayerTabs.spacer());
        header.item(small("gui.eden_ld.moderation.polls.refresh", 70, this::request));
        main.item(header);
        if (data == null) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        CompoundTag poll = data.contains("poll", Tag.TAG_COMPOUND) ? data.getCompound("poll") : null;
        boolean running = poll != null && !poll.getBoolean("ended");

        if (!running) {
            buildForm(main);
            main.item(ModerationScreen.gap(6));
        }

        if (poll == null) {
            main.item(text(tr("none"), DIM, (int) width));
            return;
        }
        int textWidth = (int) width - 8;
        Flex results = Flex.column().gap(4);
        results.item(text(running ? tr("running", poll.getInt("seconds_left"), poll.getString("by"))
                : tr("last", poll.getString("by")), running ? GOOD : DIM, textWidth));
        results.item(text(Component.literal(poll.getString("question")), TEXT, textWidth));
        ListTag list = poll.getList("answers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag answer = list.getCompound(i);
            ListTag voters = answer.getList("voters", Tag.TAG_STRING);
            StringBuilder names = new StringBuilder();
            for (int j = 0; j < voters.size(); j++) {
                names.append(j == 0 ? "" : ", ").append(voters.getString(j));
            }
            results.item(text(Component.empty().append(PlayerTabs.json(answer.getString("text")))
                    .append(": " + answer.getInt("count")), TEXT, textWidth));
            if (!names.isEmpty()) {
                results.item(text(Component.literal(names.toString()), DIM, textWidth));
            }
        }
        ScrollPanel scroll = HubScroll.panel("mod/polls/results", width, 72, results);
        scroll.sizing(Sizing.fill(), Sizing.expand());
        main.item(scroll);
        if (running) {
            Flex row = Flex.row().gap(6).alignItems(Align.CENTER);
            row.sizing(Sizing.fill(), Sizing.content());
            row.item(PlayerTabs.spacer());
            row.item(small("gui.eden_ld.moderation.polls.stop", 80,
                    () -> PlayerTabs.action(AdminTools.POLL, Util.NIL_UUID, "stop", new CompoundTag())));
            main.item(row);
        }
    }
}
