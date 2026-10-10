package net.ledok.eden_ld.hub.guild;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One guild. Persistent (saved in {@link GuildManager}); mutations go through the manager,
 * which enforces ranks. Name and tag are unique across guilds (case-insensitive); the tag
 * (≤ 5 symbols) shows only inside the hub.
 */
public final class Guild {

    /** How outsiders get in: only by invite, by an application officers accept, or freely. */
    public enum JoinMode { INVITE_ONLY, APPLICATIONS, OPEN }

    /** One chat line, styled as the game's chat shows it (hover and click included). */
    public record ChatLine(UUID sender, String name, net.minecraft.network.chat.Component text, long time) {
    }

    public final UUID id;
    public String name;
    public String tag;
    /** RGB (no alpha) for the tag and name in the hub. */
    public int color;
    public String motd = "";
    public final long created;
    public JoinMode joinMode = JoinMode.INVITE_ONLY;
    /** Pooled LC, vault tabs, rank rights, audit log, upgrades. */
    public GuildBank bank = new GuildBank();

    /** Members in join order. */
    public final Map<UUID, GuildRank> members = new LinkedHashMap<>();
    public final Map<UUID, String> memberNames = new LinkedHashMap<>();
    public final Map<UUID, Long> joinedAt = new LinkedHashMap<>();
    /** Pending applications: applicant → sent (ms). Persisted so offline officers see them. */
    public final Map<UUID, Long> applications = new LinkedHashMap<>();
    public final Map<UUID, String> applicantNames = new LinkedHashMap<>();
    public final List<ChatLine> chat = new ArrayList<>();
    /** Guild XP ever earned; the level follows from it (see GuildLevels). */
    public long xp;
    /** The quest board (see GuildQuests): days, slots, progress, contributions. */
    public CompoundTag quests = new CompoundTag();

    /**
     * Open Parties and Claims bookkeeping (see OpacGuildClaims): whose claims are the guild's
     * (the leader when last reconciled) and how many bonus chunk claims Eden added to that
     * player's OPAC config. Lets a leadership change move both, and a disband take the bonus back.
     */
    public UUID opacOwner;
    public int opacBonus;

    public Guild(UUID id, String name, String tag, int color, long created) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.color = color;
        this.created = created;
    }

    public UUID leader() {
        for (Map.Entry<UUID, GuildRank> entry : members.entrySet()) {
            if (entry.getValue() == GuildRank.LEADER) {
                return entry.getKey();
            }
        }
        return null;
    }

    public GuildRank rank(UUID player) {
        return members.getOrDefault(player, GuildRank.RECRUIT);
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    // ---------------------------------------------------------------- NBT

    CompoundTag save(net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putString("tag", this.tag);
        tag.putInt("color", color);
        tag.putString("motd", motd);
        tag.putLong("created", created);
        tag.putString("join_mode", joinMode.name());
        tag.put("bank", bank.save(registries));
        ListTag memberList = new ListTag();
        members.forEach((uuid, rank) -> {
            CompoundTag member = new CompoundTag();
            member.putUUID("uuid", uuid);
            member.putString("name", memberNames.getOrDefault(uuid, "?"));
            member.putInt("rank", rank.level);
            member.putLong("joined", joinedAt.getOrDefault(uuid, 0L));
            memberList.add(member);
        });
        tag.put("members", memberList);
        ListTag applicationList = new ListTag();
        applications.forEach((uuid, time) -> {
            CompoundTag application = new CompoundTag();
            application.putUUID("uuid", uuid);
            application.putString("name", applicantNames.getOrDefault(uuid, "?"));
            application.putLong("time", time);
            applicationList.add(application);
        });
        tag.put("applications", applicationList);
        ListTag chatList = new ListTag();
        for (ChatLine line : chat) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("sender", line.sender());
            entry.putString("name", line.name());
            entry.putString("text", net.minecraft.network.chat.Component.Serializer.toJson(line.text(), registries));
            entry.putLong("time", line.time());
            chatList.add(entry);
        }
        tag.put("chat", chatList);
        tag.putLong("xp", xp);
        tag.put("quests", quests);
        if (opacOwner != null) {
            CompoundTag opac = new CompoundTag();
            opac.putUUID("owner", opacOwner);
            opac.putInt("bonus", opacBonus);
            tag.put("opac", opac);
        }
        return tag;
    }

    static Guild load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        Guild guild = new Guild(tag.getUUID("id"), tag.getString("name"), tag.getString("tag"),
                tag.getInt("color"), tag.getLong("created"));
        guild.motd = tag.getString("motd");
        guild.xp = tag.getLong("xp");
        guild.quests = tag.getCompound("quests");
        if (tag.contains("opac", Tag.TAG_COMPOUND)) {
            CompoundTag opac = tag.getCompound("opac");
            guild.opacOwner = opac.getUUID("owner");
            guild.opacBonus = opac.getInt("bonus");
        }
        try {
            guild.joinMode = JoinMode.valueOf(tag.getString("join_mode"));
        } catch (IllegalArgumentException e) {
            guild.joinMode = JoinMode.INVITE_ONLY;
        }
        if (tag.contains("bank", Tag.TAG_COMPOUND)) {
            guild.bank = GuildBank.load(tag.getCompound("bank"), registries);
        }
        ListTag memberList = tag.getList("members", Tag.TAG_COMPOUND);
        for (int i = 0; i < memberList.size(); i++) {
            CompoundTag member = memberList.getCompound(i);
            UUID uuid = member.getUUID("uuid");
            guild.members.put(uuid, GuildRank.of(member.getInt("rank")));
            guild.memberNames.put(uuid, member.getString("name"));
            guild.joinedAt.put(uuid, member.getLong("joined"));
        }
        ListTag applicationList = tag.getList("applications", Tag.TAG_COMPOUND);
        for (int i = 0; i < applicationList.size(); i++) {
            CompoundTag application = applicationList.getCompound(i);
            UUID uuid = application.getUUID("uuid");
            guild.applications.put(uuid, application.getLong("time"));
            guild.applicantNames.put(uuid, application.getString("name"));
        }
        ListTag chatList = tag.getList("chat", Tag.TAG_COMPOUND);
        for (int i = 0; i < chatList.size(); i++) {
            CompoundTag entry = chatList.getCompound(i);
            guild.chat.add(new ChatLine(entry.getUUID("sender"), entry.getString("name"),
                    chatText(entry.getString("text"), registries), entry.getLong("time")));
        }
        return guild;
    }

    /** A stored chat line's text (component JSON); unreadable text stays as it is. */
    private static net.minecraft.network.chat.Component chatText(String json,
                                                                 net.minecraft.core.HolderLookup.Provider registries) {
        try {
            net.minecraft.network.chat.Component text = net.minecraft.network.chat.Component.Serializer.fromJson(json, registries);
            return text != null ? text : net.minecraft.network.chat.Component.literal(json);
        } catch (RuntimeException e) {
            return net.minecraft.network.chat.Component.literal(json);
        }
    }
}
