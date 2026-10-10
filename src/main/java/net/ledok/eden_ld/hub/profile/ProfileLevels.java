package net.ledok.eden_ld.hub.profile;

import net.ledok.eden_ld.chat.emoji.EmojiRegistry;
import net.ledok.eden_ld.compat.opac.OpacCompat;
import net.ledok.eden_ld.hub.HubData;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.minestar.MinestarHook;
import net.ledok.eden_ld.minestar.ProfileSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Follows each player's Minestar activity level and applies what it gives ({@link LevelPerks}):
 * emoji and sticker slots and free daily rerolls are read from the stored level where those
 * limits are computed; bonus claims are put on the player's own Open Parties and Claims limit
 * (as a tracked delta). The last seen level is kept in {@link HubData} ({@code profile.level}), so
 * perks hold before the next fetch. Players are told what a new level gave them.
 */
public final class ProfileLevels {

    private static final String TAG = "profile";
    private static final String LEVEL = "level";
    /** Bonus claims currently put on the player's OPAC limit by their level. */
    private static final String CLAIMS = "claims";

    private ProfileLevels() {
    }

    public static void initialize() {
        MinestarHook.onProfileUpdated(ProfileLevels::update);
    }

    /** The player's Minestar level as last seen; 0 before the first fetch or without Minestar. */
    public static int level(MinecraftServer server, UUID player) {
        return HubData.get(server).peek(player).getCompound(TAG).getInt(LEVEL);
    }

    /** The player's current total of a perk. */
    public static int perk(MinecraftServer server, UUID player, LevelPerks.Kind kind) {
        return LevelPerks.total(kind, level(server, player));
    }

    private static CompoundTag tag(MinecraftServer server, UUID player) {
        CompoundTag root = HubData.get(server).player(player);
        if (!root.contains(TAG, Tag.TAG_COMPOUND)) {
            root.put(TAG, new CompoundTag());
        }
        return root.getCompound(TAG);
    }

    /** A fresh profile of an online player (server thread). */
    private static void update(MinecraftServer server, UUID uuid) {
        ProfileSnapshot profile = MinestarHook.profile(uuid).orElse(null);
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (profile == null || player == null) {
            return;
        }
        CompoundTag stored = HubData.get(server).peek(uuid).getCompound(TAG);
        boolean seen = stored.contains(LEVEL, Tag.TAG_INT);
        int before = stored.getInt(LEVEL);
        int now = Math.max(0, profile.level());
        if (!seen || now != before) {
            tag(server, uuid).putInt(LEVEL, now);
        }
        applyClaims(server, uuid, now);
        if (seen && now == before) {
            return;
        }
        if (!seen) {
            introduce(player, now);
        } else if (now > before) {
            announce(player, before, now);
        }
        // Limits that follow the level: emoji and sticker slots, free daily rerolls.
        EmojiRegistry.limitsChanged(server, uuid);
        HubServer.pushDailies(player);
    }

    /** Puts the level's bonus claims on the player's OPAC limit (only the difference to what's there). */
    private static void applyClaims(MinecraftServer server, UUID uuid, int level) {
        if (!OpacCompat.LOADED) {
            return;
        }
        int target = LevelPerks.total(LevelPerks.Kind.CLAIMS, level);
        int applied = HubData.get(server).peek(uuid).getCompound(TAG).getInt(CLAIMS);
        if (target != applied && OpacCompat.addPlayerBonus(server, uuid, target - applied)) {
            tag(server, uuid).putInt(CLAIMS, target);
        }
    }

    /** First time Eden sees the player's level: what it already gives them. */
    private static void introduce(ServerPlayer player, int level) {
        Map<LevelPerks.Kind, Integer> totals = LevelPerks.totals(LevelPerks.rules(), level);
        if (level <= 0 || totals.isEmpty()) {
            return;
        }
        player.sendSystemMessage(Component.translatable("message.eden_ld.profile.level_perks", level)
                .withStyle(ChatFormatting.GOLD)
                .append(" ")
                .append(LevelPerks.describe(totals).copy().withStyle(ChatFormatting.GREEN))
                .append(" ")
                .append(profileLink()));
    }

    /** Level-up: what the new level(s) gave, or what the next perk is. */
    private static void announce(ServerPlayer player, int before, int now) {
        List<LevelPerks.Rule> rules = LevelPerks.rules();
        Map<LevelPerks.Kind, Integer> gained = new EnumMap<>(LevelPerks.Kind.class);
        for (int level = before + 1; level <= now; level++) {
            for (LevelPerks.Gain gain : LevelPerks.at(rules, level)) {
                gained.merge(gain.kind(), gain.amount(), Integer::sum);
            }
        }
        MutableComponent message = Component.translatable("message.eden_ld.profile.level_up", now)
                .withStyle(ChatFormatting.GOLD);
        if (!gained.isEmpty()) {
            message.append(" ").append(Component.translatable("message.eden_ld.profile.level_up_gained",
                    LevelPerks.describe(gained)).withStyle(ChatFormatting.GREEN));
        } else {
            int next = LevelPerks.nextPerkLevel(rules, now);
            if (next > 0) {
                Map<LevelPerks.Kind, Integer> upcoming = new EnumMap<>(LevelPerks.Kind.class);
                LevelPerks.at(rules, next).forEach(gain -> upcoming.merge(gain.kind(), gain.amount(), Integer::sum));
                message.append(" ").append(Component.translatable("message.eden_ld.profile.level_up_next",
                        next, LevelPerks.describe(upcoming)).withStyle(ChatFormatting.GRAY));
            }
        }
        player.sendSystemMessage(message.append(" ").append(profileLink()));
    }

    private static Component profileLink() {
        return Component.translatable("message.eden_ld.profile.open")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hub profile")));
    }
}
