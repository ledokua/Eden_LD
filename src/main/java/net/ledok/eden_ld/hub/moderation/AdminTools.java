package net.ledok.eden_ld.hub.moderation;

import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.api.hub.EdenDailyApi;
import net.ledok.eden_ld.chat.emoji.EmojiRegistry;
import net.ledok.eden_ld.chat.emoji.FavoriteBonus;
import net.ledok.eden_ld.command.CommandDump;
import net.ledok.eden_ld.command.EdenPermissions;
import net.ledok.eden_ld.command.OpOnlyCommands;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.activity.Activities;
import net.ledok.eden_ld.hub.activity.ActivitySource;
import net.ledok.eden_ld.hub.daily.DailyQuests;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.hub.guild.GuildServer;
import net.ledok.eden_ld.hub.party.Party;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.ledok.eden_ld.hub.profile.ProfileServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The server side of the Moderation window's admin tools — the player page's Unlocks, Dailies,
 * Dungeons and Emojis tabs, and the Activities, Reports, Guilds, Parties and Server sections. Each area is guarded
 * by the node its command uses, and each action calls what the command calls. One request / data /
 * action payload trio serves them all; the data is NBT (components as JSON). The Activities section also
 * polls a light live payload ({@link ActivityAdminData}).
 */
public final class AdminTools {

    public static final String UNLOCKS = "unlocks", DAILIES = "dailies", DUNGEONS = "dungeons", EMOJIS = "emojis",
            GUILDS = "guilds", PARTIES = "parties", SERVER = "server", ACTIVITIES = "activities", WORLD = "world",
            REPORTS = "reports";

