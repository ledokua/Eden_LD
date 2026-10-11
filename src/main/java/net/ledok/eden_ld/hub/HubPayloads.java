package net.ledok.eden_ld.hub;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Hub networking payloads. Types are registered on both sides from common init;
 * the client receivers live in {@code client.EdenLDClient}, the server receiver
 * in {@link HubServer}.
 */
public final class HubPayloads {

    private HubPayloads() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, path);
    }

    /** S2C: tells the client to open the hub screen (used by {@code /hub}). */
    public record OpenHub(String tab) implements CustomPacketPayload {
        /** Opens on the tab the player used last. */
        public static final OpenHub INSTANCE = new OpenHub("");
        public static final Type<OpenHub> TYPE = new Type<>(id("open_hub"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenHub> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.tab, 32), buf -> new OpenHub(buf.readUtf(32)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the client opened (or refreshed) the Home tab and wants fresh data. */
    public record HomeDataRequest() implements CustomPacketPayload {
        public static final HomeDataRequest INSTANCE = new HomeDataRequest();
        public static final Type<HomeDataRequest> TYPE = new Type<>(id("home_data_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HomeDataRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: everything the Home tab shows. {@code group} is empty when Minestar is absent
     * or the profile has no group; {@code hasEconomy} gates the balance display;
     * {@code shopPending} is 0 when there is nothing (or no Minestar); {@code friendsAvailable}
     * is false without Minestar (the Friends tab hides itself); {@code waystonesAvailable}
     * likewise hides the Waystones tab, and {@code duelsAvailable} the Duels tab (Duels_LD).
     */
    public record HomeData(String group, boolean prime, boolean hasEconomy, long balance,
                           long playtimeTicks, NewsView news, int shopPending, int partyInvites,
                           boolean friendsAvailable, int friendRequests, boolean waystonesAvailable,
                           int dailiesClaimable, int dailiesClaimed, int dailiesTotal,
                           boolean hasLevel, int level, long levelXp, long levelXpNeeded, String background,
                           int staff, boolean duelsAvailable)
            implements CustomPacketPayload {
        /**
         * {@code staff} bits: the Moderation window at all, writing mail, everyone's mail log,
         * BanHammer punishments (any of its commands), the punishment log, editing the news, background rules.
         */
        public static final int STAFF_MODERATION = 1, STAFF_MAIL = 2, STAFF_MAIL_LOG = 4, STAFF_PUNISH = 8,
                STAFF_PUNISH_LOG = 16, STAFF_NEWS = 32, STAFF_BACKGROUNDS = 64, STAFF_THEMES = 128,
                STAFF_DAILIES = 256, STAFF_DUNGEONS = 512, STAFF_EMOJIS = 1024, STAFF_GUILDS = 2048,
                STAFF_PARTIES = 4096, STAFF_COMMANDS = 8192, STAFF_WORLD = 16384;
        /** Any tab of the player page (the Players section). */
        public static final int STAFF_PLAYER_TABS = STAFF_PUNISH | STAFF_BACKGROUNDS | STAFF_THEMES | STAFF_DAILIES
                | STAFF_DUNGEONS | STAFF_EMOJIS;
        public static final Type<HomeData> TYPE = new Type<>(id("home_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HomeData> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.group);
                    buf.writeBoolean(payload.prime);
                    buf.writeBoolean(payload.hasEconomy);
                    buf.writeVarLong(payload.balance);
                    buf.writeVarLong(payload.playtimeTicks);
                    NewsView.write(buf, payload.news);
                    buf.writeVarInt(payload.shopPending);
                    buf.writeVarInt(payload.partyInvites);
                    buf.writeBoolean(payload.friendsAvailable);
                    buf.writeVarInt(payload.friendRequests);
                    buf.writeBoolean(payload.waystonesAvailable);
                    buf.writeVarInt(payload.dailiesClaimable);
                    buf.writeVarInt(payload.dailiesClaimed);
                    buf.writeVarInt(payload.dailiesTotal);
                    buf.writeBoolean(payload.hasLevel);
                    buf.writeVarInt(payload.level);
                    buf.writeVarLong(payload.levelXp);
                    buf.writeVarLong(payload.levelXpNeeded);
                    buf.writeUtf(payload.background, 128);
                    buf.writeVarInt(payload.staff);
                    buf.writeBoolean(payload.duelsAvailable);
                },
                buf -> new HomeData(buf.readUtf(), buf.readBoolean(), buf.readBoolean(),
                        buf.readVarLong(), buf.readVarLong(), NewsView.read(buf),
                        buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readBoolean(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readBoolean(), buf.readVarInt(), buf.readVarLong(), buf.readVarLong(), buf.readUtf(128),
                        buf.readVarInt(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- party

    /**
     * A member (or invite/request/candidate) row of the party tab. {@code ffSuspended}:
     * this member's party protection is off (EdenPartyApi#suspendFriendlyFireProtection), so client
     * combat matchers stop treating them as a teammate.
     */
    public record PartyMember(java.util.UUID uuid, String name, int rank, boolean online,
                              String dimension, boolean friend, boolean ffSuspended, boolean profile) {
        public PartyMember(java.util.UUID uuid, String name, int rank, boolean online,
                           String dimension, boolean friend) {
            this(uuid, name, rank, online, dimension, friend, false, false);
        }

        static void write(RegistryFriendlyByteBuf buf, PartyMember member) {
            buf.writeUUID(member.uuid);
            buf.writeUtf(member.name);
            buf.writeVarInt(member.rank);
            buf.writeBoolean(member.online);
            buf.writeUtf(member.dimension);
            buf.writeBoolean(member.friend);
            buf.writeBoolean(member.ffSuspended);
            buf.writeBoolean(member.profile);
        }

        static PartyMember read(RegistryFriendlyByteBuf buf) {
            return new PartyMember(buf.readUUID(), buf.readUtf(), buf.readVarInt(),
                    buf.readBoolean(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
        }
    }

    /** One party chat line; the sender uuid lets the client draw the player's head. */
    /** A hub chat line: the message as the game's chat styles it (links, emojis, hovers, clicks). */
    public record ChatEntry(java.util.UUID sender, String name, net.minecraft.network.chat.Component text) {
        static void write(RegistryFriendlyByteBuf buf, ChatEntry entry) {
            buf.writeUUID(entry.sender);
            buf.writeUtf(entry.name);
            net.minecraft.network.chat.ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, entry.text);
        }

        static ChatEntry read(RegistryFriendlyByteBuf buf) {
            return new ChatEntry(buf.readUUID(), buf.readUtf(),
                    net.minecraft.network.chat.ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf));
        }
    }

    /** A party reference shown to players outside it (joinable parties list). */
    public record PartyRef(java.util.UUID id, String name, String leader, int members, int max) {
        static void write(RegistryFriendlyByteBuf buf, PartyRef ref) {
            buf.writeUUID(ref.id);
            buf.writeUtf(ref.name);
            buf.writeUtf(ref.leader);
            buf.writeVarInt(ref.members);
            buf.writeVarInt(ref.max);
        }

        static PartyRef read(RegistryFriendlyByteBuf buf) {
            return new PartyRef(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /**
     * An invite waiting on this player, keyed by the inviter (a solo inviter has no
     * party yet — the party is created when the invite is accepted, so {@code partyName}
     * may be empty).
     */
    public record InviteRef(java.util.UUID inviter, String inviterName, String partyName) {
        static void write(RegistryFriendlyByteBuf buf, InviteRef ref) {
            buf.writeUUID(ref.inviter);
            buf.writeUtf(ref.inviterName);
            buf.writeUtf(ref.partyName);
        }

        static InviteRef read(RegistryFriendlyByteBuf buf) {
            return new InviteRef(buf.readUUID(), buf.readUtf(), buf.readUtf());
        }
    }

    /** C2S: the client opened (or refreshed) the Party tab. */
    public record PartyDataRequest() implements CustomPacketPayload {
        public static final PartyDataRequest INSTANCE = new PartyDataRequest();
        public static final Type<PartyDataRequest> TYPE = new Type<>(id("party_data_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PartyDataRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: everything the Party tab shows, both in-party and no-party states. */
    public record PartyData(boolean busy, boolean inParty,
                            String tag, String name, int myRank,
                            int invitePolicy, int joinPolicy, int maxSize,
                            List<PartyMember> members,
                            List<PartyMember> outgoingInvites,
                            List<PartyMember> inviteCandidates, List<ChatEntry> chatHistory,
                            List<InviteRef> myInvites, List<PartyRef> joinable)
            implements CustomPacketPayload {
        public static final Type<PartyData> TYPE = new Type<>(id("party_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PartyData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.busy);
                    buf.writeBoolean(p.inParty);
                    buf.writeUtf(p.tag);
                    buf.writeUtf(p.name);
                    buf.writeVarInt(p.myRank);
                    buf.writeVarInt(p.invitePolicy);
                    buf.writeVarInt(p.joinPolicy);
                    buf.writeVarInt(p.maxSize);
                    writeMembers(buf, p.members);
                    writeMembers(buf, p.outgoingInvites);
                    writeMembers(buf, p.inviteCandidates);
                    writeChat(buf, p.chatHistory);
                    buf.writeVarInt(p.myInvites.size());
                    for (InviteRef ref : p.myInvites) {
                        InviteRef.write(buf, ref);
                    }
                    writeRefs(buf, p.joinable);
                },
                buf -> {
                    boolean busy = buf.readBoolean();
                    boolean inParty = buf.readBoolean();
                    String tag = buf.readUtf();
                    String name = buf.readUtf();
                    int myRank = buf.readVarInt();
                    int invitePolicy = buf.readVarInt();
                    int joinPolicy = buf.readVarInt();
                    int maxSize = buf.readVarInt();
                    List<PartyMember> members = readMembers(buf);
                    List<PartyMember> outgoing = readMembers(buf);
                    List<PartyMember> candidates = readMembers(buf);
                    List<ChatEntry> chat = readChat(buf);
                    int inviteCount = buf.readVarInt();
                    List<InviteRef> myInvites = new ArrayList<>(inviteCount);
                    for (int i = 0; i < inviteCount; i++) {
                        myInvites.add(InviteRef.read(buf));
                    }
                    List<PartyRef> joinable = readRefs(buf);
                    return new PartyData(busy, inParty, tag, name, myRank, invitePolicy,
                            joinPolicy, maxSize, members, outgoing, candidates,
                            chat, myInvites, joinable);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: one party action. {@code action} is a {@link Action}; {@code target} is a player
     * or party id depending on the action; {@code text}/{@code value}/{@code flag} carry
     * the rest. Everything is re-validated server side.
     */
    public record PartyAction(int action, java.util.UUID target, String text, int value, boolean flag)
            implements CustomPacketPayload {
        public enum Action {
            INVITE, CANCEL_INVITE, ACCEPT_INVITE, DECLINE_INVITE, JOIN,
            LEAVE, KICK, PROMOTE, DEMOTE, TRANSFER, DISBAND,
            RENAME, SET_INVITE_POLICY, SET_JOIN_POLICY,
            CHAT
        }

        private static final java.util.UUID NIL = new java.util.UUID(0L, 0L);

        public static PartyAction of(Action action) {
            return new PartyAction(action.ordinal(), NIL, "", 0, false);
        }

        public static PartyAction of(Action action, java.util.UUID target) {
            return new PartyAction(action.ordinal(), target, "", 0, false);
        }

        public static PartyAction of(Action action, String text) {
            return new PartyAction(action.ordinal(), NIL, text, 0, false);
        }

        public static PartyAction of(Action action, int value) {
            return new PartyAction(action.ordinal(), NIL, "", value, false);
        }

        public static PartyAction of(Action action, boolean flag) {
            return new PartyAction(action.ordinal(), NIL, "", 0, flag);
        }

        public Action kind() {
            Action[] actions = Action.values();
            return actions[Math.floorMod(action, actions.length)];
        }

        public static final Type<PartyAction> TYPE = new Type<>(id("party_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PartyAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.action);
                    buf.writeUUID(p.target);
                    buf.writeUtf(p.text, 256);
                    buf.writeVarInt(p.value);
                    buf.writeBoolean(p.flag);
                },
                buf -> new PartyAction(buf.readVarInt(), buf.readUUID(), buf.readUtf(256),
                        buf.readVarInt(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: one new party chat line for an open party tab. */
    public record PartyChatLine(ChatEntry line) implements CustomPacketPayload {
        public static final Type<PartyChatLine> TYPE = new Type<>(id("party_chat_line"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PartyChatLine> CODEC = StreamCodec.of(
                (buf, p) -> ChatEntry.write(buf, p.line),
                buf -> new PartyChatLine(ChatEntry.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- friends

    /** C2S: the client opened the Friends tab; the server re-fetches the profile (throttled). */
    public record FriendsDataRequest() implements CustomPacketPayload {
        public static final FriendsDataRequest INSTANCE = new FriendsDataRequest();
        public static final Type<FriendsDataRequest> TYPE = new Type<>(id("friends_data_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FriendsDataRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A friend row. {@code presence}: 0 offline, 1 online elsewhere (launcher), 2 on this
     * server. {@code lastSeen} is epoch millis or -1; {@code playing} is a display string;
     * {@code packSearch} is the lowercase pack names + ids for "@pack" search;
     * {@code dimension} is the dimension id while on this server, else empty;
     * {@code inviteState} is a {@code PartyManager.InviteState} ordinal (meaningful on this server).
     */
    public record FriendEntry(java.util.UUID uuid, String name, int presence, long lastSeen, String playing,
                              String packSearch, String dimension, int inviteState, boolean profile,
                              String background) {
        public static final int OFFLINE = 0;
        public static final int ONLINE = 1;
        public static final int ON_SERVER = 2;

        static void write(RegistryFriendlyByteBuf buf, FriendEntry entry) {
            buf.writeUUID(entry.uuid);
            buf.writeUtf(entry.name);
            buf.writeVarInt(entry.presence);
            buf.writeLong(entry.lastSeen);
            buf.writeUtf(entry.playing);
            buf.writeUtf(entry.packSearch);
            buf.writeUtf(entry.dimension);
            buf.writeVarInt(entry.inviteState);
            buf.writeBoolean(entry.profile);
            buf.writeUtf(entry.background, 128);
        }

        static FriendEntry read(RegistryFriendlyByteBuf buf) {
            return new FriendEntry(buf.readUUID(), buf.readUtf(), buf.readVarInt(), buf.readLong(), buf.readUtf(),
                    buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(128));
        }
    }

    /** A pending request, incoming or outgoing; {@code sentAt} is epoch millis. */
    public record RequestEntry(java.util.UUID uuid, String name, long sentAt) {
        static void write(RegistryFriendlyByteBuf buf, RequestEntry entry) {
            buf.writeUUID(entry.uuid);
            buf.writeUtf(entry.name);
            buf.writeLong(entry.sentAt);
        }

        static RequestEntry read(RegistryFriendlyByteBuf buf) {
            return new RequestEntry(buf.readUUID(), buf.readUtf(), buf.readLong());
        }
    }

    /**
     * Another player on this server, how they relate to the viewer, whether the viewer may
     * party-invite them, and whether their profile is open to the viewer.
     */
    public record ServerPlayerEntry(java.util.UUID uuid, String name, int relation, int inviteState, boolean profile,
                                    String background) {
        public static final int NONE = 0;
        public static final int FRIEND = 1;
        public static final int OUTGOING = 2;
        public static final int INCOMING = 3;

        static void write(RegistryFriendlyByteBuf buf, ServerPlayerEntry entry) {
            buf.writeUUID(entry.uuid);
            buf.writeUtf(entry.name);
            buf.writeVarInt(entry.relation);
            buf.writeVarInt(entry.inviteState);
            buf.writeBoolean(entry.profile);
            buf.writeUtf(entry.background, 128);
        }

        static ServerPlayerEntry read(RegistryFriendlyByteBuf buf) {
            return new ServerPlayerEntry(buf.readUUID(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean(), buf.readUtf(128));
        }
    }

    /**
     * S2C: everything the Friends tab shows. {@code loaded} is false when the platform
     * could not be reached (the lists are then empty and relations unknown).
     */
    public record FriendsData(boolean loaded, List<FriendEntry> friends, List<RequestEntry> incoming,
                              List<RequestEntry> outgoing, List<ServerPlayerEntry> players)
            implements CustomPacketPayload {
        public static final Type<FriendsData> TYPE = new Type<>(id("friends_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FriendsData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.loaded);
                    writeList(buf, p.friends, FriendEntry::write);
                    writeList(buf, p.incoming, RequestEntry::write);
                    writeList(buf, p.outgoing, RequestEntry::write);
                    writeList(buf, p.players, ServerPlayerEntry::write);
                },
                buf -> new FriendsData(buf.readBoolean(),
                        readList(buf, FriendEntry::read),
                        readList(buf, RequestEntry::read),
                        readList(buf, RequestEntry::read),
                        readList(buf, ServerPlayerEntry::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: one friend action on {@code target}; re-validated and executed server side. */
    public record FriendAction(int action, java.util.UUID target) implements CustomPacketPayload {
        public enum Action {
            /** Send a request (auto-accepts when they already requested us). */
            ADD,
            ACCEPT,
            DECLINE,
            CANCEL,
            REMOVE
        }

        public static FriendAction of(Action action, java.util.UUID target) {
            return new FriendAction(action.ordinal(), target);
        }

        public Action kind() {
            Action[] actions = Action.values();
            return actions[Math.floorMod(action, actions.length)];
        }

        public static final Type<FriendAction> TYPE = new Type<>(id("friend_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FriendAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.action);
                    buf.writeUUID(p.target);
                },
                buf -> new FriendAction(buf.readVarInt(), buf.readUUID()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- ignore list

    /** An ignored player; {@code name}: the one they had when last seen. */
    public record IgnoredEntry(java.util.UUID uuid, String name) {
        static void write(RegistryFriendlyByteBuf buf, IgnoredEntry entry) {
            buf.writeUUID(entry.uuid);
            buf.writeUtf(entry.name, 64);
        }

        static IgnoredEntry read(RegistryFriendlyByteBuf buf) {
            return new IgnoredEntry(buf.readUUID(), buf.readUtf(64));
        }
    }

    /** S2C: everyone the player ignores ({@code IgnoreList}), on join and after each change. */
    public record IgnoreData(List<IgnoredEntry> ignored) implements CustomPacketPayload {
        public static final Type<IgnoreData> TYPE = new Type<>(id("ignore_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, IgnoreData> CODEC = StreamCodec.of(
                (buf, p) -> writeList(buf, p.ignored, IgnoredEntry::write),
                buf -> new IgnoreData(readList(buf, IgnoredEntry::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: ignore {@code target}, or stop ignoring them. */
    public record IgnoreAction(java.util.UUID target, boolean ignore) implements CustomPacketPayload {
        public static final Type<IgnoreAction> TYPE = new Type<>(id("ignore_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, IgnoreAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.target);
                    buf.writeBoolean(p.ignore);
                },
                buf -> new IgnoreAction(buf.readUUID(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- guilds

    /** A guild as others see it (directory rows, invites). */
    public record GuildRef(java.util.UUID id, String name, String tag, int color, int members, int limit,
                           int joinMode, int level) {
        static void write(RegistryFriendlyByteBuf buf, GuildRef ref) {
            buf.writeUUID(ref.id);
            buf.writeUtf(ref.name);
            buf.writeUtf(ref.tag);
            buf.writeInt(ref.color);
            buf.writeVarInt(ref.members);
            buf.writeVarInt(ref.limit);
            buf.writeVarInt(ref.joinMode);
            buf.writeVarInt(ref.level);
        }

        static GuildRef read(RegistryFriendlyByteBuf buf) {
            return new GuildRef(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /** An invite to the viewer: the guild and who sent it. */
    public record GuildInvite(GuildRef guild, String inviterName) {
        static void write(RegistryFriendlyByteBuf buf, GuildInvite invite) {
            GuildRef.write(buf, invite.guild);
            buf.writeUtf(invite.inviterName);
        }

        static GuildInvite read(RegistryFriendlyByteBuf buf) {
            return new GuildInvite(GuildRef.read(buf), buf.readUtf());
        }
    }

    /** A roster row; {@code rank} is a GuildRank level, {@code ffSuspended} as for parties. */
    public record GuildMember(java.util.UUID uuid, String name, int rank, boolean online, boolean ffSuspended,
                              boolean profile) {
        static void write(RegistryFriendlyByteBuf buf, GuildMember member) {
            buf.writeUUID(member.uuid);
            buf.writeUtf(member.name);
            buf.writeVarInt(member.rank);
            buf.writeBoolean(member.online);
            buf.writeBoolean(member.ffSuspended);
            buf.writeBoolean(member.profile);
        }

        static GuildMember read(RegistryFriendlyByteBuf buf) {
            return new GuildMember(buf.readUUID(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                    buf.readBoolean());
        }
    }

    /** A player reference: pending invites, applications. */
    public record NameRef(java.util.UUID uuid, String name) {
        static void write(RegistryFriendlyByteBuf buf, NameRef ref) {
            buf.writeUUID(ref.uuid);
            buf.writeUtf(ref.name);
        }

        static NameRef read(RegistryFriendlyByteBuf buf) {
            return new NameRef(buf.readUUID(), buf.readUtf());
        }
    }

    /** C2S: the client opened the Guild tab. */
    public record GuildDataRequest() implements CustomPacketPayload {
        public static final GuildDataRequest INSTANCE = new GuildDataRequest();
        public static final Type<GuildDataRequest> TYPE = new Type<>(id("guild_data_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuildDataRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: everything the Guild tab shows. In a guild: {@code guild} (own), rank, roster,
     * MOTD, chat, outgoing invites and applications (officers), invite candidates. Without:
     * invites to the viewer, their applications, the directory, and the creation price.
     */
    public record GuildData(boolean inGuild, GuildRef guild, String motd, int myRank,
                            List<GuildMember> members, List<NameRef> outgoing, List<NameRef> applications,
                            List<ChatEntry> chat, List<PartyMember> inviteCandidates,
                            List<GuildInvite> myInvites, List<java.util.UUID> myApplications,
                            List<GuildRef> directory, long createCost, boolean economy)
            implements CustomPacketPayload {
        public static final Type<GuildData> TYPE = new Type<>(id("guild_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuildData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.inGuild);
                    GuildRef.write(buf, p.guild);
                    buf.writeUtf(p.motd);
                    buf.writeVarInt(p.myRank);
                    writeList(buf, p.members, GuildMember::write);
                    writeList(buf, p.outgoing, NameRef::write);
                    writeList(buf, p.applications, NameRef::write);
                    writeChat(buf, p.chat);
                    writeMembers(buf, p.inviteCandidates);
                    writeList(buf, p.myInvites, GuildInvite::write);
                    writeList(buf, p.myApplications, (b, uuid) -> b.writeUUID(uuid));
                    writeList(buf, p.directory, GuildRef::write);
                    buf.writeVarLong(p.createCost);
                    buf.writeBoolean(p.economy);
                },
                buf -> new GuildData(buf.readBoolean(), GuildRef.read(buf), buf.readUtf(), buf.readVarInt(),
                        readList(buf, GuildMember::read), readList(buf, NameRef::read),
                        readList(buf, NameRef::read), readChat(buf), readMembers(buf),
                        readList(buf, GuildInvite::read), readList(buf, b -> b.readUUID()),
                        readList(buf, GuildRef::read), buf.readVarLong(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: one guild action; everything re-validated server side against ranks. */
    public record GuildAction(int action, java.util.UUID target, String text, String text2, int value)
            implements CustomPacketPayload {
        public enum Action {
            CREATE, INVITE, CANCEL_INVITE, ACCEPT_INVITE, DECLINE_INVITE,
            APPLY, CANCEL_APPLICATION, ACCEPT_APPLICATION, DECLINE_APPLICATION, JOIN,
            LEAVE, KICK, PROMOTE, DEMOTE, TRANSFER, DISBAND,
            RENAME, SET_TAG, SET_COLOR, SET_MOTD, SET_JOIN_MODE, CHAT
        }

        private static final java.util.UUID NIL = new java.util.UUID(0L, 0L);

        public static GuildAction of(Action action) {
            return new GuildAction(action.ordinal(), NIL, "", "", 0);
        }

        public static GuildAction of(Action action, java.util.UUID target) {
            return new GuildAction(action.ordinal(), target, "", "", 0);
        }

        public static GuildAction of(Action action, String text) {
            return new GuildAction(action.ordinal(), NIL, text, "", 0);
        }

        public static GuildAction of(Action action, int value) {
            return new GuildAction(action.ordinal(), NIL, "", "", value);
        }

        /** CREATE: name, tag, color. */
        public static GuildAction create(String name, String tag, int color) {
            return new GuildAction(Action.CREATE.ordinal(), NIL, name, tag, color);
        }

        public Action kind() {
            Action[] actions = Action.values();
            return actions[Math.floorMod(action, actions.length)];
        }

        public static final Type<GuildAction> TYPE = new Type<>(id("guild_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuildAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.action);
                    buf.writeUUID(p.target);
                    buf.writeUtf(p.text, 256);
                    buf.writeUtf(p.text2, 64);
                    buf.writeInt(p.value);
                },
                buf -> new GuildAction(buf.readVarInt(), buf.readUUID(), buf.readUtf(256), buf.readUtf(64),
                        buf.readInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: one new guild chat line for an open guild tab. */
    public record GuildChatLine(ChatEntry line) implements CustomPacketPayload {
        public static final Type<GuildChatLine> TYPE = new Type<>(id("guild_chat_line"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuildChatLine> CODEC = StreamCodec.of(
                (buf, p) -> ChatEntry.write(buf, p.line),
                buf -> new GuildChatLine(ChatEntry.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- guild bank

    /** One vault tab as the viewer sees it: name, their access (GuildBank.Access ordinal), items left today (-1 ∞). */
    public record BankTab(String name, int access, int itemsLeft) {
        static void write(RegistryFriendlyByteBuf buf, BankTab tab) {
            buf.writeUtf(tab.name, 32);
            buf.writeVarInt(tab.access);
            buf.writeVarInt(tab.itemsLeft + 1);
        }

        static BankTab read(RegistryFriendlyByteBuf buf) {
            return new BankTab(buf.readUtf(32), buf.readVarInt(), buf.readVarInt() - 1);
        }
    }

    /** One rank's rights for the leader's editor; tabs hold (access ordinal, items per day). */
    public record RankRightsView(int rank, long lcPerDay, List<Integer> access, List<Integer> items) {
        static void write(RegistryFriendlyByteBuf buf, RankRightsView view) {
            buf.writeVarInt(view.rank);
            buf.writeLong(view.lcPerDay);
            writeList(buf, view.access, (b, v) -> b.writeVarInt(v));
            writeList(buf, view.items, (b, v) -> b.writeInt(v));
        }

        static RankRightsView read(RegistryFriendlyByteBuf buf) {
            return new RankRightsView(buf.readVarInt(), buf.readLong(), readList(buf, b -> b.readVarInt()),
                    readList(buf, b -> b.readInt()));
        }
    }

    /** One audit entry (GuildBank.LogType ordinal); {@code tab} -1 for LC and purchases. */
    public record BankLog(long time, String name, int type, int tab, net.minecraft.world.item.ItemStack stack,
                          long amount, String detail) {
        static void write(RegistryFriendlyByteBuf buf, BankLog log) {
            buf.writeLong(log.time);
            buf.writeUtf(log.name);
            buf.writeVarInt(log.type);
            buf.writeVarInt(log.tab + 1);
            net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, log.stack);
            buf.writeLong(log.amount);
            buf.writeUtf(log.detail, 32);
        }

        static BankLog read(RegistryFriendlyByteBuf buf) {
            return new BankLog(buf.readLong(), buf.readUtf(), buf.readVarInt(), buf.readVarInt() - 1,
                    net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readLong(),
                    buf.readUtf(32));
        }
    }

    /**
     * S2C: the guild bank for a member who has it open. {@code viewTab} is the tab whose 54
     * {@code contents} are included (-1 = none/not allowed). Costs are -1 when maxed out.
     * {@code rights} is filled for the leader only (the permissions editor).
     */
    public record BankData(long balance, boolean economy, long myBalance, int myRank, long lcLeft, List<BankTab> tabs,
                           int viewTab, List<net.minecraft.world.item.ItemStack> contents,
                           long nextTabCost, int memberLevel, long nextMemberCost, int memberStep,
                           int claimLevel, long nextClaimCost, int claimStep, int claimsUsed, int claimsLimit,
                           List<RankRightsView> rights, List<BankLog> log) implements CustomPacketPayload {
        /** {@code claimsLimit} -1: guild claims aren't active (no OPAC, or not set up for guilds). */
        public static final Type<BankData> TYPE = new Type<>(id("guild_bank_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BankData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeLong(p.balance);
                    buf.writeBoolean(p.economy);
                    buf.writeLong(p.myBalance);
                    buf.writeVarInt(p.myRank);
                    buf.writeLong(p.lcLeft);
                    writeList(buf, p.tabs, BankTab::write);
                    buf.writeVarInt(p.viewTab + 1);
                    writeList(buf, p.contents, (b, stack) -> net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(b, stack));
                    buf.writeLong(p.nextTabCost);
                    buf.writeVarInt(p.memberLevel);
                    buf.writeLong(p.nextMemberCost);
                    buf.writeVarInt(p.memberStep);
                    buf.writeVarInt(p.claimLevel);
                    buf.writeLong(p.nextClaimCost);
                    buf.writeVarInt(p.claimStep);
                    buf.writeVarInt(p.claimsUsed);
                    buf.writeVarInt(p.claimsLimit + 1);
                    writeList(buf, p.rights, RankRightsView::write);
                    writeList(buf, p.log, BankLog::write);
                },
                buf -> new BankData(buf.readLong(), buf.readBoolean(), buf.readLong(), buf.readVarInt(), buf.readLong(),
                        readList(buf, BankTab::read), buf.readVarInt() - 1,
                        readList(buf, net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC::decode),
                        buf.readLong(), buf.readVarInt(), buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                        buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1,
                        readList(buf, RankRightsView::read), readList(buf, BankLog::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the bank view opened on a tab (-1 = overview only) or closed ({@link #CLOSED}). */
    public record BankView(int tab) implements CustomPacketPayload {
        public static final int CLOSED = -2;
        public static final Type<BankView> TYPE = new Type<>(id("guild_bank_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BankView> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.tab + 2),
                buf -> new BankView(buf.readVarInt() - 2));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: one bank action. Item moves: {@code tab} + {@code slot} (vault slot for WITHDRAW_ITEM,
     * main-inventory slot 0–35 for DEPOSIT_ITEM) + {@code amount} = item count (0 = whole stack).
     * LC: {@code amount}. Rights: {@code rank} (GuildRank level), {@code tab}, {@code slot} = access
     * ordinal, {@code amount} = stacks/LC per day (-1 unlimited). RENAME_TAB: {@code text}.
     */
    public record BankAction(int action, int tab, int slot, long amount, int rank, String text)
            implements CustomPacketPayload {
        public enum Action {
            DEPOSIT_ITEM, WITHDRAW_ITEM, DEPOSIT_LC, WITHDRAW_LC, BUY_TAB, BUY_MEMBERS, BUY_CLAIMS,
            SET_TAB_RIGHTS, SET_LC_LIMIT, RENAME_TAB
        }

        /** {@code count} items; 0 = the whole stack. */
        public static BankAction item(Action action, int tab, int slot, int count) {
            return new BankAction(action.ordinal(), tab, slot, count, 0, "");
        }

        public static BankAction amount(Action action, long amount) {
            return new BankAction(action.ordinal(), -1, 0, amount, 0, "");
        }

        public static BankAction of(Action action) {
            return new BankAction(action.ordinal(), -1, 0, 0, 0, "");
        }

        public static BankAction tabRights(int rank, int tab, int access, long itemsPerDay) {
            return new BankAction(Action.SET_TAB_RIGHTS.ordinal(), tab, access, itemsPerDay, rank, "");
        }

        public static BankAction lcLimit(int rank, long perDay) {
            return new BankAction(Action.SET_LC_LIMIT.ordinal(), -1, 0, perDay, rank, "");
        }

        public static BankAction renameTab(int tab, String name) {
            return new BankAction(Action.RENAME_TAB.ordinal(), tab, 0, 0, 0, name);
        }

        public Action kind() {
            Action[] actions = Action.values();
            return actions[Math.floorMod(action, actions.length)];
        }

        public static final Type<BankAction> TYPE = new Type<>(id("guild_bank_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BankAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.action);
                    buf.writeVarInt(p.tab + 1);
                    buf.writeVarInt(p.slot);
                    buf.writeLong(p.amount);
                    buf.writeVarInt(p.rank);
                    buf.writeUtf(p.text, 32);
                },
                buf -> new BankAction(buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt(),
                        buf.readLong(), buf.readVarInt(), buf.readUtf(32)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- name styles

    /**
     * A player's LuckPerms prefix/suffix, raw (legacy {@code &} codes; the client parses them),
     * and their guild tag + RGB color when they chose to show it (empty otherwise).
     */
    /** {@code nameColor}: RGB from the LuckPerms meta {@code eden_ld.name_color}, -1 when unset. */
    public record NameStyle(java.util.UUID uuid, String prefix, String suffix, String guildTag, int guildColor,
                            int nameColor) {
        static void write(RegistryFriendlyByteBuf buf, NameStyle style) {
            buf.writeUUID(style.uuid);
            buf.writeUtf(style.prefix, 256);
            buf.writeUtf(style.suffix, 256);
            buf.writeUtf(style.guildTag, 16);
            buf.writeInt(style.guildColor);
            buf.writeInt(style.nameColor);
        }

        static NameStyle read(RegistryFriendlyByteBuf buf) {
            return new NameStyle(buf.readUUID(), buf.readUtf(256), buf.readUtf(256), buf.readUtf(16), buf.readInt(),
                    buf.readInt());
        }
    }

    /** S2C: prefixes/suffixes of everyone online (full replace), for names shown in the hub. */
    public record NameStyles(List<NameStyle> styles) implements CustomPacketPayload {
        public static final Type<NameStyles> TYPE = new Type<>(id("name_styles"));
        public static final StreamCodec<RegistryFriendlyByteBuf, NameStyles> CODEC = StreamCodec.of(
                (buf, p) -> writeList(buf, p.styles, NameStyle::write),
                buf -> new NameStyles(readList(buf, NameStyle::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- notices

    /** S2C: a toast on an open hub (chat is hidden behind the screen): a refusal or error, or with {@code ok} a success (green). */
    public record HubNotice(net.minecraft.network.chat.Component message, boolean ok) implements CustomPacketPayload {
        public static final Type<HubNotice> TYPE = new Type<>(id("hub_notice"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HubNotice> CODEC = StreamCodec.of(
                (buf, p) -> {
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, p.message);
                    buf.writeBoolean(p.ok);
                },
                buf -> new HubNotice(net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf),
                        buf.readBoolean()));

        /** A problem (red toast). */
        public HubNotice(net.minecraft.network.chat.Component message) {
            this(message, false);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- mail (Economy_LD)

    /** One language of a written mail: its title and Markdown body. */
    public record MailText(String language, String title, String body) {
    }

    /**
     * One mail as the player sees it. {@code kind} is Economy_LD's MailKind ordinal. Written mail
     * carries {@code texts} (placeholders already filled for this reader) and {@code defaultLanguage};
     * automatic mail carries lang keys ({@code titleKey}, {@code bodyKey}, may be empty) and
     * {@code args}. {@code senderKey}: the sender is a lang key. Items come with their full counts.
     * Times are epoch seconds.
     */
    public record MailView(long id, int kind, String sender, boolean senderKey, String defaultLanguage,
                           List<MailText> texts, String titleKey, String bodyKey, List<String> args,
                           List<net.minecraft.world.item.ItemStack> items, List<Integer> counts, long coins,
                           long createdAt, long expiresAt, boolean read, boolean claimed) {

        public boolean hasUnclaimed() {
            return !claimed && (!items.isEmpty() || coins > 0L);
        }

        static void write(RegistryFriendlyByteBuf buf, MailView m) {
            buf.writeVarLong(m.id);
            buf.writeVarInt(m.kind);
            buf.writeUtf(m.sender);
            buf.writeBoolean(m.senderKey);
            buf.writeUtf(m.defaultLanguage);
            writeList(buf, m.texts, (b, t) -> {
                b.writeUtf(t.language());
                b.writeUtf(t.title());
                b.writeUtf(t.body(), 65536);
            });
            buf.writeUtf(m.titleKey);
            buf.writeUtf(m.bodyKey);
            writeStrings(buf, m.args);
            writeList(buf, m.items, (b, stack) -> net.minecraft.world.item.ItemStack.STREAM_CODEC.encode(b, stack));
            writeList(buf, m.counts, (b, count) -> b.writeVarInt(count));
            buf.writeVarLong(m.coins);
            buf.writeVarLong(m.createdAt);
            buf.writeVarLong(m.expiresAt);
            buf.writeBoolean(m.read);
            buf.writeBoolean(m.claimed);
        }

        static MailView read(RegistryFriendlyByteBuf buf) {
            return new MailView(buf.readVarLong(), buf.readVarInt(), buf.readUtf(), buf.readBoolean(), buf.readUtf(),
                    readList(buf, b -> new MailText(b.readUtf(), b.readUtf(), b.readUtf(65536))),
                    buf.readUtf(), buf.readUtf(), readStrings(buf),
                    readList(buf, net.minecraft.world.item.ItemStack.STREAM_CODEC::decode),
                    readList(buf, b -> b.readVarInt()), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
                    buf.readBoolean(), buf.readBoolean());
        }
    }

    /** A website purchase waiting for delivery (Minestar): its id, the shop's name for it, how many. */
    public record ShopPurchase(long id, String name, int quantity) {
        static void write(RegistryFriendlyByteBuf buf, ShopPurchase purchase) {
            buf.writeVarLong(purchase.id);
            buf.writeUtf(purchase.name, 256);
            buf.writeVarInt(purchase.quantity);
        }

        static ShopPurchase read(RegistryFriendlyByteBuf buf) {
            return new ShopPurchase(buf.readVarLong(), buf.readUtf(256), buf.readVarInt());
        }
    }

    /**
     * S2C: the player's whole mailbox (newest first) and the website purchases waiting for them. Sent on join and
     * whenever it changes; the Mail tab shows only once this has arrived (Economy_LD installed).
     */
    public record MailData(List<MailView> mails, List<ShopPurchase> purchases) implements CustomPacketPayload {
        public static final Type<MailData> TYPE = new Type<>(id("mail_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeList(buf, p.mails, MailView::write);
                    writeList(buf, p.purchases, ShopPurchase::write);
                },
                buf -> new MailData(readList(buf, MailView::read), readList(buf, ShopPurchase::read)));

        /** Unread mail, plus every waiting purchase (they stay pinned until claimed). */
        public int unread() {
            return (int) mails.stream().filter(m -> !m.read()).count() + purchases.size();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: a Mail tab action; {@code mailId} for the one-mail actions. {@code CLAIM_SHOP} takes a purchase id, or 0
     * for every waiting purchase.
     */
    public record MailAction(Action action, long mailId) implements CustomPacketPayload {
        public enum Action { OPEN, CLAIM, CLAIM_ALL, DELETE, DELETE_READ, CLAIM_SHOP }

        public static final Type<MailAction> TYPE = new Type<>(id("mail_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.action.ordinal());
                    buf.writeVarLong(p.mailId);
                },
                buf -> {
                    Action[] actions = Action.values();
                    int index = buf.readVarInt();
                    return new MailAction(actions[Math.floorMod(index, actions.length)], buf.readVarLong());
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- moderation window

    /**
     * A staff mail being written, saved on the server per admin. {@code audience}: 0 players (by
     * name), 1 all, 2 LuckPerms groups; {@code texts} one per language; items with full counts.
     */
    public record MailDraftData(long id, String name, int audience, List<String> players, List<String> groups, String sender,
                                int expiryDays, long coins, String defaultLanguage, List<MailText> texts,
                                List<net.minecraft.world.item.ItemStack> items, List<Integer> counts, long updatedAt) {
        public static final int TO_PLAYERS = 0, TO_ALL = 1, TO_GROUPS = 2;

        public static void write(RegistryFriendlyByteBuf buf, MailDraftData d) {
            buf.writeVarLong(d.id);
            buf.writeUtf(d.name, 64);
            buf.writeVarInt(d.audience);
            writeStrings(buf, d.players);
            writeStrings(buf, d.groups);
            buf.writeUtf(d.sender, 64);
            buf.writeVarInt(d.expiryDays);
            buf.writeVarLong(d.coins);
            buf.writeUtf(d.defaultLanguage, 16);
            writeList(buf, d.texts, (b, t) -> {
                b.writeUtf(t.language(), 16);
                b.writeUtf(t.title(), 256);
                b.writeUtf(t.body(), 16384);
            });
            writeList(buf, d.items, (b, stack) -> net.minecraft.world.item.ItemStack.STREAM_CODEC.encode(b, stack));
            writeList(buf, d.counts, (b, count) -> b.writeVarInt(count));
            buf.writeVarLong(d.updatedAt);
        }

        public static MailDraftData read(RegistryFriendlyByteBuf buf) {
            return new MailDraftData(buf.readVarLong(), buf.readUtf(64), buf.readVarInt(), readStrings(buf), readStrings(buf),
                    buf.readUtf(64), buf.readVarInt(), buf.readVarLong(), buf.readUtf(16),
                    readList(buf, b -> new MailText(b.readUtf(16), b.readUtf(256), b.readUtf(16384))),
                    readList(buf, net.minecraft.world.item.ItemStack.STREAM_CODEC::decode),
                    readList(buf, b -> b.readVarInt()), buf.readVarLong());
        }
    }

    /** A Placeholder API placeholder and its value for the admin (the placeholder browser). */
    public record PlaceholderInfo(String token, String value) {
    }

    /**
     * The Home tab's news: Markdown per language ({@link MailText} with no title), the default
     * language for readers without their own, when and by whom it was last published.
     */
    public record NewsView(String defaultLanguage, List<MailText> texts, long updatedAt, String updatedBy) {
        public static final NewsView EMPTY = new NewsView("en_us", List.of(), 0, "");

        static void write(RegistryFriendlyByteBuf buf, NewsView v) {
            buf.writeUtf(v.defaultLanguage, 16);
            writeList(buf, v.texts, (b, t) -> {
                b.writeUtf(t.language(), 16);
                b.writeUtf(t.body(), 16384);
            });
            buf.writeVarLong(v.updatedAt);
            buf.writeUtf(v.updatedBy, 64);
        }

        static NewsView read(RegistryFriendlyByteBuf buf) {
            return new NewsView(buf.readUtf(16), readList(buf, b -> new MailText(b.readUtf(16), "", b.readUtf(16384))),
                    buf.readVarLong(), buf.readUtf(64));
        }

        /** The text for this language, else the default's, else the first; "" when there is none. */
        public String text(String language) {
            for (String wanted : new String[]{language, defaultLanguage}) {
                for (MailText t : texts) {
                    if (t.language().equals(wanted)) {
                        return t.body();
                    }
                }
            }
            return texts.isEmpty() ? "" : texts.get(0).body();
        }
    }

    /**
     * Who may use a profile background or a hub theme ({@code LookRules}): {@code locked} false —
     * everyone; else the players in {@code groups} (LuckPerms), those who unlocked or bought it,
     * and nobody else. A locked one is {@code hidden} from the picker or shown there, and for sale
     * when {@code price} > 0 (LC).
     */
    public record LookRule(String id, boolean locked, List<String> groups, boolean hidden, long price) {
        static void write(RegistryFriendlyByteBuf buf, LookRule v) {
            buf.writeUtf(v.id, 128);
            buf.writeBoolean(v.locked);
            writeStrings(buf, v.groups);
            buf.writeBoolean(v.hidden);
            buf.writeVarLong(v.price);
        }

        static LookRule read(RegistryFriendlyByteBuf buf) {
            return new LookRule(buf.readUtf(128), buf.readBoolean(), readStrings(buf), buf.readBoolean(),
                    buf.readVarLong());
        }
    }

    /** A locked background or theme the picker shows: its price in LC (0: not for sale). */
    public record LookOffer(String id, long price) {
        static void write(RegistryFriendlyByteBuf buf, LookOffer o) {
            buf.writeUtf(o.id, 128);
            buf.writeVarLong(o.price);
        }

        static LookOffer read(RegistryFriendlyByteBuf buf) {
            return new LookOffer(buf.readUtf(128), buf.readVarLong());
        }
    }

    /** C2S: set a background's ({@code theme} false) or a theme's rule (Moderation → Customization). */
    public record LookRuleSave(boolean theme, LookRule rule) implements CustomPacketPayload {
        public static final Type<LookRuleSave> TYPE = new Type<>(id("look_rule_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LookRuleSave> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.theme);
                    LookRule.write(buf, p.rule);
                },
                buf -> new LookRuleSave(buf.readBoolean(), LookRule.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: buy a background ({@code theme} false) or a theme for its price. */
    public record LookBuy(boolean theme, String id) implements CustomPacketPayload {
        public static final Type<LookBuy> TYPE = new Type<>(HubPayloads.id("look_buy"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LookBuy> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.theme);
                    buf.writeUtf(p.id, 128);
                },
                buf -> new LookBuy(buf.readBoolean(), buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- wardrobe

    /** C2S: the Wardrobe tab opened (or an action finished): send its data. */
    public record WardrobeRequest() implements CustomPacketPayload {
        public static final WardrobeRequest INSTANCE = new WardrobeRequest();
        public static final Type<WardrobeRequest> TYPE = new Type<>(id("wardrobe_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WardrobeRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One look in the Wardrobe: {@code state} {@link #OWNED} (or {@link #WORN}), {@link #FOR_SALE}
     * (locked, price in LC), {@link #LOCKED} (shown but not for sale); {@code giftable} when it has a
     * price (whether or not the player owns it).
     */
    public record WardrobeItem(String id, int state, long price) {
        public static final int OWNED = 0, WORN = 1, FOR_SALE = 2, LOCKED = 3;

        static void write(RegistryFriendlyByteBuf buf, WardrobeItem i) {
            buf.writeUtf(i.id, 128);
            buf.writeVarInt(i.state);
            buf.writeVarLong(i.price);
        }

        static WardrobeItem read(RegistryFriendlyByteBuf buf) {
            return new WardrobeItem(buf.readUtf(128), buf.readVarInt(), buf.readVarLong());
        }
    }

    /** S2C: the Wardrobe — every look per category, and whether there's an economy (buying, gifts). */
    public record WardrobeData(List<WardrobeItem> backgrounds, List<WardrobeItem> themes, boolean economy)
            implements CustomPacketPayload {
        public static final Type<WardrobeData> TYPE = new Type<>(id("wardrobe_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WardrobeData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeList(buf, p.backgrounds, WardrobeItem::write);
                    writeList(buf, p.themes, WardrobeItem::write);
                    buf.writeBoolean(p.economy);
                },
                buf -> new WardrobeData(readList(buf, WardrobeItem::read), readList(buf, WardrobeItem::read),
                        buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: gift a background ({@code theme} false) or theme to {@code recipient} (a player name), paid by the giver. */
    public record LookGift(boolean theme, String id, String recipient, String note) implements CustomPacketPayload {
        public static final Type<LookGift> TYPE = new Type<>(HubPayloads.id("look_gift"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LookGift> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.theme);
                    buf.writeUtf(p.id, 128);
                    buf.writeUtf(p.recipient, 32);
                    buf.writeUtf(p.note, 200);
                },
                buf -> new LookGift(buf.readBoolean(), buf.readUtf(128), buf.readUtf(32), buf.readUtf(200)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: publish the news (with {@code eden_ld.admin.news}). */
    public record NewsSave(String defaultLanguage, List<MailText> texts) implements CustomPacketPayload {
        public static final Type<NewsSave> TYPE = new Type<>(id("news_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, NewsSave> CODEC = StreamCodec.of(
                (buf, p) -> NewsView.write(buf, new NewsView(p.defaultLanguage, p.texts, 0, "")),
                buf -> {
                    NewsView v = NewsView.read(buf);
                    return new NewsSave(v.defaultLanguage(), v.texts());
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: open the Moderation window (answered with {@link ModInfo} when allowed). */
    public record ModOpen() implements CustomPacketPayload {
        public static final ModOpen INSTANCE = new ModOpen();
        public static final Type<ModOpen> TYPE = new Type<>(id("mod_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ModOpen> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: what the Moderation window needs: the admin's rights ({@code HomeData.STAFF_*} bits),
     * mail limits, LuckPerms groups, their drafts (newest first) and the server's placeholders.
     */
    public record ModInfo(int staff, int maxItems, int maxExpiryDays, List<String> groups, List<MailDraftData> drafts,
                          List<PlaceholderInfo> placeholders, NewsView news, List<LookRule> backgroundRules,
                          List<LookRule> themeRules)
            implements CustomPacketPayload {
        public static final Type<ModInfo> TYPE = new Type<>(id("mod_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ModInfo> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.staff);
                    buf.writeVarInt(p.maxItems);
                    buf.writeVarInt(p.maxExpiryDays);
                    writeStrings(buf, p.groups);
                    writeList(buf, p.drafts, MailDraftData::write);
                    writeList(buf, p.placeholders, (b, ph) -> {
                        b.writeUtf(ph.token(), 256);
                        b.writeUtf(ph.value(), 256);
                    });
                    NewsView.write(buf, p.news);
                    writeList(buf, p.backgroundRules, LookRule::write);
                    writeList(buf, p.themeRules, LookRule::write);
                },
                buf -> new ModInfo(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), readStrings(buf),
                        readList(buf, MailDraftData::read),
                        readList(buf, b -> new PlaceholderInfo(b.readUtf(256), b.readUtf(256))), NewsView.read(buf),
                        readList(buf, LookRule::read), readList(buf, LookRule::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: save (create or replace) a draft; {@code delete} removes it instead. */
    public record DraftSave(MailDraftData draft, boolean delete) implements CustomPacketPayload {
        public static final Type<DraftSave> TYPE = new Type<>(id("draft_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DraftSave> CODEC = StreamCodec.of(
                (buf, p) -> {
                    MailDraftData.write(buf, p.draft);
                    buf.writeBoolean(p.delete);
                },
                buf -> new DraftSave(MailDraftData.read(buf), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: send a draft (saved first). Without {@code confirm} the server only checks it and
     * answers {@link MailSendCheck}; with it, it sends and deletes the draft.
     */
    public record MailSend(MailDraftData draft, boolean confirm) implements CustomPacketPayload {
        public static final Type<MailSend> TYPE = new Type<>(id("mail_send"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailSend> CODEC = StreamCodec.of(
                (buf, p) -> {
                    MailDraftData.write(buf, p.draft);
                    buf.writeBoolean(p.confirm);
                },
                buf -> new MailSend(MailDraftData.read(buf), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: a checked draft: how many players it reaches ({@code -1}: everyone, joining later too),
     * names nobody has joined with, and what stops it from being sent ({@code problems}, lang keys).
     */
    public record MailSendCheck(long draftId, int recipients, List<String> unknownNames, List<String> problems)
            implements CustomPacketPayload {
        public static final Type<MailSendCheck> TYPE = new Type<>(id("mail_send_check"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailSendCheck> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarLong(p.draftId);
                    buf.writeVarInt(p.recipients);
                    writeStrings(buf, p.unknownNames);
                    writeStrings(buf, p.problems);
                },
                buf -> new MailSendCheck(buf.readVarLong(), buf.readVarInt(), readStrings(buf), readStrings(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A sent staff mail in the log: the mail, who it went to, and how many read and claimed it. */
    public record SentMailView(MailView mail, java.util.UUID senderUuid, boolean toAll, String audienceNote, int recipients,
                               int read, int claimed) {
        static void write(RegistryFriendlyByteBuf buf, SentMailView v) {
            MailView.write(buf, v.mail);
            buf.writeUUID(v.senderUuid);
            buf.writeBoolean(v.toAll);
            buf.writeUtf(v.audienceNote, 1024);
            buf.writeVarInt(v.recipients);
            buf.writeVarInt(v.read);
            buf.writeVarInt(v.claimed);
        }

        static SentMailView read(RegistryFriendlyByteBuf buf) {
            return new SentMailView(MailView.read(buf), buf.readUUID(), buf.readBoolean(), buf.readUtf(1024),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /** C2S: the mail log ({@code everyone}: all staff's mail, with the log permission) or recall one ({@code recall} > 0). */
    public record MailLogRequest(boolean everyone, long recall) implements CustomPacketPayload {
        public static final Type<MailLogRequest> TYPE = new Type<>(id("mail_log_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailLogRequest> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.everyone);
                    buf.writeVarLong(p.recall);
                },
                buf -> new MailLogRequest(buf.readBoolean(), buf.readVarLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the mail log, newest first. */
    public record MailLog(boolean everyone, List<SentMailView> mails) implements CustomPacketPayload {
        public static final Type<MailLog> TYPE = new Type<>(id("mail_log"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailLog> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.everyone);
                    writeList(buf, p.mails, SentMailView::write);
                },
                buf -> new MailLog(buf.readBoolean(), readList(buf, SentMailView::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the result of a Moderation window action, shown in the window ({@code ok}: green, else red). */
    public record ModNotice(net.minecraft.network.chat.Component message, boolean ok) implements CustomPacketPayload {
        public static final Type<ModNotice> TYPE = new Type<>(id("mod_notice"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ModNotice> CODEC = StreamCodec.of(
                (buf, p) -> {
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, p.message);
                    buf.writeBoolean(p.ok);
                },
                buf -> new ModNotice(net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf),
                        buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- admin tools

    /**
     * C2S: the data of one Moderation window area ({@code AdminTools.UNLOCKS}, {@code GUILDS}, …),
     * for a player ({@code target}) or server-wide (nil UUID).
     */
    public record AdminRequest(String area, java.util.UUID target) implements CustomPacketPayload {
        public static final Type<AdminRequest> TYPE = new Type<>(id("admin_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminRequest> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.area, 32);
                    buf.writeUUID(p.target);
                },
                buf -> new AdminRequest(buf.readUtf(32), buf.readUUID()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: an area's data (NBT; see {@code AdminTools}). */
    public record AdminData(String area, java.util.UUID target, net.minecraft.nbt.CompoundTag data)
            implements CustomPacketPayload {
        public static final Type<AdminData> TYPE = new Type<>(id("admin_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.area, 32);
                    buf.writeUUID(p.target);
                    buf.writeNbt(p.data);
                },
                buf -> new AdminData(buf.readUtf(32), buf.readUUID(), buf.readNbt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: an action in an area ({@code action} and its {@code args}); answered with a notice and fresh data. */
    public record AdminAction(String area, java.util.UUID target, String action, net.minecraft.nbt.CompoundTag args)
            implements CustomPacketPayload {
        public static final Type<AdminAction> TYPE = new Type<>(id("admin_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.area, 32);
                    buf.writeUUID(p.target);
                    buf.writeUtf(p.action, 32);
                    buf.writeNbt(p.args);
                },
                buf -> new AdminAction(buf.readUtf(32), buf.readUUID(), buf.readUtf(32), buf.readNbt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- punishments (BanHammer)

    /**
     * BanHammer's commands the Moderation window runs, in the order of {@link PunishPage#allowed}'s
     * bits: punishing, then lifting.
     */
    public static final List<String> PUNISH_COMMANDS = List.of("ban", "tempban", "ban-ip", "tempban-ip", "mute",
            "tempmute", "warn", "tempwarn", "kick", "unban", "unban-ip", "unmute", "unwarn", "pardon");

    /**
     * One punishment: {@code type} is BanHammer's name ({@code ban}, {@code ipban}, {@code mute},
     * {@code warn}, {@code kick}); times in epoch seconds, {@code duration} -1 when permanent.
     */
    public record PunishView(String type, java.util.UUID player, String playerName, long time, long duration,
                             String reason, String admin) {
        public boolean permanent() {
            return duration < 0;
        }

        public boolean expired(long nowSeconds) {
            return duration >= 0 && time + duration < nowSeconds;
        }

        static void write(RegistryFriendlyByteBuf buf, PunishView v) {
            buf.writeUtf(v.type, 16);
            buf.writeUUID(v.player);
            buf.writeUtf(v.playerName, 64);
            buf.writeVarLong(v.time);
            buf.writeLong(v.duration);
            buf.writeUtf(v.reason, 512);
            buf.writeUtf(v.admin, 128);
        }

        static PunishView read(RegistryFriendlyByteBuf buf) {
            return new PunishView(buf.readUtf(16), buf.readUUID(), buf.readUtf(64), buf.readVarLong(), buf.readLong(),
                    buf.readUtf(512), buf.readUtf(128));
        }
    }

    /** A player found by the Moderation window's search; {@code lastSeen} epoch millis, 0 when online or unknown. */
    public record PunishPlayer(java.util.UUID uuid, String name, boolean online, long lastSeen) {
    }

    /** C2S: find players who have joined ({@code query} blank: everyone online, then the recently active). */
    public record PunishSearch(String query) implements CustomPacketPayload {
        public static final Type<PunishSearch> TYPE = new Type<>(id("punish_search"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PunishSearch> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.query, 32),
                buf -> new PunishSearch(buf.readUtf(32)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: search results for {@code query}, online players first. */
    public record PunishPlayers(String query, List<PunishPlayer> players) implements CustomPacketPayload {
        public static final Type<PunishPlayers> TYPE = new Type<>(id("punish_players"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PunishPlayers> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.query, 32);
                    writeList(buf, p.players, (b, player) -> {
                        b.writeUUID(player.uuid());
                        b.writeUtf(player.name(), 64);
                        b.writeBoolean(player.online());
                        b.writeVarLong(player.lastSeen());
                    });
                },
                buf -> new PunishPlayers(buf.readUtf(32),
                        readList(buf, b -> new PunishPlayer(b.readUUID(), b.readUtf(64), b.readBoolean(), b.readVarLong()))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: a player's page. */
    public record PunishPageRequest(java.util.UUID target) implements CustomPacketPayload {
        public static final Type<PunishPageRequest> TYPE = new Type<>(id("punish_page_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PunishPageRequest> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUUID(p.target),
                buf -> new PunishPageRequest(buf.readUUID()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: a player's page — which of {@link #PUNISH_COMMANDS} the admin may run (bits), their
     * active punishments and their history (newest first; {@code historyKept} false when BanHammer
     * doesn't store it).
     */
    public record PunishPage(java.util.UUID target, String name, boolean online, int allowed, boolean historyKept,
                             List<PunishView> active, List<PunishView> history) implements CustomPacketPayload {
        public static final Type<PunishPage> TYPE = new Type<>(id("punish_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PunishPage> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.target);
                    buf.writeUtf(p.name, 64);
                    buf.writeBoolean(p.online);
                    buf.writeVarInt(p.allowed);
                    buf.writeBoolean(p.historyKept);
                    writeList(buf, p.active, PunishView::write);
                    writeList(buf, p.history, PunishView::write);
                },
                buf -> new PunishPage(buf.readUUID(), buf.readUtf(64), buf.readBoolean(), buf.readVarInt(),
                        buf.readBoolean(), readList(buf, PunishView::read), readList(buf, PunishView::read)));

        public boolean may(String command) {
            int index = PUNISH_COMMANDS.indexOf(command);
            return index >= 0 && (allowed & (1 << index)) != 0;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: run one of {@link #PUNISH_COMMANDS} on a player, as the admin ({@code duration} in
     * BanHammer's form, e.g. {@code 7d}, for the temporary ones; {@code silent}: only staff hear).
     */
    public record PunishAction(java.util.UUID target, String command, String duration, String reason, boolean silent)
            implements CustomPacketPayload {
        public static final Type<PunishAction> TYPE = new Type<>(id("punish_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PunishAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.target);
                    buf.writeUtf(p.command, 16);
                    buf.writeUtf(p.duration, 16);
                    buf.writeUtf(p.reason, 256);
                    buf.writeBoolean(p.silent);
                },
                buf -> new PunishAction(buf.readUUID(), buf.readUtf(16), buf.readUtf(16), buf.readUtf(256),
                        buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the punishment log (everyone's recent punishments). */
    public record PunishLogRequest() implements CustomPacketPayload {
        public static final PunishLogRequest INSTANCE = new PunishLogRequest();
        public static final Type<PunishLogRequest> TYPE = new Type<>(id("punish_log_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PunishLogRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the punishment log, newest first ({@code historyKept} false when BanHammer doesn't store it). */
    public record PunishLog(boolean historyKept, List<PunishView> entries) implements CustomPacketPayload {
        public static final Type<PunishLog> TYPE = new Type<>(id("punish_log"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PunishLog> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.historyKept);
                    writeList(buf, p.entries, PunishView::write);
                },
                buf -> new PunishLog(buf.readBoolean(), readList(buf, PunishView::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- dailies

    /** C2S: the client opened the Dailies tab (or its reset timer ran out). */
    public record DailiesDataRequest() implements CustomPacketPayload {
        public static final DailiesDataRequest INSTANCE = new DailiesDataRequest();
        public static final Type<DailiesDataRequest> TYPE = new Type<>(id("dailies_data_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DailiesDataRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * What a quest or the bonus pays, for display: real item stacks (the tab renders them as
     * icons with the vanilla tooltip), XP points, LC (0 without Economy_LD), and an authored
     * note for rewards the tab can't show (commands). Text is already localized per player.
     */
    public record RewardView(List<net.minecraft.world.item.ItemStack> items, int xp, long lc,
                             net.minecraft.network.chat.Component note) {
        static void write(RegistryFriendlyByteBuf buf, RewardView view) {
            writeList(buf, view.items, (b, stack) -> net.minecraft.world.item.ItemStack.STREAM_CODEC.encode(b, stack));
            buf.writeVarInt(view.xp);
            buf.writeVarLong(view.lc);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, view.note);
        }

        static RewardView read(RegistryFriendlyByteBuf buf) {
            return new RewardView(readList(buf, net.minecraft.world.item.ItemStack.STREAM_CODEC::decode),
                    buf.readVarInt(), buf.readVarLong(),
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf));
        }

        public boolean isEmpty() {
            return items.isEmpty() && xp <= 0 && lc <= 0 && note.getString().isEmpty();
        }
    }

    /**
     * One quest card. {@code title} is the authored name or the generated objective line;
     * {@code objective} is that line again when a name exists (shown under it), else empty.
     * Both empty = the quest left the pool after being claimed.
     */
    public record DailyEntry(int slot, net.minecraft.network.chat.Component title,
                             net.minecraft.network.chat.Component objective,
                             net.minecraft.resources.ResourceLocation icon, int progress, int count,
                             boolean claimed, RewardView rewards, int guildXp) {
        /** {@code guildXp}: what claiming gives the player's guild; 0 outside a guild (not shown then). */
        static void write(RegistryFriendlyByteBuf buf, DailyEntry entry) {
            buf.writeVarInt(entry.slot);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, entry.title);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, entry.objective);
            buf.writeResourceLocation(entry.icon);
            buf.writeVarInt(entry.progress);
            buf.writeVarInt(entry.count);
            buf.writeBoolean(entry.claimed);
            RewardView.write(buf, entry.rewards);
            buf.writeVarInt(entry.guildXp);
        }

        static DailyEntry read(RegistryFriendlyByteBuf buf) {
            return new DailyEntry(buf.readVarInt(),
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf),
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf),
                    buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                    RewardView.read(buf), buf.readVarInt());
        }
    }

    /**
     * S2C: the Dailies tab. {@code resetMillis} counts down to the next day;
     * {@code freeRerollsLeft} / {@code nextRerollCost} (LC) price the reroll buttons;
     * {@code economy} false means paid rerolls are impossible; {@code balance} is the player's
     * LC (-1 when unknown) so the tab can grey out a reroll they can't afford.
     */
    public record DailiesData(long resetMillis, int freeRerollsLeft, long nextRerollCost, boolean economy, long balance,
                              List<DailyEntry> quests, RewardView bonusRewards,
                              boolean bonusClaimed) implements CustomPacketPayload {
        public static final Type<DailiesData> TYPE = new Type<>(id("dailies_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DailiesData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarLong(p.resetMillis);
                    buf.writeVarInt(p.freeRerollsLeft);
                    buf.writeVarLong(p.nextRerollCost);
                    buf.writeBoolean(p.economy);
                    buf.writeVarLong(p.balance + 1);
                    writeList(buf, p.quests, DailyEntry::write);
                    RewardView.write(buf, p.bonusRewards);
                    buf.writeBoolean(p.bonusClaimed);
                },
                buf -> new DailiesData(buf.readVarLong(), buf.readVarInt(), buf.readVarLong(), buf.readBoolean(),
                        buf.readVarLong() - 1, readList(buf, DailyEntry::read),
                        RewardView.read(buf),
                        buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: claim a quest, reroll it, or claim the all-quests bonus ({@code slot} ignored). */
    public record DailyAction(int action, int slot) implements CustomPacketPayload {
        public enum Action { CLAIM, REROLL, CLAIM_BONUS }

        public static DailyAction of(Action action, int slot) {
            return new DailyAction(action.ordinal(), slot);
        }

        public Action kind() {
            Action[] actions = Action.values();
            return actions[Math.floorMod(action, actions.length)];
        }

        public static final Type<DailyAction> TYPE = new Type<>(id("daily_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DailyAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.action);
                    buf.writeVarInt(p.slot);
                },
                buf -> new DailyAction(buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- waystones

    /** C2S: the client opened the Waystones tab. */
    public record WaystonesDataRequest() implements CustomPacketPayload {
        public static final WaystonesDataRequest INSTANCE = new WaystonesDataRequest();
        public static final Type<WaystonesDataRequest> TYPE = new Type<>(id("waystones_data_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WaystonesDataRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One destination; {@code distance} in blocks, -1 when in another dimension.
     * {@code category}: which section it's listed under ({@link #MINE} … {@link #GUILD});
     * {@code ownerName} is shown for friends' and guildmates' stones. {@code name} is the player's alias when
     * they set one ({@code originalName} is then the stone's own name, else empty).
     */
    public record WaystoneEntry(java.util.UUID id, net.minecraft.network.chat.Component name,
                                net.minecraft.network.chat.Component originalName, String dimension,
                                int distance, int category, String ownerName, boolean favorite) {
        public static final int MINE = 0;
        public static final int FRIEND = 1;
        public static final int GLOBAL = 2;
        public static final int ACTIVATED = 3;
        /** A guildmate's stone: their "Visible to Team" (= guild) stones, or ones you activated. */
        public static final int GUILD = 4;

        static void write(RegistryFriendlyByteBuf buf, WaystoneEntry entry) {
            buf.writeUUID(entry.id);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, entry.name);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, entry.originalName);
            buf.writeUtf(entry.dimension);
            buf.writeVarInt(entry.distance + 1);
            buf.writeVarInt(entry.category);
            buf.writeUtf(entry.ownerName);
            buf.writeBoolean(entry.favorite);
        }

        static WaystoneEntry read(RegistryFriendlyByteBuf buf) {
            return new WaystoneEntry(buf.readUUID(),
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf),
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf),
                    buf.readUtf(), buf.readVarInt() - 1, buf.readVarInt(), buf.readUtf(), buf.readBoolean());
        }
    }

    /**
     * S2C: the Waystones tab — every destination, plus what blocks teleporting right now:
     * busy (BusyLib), and milliseconds left on the hub cooldown and on the PvP block.
     */
    /**
     * The player's waystones; {@code favoriteOrder} is their own order for the favorites (dragged into place
     * in the tab) — favorites missing from it follow, by name.
     */
    public record WaystonesData(boolean busy, long cooldownMillis, long pvpMillis, List<WaystoneEntry> waystones,
                                List<java.util.UUID> favoriteOrder)
            implements CustomPacketPayload {
        public static final Type<WaystonesData> TYPE = new Type<>(id("waystones_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WaystonesData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.busy);
                    buf.writeVarLong(p.cooldownMillis);
                    buf.writeVarLong(p.pvpMillis);
                    writeList(buf, p.waystones, WaystoneEntry::write);
                    writeList(buf, p.favoriteOrder, (b, id) -> b.writeUUID(id));
                },
                buf -> new WaystonesData(buf.readBoolean(), buf.readVarLong(), buf.readVarLong(),
                        readList(buf, WaystoneEntry::read), readList(buf, b -> b.readUUID())));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: star or unstar a waystone — Waystones' own Favorites group (the tab's left column). */
    public record WaystoneFavorite(java.util.UUID waystone, boolean favorite) implements CustomPacketPayload {
        public static final Type<WaystoneFavorite> TYPE = new Type<>(id("waystone_favorite"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WaystoneFavorite> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.waystone);
                    buf.writeBoolean(p.favorite);
                },
                buf -> new WaystoneFavorite(buf.readUUID(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: set the player's own name for a waystone (Waystones alias); blank resets it. */
    public record WaystoneRename(java.util.UUID waystone, String alias) implements CustomPacketPayload {
        public static final Type<WaystoneRename> TYPE = new Type<>(id("waystone_rename"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WaystoneRename> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.waystone);
                    buf.writeUtf(p.alias, 64);
                },
                buf -> new WaystoneRename(buf.readUUID(), buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the player's order for their favorite waystones (the tab's left column, dragged into place). */
    public record WaystoneOrder(List<java.util.UUID> favorites) implements CustomPacketPayload {
        /** The longest order kept (more favorites just follow by name). */
        public static final int MAX = 256;
        public static final Type<WaystoneOrder> TYPE = new Type<>(id("waystone_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WaystoneOrder> CODEC = StreamCodec.of(
                (buf, p) -> writeList(buf, p.favorites.subList(0, Math.min(MAX, p.favorites.size())), (b, id) -> b.writeUUID(id)),
                buf -> new WaystoneOrder(readList(buf, b -> b.readUUID())));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: forget a waystone (Waystones deactivates it for this player; global and guild ones stay). */
    public record WaystoneForget(java.util.UUID waystone) implements CustomPacketPayload {
        public static final Type<WaystoneForget> TYPE = new Type<>(id("waystone_forget"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WaystoneForget> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUUID(p.waystone),
                buf -> new WaystoneForget(buf.readUUID()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: teleport to a waystone from the hub. */
    public record WaystoneTeleport(java.util.UUID waystone) implements CustomPacketPayload {
        public static final Type<WaystoneTeleport> TYPE = new Type<>(id("waystone_teleport"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WaystoneTeleport> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUUID(p.waystone),
                buf -> new WaystoneTeleport(buf.readUUID()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: teleport outcome — success closes the hub; otherwise {@code message} explains why. */
    public record WaystoneTeleportResult(boolean success, net.minecraft.network.chat.Component message)
            implements CustomPacketPayload {
        public static final Type<WaystoneTeleportResult> TYPE = new Type<>(id("waystone_teleport_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WaystoneTeleportResult> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.success);
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, p.message);
                },
                buf -> new WaystoneTeleportResult(buf.readBoolean(),
                        net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- activities

    /** C2S: the client opened the Activities tab. */
    public record ActivitiesRequest() implements CustomPacketPayload {
        public static final ActivitiesRequest INSTANCE = new ActivitiesRequest();
        public static final Type<ActivitiesRequest> TYPE = new Type<>(id("activities_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActivitiesRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One activity card: {@code type} an {@code Activity.Type} ordinal, {@code cardArt} a profile
     * background id ("" = default art), {@code unlockLevel} the level that opens it, {@code fresh}
     * opened by the player's level and not seen yet (the card glows).
     */
    public record ActivityEntry(net.minecraft.resources.ResourceLocation id, int type,
                                net.minecraft.network.chat.Component name, String cardArt, int unlockLevel,
                                boolean unlocked, boolean fresh) {
        static void write(RegistryFriendlyByteBuf buf, ActivityEntry e) {
            buf.writeResourceLocation(e.id);
            buf.writeVarInt(e.type);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, e.name);
            buf.writeUtf(e.cardArt, 256);
            buf.writeVarInt(e.unlockLevel);
            buf.writeBoolean(e.unlocked);
            buf.writeBoolean(e.fresh);
        }

        static ActivityEntry read(RegistryFriendlyByteBuf buf) {
            return new ActivityEntry(buf.readResourceLocation(), buf.readVarInt(),
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), buf.readUtf(256),
                    buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
        }
    }

    /** An online partymate's activity level (the cards warn when one is below an activity's). */
    public record PartyLevel(String name, int level) {
    }

    /**
     * S2C: the Activities tab — the player's level ({@code gated} false: nothing is locked), every
     * card, and their online partymates' levels (empty when not gated or not in a party).
     */
    public record ActivitiesData(int level, boolean gated, List<ActivityEntry> activities, List<PartyLevel> party)
            implements CustomPacketPayload {
        public static final Type<ActivitiesData> TYPE = new Type<>(id("activities_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActivitiesData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.level);
                    buf.writeBoolean(p.gated);
                    writeList(buf, p.activities, ActivityEntry::write);
                    writeList(buf, p.party, (b, m) -> {
                        b.writeUtf(m.name(), 64);
                        b.writeVarInt(m.level());
                    });
                },
                buf -> new ActivitiesData(buf.readVarInt(), buf.readBoolean(), readList(buf, ActivityEntry::read),
                        readList(buf, b -> new PartyLevel(b.readUtf(64), b.readVarInt()))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: open this activity's lobby screen (the server checks the level first). */
    public record ActivityOpen(net.minecraft.resources.ResourceLocation id) implements CustomPacketPayload {
        public static final Type<ActivityOpen> TYPE = new Type<>(HubPayloads.id("activity_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActivityOpen> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeResourceLocation(p.id), buf -> new ActivityOpen(buf.readResourceLocation()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the player saw the Activities tab — its new unlocks stop being new. */
    public record ActivitiesSeen() implements CustomPacketPayload {
        public static final ActivitiesSeen INSTANCE = new ActivitiesSeen();
        public static final Type<ActivitiesSeen> TYPE = new Type<>(id("activities_seen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActivitiesSeen> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the activities the player's level opened that they haven't seen yet (tab badge, Home card). */
    public record ActivityUnlocks(List<net.minecraft.network.chat.Component> names) implements CustomPacketPayload {
        public static final Type<ActivityUnlocks> TYPE = new Type<>(id("activity_unlocks"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActivityUnlocks> CODEC = StreamCodec.of(
                (buf, p) -> writeList(buf, p.names, (b, name) ->
                        net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(b, name)),
                buf -> new ActivityUnlocks(readList(buf, net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC::decode)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- duels

    /** C2S: the Duels tab was opened ({@code open}) or left; while it's open the server pushes changes. */
    public record DuelsView(boolean open) implements CustomPacketPayload {
        public static final Type<DuelsView> TYPE = new Type<>(id("duels_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DuelsView> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.open), buf -> new DuelsView(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: the player's own duel queue (the Home card): searching per mode, matched and waiting
     * for an arena, and how long they've searched (when sent; -1 when not searching).
     */
    public record DuelsStatus(boolean oneVsOne, boolean twoVsTwo, boolean pending, long searchingMillis)
            implements CustomPacketPayload {
        public static final Type<DuelsStatus> TYPE = new Type<>(id("duels_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DuelsStatus> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.oneVsOne);
                    buf.writeBoolean(p.twoVsTwo);
                    buf.writeBoolean(p.pending);
                    buf.writeVarLong(p.searchingMillis + 1);
                },
                buf -> new DuelsStatus(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readVarLong() - 1));

        public boolean searching() {
            return oneVsOne || twoVsTwo;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the player's duel is starting ({@code mode} 0 = 1v1, 1 = 2v2): a sound and a notice. */
    public record DuelsAlert(int mode) implements CustomPacketPayload {
        public static final Type<DuelsAlert> TYPE = new Type<>(id("duels_alert"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DuelsAlert> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.mode), buf -> new DuelsAlert(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: join ({@code join}) or leave a queue; {@code mode} 0 = 1v1, 1 = 2v2, -1 = every queue (leave only). */
    public record DuelsQueue(int mode, boolean join) implements CustomPacketPayload {
        public static final Type<DuelsQueue> TYPE = new Type<>(id("duels_queue"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DuelsQueue> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.mode + 1);
                    buf.writeBoolean(p.join);
                },
                buf -> new DuelsQueue(buf.readVarInt() - 1, buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The player's results in one mode. {@code rank}: 1-based leaderboard position, -1 unranked;
     * {@code queued}: players searching in that mode right now; {@code searching}: the player is.
     */
    public record DuelsMode(int rating, int wins, int losses, int draws, int rank, int queued, boolean searching) {
        public int played() {
            return wins + losses + draws;
        }

        static void write(RegistryFriendlyByteBuf buf, DuelsMode m) {
            buf.writeVarInt(m.rating);
            buf.writeVarInt(m.wins);
            buf.writeVarInt(m.losses);
            buf.writeVarInt(m.draws);
            buf.writeVarInt(m.rank + 1);
            buf.writeVarInt(m.queued);
            buf.writeBoolean(m.searching);
        }

        static DuelsMode read(RegistryFriendlyByteBuf buf) {
            return new DuelsMode(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt() - 1, buf.readVarInt(), buf.readBoolean());
        }
    }

    /**
     * One finished match from the player's side: {@code mode} 0 = 1v1, 1 = 2v2; {@code result}
     * 0 win, 1 loss, 2 draw; {@code agoMillis} how long ago it ended (when sent).
     */
    public record DuelsMatch(long agoMillis, int mode, int result, int ratingBefore, int ratingAfter,
                             List<String> teammates, List<String> opponents, long durationMillis) {
        static void write(RegistryFriendlyByteBuf buf, DuelsMatch m) {
            buf.writeVarLong(Math.max(0, m.agoMillis));
            buf.writeVarInt(m.mode);
            buf.writeVarInt(m.result);
            buf.writeVarInt(m.ratingBefore);
            buf.writeVarInt(m.ratingAfter);
            writeList(buf, m.teammates, (b, name) -> b.writeUtf(name, 64));
            writeList(buf, m.opponents, (b, name) -> b.writeUtf(name, 64));
            buf.writeVarLong(Math.max(0, m.durationMillis));
        }

        static DuelsMatch read(RegistryFriendlyByteBuf buf) {
            return new DuelsMatch(buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), readList(buf, b -> b.readUtf(64)), readList(buf, b -> b.readUtf(64)),
                    buf.readVarLong());
        }
    }

    /** A leaderboard row. */
    public record DuelsLeader(java.util.UUID uuid, String name, int rating, int played) {
        static void write(RegistryFriendlyByteBuf buf, DuelsLeader l) {
            buf.writeUUID(l.uuid);
            buf.writeUtf(l.name, 64);
            buf.writeVarInt(l.rating);
            buf.writeVarInt(l.played);
        }

        static DuelsLeader read(RegistryFriendlyByteBuf buf) {
            return new DuelsLeader(buf.readUUID(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt());
        }
    }

    /**
     * S2C: the Duels tab. {@code state}: 0 idle, 1 searching, 2 matched and waiting for an arena,
     * 3 in a match. {@code searchingMillis}: how long the player has searched (when sent), -1 when not;
     * {@code searchRange}: the Elo range it has widened to. {@code partySize} 0 = no party;
     * {@code partyLeader}: the player leads it. Rewards: today's battle currency and the daily cap.
     */
    public record DuelsData(DuelsMode oneVsOne, DuelsMode twoVsTwo, int state, long searchingMillis,
                            int searchRange, boolean viaParty, int partySize, boolean partyLeader,
                            boolean rewards, long earnedToday, long dailyCap,
                            List<DuelsMatch> history, List<DuelsLeader> top1v1, List<DuelsLeader> top2v2)
            implements CustomPacketPayload {
        public static final int IDLE = 0, SEARCHING = 1, PENDING = 2, IN_MATCH = 3;
        public static final Type<DuelsData> TYPE = new Type<>(id("duels_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DuelsData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    DuelsMode.write(buf, p.oneVsOne);
                    DuelsMode.write(buf, p.twoVsTwo);
                    buf.writeVarInt(p.state);
                    buf.writeVarLong(p.searchingMillis + 1);
                    buf.writeVarInt(p.searchRange);
                    buf.writeBoolean(p.viaParty);
                    buf.writeVarInt(p.partySize);
                    buf.writeBoolean(p.partyLeader);
                    buf.writeBoolean(p.rewards);
                    buf.writeVarLong(p.earnedToday);
                    buf.writeVarLong(p.dailyCap);
                    writeList(buf, p.history, DuelsMatch::write);
                    writeList(buf, p.top1v1, DuelsLeader::write);
                    writeList(buf, p.top2v2, DuelsLeader::write);
                },
                buf -> new DuelsData(DuelsMode.read(buf), DuelsMode.read(buf), buf.readVarInt(), buf.readVarLong() - 1,
                        buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readBoolean(),
                        buf.readBoolean(), buf.readVarLong(), buf.readVarLong(),
                        readList(buf, DuelsMatch::read), readList(buf, DuelsLeader::read), readList(buf, DuelsLeader::read)));

        public DuelsMode of(int mode) {
            return mode == 0 ? oneVsOne : twoVsTwo;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- settings

    /** C2S: the client opened the Settings tab. */
    public record SettingsDataRequest() implements CustomPacketPayload {
        public static final SettingsDataRequest INSTANCE = new SettingsDataRequest();
        public static final Type<SettingsDataRequest> TYPE = new Type<>(id("settings_data_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SettingsDataRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: the player's hub settings. {@code partyInvitesFrom} is a {@code HubSettings.InvitesFrom}
     * ordinal; {@code friendsAvailable} hides the "friends" choice without Minestar;
     * {@code theme} is the chosen hub theme and {@code themes} the ones this player may use;
     * {@code chatDeaths} / {@code chatAdvancements} are {@code HubSettings.MessagesFrom} ordinals,
     * {@code chatHidden} the hidden channels ({@code HubSettings.hiddenMask}); {@code notifyStyle}
     * a {@code HubSettings.NotifyStyle} ordinal, {@code notifyColor} 0 (theme) or a fixed color,
     * {@code notifyOff} the turned-off notification kinds and {@code seenTabs} the opened tabs, as bits;
     * {@code mentionVolume} the mention ping's volume, 0-100.
     */
    public record SettingsData(int partyInvitesFrom, boolean friendsAvailable, String theme,
                               List<String> themes, boolean inGuild, boolean showGuildTag,
                               int chatDeaths, int chatAdvancements, int chatHidden, boolean chatFocus,
                               int mentionVolume, int profileVisibleTo, int notifyStyle, int notifyColor,
                               int notifyOff, int seenTabs, int hubLanguage, List<LookOffer> themeOffers,
                               boolean themeIconsOff)
            implements CustomPacketPayload {
        public static final Type<SettingsData> TYPE = new Type<>(id("settings_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SettingsData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.partyInvitesFrom);
                    buf.writeBoolean(p.friendsAvailable);
                    buf.writeUtf(p.theme);
                    writeStrings(buf, p.themes);
                    buf.writeBoolean(p.inGuild);
                    buf.writeBoolean(p.showGuildTag);
                    buf.writeVarInt(p.chatDeaths);
                    buf.writeVarInt(p.chatAdvancements);
                    buf.writeVarInt(p.chatHidden);
                    buf.writeBoolean(p.chatFocus);
                    buf.writeVarInt(p.mentionVolume);
                    buf.writeVarInt(p.profileVisibleTo);
                    buf.writeVarInt(p.notifyStyle);
                    buf.writeVarInt(p.notifyColor);
                    buf.writeVarInt(p.notifyOff);
                    buf.writeVarInt(p.seenTabs);
                    buf.writeVarInt(p.hubLanguage);
                    writeList(buf, p.themeOffers, LookOffer::write);
                    buf.writeBoolean(p.themeIconsOff);
                },
                buf -> new SettingsData(buf.readVarInt(), buf.readBoolean(), buf.readUtf(), readStrings(buf),
                        buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), readList(buf, LookOffer::read),
                        buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: someone @mentioned the player — the client plays the ping at the player's own volume. */
    public record MentionPing() implements CustomPacketPayload {
        public static final MentionPing INSTANCE = new MentionPing();
        public static final Type<MentionPing> TYPE = new Type<>(id("mention_ping"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MentionPing> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: pick a hub theme (must be free or unlocked for the player). */
    public record ThemeSelect(String theme) implements CustomPacketPayload {
        public static final Type<ThemeSelect> TYPE = new Type<>(id("theme_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ThemeSelect> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.theme, 128),
                buf -> new ThemeSelect(buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- chat channels

    /** C2S: the channel the player now types into (ChatChannels.Channel ordinal). */
    public record ChatChannelSelect(int channel) implements CustomPacketPayload {
        public static final Type<ChatChannelSelect> TYPE = new Type<>(id("chat_channel_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChatChannelSelect> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.channel),
                buf -> new ChatChannelSelect(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: the next chat message goes to this channel once ("!l hi" typed: the client strips the
     * prefix and sends this right before the message, so the signed text stays clean).
     */
    public record ChatOneShot(int channel) implements CustomPacketPayload {
        public static final Type<ChatOneShot> TYPE = new Type<>(id("chat_one_shot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChatOneShot> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.channel),
                buf -> new ChatOneShot(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: the player's channel, whether PARTY / GUILD are open to them, and whether they get the
     * chat screen's emoji picker button (permission {@code eden_ld.emoji_picker}).
     */
    public record ChatChannelState(int channel, boolean party, boolean guild, boolean emojiPicker)
            implements CustomPacketPayload {
        public static final Type<ChatChannelState> TYPE = new Type<>(id("chat_channel_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChatChannelState> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.channel);
                    buf.writeBoolean(p.party);
                    buf.writeBoolean(p.guild);
                    buf.writeBoolean(p.emojiPicker);
                },
                buf -> new ChatChannelState(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Longest emoji id or name on the wire. Standard emoji ids reach 56 characters
     * ("u:1F9D1-1F3FB-200D-2764-FE0F-200D-1F48B-200D-1F9D1-1F3FC"), names 76 — a smaller limit
     * makes encoding throw, which drops the connection.
     */
    private static final int EMOJI_TEXT = 96;

    /**
     * One Discord emoji: its :name:, its character in the eden_ld:emoji font, its Discord id, and
     * for animated ones each frame's delay in ms (the image is then a strip of square frames).
     */
    public record EmojiRef(String name, int codepoint, String id, List<Integer> delays, boolean guest) {
        static void write(RegistryFriendlyByteBuf buf, EmojiRef ref) {
            buf.writeUtf(ref.name, EMOJI_TEXT);
            buf.writeVarInt(ref.codepoint);
            buf.writeUtf(ref.id, EMOJI_TEXT);
            writeList(buf, ref.delays, RegistryFriendlyByteBuf::writeVarInt);
            buf.writeBoolean(ref.guest);
        }

        static EmojiRef read(RegistryFriendlyByteBuf buf) {
            return new EmojiRef(buf.readUtf(EMOJI_TEXT), buf.readVarInt(), buf.readUtf(EMOJI_TEXT),
                    readList(buf, RegistryFriendlyByteBuf::readVarInt), buf.readBoolean());
        }

        public boolean animated() {
            return !delays.isEmpty();
        }
    }

    /**
     * S2C: the server's emojis (full replace), images not included — the client uses its cache
     * (images by emoji id) and asks for the rest with {@link EmojiRequest}.
     */
    public record EmojiList(List<EmojiRef> emojis, List<String> favorites, int favoritesLimit,
                            List<EmojiGroup> groups, List<String> locked,
                            List<String> stickers, int stickersLimit) implements CustomPacketPayload {
        public static final Type<EmojiList> TYPE = new Type<>(id("emoji_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EmojiList> CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeList(buf, p.emojis, EmojiRef::write);
                    writeList(buf, p.favorites, (b, id) -> b.writeUtf(id, EMOJI_TEXT));
                    buf.writeVarInt(p.favoritesLimit);
                    writeList(buf, p.groups, EmojiGroup::write);
                    writeList(buf, p.locked, (b, id) -> b.writeUtf(id, EMOJI_TEXT));
                    writeList(buf, p.stickers, (b, id) -> b.writeUtf(id, EMOJI_TEXT));
                    buf.writeVarInt(p.stickersLimit);
                },
                buf -> new EmojiList(readList(buf, EmojiRef::read), readList(buf, b -> b.readUtf(EMOJI_TEXT)),
                        buf.readVarInt(), readList(buf, EmojiGroup::read), readList(buf, b -> b.readUtf(EMOJI_TEXT)),
                        readList(buf, b -> b.readUtf(EMOJI_TEXT)), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A picker tab of the server's emojis the player may use: id, title, icon emoji name, emoji ids. */
    public record EmojiGroup(String id, String name, String icon, List<String> emojis) {
        static void write(RegistryFriendlyByteBuf buf, EmojiGroup group) {
            buf.writeUtf(group.id, EMOJI_TEXT);
            buf.writeUtf(group.name, EMOJI_TEXT);
            buf.writeUtf(group.icon, EMOJI_TEXT);
            writeList(buf, group.emojis, (b, id) -> b.writeUtf(id, EMOJI_TEXT));
        }

        static EmojiGroup read(RegistryFriendlyByteBuf buf) {
            return new EmojiGroup(buf.readUtf(EMOJI_TEXT), buf.readUtf(EMOJI_TEXT), buf.readUtf(EMOJI_TEXT),
                    readList(buf, b -> b.readUtf(EMOJI_TEXT)));
        }
    }

    /** S2C: guest emojis that just arrived from Discord (added to the client's list). */
    public record EmojiGuests(List<EmojiRef> emojis) implements CustomPacketPayload {
        public static final Type<EmojiGuests> TYPE = new Type<>(id("emoji_guests"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EmojiGuests> CODEC = StreamCodec.of(
                (buf, p) -> writeList(buf, p.emojis, EmojiRef::write),
                buf -> new EmojiGuests(readList(buf, EmojiRef::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: every listed player's rank for the tab list (higher first); empty = vanilla order.
     * 1.21.1's player list has no order field, so the client sorts by these.
     */
    public record TabOrder(java.util.Map<java.util.UUID, Integer> weights) implements CustomPacketPayload {
        public static final Type<TabOrder> TYPE = new Type<>(HubPayloads.id("tab_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TabOrder> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.weights.size());
                    p.weights.forEach((uuid, weight) -> {
                        buf.writeUUID(uuid);
                        buf.writeInt(weight);
                    });
                },
                buf -> {
                    int size = buf.readVarInt();
                    java.util.Map<java.util.UUID, Integer> weights = new java.util.HashMap<>();
                    for (int i = 0; i < size; i++) {
                        weights.put(buf.readUUID(), buf.readInt());
                    }
                    return new TabOrder(weights);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: star or unstar an emoji (by id) in the player's picker. */
    public record EmojiFavorite(String id, boolean add) implements CustomPacketPayload {
        public static final Type<EmojiFavorite> TYPE = new Type<>(HubPayloads.id("emoji_favorite"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EmojiFavorite> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.id, EMOJI_TEXT);
                    buf.writeBoolean(p.add);
                },
                buf -> new EmojiFavorite(buf.readUtf(EMOJI_TEXT), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: emoji images (by id) the client has no cached copy of. */
    public record EmojiRequest(List<String> ids) implements CustomPacketPayload {
        public static final Type<EmojiRequest> TYPE = new Type<>(id("emoji_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EmojiRequest> CODEC = StreamCodec.of(
                (buf, p) -> writeList(buf, p.ids, (b, id) -> b.writeUtf(id, EMOJI_TEXT)),
                buf -> new EmojiRequest(readList(buf, b -> b.readUtf(EMOJI_TEXT))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One emoji image (PNG) by Discord id. */
    public record EmojiImage(String id, byte[] png) {
        static void write(RegistryFriendlyByteBuf buf, EmojiImage image) {
            buf.writeUtf(image.id, EMOJI_TEXT);
            buf.writeByteArray(image.png);
        }

        static EmojiImage read(RegistryFriendlyByteBuf buf) {
            return new EmojiImage(buf.readUtf(EMOJI_TEXT), buf.readByteArray(262144));
        }
    }

    /** S2C: the images a client asked for. */
    public record EmojiImages(List<EmojiImage> images) implements CustomPacketPayload {
        public static final Type<EmojiImages> TYPE = new Type<>(id("emoji_images"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EmojiImages> CODEC = StreamCodec.of(
                (buf, p) -> writeList(buf, p.images, EmojiImage::write),
                buf -> new EmojiImages(readList(buf, EmojiImage::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: change one setting; the key is validated server side ({@code HubSettings}). */
    public record SettingChange(String key, int value) implements CustomPacketPayload {
        public static final Type<SettingChange> TYPE = new Type<>(id("setting_change"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SettingChange> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.key, 64);
                    buf.writeVarInt(p.value);
                },
                buf -> new SettingChange(buf.readUtf(64), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- helpers

    // ---------------------------------------------------------------- profile

    /** C2S: open a player's profile (the viewer's own uuid for their own). */
    public record ProfileRequest(java.util.UUID target) implements CustomPacketPayload {
        public static final Type<ProfileRequest> TYPE = new Type<>(id("profile_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ProfileRequest> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUUID(p.target), buf -> new ProfileRequest(buf.readUUID()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** An earned Minestar reward. {@code rarity}: 0 common, 1 rare, 2 epic, 3 legendary. */
    public record ProfileReward(long id, String title, String description, String imageUrl, int rarity) {
        static void write(RegistryFriendlyByteBuf buf, ProfileReward reward) {
            buf.writeVarLong(reward.id);
            buf.writeUtf(reward.title, 256);
            buf.writeUtf(reward.description, 1024);
            buf.writeUtf(reward.imageUrl, 1024);
            buf.writeVarInt(reward.rarity);
        }

        static ProfileReward read(RegistryFriendlyByteBuf buf) {
            return new ProfileReward(buf.readVarLong(), buf.readUtf(256), buf.readUtf(1024), buf.readUtf(1024),
                    buf.readVarInt());
        }
    }

    /** A level perk rule ({@code LevelPerks.Rule}), so the client can draw the level track. */
    public record PerkRule(String perk, int from, int every, int amount, int until) {
        static void write(RegistryFriendlyByteBuf buf, PerkRule rule) {
            buf.writeUtf(rule.perk, 64);
            buf.writeVarInt(rule.from);
            buf.writeVarInt(rule.every);
            buf.writeVarInt(rule.amount);
            buf.writeVarInt(rule.until);
        }

        static PerkRule read(RegistryFriendlyByteBuf buf) {
            return new PerkRule(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /**
     * S2C: a profile page. {@code access}: {@link #SHOWN}, {@link #PRIVATE} (the owner's privacy
     * setting), {@link #UNKNOWN} (no such player). {@code platform}: Minestar data is present (group,
     * prime, level, all-servers playtime, registration, rewards). {@code groupColor} RGB or -1;
     * {@code registeredAt} epoch millis or -1; {@code serverPlaytimeTicks} this server's playtime
     * (-1 unknown). {@code backgrounds} and {@code perks} only come with the viewer's own profile.
     */
    public record ProfileData(java.util.UUID uuid, String name, int access, boolean self, boolean platform,
                              String group, int groupColor, boolean prime, int level, long totalXp, long levelXp,
                              long levelXpNeeded, long xpToNextLevel, long playtimeSeconds, long serverPlaytimeTicks,
                              long registeredAt, List<ProfileReward> rewards, String background,
                              List<String> backgrounds, List<LookOffer> offers, String guildName,
                              String guildTag, int guildColor, List<PerkRule> perks) implements CustomPacketPayload {
        public static final int SHOWN = 0;
        public static final int PRIVATE = 1;
        public static final int UNKNOWN = 2;
        public static final Type<ProfileData> TYPE = new Type<>(id("profile_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ProfileData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.uuid);
                    buf.writeUtf(p.name, 64);
                    buf.writeVarInt(p.access);
                    buf.writeBoolean(p.self);
                    buf.writeBoolean(p.platform);
                    buf.writeUtf(p.group, 128);
                    buf.writeInt(p.groupColor);
                    buf.writeBoolean(p.prime);
                    buf.writeVarInt(p.level);
                    buf.writeVarLong(p.totalXp);
                    buf.writeVarLong(p.levelXp);
                    buf.writeVarLong(p.levelXpNeeded);
                    buf.writeVarLong(p.xpToNextLevel);
                    buf.writeVarLong(p.playtimeSeconds);
                    buf.writeLong(p.serverPlaytimeTicks);
                    buf.writeLong(p.registeredAt);
                    writeList(buf, p.rewards, ProfileReward::write);
                    buf.writeUtf(p.background, 128);
                    writeStrings(buf, p.backgrounds);
                    writeList(buf, p.offers, (b, o) -> {
                        b.writeUtf(o.id(), 128);
                        b.writeVarLong(o.price());
                    });
                    buf.writeUtf(p.guildName, 128);
                    buf.writeUtf(p.guildTag, 32);
                    buf.writeInt(p.guildColor);
                    writeList(buf, p.perks, PerkRule::write);
                },
                buf -> new ProfileData(buf.readUUID(), buf.readUtf(64), buf.readVarInt(), buf.readBoolean(),
                        buf.readBoolean(), buf.readUtf(128), buf.readInt(), buf.readBoolean(), buf.readVarInt(),
                        buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
                        buf.readLong(), buf.readLong(), readList(buf, ProfileReward::read), buf.readUtf(128),
                        readStrings(buf), readList(buf, LookOffer::read),
                        buf.readUtf(128), buf.readUtf(32), buf.readInt(), readList(buf, PerkRule::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: pick a profile background (must be free or unlocked for the player). */
    public record BackgroundSelect(String background) implements CustomPacketPayload {
        public static final Type<BackgroundSelect> TYPE = new Type<>(id("background_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BackgroundSelect> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.background, 128),
                buf -> new BackgroundSelect(buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static <T> void writeList(RegistryFriendlyByteBuf buf, List<T> list,
                                      java.util.function.BiConsumer<RegistryFriendlyByteBuf, T> writer) {
        buf.writeVarInt(list.size());
        for (T item : list) {
            writer.accept(buf, item);
        }
    }

    private static <T> List<T> readList(RegistryFriendlyByteBuf buf,
                                        java.util.function.Function<RegistryFriendlyByteBuf, T> reader) {
        int count = buf.readVarInt();
        List<T> list = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            list.add(reader.apply(buf));
        }
        return list;
    }

    private static void writeStrings(RegistryFriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) {
            buf.writeUtf(s);
        }
    }

    private static List<String> readStrings(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(buf.readUtf());
        }
        return list;
    }

    private static void writeMembers(RegistryFriendlyByteBuf buf, List<PartyMember> list) {
        buf.writeVarInt(list.size());
        for (PartyMember member : list) {
            PartyMember.write(buf, member);
        }
    }

    private static List<PartyMember> readMembers(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<PartyMember> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(PartyMember.read(buf));
        }
        return list;
    }

    private static void writeRefs(RegistryFriendlyByteBuf buf, List<PartyRef> list) {
        buf.writeVarInt(list.size());
        for (PartyRef ref : list) {
            PartyRef.write(buf, ref);
        }
    }

    private static List<PartyRef> readRefs(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<PartyRef> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(PartyRef.read(buf));
        }
        return list;
    }

    private static void writeChat(RegistryFriendlyByteBuf buf, List<ChatEntry> list) {
        buf.writeVarInt(list.size());
        for (ChatEntry entry : list) {
            ChatEntry.write(buf, entry);
        }
    }

    private static List<ChatEntry> readChat(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ChatEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(ChatEntry.read(buf));
        }
        return list;
    }

    // ---------------------------------------------------------------- guild quests and levels

    /** C2S: the guild's quest board and level, please. */
    public record GuildQuestsRequest() implements CustomPacketPayload {
        public static final GuildQuestsRequest INSTANCE = new GuildQuestsRequest();
        public static final Type<GuildQuestsRequest> TYPE = new Type<>(id("guild_quests_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuildQuestsRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One board quest. {@code period} 0 daily, 1 weekly; {@code title}/{@code objective} as for
     * dailies; rewards: guild XP, LC into the bank (0 without Economy_LD), items each member gets
     * by mail. {@code mine}: the viewer's share; {@code topName}/{@code topAmount}: the biggest.
     */
    public record GuildQuestEntry(int period, net.minecraft.network.chat.Component title,
                                  net.minecraft.network.chat.Component objective,
                                  net.minecraft.resources.ResourceLocation icon, int progress, int goal, boolean done,
                                  int guildXp, long lc, List<net.minecraft.world.item.ItemStack> items, int mine,
                                  String topName, int topAmount) {
        static void write(RegistryFriendlyByteBuf buf, GuildQuestEntry e) {
            buf.writeVarInt(e.period);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, e.title);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, e.objective);
            buf.writeResourceLocation(e.icon);
            buf.writeVarInt(e.progress);
            buf.writeVarInt(e.goal);
            buf.writeBoolean(e.done);
            buf.writeVarInt(e.guildXp);
            buf.writeVarLong(e.lc);
            writeList(buf, e.items, (b, stack) -> net.minecraft.world.item.ItemStack.STREAM_CODEC.encode(b, stack));
            buf.writeVarInt(e.mine);
            buf.writeUtf(e.topName, 64);
            buf.writeVarInt(e.topAmount);
        }

        static GuildQuestEntry read(RegistryFriendlyByteBuf buf) {
            return new GuildQuestEntry(buf.readVarInt(),
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf),
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf),
                    buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                    buf.readVarInt(), buf.readVarLong(),
                    readList(buf, net.minecraft.world.item.ItemStack.STREAM_CODEC::decode),
                    buf.readVarInt(), buf.readUtf(64), buf.readVarInt());
        }
    }

    /**
     * S2C: the guild's level ({@code xpInto} of {@code xpNeeded} toward the next; needed 0 at
     * {@code maxLevel}), the reset countdowns, the board, and the level perk rules (for the Level
     * sub-tab's track). Pushed to online members when the board moves (at most once a second).
     */
    public record GuildQuestsData(int level, long xpInto, long xpNeeded, int maxLevel, long dailyResetMillis,
                                  long weeklyResetMillis, List<GuildQuestEntry> quests, List<PerkRule> perks,
                                  boolean economy) implements CustomPacketPayload {
        public static final Type<GuildQuestsData> TYPE = new Type<>(id("guild_quests_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuildQuestsData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.level);
                    buf.writeVarLong(p.xpInto);
                    buf.writeVarLong(p.xpNeeded);
                    buf.writeVarInt(p.maxLevel);
                    buf.writeVarLong(p.dailyResetMillis);
                    buf.writeVarLong(p.weeklyResetMillis);
                    writeList(buf, p.quests, GuildQuestEntry::write);
                    writeList(buf, p.perks, PerkRule::write);
                    buf.writeBoolean(p.economy);
                },
                buf -> new GuildQuestsData(buf.readVarInt(), buf.readVarLong(), buf.readVarLong(), buf.readVarInt(),
                        buf.readVarLong(), buf.readVarLong(), readList(buf, GuildQuestEntry::read),
                        readList(buf, PerkRule::read), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(OpenHub.TYPE, OpenHub.CODEC);
        PayloadTypeRegistry.playS2C().register(MentionPing.TYPE, MentionPing.CODEC);
        PayloadTypeRegistry.playC2S().register(GuildQuestsRequest.TYPE, GuildQuestsRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(GuildQuestsData.TYPE, GuildQuestsData.CODEC);
        PayloadTypeRegistry.playC2S().register(ProfileRequest.TYPE, ProfileRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(ProfileData.TYPE, ProfileData.CODEC);
        PayloadTypeRegistry.playC2S().register(BackgroundSelect.TYPE, BackgroundSelect.CODEC);
        PayloadTypeRegistry.playS2C().register(HomeData.TYPE, HomeData.CODEC);
        PayloadTypeRegistry.playS2C().register(PartyData.TYPE, PartyData.CODEC);
        PayloadTypeRegistry.playS2C().register(PartyChatLine.TYPE, PartyChatLine.CODEC);
        PayloadTypeRegistry.playC2S().register(HomeDataRequest.TYPE, HomeDataRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(PartyDataRequest.TYPE, PartyDataRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(PartyAction.TYPE, PartyAction.CODEC);
        PayloadTypeRegistry.playS2C().register(FriendsData.TYPE, FriendsData.CODEC);
        PayloadTypeRegistry.playC2S().register(FriendsDataRequest.TYPE, FriendsDataRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(FriendAction.TYPE, FriendAction.CODEC);
        PayloadTypeRegistry.playS2C().register(IgnoreData.TYPE, IgnoreData.CODEC);
        PayloadTypeRegistry.playC2S().register(IgnoreAction.TYPE, IgnoreAction.CODEC);
        PayloadTypeRegistry.playS2C().register(SettingsData.TYPE, SettingsData.CODEC);
        PayloadTypeRegistry.playS2C().register(ActivitiesData.TYPE, ActivitiesData.CODEC);
        PayloadTypeRegistry.playS2C().register(ActivityUnlocks.TYPE, ActivityUnlocks.CODEC);
        PayloadTypeRegistry.playS2C().register(DuelsData.TYPE, DuelsData.CODEC);
        PayloadTypeRegistry.playS2C().register(DuelsStatus.TYPE, DuelsStatus.CODEC);
        PayloadTypeRegistry.playS2C().register(DuelsAlert.TYPE, DuelsAlert.CODEC);
        PayloadTypeRegistry.playS2C().register(DailiesData.TYPE, DailiesData.CODEC);
        PayloadTypeRegistry.playS2C().register(HubNotice.TYPE, HubNotice.CODEC);
        PayloadTypeRegistry.playS2C().register(MailData.TYPE, MailData.CODEC);
        PayloadTypeRegistry.playC2S().register(MailAction.TYPE, MailAction.CODEC);
        PayloadTypeRegistry.playC2S().register(ModOpen.TYPE, ModOpen.CODEC);
        PayloadTypeRegistry.playS2C().register(ModInfo.TYPE, ModInfo.CODEC);
        PayloadTypeRegistry.playC2S().register(DraftSave.TYPE, DraftSave.CODEC);
        PayloadTypeRegistry.playC2S().register(MailSend.TYPE, MailSend.CODEC);
        PayloadTypeRegistry.playS2C().register(MailSendCheck.TYPE, MailSendCheck.CODEC);
        PayloadTypeRegistry.playC2S().register(MailLogRequest.TYPE, MailLogRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(MailLog.TYPE, MailLog.CODEC);
        PayloadTypeRegistry.playS2C().register(ModNotice.TYPE, ModNotice.CODEC);
        PayloadTypeRegistry.playC2S().register(NewsSave.TYPE, NewsSave.CODEC);
        PayloadTypeRegistry.playC2S().register(AdminRequest.TYPE, AdminRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(AdminData.TYPE, AdminData.CODEC);
        PayloadTypeRegistry.playC2S().register(AdminAction.TYPE, AdminAction.CODEC);
        PayloadTypeRegistry.playC2S().register(LookRuleSave.TYPE, LookRuleSave.CODEC);
        PayloadTypeRegistry.playC2S().register(LookBuy.TYPE, LookBuy.CODEC);
        PayloadTypeRegistry.playC2S().register(LookGift.TYPE, LookGift.CODEC);
        PayloadTypeRegistry.playC2S().register(WardrobeRequest.TYPE, WardrobeRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(WardrobeData.TYPE, WardrobeData.CODEC);
        PayloadTypeRegistry.playC2S().register(PunishSearch.TYPE, PunishSearch.CODEC);
        PayloadTypeRegistry.playS2C().register(PunishPlayers.TYPE, PunishPlayers.CODEC);
        PayloadTypeRegistry.playC2S().register(PunishPageRequest.TYPE, PunishPageRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(PunishPage.TYPE, PunishPage.CODEC);
        PayloadTypeRegistry.playC2S().register(PunishAction.TYPE, PunishAction.CODEC);
        PayloadTypeRegistry.playC2S().register(PunishLogRequest.TYPE, PunishLogRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(PunishLog.TYPE, PunishLog.CODEC);
        PayloadTypeRegistry.playS2C().register(NameStyles.TYPE, NameStyles.CODEC);
        PayloadTypeRegistry.playS2C().register(GuildData.TYPE, GuildData.CODEC);
        PayloadTypeRegistry.playS2C().register(GuildChatLine.TYPE, GuildChatLine.CODEC);
        PayloadTypeRegistry.playC2S().register(GuildDataRequest.TYPE, GuildDataRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(GuildAction.TYPE, GuildAction.CODEC);
        PayloadTypeRegistry.playS2C().register(BankData.TYPE, BankData.CODEC);
        PayloadTypeRegistry.playC2S().register(BankView.TYPE, BankView.CODEC);
        PayloadTypeRegistry.playC2S().register(BankAction.TYPE, BankAction.CODEC);
        PayloadTypeRegistry.playC2S().register(DailiesDataRequest.TYPE, DailiesDataRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(DailyAction.TYPE, DailyAction.CODEC);
        PayloadTypeRegistry.playS2C().register(WaystonesData.TYPE, WaystonesData.CODEC);
        PayloadTypeRegistry.playS2C().register(WaystoneTeleportResult.TYPE, WaystoneTeleportResult.CODEC);
        PayloadTypeRegistry.playC2S().register(WaystonesDataRequest.TYPE, WaystonesDataRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(WaystoneTeleport.TYPE, WaystoneTeleport.CODEC);
        PayloadTypeRegistry.playC2S().register(WaystoneFavorite.TYPE, WaystoneFavorite.CODEC);
        PayloadTypeRegistry.playC2S().register(WaystoneRename.TYPE, WaystoneRename.CODEC);
        PayloadTypeRegistry.playC2S().register(WaystoneForget.TYPE, WaystoneForget.CODEC);
        PayloadTypeRegistry.playC2S().register(WaystoneOrder.TYPE, WaystoneOrder.CODEC);
        PayloadTypeRegistry.playC2S().register(ActivitiesRequest.TYPE, ActivitiesRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(ActivityOpen.TYPE, ActivityOpen.CODEC);
        PayloadTypeRegistry.playC2S().register(ActivitiesSeen.TYPE, ActivitiesSeen.CODEC);
        PayloadTypeRegistry.playC2S().register(DuelsView.TYPE, DuelsView.CODEC);
        PayloadTypeRegistry.playC2S().register(DuelsQueue.TYPE, DuelsQueue.CODEC);
        PayloadTypeRegistry.playC2S().register(SettingsDataRequest.TYPE, SettingsDataRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(SettingChange.TYPE, SettingChange.CODEC);
        PayloadTypeRegistry.playC2S().register(ThemeSelect.TYPE, ThemeSelect.CODEC);
        PayloadTypeRegistry.playC2S().register(ChatChannelSelect.TYPE, ChatChannelSelect.CODEC);
        PayloadTypeRegistry.playC2S().register(ChatOneShot.TYPE, ChatOneShot.CODEC);
        PayloadTypeRegistry.playS2C().register(ChatChannelState.TYPE, ChatChannelState.CODEC);
        PayloadTypeRegistry.playS2C().register(EmojiList.TYPE, EmojiList.CODEC);
        PayloadTypeRegistry.playC2S().register(EmojiRequest.TYPE, EmojiRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(EmojiImages.TYPE, EmojiImages.CODEC);
        PayloadTypeRegistry.playS2C().register(EmojiGuests.TYPE, EmojiGuests.CODEC);
        PayloadTypeRegistry.playC2S().register(EmojiFavorite.TYPE, EmojiFavorite.CODEC);
        PayloadTypeRegistry.playS2C().register(TabOrder.TYPE, TabOrder.CODEC);
    }
}
