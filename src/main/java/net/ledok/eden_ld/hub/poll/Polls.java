package net.ledok.eden_ld.hub.poll;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.command.EdenPermissions;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.tutorial.Tutorial;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff polls ({@code Issues/2026-11-15-eden-polls-IDEA.md}): a question, 2-{@link #MAX_ANSWERS} answers and a
 * timeout, shown to every online player as a panel on the right of their screen (over the game and over GUIs) until it
 * ends. Started with {@code /poll start} or Moderation → Polls ({@code eden_ld.admin.poll}); one at a time. Players
 * answer with the panel's buttons, the two vote keys, or the chat line's [answers] ({@code /poll vote}); a vote can be
 * changed until the end. Everyone sees the live counts and the final result in chat; who voted what is for staff only
 * (the Moderation section and the server log). A poll never acts on its result. Players in the tutorial aren't asked.
 * In memory only: a restart ends it. Server thread.
 */
public final class Polls {

    public static final int MAX_ANSWERS = 4;
    public static final int MIN_SECONDS = 10, MAX_SECONDS = 600, DEFAULT_SECONDS = 60;
    private static final int MAX_QUESTION = 120, MAX_ANSWER = 24;

    private static final class Poll {
        final long id;
        final String question;
        final List<Component> answers;
        final int totalSeconds;
        final long endsAt;
        final String startedBy;
        /** Who voted what, in voting order. */
        final Map<UUID, Integer> votes = new LinkedHashMap<>();
        final Map<UUID, String> names = new LinkedHashMap<>();

        Poll(long id, String question, List<Component> answers, int totalSeconds, long endsAt, String startedBy) {
            this.id = id;
            this.question = question;
            this.answers = answers;
            this.totalSeconds = totalSeconds;
            this.endsAt = endsAt;
            this.startedBy = startedBy;
        }
    }

    @Nullable
    private static Poll current;
    private static long nextId = System.currentTimeMillis() / 1000;
    private static boolean dirty;
    private static int ticks;

    private Polls() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.PollVote.TYPE,
                (payload, context) -> context.server().execute(() -> vote(context.player(), payload.poll(), payload.answer())));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            if (current != null && !handler.getPlayer().hasDisconnected()) {
                ask(handler.getPlayer(), current);
            }
        }));
        ServerTickEvents.END_SERVER_TICK.register(Polls::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> current = null);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> register(dispatcher));
    }

    public static boolean mayStart(ServerPlayer player) {
        return LuckPermsCompat.hasPermission(player, EdenPermissions.POLL);
    }

    // ---------------------------------------------------------------- commands

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("poll")
                // /poll start 60 "Перезапустити сервер зараз?" Так | Ні   (answers optional: Так / Ні)
                .then(Commands.literal("start").requires(EdenPermissions.require(EdenPermissions.POLL))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS))
                                .then(Commands.argument("question", StringArgumentType.string())
                                        .executes(context -> start(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "seconds"),
                                                StringArgumentType.getString(context, "question"), ""))
                                        .then(Commands.argument("answers", StringArgumentType.greedyString())
                                                .executes(context -> start(context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "seconds"),
                                                        StringArgumentType.getString(context, "question"),
                                                        StringArgumentType.getString(context, "answers")))))))
                .then(Commands.literal("stop").requires(EdenPermissions.require(EdenPermissions.POLL))
                        .executes(context -> {
                            Component result = stop(context.getSource().getServer(), context.getSource().getTextName());
                            context.getSource().sendSuccess(() -> result, false);
                            return 1;
                        }))
                .then(Commands.literal("vote")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .then(Commands.argument("answer", IntegerArgumentType.integer(1, MAX_ANSWERS))
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayer();
                                            if (player != null) {
                                                vote(player, LongArgumentType.getLong(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "answer") - 1);
                                            }
                                            return 1;
                                        })))));
    }

    private static int start(CommandSourceStack source, int seconds, String question, String answers) {
        Component refused = start(source.getServer(), source.getTextName(), question, splitAnswers(answers), seconds);
        if (refused != null) {
            source.sendFailure(refused);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("message.eden_ld.poll.started"), false);
        return 1;
    }

    /** "Так | Ні | Пізніше", or space-separated words when there's no "|". */
    public static List<String> splitAnswers(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.contains("|") ? text.split("\\|") : text.trim().split("\\s+")) {
            String answer = part.strip();
            if (!answer.isEmpty()) {
                out.add(answer.length() > MAX_ANSWER ? answer.substring(0, MAX_ANSWER) : answer);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- running a poll

    /** Starts a poll (no answers: Так / Ні). Null when started, else why not. */
    @Nullable
    public static Component start(MinecraftServer server, String by, String question, List<String> answers, int seconds) {
        if (current != null) {
            return Component.translatable("message.eden_ld.poll.running");
        }
        String asked = question == null ? "" : question.strip();
        if (asked.isEmpty()) {
            return Component.translatable("message.eden_ld.poll.no_question");
        }
        if (asked.length() > MAX_QUESTION) {
            asked = asked.substring(0, MAX_QUESTION);
        }
        if (answers.size() == 1 || answers.size() > MAX_ANSWERS) {
            return Component.translatable("message.eden_ld.poll.bad_answers", MAX_ANSWERS);
        }
        List<Component> choices = new ArrayList<>();
        if (answers.isEmpty()) {
            choices.add(Component.translatableWithFallback("gui.eden_ld.poll.yes", "Yes"));
            choices.add(Component.translatableWithFallback("gui.eden_ld.poll.no", "No"));
        } else {
            answers.forEach(answer -> choices.add(Component.literal(answer)));
        }
        int length = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
        Poll poll = new Poll(nextId++, asked, List.copyOf(choices), length, System.currentTimeMillis() + length * 1000L, by);
        current = poll;
        EdenLD.LOGGER.info("[Polls] {} started poll #{} ({} s): {} {}", by, poll.id, length, asked,
                choices.stream().map(Component::getString).toList());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ask(player, poll);
        }
        return null;
    }

    /** Whether the player is asked: not while in the tutorial. */
    private static boolean eligible(ServerPlayer player) {
        return !Tutorial.isActive(player);
    }

    /** The panel (clients that know it) and the chat line with clickable answers (everyone). */
    private static void ask(ServerPlayer player, Poll poll) {
        if (!eligible(player)) {
            return;
        }
        send(player, poll, false);
        MutableComponent line = Component.translatableWithFallback("message.eden_ld.poll.asked", "Poll: %s", poll.question)
                .withStyle(ChatFormatting.GOLD);
        for (int i = 0; i < poll.answers.size(); i++) {
            int number = i + 1;
            Component answer = poll.answers.get(i);
            line.append(" ").append(Component.literal("[").append(answer).append("]")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/poll vote " + poll.id + " " + number))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatableWithFallback("message.eden_ld.poll.vote_hover", "Vote: %s",
                                            answer)))));
        }
        player.sendSystemMessage(line);
    }

    private static void vote(ServerPlayer player, long id, int answer) {
        Poll poll = current;
        if (poll == null || poll.id != id) {
            player.displayClientMessage(Component.translatableWithFallback("message.eden_ld.poll.over",
                    "That poll is over."), true);
            return;
        }
        if (answer < 0 || answer >= poll.answers.size() || !eligible(player)) {
            return;
        }
        Integer before = poll.votes.put(player.getUUID(), answer);
        poll.names.put(player.getUUID(), player.getGameProfile().getName());
        if (before == null || before != answer) {
            dirty = true;
            // The voter's own panel at once; everyone's counts follow within half a second.
            send(player, poll, false);
            if (!ServerPlayNetworking.canSend(player, HubPayloads.PollData.TYPE)) {
                player.displayClientMessage(Component.translatableWithFallback("gui.eden_ld.poll.voted",
                        "You voted: %s.", poll.answers.get(answer)), true);
            }
        }
    }

    private static void tick(MinecraftServer server) {
        Poll poll = current;
        if (poll == null) {
            return;
        }
        if (System.currentTimeMillis() >= poll.endsAt) {
            finish(server, poll, null);
            return;
        }
        if (++ticks % 10 == 0 && dirty) {
            dirty = false;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (eligible(player)) {
                    send(player, poll, false);
                }
            }
        }
    }

    /** Ends the running poll early; what to tell who asked. */
    public static Component stop(MinecraftServer server, String by) {
        Poll poll = current;
        if (poll == null) {
            return Component.translatable("message.eden_ld.poll.none");
        }
        finish(server, poll, by);
        return Component.translatable("message.eden_ld.poll.stopped");
    }

    private static void finish(MinecraftServer server, Poll poll, @Nullable String stoppedBy) {
        current = null;
        dirty = false;
        int[] counts = counts(poll);
        int asked = 0;
        MutableComponent result = Component.translatableWithFallback("message.eden_ld.poll.result", "Poll over: %s",
                poll.question).withStyle(ChatFormatting.GOLD);
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < poll.answers.size(); i++) {
            result.append(Component.literal(i == 0 ? " — " : ", ").withStyle(ChatFormatting.GRAY))
                    .append(poll.answers.get(i).copy().withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(": " + counts[i]).withStyle(ChatFormatting.WHITE));
            log.append(i == 0 ? "" : "; ").append(poll.answers.get(i).getString()).append(": ").append(counts[i])
                    .append(' ').append(voters(poll, i));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!eligible(player)) {
                continue;
            }
            asked++;
            send(player, poll, true);
        }
        int silent = Math.max(0, asked - (int) server.getPlayerList().getPlayers().stream()
                .filter(player -> eligible(player) && poll.votes.containsKey(player.getUUID())).count());
        result.append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatableWithFallback("message.eden_ld.poll.not_voted", "didn't vote: %s", silent)
                        .withStyle(ChatFormatting.GRAY));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (eligible(player)) {
                player.sendSystemMessage(result);
            }
        }
        EdenLD.LOGGER.info("[Polls] Poll #{} ended{}: {} — {}; didn't vote: {}", poll.id,
                stoppedBy == null ? "" : " (stopped by " + stoppedBy + ")", poll.question, log, silent);
        last = adminTag(poll, counts, true);
    }

    private static int[] counts(Poll poll) {
        int[] counts = new int[poll.answers.size()];
        poll.votes.values().forEach(answer -> counts[answer]++);
        return counts;
    }

    private static List<String> voters(Poll poll, int answer) {
        List<String> names = new ArrayList<>();
        poll.votes.forEach((uuid, voted) -> {
            if (voted == answer) {
                names.add(poll.names.getOrDefault(uuid, uuid.toString()));
            }
        });
        return names;
    }

    private static void send(ServerPlayer player, Poll poll, boolean ended) {
        if (!ServerPlayNetworking.canSend(player, HubPayloads.PollData.TYPE)) {
            return;
        }
        int[] counts = counts(poll);
        List<Integer> list = new ArrayList<>();
        for (int count : counts) {
            list.add(count);
        }
        int online = (int) player.server.getPlayerList().getPlayers().stream().filter(Polls::eligible).count();
        int secondsLeft = ended ? 0 : (int) Math.max(0, (poll.endsAt - System.currentTimeMillis() + 999) / 1000);
        ServerPlayNetworking.send(player, new HubPayloads.PollData(poll.id, poll.question, poll.answers, poll.totalSeconds,
                secondsLeft, list, online, poll.votes.getOrDefault(player.getUUID(), -1), ended));
    }

    // ---------------------------------------------------------------- the Moderation window

    /** The last finished poll, for the Moderation section. */
    @Nullable
    private static CompoundTag last;

    /** The running poll (or the last finished one) with who voted what: staff only. */
    public static CompoundTag adminData() {
        CompoundTag tag = new CompoundTag();
        Poll poll = current;
        if (poll != null) {
            tag.put("poll", adminTag(poll, counts(poll), false));
        } else if (last != null) {
            tag.put("poll", last);
        }
        tag.putInt("max_answers", MAX_ANSWERS);
        tag.putInt("min_seconds", MIN_SECONDS);
        tag.putInt("max_seconds", MAX_SECONDS);
        return tag;
    }

    private static CompoundTag adminTag(Poll poll, int[] counts, boolean ended) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("id", poll.id);
        tag.putString("question", poll.question);
        tag.putString("by", poll.startedBy);
        tag.putBoolean("ended", ended);
        tag.putInt("seconds_left", ended ? 0 : (int) Math.max(0, (poll.endsAt - System.currentTimeMillis()) / 1000));
        ListTag answers = new ListTag();
        for (int i = 0; i < poll.answers.size(); i++) {
            CompoundTag answer = new CompoundTag();
            answer.putString("text", Component.Serializer.toJson(poll.answers.get(i), EdenLD.server().registryAccess()));
            answer.putInt("count", counts[i]);
            ListTag names = new ListTag();
            voters(poll, i).forEach(name -> names.add(StringTag.valueOf(name)));
            answer.put("voters", names);
            answers.add(answer);
        }
        tag.put("answers", answers);
        return tag;
    }

    /** {@code start} ({@code question}, {@code answers} as "a | b", {@code seconds}) and {@code stop}. */
    @Nullable
    public static Component act(ServerPlayer admin, String action, CompoundTag args) {
        String name = admin.getGameProfile().getName();
        return switch (action) {
            case "start" -> {
                Component refused = start(admin.server, name, args.getString("question"),
                        splitAnswers(args.getString("answers")), args.getInt("seconds"));
                yield refused != null ? refused : Component.translatable("message.eden_ld.poll.started");
            }
            case "stop" -> stop(admin.server, name);
            default -> null;
        };
    }
}