    private AdminTools() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.AdminRequest.TYPE,
                (payload, context) -> context.server().execute(() -> send(context.player(), payload.area(), payload.target())));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.AdminAction.TYPE,
                (payload, context) -> context.server().execute(() -> act(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ActivityLiveRequest.TYPE,
                (payload, context) -> context.server().execute(() -> sendLive(context.player(), payload.selected())));
    }

    private static boolean may(ServerPlayer admin, String node) {
        return LuckPermsCompat.hasPermission(admin, node);
    }

    private static boolean allowed(ServerPlayer admin, String area) {
        return switch (area) {
            case UNLOCKS -> may(admin, EdenPermissions.THEMES) || may(admin, EdenPermissions.BACKGROUNDS);
            case DAILIES -> may(admin, EdenPermissions.DAILIES);
            case DUNGEONS, ACTIVITIES -> may(admin, EdenPermissions.DUNGEONS);
            case EMOJIS -> may(admin, EdenPermissions.EMOJIS);
            case GUILDS -> may(admin, EdenPermissions.GUILDS);
            case PARTIES -> may(admin, EdenPermissions.PARTIES);
            case SERVER -> may(admin, EdenPermissions.EMOJIS) || may(admin, EdenPermissions.COMMANDS);
            case WORLD -> may(admin, EdenPermissions.WORLD);
            case REPORTS -> may(admin, EdenPermissions.REPORTS);
            default -> false;
        };
    }

    // ---------------------------------------------------------------- data

    private static void send(ServerPlayer admin, String area, UUID target) {
        if (!allowed(admin, area)) {
            return;
        }
        MinecraftServer server = admin.server;
        CompoundTag data = switch (area) {
            case UNLOCKS -> unlocks(admin, server, target);
            case DAILIES -> dailies(server, target);
            case DUNGEONS -> dungeons(server, target);
            case EMOJIS -> emojis(server, target);
            case GUILDS -> guilds(server);
            case PARTIES -> parties(server);
            case SERVER -> serverData(admin, server);
            case ACTIVITIES -> activities(admin, server);
            case WORLD -> net.ledok.eden_ld.world.WorldAdmin.data(server);
            case REPORTS -> ReportDesk.data(admin, server);
            default -> new CompoundTag();
        };
        ServerPlayNetworking.send(admin, new HubPayloads.AdminData(area, target, data));
    }

    private static CompoundTag unlocks(ServerPlayer admin, MinecraftServer server, UUID target) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("themes", may(admin, EdenPermissions.THEMES));
        tag.putBoolean("backgrounds", may(admin, EdenPermissions.BACKGROUNDS));
        tag.put("theme_open", strings(HubSettings.availableThemes(server, target)));
        tag.put("theme_unlocked", strings(HubSettings.unlockedThemes(server, target)));
        tag.put("background_open", strings(HubSettings.availableBackgrounds(server, target)));
        tag.put("background_unlocked", strings(HubSettings.unlockedBackgrounds(server, target)));
        return tag;
    }

    private static CompoundTag dailies(MinecraftServer server, UUID target) {
        CompoundTag tag = new CompoundTag();
        DailyQuests.State state = DailyQuests.state(server, target);
        ListTag slots = new ListTag();
        for (DailyQuests.Slot slot : state.slots()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("title", json(server, slot.quest() == null
                    ? Component.literal(slot.questId().toString()) : slot.quest().title()));
            entry.putString("icon", slot.quest() == null ? "minecraft:paper" : slot.quest().icon().toString());
            entry.putInt("progress", slot.progress());
            entry.putInt("count", slot.quest() == null ? 0 : slot.quest().count());
            entry.putBoolean("claimed", slot.claimed());
            slots.add(entry);
        }
        tag.put("slots", slots);
        tag.putInt("free_used", state.freeUsed());
        tag.putInt("paid_used", state.paidUsed());
        tag.putInt("extra", state.extraRerolls());
        tag.putBoolean("bonus_claimed", state.bonusClaimed());
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        tag.putInt("free_left", online == null ? -1 : DailyQuests.freeRerollsLeft(online, state));
        tag.putLong("reset_in", DailyQuests.millisUntilReset());
        return tag;
    }

    /**
     * Every activity (shown or hidden) with its hub settings, for the Activities section; per entry the
     * controller actions its source supports ({@code actions}, an {@code AdminAction} bitmask), and {@code op}
     * so the window hides what only ops may do (renaming, the source's admin screen).
     */
    private static CompoundTag activities(ServerPlayer admin, MinecraftServer server) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("op", admin.hasPermissions(2));
        net.ledok.eden_ld.hub.activity.ActivitySettings settings = net.ledok.eden_ld.hub.activity.ActivitySettings.get(server);
        java.util.Map<ResourceLocation, ActivitySource> sources = Activities.sources(server);
        ListTag list = new ListTag();
        for (net.ledok.eden_ld.hub.activity.Activity activity : net.ledok.eden_ld.hub.activity.Activities.all(server)) {
            net.ledok.eden_ld.hub.activity.ActivitySettings.Setting setting = settings.setting(activity.id().toString());
            ActivitySource source = sources.get(activity.id());
            CompoundTag entry = new CompoundTag();
            entry.putString("id", activity.id().toString());
            entry.putInt("type", activity.type().ordinal());
            entry.putString("name", json(server, activity.name()));
            entry.putString("source", source == null ? "" : source.name());
            entry.putBoolean("visible", setting != null && setting.visible());
            entry.putBoolean("closed", setting != null && setting.closed());
            entry.putInt("actions", source == null ? 0 : ActivitySource.AdminAction.mask(source.adminActions()));
            entry.putString("card_art", activity.cardArt() == null ? "" : activity.cardArt().toString());
            entry.putInt("level", Math.max(0, activity.requiredLevel()));
            ListTag difficulties = new ListTag();
            for (net.ledok.eden_ld.hub.activity.Activity.Difficulty difficulty : activity.difficulties()) {
                CompoundTag d = new CompoundTag();
                d.putString("id", difficulty.id());
                d.putInt("level", Math.max(0, difficulty.requiredLevel()));
                difficulties.add(d);
            }
            entry.put("difficulties", difficulties);
            list.add(entry);
        }
        tag.put("activities", list);
        return tag;
    }

    /** The player's activities: level (-1 when offline — Pufferfish needs them online), and per activity its stats. */
    private static CompoundTag dungeons(MinecraftServer server, UUID target) {
        CompoundTag tag = new CompoundTag();
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        boolean gated = net.ledok.eden_ld.hub.activity.ActivityLevels.gated();
        int level = online == null ? -1 : net.ledok.eden_ld.hub.activity.ActivityLevels.level(online);
        tag.putBoolean("gated", gated);
        tag.putInt("level", level);
        ListTag list = new ListTag();
        for (net.ledok.eden_ld.hub.activity.Activity activity : net.ledok.eden_ld.hub.activity.Activities.all(server)) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", activity.id().toString());
            entry.putString("name", json(server, activity.name()));
            entry.putInt("type", activity.type().ordinal());
            entry.putInt("unlock_level", activity.unlockLevel());
            entry.putInt("clears", net.ledok.eden_ld.hub.activity.ActivityProgress.totalClears(server, target, activity.id()));
            entry.putInt("wave", net.ledok.eden_ld.hub.activity.ActivityProgress.bestWave(server, target, activity.id()));
            ListTag difficulties = new ListTag();
            for (net.ledok.eden_ld.hub.activity.Activity.Difficulty difficulty : activity.difficulties()) {
                CompoundTag d = new CompoundTag();
                d.putString("id", difficulty.id());
                d.putInt("level", difficulty.requiredLevel());
                var stats = net.ledok.eden_ld.hub.activity.ActivityProgress.stats(server, target, activity.id(), difficulty.id());
                d.putInt("clears", stats.clears());
                d.putLong("best", stats.bestMillis());
                difficulties.add(d);
            }
            entry.put("difficulties", difficulties);
            list.add(entry);
        }
        tag.put("dungeons", list);
        return tag;
    }

    private static CompoundTag emojis(MinecraftServer server, UUID target) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (FavoriteBonus.Grant grant : FavoriteBonus.grants(server, target)) {
            CompoundTag entry = new CompoundTag();
            entry.putString("kind", grant.kind().id());
            entry.putInt("amount", grant.amount());
            entry.putLong("expires", grant.expires());
            list.add(entry);
        }
        tag.put("grants", list);
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        tag.putInt("emoji_limit", online == null ? -1 : EmojiRegistry.favoritesLimit(online));
        tag.putInt("sticker_limit", online == null ? -1 : EmojiRegistry.stickersLimit(online));
        return tag;
    }

    private static CompoundTag guilds(MinecraftServer server) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        List<Guild> all = new ArrayList<>(GuildManager.get(server).all());
        all.sort(Comparator.comparing((Guild g) -> g.tag, String.CASE_INSENSITIVE_ORDER));
        for (Guild guild : all) {
            CompoundTag entry = new CompoundTag();
            entry.putString("tag", guild.tag);
            entry.putString("name", guild.name);
            entry.putInt("color", guild.color);
            entry.putLong("created", guild.created);
            UUID leader = guild.leader();
            entry.putString("leader", leader == null ? "" : guild.memberNames.getOrDefault(leader, "?"));
            ListTag members = new ListTag();
            guild.members.forEach((uuid, rank) -> {
                CompoundTag member = new CompoundTag();
                member.putString("name", guild.memberNames.getOrDefault(uuid, uuid.toString()));
                member.putString("rank", rank.name().toLowerCase(Locale.ROOT));
                member.putBoolean("online", server.getPlayerList().getPlayer(uuid) != null);
                members.add(member);
            });
            entry.put("members", members);
            list.add(entry);
        }
        tag.put("guilds", list);
        return tag;
    }

    private static CompoundTag parties(MinecraftServer server) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        List<Party> all = new ArrayList<>(PartyManager.get(server).all());
        all.sort(Comparator.comparing((Party p) -> p.tag, String.CASE_INSENSITIVE_ORDER));
        for (Party party : all) {
            CompoundTag entry = new CompoundTag();
            entry.putString("tag", party.tag);
            entry.putString("name", party.title().getString());
            UUID leader = party.leader();
            entry.putString("leader", leader == null ? "" : party.memberNames.getOrDefault(leader, "?"));
            ListTag members = new ListTag();
            for (UUID uuid : party.memberOrder) {
                CompoundTag member = new CompoundTag();
                member.putString("name", party.memberNames.getOrDefault(uuid, uuid.toString()));
                member.putString("rank", party.members.containsKey(uuid)
                        ? party.members.get(uuid).name().toLowerCase(Locale.ROOT) : "");
                member.putBoolean("online", server.getPlayerList().getPlayer(uuid) != null);
                members.add(member);
            }
            entry.put("members", members);
            list.add(entry);
        }
        tag.put("parties", list);
        return tag;
    }

    private static CompoundTag serverData(ServerPlayer admin, MinecraftServer server) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("emojis", may(admin, EdenPermissions.EMOJIS));
        tag.putBoolean("commands", may(admin, EdenPermissions.COMMANDS));
        tag.put("op_only", strings(EdenLD.CONFIG.commands.op_only));
        List<String> roots = new ArrayList<>();
        for (CommandNode<CommandSourceStack> node : server.getCommands().getDispatcher().getRoot().getChildren()) {
            roots.add(node.getName());
        }
        roots.sort(String.CASE_INSENSITIVE_ORDER);
        tag.put("roots", strings(roots));
        return tag;
    }

    // ---------------------------------------------------------------- actions

    private static void act(ServerPlayer admin, HubPayloads.AdminAction action) {
        String area = action.area();
        if (!allowed(admin, area)) {
            notice(admin, Component.translatable("message.eden_ld.moderation.not_allowed"), false);
            return;
        }
        MinecraftServer server = admin.server;
        UUID target = action.target();
        CompoundTag args = action.args();
        String name = admin.getGameProfile().getName();
        Component done = switch (area + "/" + action.action()) {
            case "unlocks/theme" -> {
                if (!may(admin, EdenPermissions.THEMES)) {
                    yield null;
                }
                boolean on = args.getBoolean("on");
                HubSettings.setThemeUnlocked(server, target, args.getString("id"), on);
                HubServer.pushSettings(server, target);
                EdenLD.LOGGER.info("{} {} theme {} for {}", name, on ? "unlocked" : "locked", args.getString("id"), target);
                yield Component.translatable(on ? "message.eden_ld.admin.unlocked" : "message.eden_ld.admin.locked");
            }
            case "unlocks/background" -> {
                if (!may(admin, EdenPermissions.BACKGROUNDS)) {
                    yield null;
                }
                boolean on = args.getBoolean("on");
                HubSettings.setBackgroundUnlocked(server, target, args.getString("id"), on);
                ServerPlayer player = server.getPlayerList().getPlayer(target);
                if (player != null) {
                    ProfileServer.send(player, target);
                }
                EdenLD.LOGGER.info("{} {} background {} for {}", name, on ? "unlocked" : "locked", args.getString("id"), target);
                yield Component.translatable(on ? "message.eden_ld.admin.unlocked" : "message.eden_ld.admin.locked");
            }
            case "dailies/rerolls" -> {
                int amount = Math.max(1, Math.min(100, args.getInt("amount")));
                EdenDailyApi.grantExtraRerolls(server, target, amount);
                EdenLD.LOGGER.info("{} gave {} extra rerolls to {}", name, amount, target);
                yield Component.translatable("message.eden_ld.admin.rerolls", amount);
            }
            case "dailies/reset" -> {
                DailyQuests.resetToday(server, target);
                ServerPlayer player = server.getPlayerList().getPlayer(target);
                if (player != null) {
                    HubServer.pushDailies(player);
                }
                EdenLD.LOGGER.info("{} reset today's dailies of {}", name, target);
                yield Component.translatable("message.eden_ld.admin.dailies_reset");
            }
            case "dungeons/complete" -> {
                ResourceLocation id = ResourceLocation.tryParse(args.getString("id"));
                if (id == null || net.ledok.eden_ld.hub.activity.Activities.get(server, id) == null) {
                    yield null;
                }
                String difficulty = args.getString("difficulty");
                net.ledok.eden_ld.hub.activity.ActivityServer.credit(server, id, difficulty.isBlank() ? null : difficulty,
                        true, Math.max(0, args.getLong("seconds")) * 1000L, 0, List.of(target));
                EdenLD.LOGGER.info("{} recorded a clear of {} {} for {}", name, id, difficulty, target);
                yield Component.translatable("message.eden_ld.admin.dungeon_complete");
            }
            case "activities/save" -> {
                ResourceLocation id = ResourceLocation.tryParse(args.getString("id"));
                if (id == null || net.ledok.eden_ld.hub.activity.Activities.get(server, id) == null) {
                    yield null;
                }
                int max = net.ledok.eden_ld.hub.activity.ActivitySettings.MAX_LEVEL;
                java.util.Map<String, Integer> levels = new java.util.HashMap<>();
                CompoundTag stored = args.getCompound("levels");
                for (String difficulty : stored.getAllKeys()) {
                    levels.put(difficulty, Math.max(0, Math.min(max, stored.getInt(difficulty))));
                }
                String art = args.getString("card_art").trim();
                if (!art.isEmpty() && ResourceLocation.tryParse(art) == null) {
                    art = "";
                }
                net.ledok.eden_ld.hub.activity.ActivitySettings.get(server).put(id.toString(),
                        new net.ledok.eden_ld.hub.activity.ActivitySettings.Setting(args.getBoolean("visible"), art,
                                Math.max(0, Math.min(max, args.getInt("level"))), levels, args.getBoolean("closed")));
                net.ledok.eden_ld.hub.activity.Activities.changed();
                server.getPlayerList().getPlayers().forEach(net.ledok.eden_ld.hub.activity.ActivityServer::send);
                EdenLD.LOGGER.info("{} set up activity {} (shown: {}, closed: {})", name, id, args.getBoolean("visible"),
                        args.getBoolean("closed"));
                yield Component.translatable("message.eden_ld.admin.activity_saved");
            }
            case "activities/teleport", "activities/rename", "activities/admin", "activities/force_end",
                 "activities/forget" -> activityAction(admin, action.action(), args);
            case "reports/view", "reports/handle", "reports/reopen", "reports/note" ->
                    ReportDesk.act(admin, action.action(), args);
            case "dungeons/reset" -> {
                ResourceLocation id = args.getString("id").isBlank() ? null : ResourceLocation.tryParse(args.getString("id"));
                net.ledok.eden_ld.hub.activity.ActivityProgress.reset(server, target, id);
                EdenLD.LOGGER.info("{} reset activity stats ({}) of {}", name, id == null ? "all" : id, target);
                yield Component.translatable("message.eden_ld.admin.dungeon_reset");
            }
            case "emojis/add" -> {
                FavoriteBonus.Kind kind = FavoriteBonus.Kind.of(args.getString("kind"));
                int amount = Math.max(-1000, Math.min(1000, args.getInt("amount")));
                String duration = args.getString("duration").trim();
                long ms = duration.isEmpty() ? 0 : FavoriteBonus.parseDuration(duration);
                if (kind == null || amount == 0 || ms < 0) {
                    notice(admin, Component.translatable("message.eden_ld.moderation.bad_duration"), false);
                    yield null;
                }
                FavoriteBonus.grant(server, target, kind, amount, ms);
                EmojiRegistry.limitsChanged(server, target);
                EdenLD.LOGGER.info("{} gave {} {} bonus slots ({}) to {}", name, amount, kind.id(), duration, target);
                yield Component.translatable("message.eden_ld.admin.bonus_added");
            }
            case "emojis/clear" -> {
                FavoriteBonus.Kind kind = FavoriteBonus.Kind.of(args.getString("kind"));
                FavoriteBonus.clear(server, target, kind);
                EmojiRegistry.limitsChanged(server, target);
                yield Component.translatable("message.eden_ld.admin.bonus_cleared");
            }
            case "guilds/disband" -> {
                GuildManager manager = GuildManager.get(server);
                Guild guild = manager.byTag(args.getString("tag"));
                if (guild == null) {
                    yield null;
                }
                manager.forceDisband(server, guild);
                GuildServer.syncAll(server);
                HubServer.refreshWaystones(server);
                EdenLD.LOGGER.info("{} disbanded the guild [{}] {}", name, guild.tag, guild.name);
                yield Component.translatable("message.eden_ld.admin.disbanded", guild.name);
            }
            case "parties/disband" -> {
                PartyManager manager = PartyManager.get(server);
                Party party = manager.byTag(args.getString("tag"));
                if (party == null) {
                    yield null;
                }
                manager.forceDisband(server, party);
                HubServer.syncParties(server);
                EdenLD.LOGGER.info("{} disbanded the party {}", name, party.tag);
                yield Component.translatable("message.eden_ld.admin.disbanded", party.tag);
            }
            case "server/emoji_reload" -> {
                if (!may(admin, EdenPermissions.EMOJIS)) {
                    yield null;
                }
                EmojiRegistry.reload(server);
                yield Component.translatable("message.eden_ld.admin.emojis_reloading");
            }
            case "server/dump" -> {
                if (!may(admin, EdenPermissions.COMMANDS)) {
                    yield null;
                }
                try {
                    int[] counts = CommandDump.write(server);
                    yield Component.translatable("message.eden_ld.admin.dumped", counts[0], counts[1]);
                } catch (IOException e) {
                    EdenLD.LOGGER.error("Command dump failed", e);
                    notice(admin, Component.translatable("message.eden_ld.admin.failed"), false);
                    yield null;
                }
            }
            case "server/op_only" -> {
                if (!may(admin, EdenPermissions.COMMANDS)) {
                    yield null;
                }
                List<String> names = new ArrayList<>();
                ListTag list = args.getList("names", Tag.TAG_STRING);
                for (int i = 0; i < list.size() && names.size() < 200; i++) {
                    String command = list.getString(i).trim();
                    if (command.startsWith("/")) {
                        command = command.substring(1);
                    }
                    if (!command.isEmpty() && !names.contains(command)) {
                        names.add(command);
                    }
                }
                OpOnlyCommands.set(server, names);
                EdenLD.LOGGER.info("{} set the op-only commands: {}", name, names);
                yield Component.translatable("message.eden_ld.admin.op_only_saved", names.size());
            }
            default -> area.equals(WORLD) ? net.ledok.eden_ld.world.WorldAdmin.act(admin, action.action(), args) : null;
        };
        if (done != null) {
            // World settings report only what failed; the window shows a change by itself.
            notice(admin, done, !area.equals(WORLD));
        }
        if (area.equals(ACTIVITIES)) {
            // Teleport and the admin screen take the admin away; an ended run shows in the live data.
            switch (action.action()) {
                case "teleport", "admin" -> {
                }
                case "force_end" -> sendLive(admin, args.getString("id"));
                default -> send(admin, area, target);
            }
            return;
        }
        send(admin, area, target);
    }

    // ---------------------------------------------------------------- activities

    /** The selected activity's live status and history, and every row's markers (the section polls this). */
    private static void sendLive(ServerPlayer admin, String selected) {
        if (!allowed(admin, ACTIVITIES) || !ServerPlayNetworking.canSend(admin, HubPayloads.ActivityLive.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(admin, new HubPayloads.ActivityLive(ActivityAdminData.live(admin.server, admin, selected)));
    }

    /**
     * A controller action, through the activity's source (Arenas_LD): teleport, rename and the source's
     * admin screen (both op only — sources accept op edits only), end a run or all, forget a lost one.
     * Null when refused (the refusal is already shown).
     */
    private static Component activityAction(ServerPlayer admin, String action, CompoundTag args) {
        MinecraftServer server = admin.server;
        String name = admin.getGameProfile().getName();
        ResourceLocation id = ResourceLocation.tryParse(args.getString("id"));
        ActivitySource source = id == null ? null : Activities.sourceFor(server, id);
        if (source == null) {
            notice(admin, Component.translatable("message.eden_ld.activities.unknown"), false);
            return null;
        }
        ActivitySource.AdminAction needed = switch (action) {
            case "teleport" -> ActivitySource.AdminAction.TELEPORT;
            case "rename" -> ActivitySource.AdminAction.RENAME;
            case "admin" -> ActivitySource.AdminAction.OPEN_ADMIN;
            case "force_end" -> ActivitySource.AdminAction.FORCE_END;
            default -> ActivitySource.AdminAction.FORGET;
        };
        if (!source.adminActions().contains(needed)) {
            notice(admin, ActivitySource.unsupported(), false);
            return null;
        }
        if ((needed == ActivitySource.AdminAction.RENAME || needed == ActivitySource.AdminAction.OPEN_ADMIN)
                && !admin.hasPermissions(2)) {
            notice(admin, Component.translatable("message.eden_ld.admin.op_only"), false);
            return null;
        }
        Component refused;
        Component done;
        switch (needed) {
            case TELEPORT -> {
                refused = source.teleport(admin, id);
                // The window closes on Teleport: a refusal goes to chat as well.
                if (refused != null) {
                    admin.sendSystemMessage(refused.copy().withStyle(net.minecraft.ChatFormatting.RED));
                } else {
                    EdenLD.LOGGER.info("{} teleported to activity {}", name, id);
                }
                done = null;
            }
            case RENAME -> {
                String newName = args.getString("name").strip();
                if (newName.isEmpty() || newName.length() > 48) {
                    notice(admin, Component.translatable("message.eden_ld.admin.activity_bad_name"), false);
                    return null;
                }
                refused = source.rename(admin, id, newName);
                if (refused == null) {
                    Activities.changed();
                    server.getPlayerList().getPlayers().forEach(net.ledok.eden_ld.hub.activity.ActivityServer::send);
                    EdenLD.LOGGER.info("{} renamed activity {} to \"{}\"", name, id, newName);
                }
                done = Component.translatable("message.eden_ld.admin.activity_renamed", newName);
            }
            case OPEN_ADMIN -> {
                refused = source.openAdmin(admin, id);
                if (refused == null) {
                    EdenLD.LOGGER.info("{} opened the admin screen of activity {}", name, id);
                }
                done = null;
            }
            case FORCE_END -> {
                // No "instance" = every run; one that doesn't parse is refused, never widened to every run.
                ActivitySource.InstanceRef instance = null;
                if (args.contains("instance")) {
                    instance = args.contains("instance", Tag.TAG_COMPOUND) ? instance(args.getCompound("instance")) : null;
                    if (instance == null) {
                        notice(admin, Component.translatable("message.eden_ld.admin.activity_bad_instance"), false);
                        return null;
                    }
                }
                refused = source.forceEnd(server, id, instance);
                if (refused == null) {
                    EdenLD.LOGGER.info("{} ended {} of activity {}", name,
                            instance == null ? "every run" : "the run at " + instance.dimension() + " "
                                    + instance.pos().toShortString(), id);
                }
                done = Component.translatable(instance == null ? "message.eden_ld.admin.activity_ended_all"
                        : "message.eden_ld.admin.activity_ended");
            }
            default -> {
                refused = source.forget(server, id);
                if (refused == null) {
                    Activities.changed();
                    server.getPlayerList().getPlayers().forEach(net.ledok.eden_ld.hub.activity.ActivityServer::send);
                    EdenLD.LOGGER.info("{} forgot activity {}", name, id);
                }
                done = Component.translatable("message.eden_ld.admin.activity_forgotten", id.toString());
            }
        }
        if (refused != null) {
            notice(admin, refused, false);
            return null;
        }
        return done;
    }

    /** {@code {dim, x, y, z}} → an instance; null when any part is missing or unreadable. */
    @org.jetbrains.annotations.Nullable
    private static ActivitySource.InstanceRef instance(CompoundTag tag) {
        if (!tag.contains("dim", Tag.TAG_STRING) || !tag.contains("x", Tag.TAG_INT)
                || !tag.contains("y", Tag.TAG_INT) || !tag.contains("z", Tag.TAG_INT)) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dim"));
        return dimension == null ? null : new ActivitySource.InstanceRef(dimension,
                new net.minecraft.core.BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
    }

    // ---------------------------------------------------------------- helpers

    private static ListTag strings(List<String> values) {
        ListTag list = new ListTag();
        values.forEach(v -> list.add(StringTag.valueOf(v)));
        return list;
    }

    private static String json(MinecraftServer server, Component component) {
        return Component.Serializer.toJson(component, server.registryAccess());
    }

    private static void notice(ServerPlayer player, Component message, boolean ok) {
        ServerPlayNetworking.send(player, new HubPayloads.ModNotice(message, ok));
    }
}
