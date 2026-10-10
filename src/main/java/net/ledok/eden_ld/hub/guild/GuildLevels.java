package net.ledok.eden_ld.hub.guild;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.chat.emoji.EmojiRegistry;
import net.ledok.eden_ld.hub.guild.quest.GuildQuests;
import net.ledok.eden_ld.hub.profile.LevelPerks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guild XP and levels. A guild keeps all the XP it ever earned ({@link Guild#xp}); its level
 * follows from config {@code guilds.level_xp} (XP for each next level, the list length + 1 being
 * the top). Leveling up tells the members what the new level gives ({@link GuildPerks}), adds
 * board slots, and refreshes member limits. Server thread only.
 */
public final class GuildLevels {

    /** {@code into} XP of {@code needed} toward the next level; {@code needed} 0 at the top level. */
    public record Progress(int level, long into, long needed) {
    }

    private GuildLevels() {
    }

    public static int maxLevel() {
        return EdenLD.CONFIG.guilds.level_xp.size() + 1;
    }

    public static Progress progress(long xp) {
        List<Long> steps = EdenLD.CONFIG.guilds.level_xp;
        int level = 1;
        long left = Math.max(0, xp);
        for (Long step : steps) {
            long need = Math.max(1, step == null ? 1 : step);
            if (left < need) {
                return new Progress(level, left, need);
            }
            left -= need;
            level++;
        }
        return new Progress(level, 0, 0);
    }

    public static int level(Guild guild) {
        return progress(guild.xp).level();
    }

    /** A perk's total for this guild. */
    public static int perk(Guild guild, GuildPerks.Kind kind) {
        return GuildPerks.total(kind, level(guild));
    }

    /** A member perk for a player: 0 outside a guild. */
    public static int memberPerk(MinecraftServer server, UUID player, GuildPerks.Kind kind) {
        Guild guild = GuildManager.get(server).guildOf(player);
        return guild == null ? 0 : perk(guild, kind);
    }

    /** Adds guild XP; a new level is announced and applied. */
    public static void addXp(MinecraftServer server, Guild guild, long amount) {
        if (amount == 0) {
            return;
        }
        int before = level(guild);
        guild.xp = Math.max(0, guild.xp + amount);
        GuildManager.get(server).markChanged();
        int after = level(guild);
        if (after != before) {
            onLevelChanged(server, guild, before, after);
        }
        GuildQuests.markDirty(guild);
    }

    /** Sets the XP outright (admin). */
    public static void setXp(MinecraftServer server, Guild guild, long xp) {
        addXp(server, guild, Math.max(0, xp) - guild.xp);
    }

    private static void onLevelChanged(MinecraftServer server, Guild guild, int before, int after) {
        if (after > before) {
            List<LevelPerks.Rule> rules = GuildPerks.rules();
            Map<GuildPerks.Kind, Integer> gained = new java.util.EnumMap<>(GuildPerks.Kind.class);
            for (int level = before + 1; level <= after; level++) {
                GuildPerks.at(rules, level).forEach((kind, amount) -> gained.merge(kind, amount, Integer::sum));
            }
            MutableComponent message = Component.translatable("message.eden_ld.guild.level_up", guild.name, after)
                    .withStyle(ChatFormatting.GOLD);
            if (!gained.isEmpty()) {
                message.append(" ").append(Component.translatable("message.eden_ld.guild.level_up_gained",
                        GuildPerks.describe(gained)).withStyle(ChatFormatting.GREEN));
            }
            message.append(" ").append(Component.translatable("message.eden_ld.party.invited_open")
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                    .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hub"))));
            for (UUID member : guild.members.keySet()) {
                ServerPlayer online = server.getPlayerList().getPlayer(member);
                if (online != null) {
                    online.sendSystemMessage(message);
                }
            }
        }
        // More board slots, member slots and prices follow the level.
        GuildQuests.ensure(server, guild);
        for (UUID member : guild.members.keySet()) {
            EmojiRegistry.limitsChanged(server, member);
            // Free daily rerolls follow the guild level too.
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) {
                net.ledok.eden_ld.hub.HubServer.pushDailies(online);
            }
        }
        GuildServer.syncAll(server);
        GuildBankServer.pushToViewers(server, guild);
    }
}
