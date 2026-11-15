package net.ledok.eden_ld.chat.dm;

import net.ledok.eden_ld.EdenData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every private conversation ({@link DirectMessages}), kept in {@code data/eden_ld/conversations.dat}: the last
 * {@link DmRules#KEEP} lines of each for {@link DmRules#KEEP_MS}, so a player who was away reads what they missed.
 * Pruned when loaded, when saved and when a member joins — never per tick. Server thread.
 */
final class ConversationStore extends SavedData {

    private static final Factory<ConversationStore> FACTORY = new Factory<>(ConversationStore::new,
            ConversationStore::load, null);

    private final Map<String, Conversation> byId = new LinkedHashMap<>();
    /** Who is in what, kept with the members. */
    private final Map<UUID, Set<String>> byMember = new HashMap<>();

    static ConversationStore get(MinecraftServer server) {
        return EdenData.get(server, FACTORY, "conversations");
    }

    @Nullable
    Conversation get(String id) {
        return byId.get(id);
    }

    /** The conversations {@code player} is in. */
    List<Conversation> of(UUID player) {
        Set<String> ids = byMember.get(player);
        List<Conversation> out = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                Conversation conv = byId.get(id);
                if (conv != null) {
                    out.add(conv);
                }
            }
        }
        return out;
    }

    /** How many groups {@code player} is in. */
    int groupsOf(UUID player) {
        int groups = 0;
        for (Conversation conv : of(player)) {
            if (conv.group) {
                groups++;
            }
        }
        return groups;
    }

    void add(Conversation conv) {
        byId.put(conv.id, conv);
        for (UUID member : conv.members.keySet()) {
            byMember.computeIfAbsent(member, k -> new LinkedHashSet<>()).add(conv.id);
        }
        setDirty();
    }

    void addMember(Conversation conv, UUID player, String name, long now) {
        conv.members.put(player, new Conversation.Member(name, now));
        byMember.computeIfAbsent(player, k -> new LinkedHashSet<>()).add(conv.id);
        conv.touched = now;
        setDirty();
    }

    void removeMember(Conversation conv, UUID player) {
        conv.members.remove(player);
        unindex(player, conv.id);
        if (conv.members.isEmpty()) {
            byId.remove(conv.id);
        }
        setDirty();
    }

    private void unindex(UUID player, String id) {
        Set<String> ids = byMember.get(player);
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) {
                byMember.remove(player);
            }
        }
    }

    void changed() {
        setDirty();
    }

    /** Old lines go, then conversations with nothing left to keep; returns those removed. */
    List<Conversation> prune(long now) {
        List<Conversation> gone = new ArrayList<>();
        Iterator<Conversation> it = byId.values().iterator();
        while (it.hasNext()) {
            Conversation conv = it.next();
            if (DmRules.trim(conv.lines, Conversation.Line::time, now) > 0) {
                setDirty();
            }
            if (DmRules.expired(conv.group, conv.lines.size(), conv.members.size(), conv.touched, now)) {
                it.remove();
                for (UUID member : conv.members.keySet()) {
                    unindex(member, conv.id);
                }
                gone.add(conv);
                setDirty();
            }
        }
        return gone;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        prune(System.currentTimeMillis());
        ListTag list = new ListTag();
        for (Conversation conv : byId.values()) {
            list.add(conv.save());
        }
        tag.put("conversations", list);
        return tag;
    }

    private static ConversationStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ConversationStore store = new ConversationStore();
        ListTag list = tag.getList("conversations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Conversation conv = Conversation.load(list.getCompound(i));
            if (conv != null) {
                store.add(conv);
            }
        }
        store.prune(System.currentTimeMillis());
        return store;
    }
}
