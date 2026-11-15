package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.hub.moderation.AdminTools;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.NumberField;
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
 * The Moderation window's <b>Polls</b> section ({@code eden_ld.admin.poll}): start a poll for every online player (a
 * question, 2-4 answers as "Так | Ні" — empty means Так / Ні — and its length), stop the running one, and see the
 * running or last poll's votes with who voted what (players only ever see the counts). {@code hub.poll.Polls}.
 */
final class PollsSection {

    private final float width;
    @Nullable
    private CompoundTag data;
    private String question = "";
    private String answers = "";
    private long seconds = 60;

    PollsSection(float width) {
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
            main.item(text(tr("new"), TEXT));
            TextField ask = new TextField(width, question, value -> question = value);
            ask.size(width, 16);
            ask.hint(tr("question_hint").getString());
            ask.maxLength(120);
            main.item(ask);
            TextField choices = new TextField(width, answers, value -> answers = value);
            choices.size(width, 16);
            choices.hint(tr("answers_hint", data.getInt("max_answers")).getString());
            choices.maxLength(120);
            main.item(choices);
            Flex row = Flex.row().gap(6).alignItems(Align.CENTER);
            row.sizing(Sizing.fill(), Sizing.content());
            row.item(text(tr("seconds"), DIM));
            NumberField length = new NumberField(50, seconds, value -> seconds = value);
            length.range(data.getInt("min_seconds"), data.getInt("max_seconds")).step(10);
            row.item(length);
            row.item(PlayerTabs.spacer());
            row.item(small("gui.eden_ld.moderation.polls.start", 80, () -> {
                CompoundTag args = new CompoundTag();
                args.putString("question", question);
                args.putString("answers", answers);
                args.putInt("seconds", (int) length.value());
                PlayerTabs.action(AdminTools.POLL, Util.NIL_UUID, "start", args);
            }));
            main.item(row);
            main.item(ModerationScreen.gap(6));
        }

        if (poll == null) {
            main.item(text(tr("none"), DIM, (int) width));
            return;
        }
        main.item(text(running ? tr("running", poll.getInt("seconds_left"), poll.getString("by"))
                : tr("last", poll.getString("by")), running ? GOOD : DIM, (int) width));
        main.item(text(Component.literal(poll.getString("question")), TEXT, (int) width));
        ListTag list = poll.getList("answers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag answer = list.getCompound(i);
            ListTag voters = answer.getList("voters", Tag.TAG_STRING);
            StringBuilder names = new StringBuilder();
            for (int j = 0; j < voters.size(); j++) {
                names.append(j == 0 ? "" : ", ").append(voters.getString(j));
            }
            main.item(text(Component.empty().append(PlayerTabs.json(answer.getString("text")))
                    .append(": " + answer.getInt("count")), TEXT, (int) width));
            if (!names.isEmpty()) {
                main.item(text(Component.literal(names.toString()), DIM, (int) width));
            }
        }
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
