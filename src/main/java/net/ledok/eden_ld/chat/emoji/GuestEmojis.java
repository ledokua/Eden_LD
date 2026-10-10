package net.ledok.eden_ld.chat.emoji;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ledok.eden_ld.EdenLD;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guest emojis: custom emojis that came in from Discord but aren't in emoji.json (other servers'
 * emojis via Nitro, or ones not listed yet). Each keeps a character of its own (U+F8000 on) across
 * restarts — players can favorite them — in {@code config/eden_ld/emoji/guests.json}. When the
 * store is full, the guest seen longest ago makes room.
 */
final class GuestEmojis {

    static final int FIRST_CODEPOINT = 0xF8000;
    static final int LAST_CODEPOINT = 0xF8FFF;
    private static final int MAX_GUESTS = 500;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    record Guest(String id, String name, boolean animated, int codepoint, long seen) {
    }

    private final Path file;
    private final Map<String, Guest> byId = new LinkedHashMap<>();

    GuestEmojis(Path file) {
        this.file = file;
    }

    synchronized void load() {
        byId.clear();
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject guest = entry.getValue().getAsJsonObject();
                int codepoint = guest.get("codepoint").getAsInt();
                if (codepoint < FIRST_CODEPOINT || codepoint > LAST_CODEPOINT) {
                    continue;
                }
                byId.put(entry.getKey(), new Guest(entry.getKey(), guest.get("name").getAsString(),
                        guest.get("animated").getAsBoolean(), codepoint, guest.get("seen").getAsLong()));
            }
        } catch (IOException | RuntimeException e) {
            EdenLD.LOGGER.error("[Emoji] Can't read {}.", file, e);
        }
    }

    synchronized List<Guest> all() {
        return new ArrayList<>(byId.values());
    }

    synchronized Guest get(String id) {
        return byId.get(id);
    }

    /**
     * Registers a new guest (or refreshes a known one), making room when full — never evicting
     * one in {@code keep} (favorites). Null when there's no room.
     */
    synchronized Guest add(String id, String name, boolean animated, Set<String> keep) {
        long now = System.currentTimeMillis();
        Guest known = byId.get(id);
        if (known != null) {
            Guest refreshed = new Guest(id, known.name(), known.animated(), known.codepoint(), now);
            byId.put(id, refreshed);
            return refreshed;
        }
        if (byId.size() >= MAX_GUESTS) {
            byId.values().stream().filter(guest -> !keep.contains(guest.id()))
                    .min(Comparator.comparingLong(Guest::seen))
                    .ifPresent(oldest -> byId.remove(oldest.id()));
        }
        BitSet used = new BitSet();
        byId.values().forEach(guest -> used.set(guest.codepoint() - FIRST_CODEPOINT));
        int free = used.nextClearBit(0);
        if (byId.size() >= MAX_GUESTS || FIRST_CODEPOINT + free > LAST_CODEPOINT) {
            return null;
        }
        Guest guest = new Guest(id, name, animated, FIRST_CODEPOINT + free, now);
        byId.put(id, guest);
        return guest;
    }

    synchronized void save() {
        JsonObject root = new JsonObject();
        byId.values().forEach(guest -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", guest.name());
            entry.addProperty("animated", guest.animated());
            entry.addProperty("codepoint", guest.codepoint());
            entry.addProperty("seen", guest.seen());
            root.add(guest.id(), entry);
        });
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            EdenLD.LOGGER.error("[Emoji] Can't save {}.", file, e);
        }
    }
}
