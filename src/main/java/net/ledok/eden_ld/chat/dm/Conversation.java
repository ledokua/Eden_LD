package net.ledok.eden_ld.chat.dm;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One private conversation ({@link DirectMessages}): a DM between two players or a group chat, its members (each with
 * what they've read and whether they closed its tab) and its last lines. Server thread.
 */
final class Conversation {

    /** {@code ChannelNotify.ALL_MESSAGES}: a private line pings, as before 1.3.95. */
    static final int DEFAULT_NOTIFY = net.ledok.eden_ld.hub.HubSettings.ChannelNotify.ALL_MESSAGES.ordinal();

    /** A member: their name when last seen, when they joined (a group's older lines aren't theirs to read), what they read. */
    static final class Member {
        String name;
        final long joined;
        long readAt;
        boolean hidden;
        /** Their notifications for it (1.3.95): a {@code HubSettings.ChannelNotify} ordinal, every line by default. */
        int notify = DEFAULT_NOTIFY;

        Member(String name, long joined) {
            this.name = name;
            this.joined = joined;
            this.readAt = joined;
        }
    }

    /**
     * A line: when, by whom, their name as chat showed it and the text as chat styled it (both JSON components). The
     * two parsed components are kept on it once made ({@code DirectMessages.lineFor}, server thread; never saved):
     * the history goes out on every join and open, and parsing every line's JSON each time cost the server thread
     * (review finding 39).
     */
    static final class Line {
        private final long time;
        private final UUID sender;
        private final String nameJson;
        private final String bodyJson;
        @Nullable
        Component name;
        @Nullable
        Component body;

        Line(long time, UUID sender, String nameJson, String bodyJson) {
            this.time = time;
            this.sender = sender;
            this.nameJson = nameJson;
            this.bodyJson = bodyJson;
        }

        long time() {
            return time;
        }

        UUID sender() {
            return sender;
        }

        String nameJson() {
            return nameJson;
        }

        String bodyJson() {
            return bodyJson;
        }
    }

    final String id;
    final boolean group;
    String name;
    UUID owner;
    final Map<UUID, Member> members = new LinkedHashMap<>();
    final Deque<Line> lines = new ArrayDeque<>();
    /** When anything last happened in it (a line, a member, a rename). */
    long touched;

    Conversation(String id, boolean group, String name, UUID owner, long now) {
        this.id = id;
        this.group = group;
        this.name = name;
        this.owner = owner;
        this.touched = now;
    }

    @Nullable
    Member member(UUID player) {
        return members.get(player);
    }

    /** Whether {@code player} wrote in it (a DM they answer is never blocked by their partner's switches). */
    boolean wrote(UUID player) {
        for (Line line : lines) {
            if (line.sender().equals(player)) {
                return true;
            }
        }
        return false;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putBoolean("group", group);
        tag.putString("name", name);
        tag.putUUID("owner", owner);
        tag.putLong("touched", touched);
        ListTag memberList = new ListTag();
        for (Map.Entry<UUID, Member> entry : members.entrySet()) {
            CompoundTag m = new CompoundTag();
            m.putUUID("uuid", entry.getKey());
            m.putString("name", entry.getValue().name);
            m.putLong("joined", entry.getValue().joined);
            m.putLong("read", entry.getValue().readAt);
            m.putBoolean("hidden", entry.getValue().hidden);
            if (entry.getValue().notify != DEFAULT_NOTIFY) {
                m.putByte("notify", (byte) entry.getValue().notify);
            }
            memberList.add(m);
        }
        tag.put("members", memberList);
        ListTag lineList = new ListTag();
        for (Line line : lines) {
            CompoundTag l = new CompoundTag();
            l.putLong("time", line.time());
            l.putUUID("sender", line.sender());
            l.putString("name", line.nameJson());
            l.putString("body", line.bodyJson());
            lineList.add(l);
        }
        tag.put("lines", lineList);
        return tag;
    }

    @Nullable
    static Conversation load(CompoundTag tag) {
        if (!tag.hasUUID("owner") || tag.getString("id").isEmpty()) {
            return null;
        }
        Conversation conv = new Conversation(tag.getString("id"), tag.getBoolean("group"), tag.getString("name"),
                tag.getUUID("owner"), tag.getLong("touched"));
        ListTag memberList = tag.getList("members", Tag.TAG_COMPOUND);
        for (int i = 0; i < memberList.size(); i++) {
            CompoundTag m = memberList.getCompound(i);
            if (!m.hasUUID("uuid")) {
                continue;
            }
            Member member = new Member(m.getString("name"), m.getLong("joined"));
            member.readAt = m.getLong("read");
            member.hidden = m.getBoolean("hidden");
            member.notify = m.contains("notify") ? m.getByte("notify") : DEFAULT_NOTIFY;
            conv.members.put(m.getUUID("uuid"), member);
        }
        ListTag lineList = tag.getList("lines", Tag.TAG_COMPOUND);
        for (int i = 0; i < lineList.size(); i++) {
            CompoundTag l = lineList.getCompound(i);
            if (l.hasUUID("sender")) {
                conv.lines.add(new Line(l.getLong("time"), l.getUUID("sender"), l.getString("name"),
                        l.getString("body")));
            }
        }
        return conv;
    }
}
