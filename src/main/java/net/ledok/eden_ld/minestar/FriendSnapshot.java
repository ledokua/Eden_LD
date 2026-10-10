package net.ledok.eden_ld.minestar;

import java.util.List;
import java.util.UUID;

/**
 * A player's Minestar friends and pending requests, copied out of the platform
 * types so callers never touch {@code ua.com.minestar} classes. Times are epoch
 * milliseconds, {@code -1} when unknown.
 */
public record FriendSnapshot(List<Friend> friends, List<Request> incoming, List<Request> outgoing) {

    /**
     * {@code online} is the launcher-reported status (~90s window), not "on this server";
     * {@code playing} holds modpack display names, {@code playingIds} the matching pack ids.
     */
    public record Friend(UUID uuid, String nickname, boolean online, long lastSeenMillis,
                         List<String> playing, List<String> playingIds) {
    }

    public record Request(UUID uuid, String nickname, long sentAtMillis) {
    }

    /** The nickname of anyone in this snapshot, or null when the uuid isn't in it. */
    public String nicknameOf(UUID uuid) {
        for (Friend friend : friends) {
            if (friend.uuid().equals(uuid)) {
                return friend.nickname();
            }
        }
        for (Request request : incoming) {
            if (request.uuid().equals(uuid)) {
                return request.nickname();
            }
        }
        for (Request request : outgoing) {
            if (request.uuid().equals(uuid)) {
                return request.nickname();
            }
        }
        return null;
    }
}
