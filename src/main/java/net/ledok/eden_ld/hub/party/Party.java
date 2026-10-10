package net.ledok.eden_ld.hub.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One party's state. Parties are TEMPORARY: never persisted, destroyed when the
 * last member logs off or the member count drops below 2. Friendly fire between
 * members is always off (no toggle). Mutations go through {@link PartyManager}.
 */
public final class Party {

    /** Who may invite new members. */
    public enum InvitePolicy { LEADER, VICE, EVERYONE }

    /**
     * How outsiders may get in without an invite. INVITE_ONLY: they can't.
     * FRIENDS: Minestar friends of a member join directly. OPEN: anyone joins directly.
     */
    public enum JoinPolicy { INVITE_ONLY, FRIENDS, OPEN }

    public final UUID id;
    /** Unique handle ("{@code <creator>_party}") — what admin commands address a party by. */
    public final String tag;
    /** Display name; free-form, not unique. */
    public String name;
    public InvitePolicy invitePolicy = InvitePolicy.VICE;
    public JoinPolicy joinPolicy = JoinPolicy.INVITE_ONLY;

    public final Map<UUID, PartyRank> members = new HashMap<>();
    /** Display names (members may be inside their offline grace window). */
    public final Map<UUID, String> memberNames = new HashMap<>();
    /** Join order, oldest first — leader succession follows it. */
    public final List<UUID> memberOrder = new ArrayList<>();
    /** Disconnect timestamps (ms); absent = online. Grace-expired members are auto-kicked. */
    public final Map<UUID, Long> offlineSince = new HashMap<>();

    /**
     * One chat line, styled as the game's chat shows it (hover and click included); the sender
     * uuid lets clients render the player's head.
     */
    public record ChatLine(UUID sender, String name, net.minecraft.network.chat.Component text) {
    }

    /** Recent chat lines, newest last. */
    public final List<ChatLine> chatHistory = new ArrayList<>();

    public Party(UUID id, String tag, String name) {
        this.id = id;
        this.tag = tag;
        this.name = name;
    }

    /** The party's name: its custom name, or "<leader>'s Party" in the reader's language. */
    public net.minecraft.network.chat.Component title() {
        if (name != null && !name.isEmpty()) {
            return net.minecraft.network.chat.Component.literal(name);
        }
        UUID leader = leader();
        String leaderName = leader == null ? "?" : memberNames.getOrDefault(leader, "?");
        return net.minecraft.network.chat.Component.translatable("gui.eden_ld.hub.party.default_name", leaderName);
    }

    public UUID leader() {
        return members.entrySet().stream()
                .filter(e -> e.getValue() == PartyRank.LEADER)
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    public PartyRank rank(UUID player) {
        return members.getOrDefault(player, PartyRank.MEMBER);
    }

    public boolean mayInvite(UUID player) {
        PartyRank rank = members.get(player);
        if (rank == null) {
            return false;
        }
        return switch (invitePolicy) {
            case LEADER -> rank == PartyRank.LEADER;
            case VICE -> rank.level >= PartyRank.VICE.level;
            case EVERYONE -> true;
        };
    }

    /** Leader/vice manage membership (kick below their rank) and settings. */
    public boolean mayManage(UUID player) {
        return rank(player).level >= PartyRank.VICE.level;
    }
}
