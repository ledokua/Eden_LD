package net.ledok.eden_ld.hub;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.busylib.BusyState;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.chat.ChatChannels;
import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.ledok.eden_ld.compat.waystones.WaystonesHook;
import net.ledok.eden_ld.hub.daily.DailyObjectives;
import net.ledok.eden_ld.hub.daily.DailyProgressHooks;
import net.ledok.eden_ld.hub.daily.DailyReward;
import net.ledok.eden_ld.hub.lang.ServerLang;
import net.ledok.eden_ld.hub.daily.DailyQuestRegistry;
import net.ledok.eden_ld.hub.daily.DailyQuests;
import net.ledok.eden_ld.hub.party.Party;
import net.ledok.eden_ld.hub.waystone.HubTeleportRules;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.ledok.eden_ld.minestar.FriendSnapshot;
import net.ledok.eden_ld.minestar.MinestarHook;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the hub: payload receivers, tab data assembly, and cross-cutting
 * rules (BusyLib gating, party PvP protection). All async lookups come back on the
 * server thread before anything is sent.
 */
public final class HubServer {

    private HubServer() {
    }

    public static void initialize() {
        // Registered first so party lifecycle (grace, succession, last-logout
        // disband) runs before the sync handlers below build their payloads.
        PartyManager.initialize();
        HubPayloads.register();
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.HomeDataRequest.TYPE,
                (payload, context) -> sendHomeData(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.PartyDataRequest.TYPE,
                (payload, context) -> sendPartyData(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.PartyAction.TYPE,
                (payload, context) -> handlePartyAction(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.FriendsDataRequest.TYPE,
                (payload, context) -> sendFriendsData(context.player(), false, null));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.FriendAction.TYPE,
                (payload, context) -> handleFriendAction(context.player(), payload));
        IgnoreList.initialize();
        net.ledok.eden_ld.hub.activity.ActivityServer.initialize();
        net.ledok.eden_ld.hub.duels.DuelsHub.initialize();
        net.ledok.eden_ld.hub.profile.Wardrobe.initialize();
        ServerLang.initialize();
        DailyQuestRegistry.initialize();
        DailyProgressHooks.initialize();
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.DailiesDataRequest.TYPE,
                (payload, context) -> pushDailies(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.DailyAction.TYPE,
                (payload, context) -> handleDailyAction(context.player(), payload));
        WaystonesHook.initialize(HubServer::sendWaystones, () -> {
            MinecraftServer server = currentServer;
            if (server != null) {
                refreshWaystones(server);
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
                started -> currentServer = started);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
                stopped -> currentServer = null);
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.WaystonesDataRequest.TYPE,
                (payload, context) -> sendWaystones(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.WaystoneTeleport.TYPE,
                (payload, context) -> teleportToWaystone(context.player(), payload.waystone()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.WaystoneFavorite.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = player.getServer();
            if (WaystonesHook.LOADED) {
                WaystonesHook.setFavorite(player, payload.waystone(), payload.favorite());
                sendWaystones(player);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.WaystoneOrder.TYPE, (payload, context) -> {
            if (WaystonesHook.LOADED) {
                setFavoriteOrder(context.server(), context.player().getUUID(), payload.favorites());
                sendWaystones(context.player());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.WaystoneForget.TYPE, (payload, context) -> {
            if (WaystonesHook.LOADED) {
                WaystonesHook.forget(context.player(), payload.waystone());
                sendWaystones(context.player());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.WaystoneRename.TYPE, (payload, context) -> {
            if (WaystonesHook.LOADED) {
                WaystonesHook.setAlias(context.player(), payload.waystone(), payload.alias());
                sendWaystones(context.player());
            }
        });
        HubTeleportRules.initialize();
        net.ledok.eden_ld.hub.profile.ProfileServer.initialize();
        net.ledok.eden_ld.hub.guild.GuildServer.initialize();
        ChatChannels.initialize();
        net.ledok.eden_ld.chat.TabList.initialize();
        net.ledok.eden_ld.hub.guild.GuildBankServer.initialize();
        net.ledok.eden_ld.hub.guild.quest.GuildQuests.initialize();
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.SettingsDataRequest.TYPE,
                (payload, context) -> sendSettings(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.SettingChange.TYPE,
                (payload, context) -> changeSetting(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ThemeSelect.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = player.getServer();
            if (server != null) {
                HubSettings.selectTheme(server, player.getUUID(), payload.theme());
                sendSettings(player);
                net.ledok.eden_ld.hub.profile.Wardrobe.send(player);
            }
        });

        // Push party + friends data to everyone on join and disconnect: client combat matchers
        // need the party without the hub ever opening, and open tabs (Party, Friends' "On this
        // server" and invite buttons, Home's invite count) update live. Both deferred one tick:
        // Fabric's JOIN fires inside placeNewPlayer before the player is in the list, and on
        // DISCONNECT they're still in it.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> {
                    syncParties(server);
                    syncNameStyles(server);
                    // The hub theme applies from login, before the hub is ever opened.
                    if (!handler.getPlayer().hasDisconnected()) {
                        sendSettings(handler.getPlayer());
                    }
                }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> {
                    syncParties(server);
                    syncNameStyles(server);
                }));
        // LuckPerms edits (groups, prefixes) recolor names live; the event arrives off-thread.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(started ->
                net.ledok.eden_ld.compat.LuckPermsCompat.onUserDataChange(() ->
                        started.execute(() -> syncNameStyles(started))));

        // Friendly fire is off inside a party — players and their pets alike: partymates
        // can't hurt each other or each other's pets, and pets can't hurt partymates. With
        // no damage landing, pets never get a reason to retaliate either.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (source.getEntity() == null || source.getEntity() == entity) {
                return true;
            }
            CombatTeamsCompat.Relation relation = CombatTeamsCompat.resolve(source.getEntity(), entity);
            return relation == null || relation.friendlyFire();
        });
    }

    public static void openHub(ServerPlayer player) {
        ServerPlayNetworking.send(player, HubPayloads.OpenHub.INSTANCE);
    }

    /** Opens the hub on a tab ({@code HubTab} name in lower case, e.g. "profile"). */
    public static void openHub(ServerPlayer player, String tab) {
        ServerPlayNetworking.send(player, new HubPayloads.OpenHub(tab));
    }

    /** The {@code /invite} command's path — same rules as the hub INVITE action. */
    @Nullable
    public static Component invite(ServerPlayer inviter, ServerPlayer target) {
        MinecraftServer server = inviter.getServer();
        if (server == null) {
            return null;
        }
        if (BusyState.isBusy(inviter)) {
            return Component.translatable("message.eden_ld.party.busy");
        }
        PartyManager manager = PartyManager.get(server);
        Party party = manager.partyOf(inviter.getUUID());
        Component error = manager.invite(inviter, target);
        if (error == null) {
            notifyInvited(target, party, inviter);
            syncParties(server);
        }
        return error;
    }

    // ---------------------------------------------------------------- home

    /** Fresh Home data for everyone online (the news changed). */
    public static void refreshHomeForAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendHomeData(player);
        }
    }

    /** The player's LC balance changed: the hub's tabs show the new amount (no full Home resend, no web calls). */
    public static void sendBalance(ServerPlayer player) {
        EconomyCompat.fetchBalance(player, (hasEconomy, balance) -> {
            if (hasEconomy && !player.hasDisconnected()) {
                ServerPlayNetworking.send(player, new HubPayloads.Balance(balance));
            }
        });
    }

    private static void sendHomeData(ServerPlayer player) {
        String group = MinestarHook.platformGroup(player.getUUID()).orElse("");
        boolean prime = MinestarHook.isPrime(player.getUUID());
        long playtimeTicks = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        MinecraftServer server = player.getServer();
        HubPayloads.NewsView news = server == null ? HubPayloads.NewsView.EMPTY
                : net.ledok.eden_ld.hub.news.NewsBoard.get(server).forReader(player);
        int partyInvites = server == null ? 0
                : PartyManager.get(server).invitesFor(player.getUUID()).size();
        DailyQuests.State dailies = server == null ? null : DailyQuests.state(server, player.getUUID());
        FriendSnapshot friends = MinestarHook.cachedFriends(player.getUUID());
        // Requests from ignored players wait in the panel but don't count toward the badge.
        int friendRequests = friends == null || server == null ? 0 : (int) friends.incoming().stream()
                .filter(request -> !IgnoreList.ignores(server, player.getUUID(), request.uuid())).count();
        net.ledok.eden_ld.minestar.ProfileSnapshot profile = MinestarHook.profile(player.getUUID()).orElse(null);
        String background = server == null ? HubSettings.DEFAULT_BACKGROUND
                : HubSettings.background(server, player.getUUID());

        EconomyCompat.fetchBalance(player, (hasEconomy, balance) ->
                MinestarHook.fetchPendingShopCount(player, shopPending -> {
                    if (player.hasDisconnected()) {
                        return;
                    }
                    ServerPlayNetworking.send(player, new HubPayloads.HomeData(
                            group, prime, hasEconomy, balance, playtimeTicks, news,
                            shopPending, partyInvites, MinestarHook.LOADED, friendRequests,
                            WaystonesHook.LOADED,
                            dailies == null ? 0 : dailies.claimableCount(),
                            dailies == null ? 0 : dailies.claimedCount(),
                            dailies == null ? 0 : dailies.slots().size(),
                            profile != null, profile == null ? 0 : profile.level(),
                            profile == null ? 0 : profile.levelXp(),
                            profile == null ? 0 : profile.levelXpNeeded(), background,
                            net.ledok.eden_ld.command.EdenPermissions.staff(player), net.ledok.eden_ld.compat.duels.DuelsHook.LOADED));
                }));
    }

    // ---------------------------------------------------------------- party

    private static void sendPartyData(ServerPlayer player) {
        ServerPlayNetworking.send(player, buildPartyData(player));
    }

    /**
     * Pushes fresh party data to every online player (party actions are rare and small),
     * and cached friends data — its party-invite buttons follow the same party state.
     */
    public static void syncParties(MinecraftServer server) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(online, buildPartyData(online));
        }
        // Joining or leaving a party opens or closes the PARTY chat channel.
        ChatChannels.pushStateAll(server);
        pushCachedFriends(server);
    }

    /**
     * Everyone's LuckPerms prefix/suffix to everyone (online players only — LuckPerms only
     * has their data loaded). Sent on join/leave and when LuckPerms recalculates anyone.
     */
    public static void syncNameStyles(MinecraftServer server) {
        List<HubPayloads.NameStyle> styles = new ArrayList<>();
        var guilds = net.ledok.eden_ld.hub.guild.GuildManager.get(server);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            String[] parts = net.ledok.eden_ld.compat.LuckPermsCompat.prefixSuffix(online);
            var guild = HubSettings.showGuildTag(server, online.getUUID()) ? guilds.guildOf(online.getUUID()) : null;
            // Only a group's own name color: the config default is for the dark chat, not parchment.
            var nameColor = net.ledok.eden_ld.chat.LegacyText.color(net.ledok.eden_ld.compat.LuckPermsCompat.metaString(
                    online, net.ledok.eden_ld.chat.ChatStyle.META_NAME_COLOR));
            if (!parts[0].isEmpty() || !parts[1].isEmpty() || guild != null || nameColor != null) {
                styles.add(new HubPayloads.NameStyle(online.getUUID(), parts[0], parts[1],
                        guild != null ? guild.tag : "", guild != null ? guild.color : 0,
                        nameColor != null ? nameColor.getValue() : -1));
            }
        }
        HubPayloads.NameStyles payload = new HubPayloads.NameStyles(styles);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(online, payload);
        }
    }

    public static void broadcastPartyChatLine(MinecraftServer server, Party party, Party.ChatLine line) {
        HubPayloads.PartyChatLine payload = new HubPayloads.PartyChatLine(
                new HubPayloads.ChatEntry(line.sender(), line.name(), line.text()));
        for (UUID member : party.members.keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) {
                ServerPlayNetworking.send(online, payload);
            }
        }
    }

    private static HubPayloads.PartyData buildPartyData(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        boolean busy = BusyState.isBusy(player);
        if (server == null) {
            return emptyPartyData(busy);
        }
        PartyManager manager = PartyManager.get(server);
        Party party = manager.partyOf(player.getUUID());
        int maxSize = Math.max(2, EdenLD.CONFIG.hub.party_max_size);

        if (party == null) {
            List<HubPayloads.InviteRef> myInvites = new ArrayList<>();
            manager.invitesFor(player.getUUID()).forEach((inviter, invite) -> {
                Party inviting = invite.partyId() == null ? null : manager.byId(invite.partyId());
                myInvites.add(new HubPayloads.InviteRef(inviter, invite.inviterName(),
                        inviting == null ? "" : inviting.name));
            });
            List<HubPayloads.PartyRef> joinable = new ArrayList<>();
            for (Party open : manager.joinableFor(player)) {
                java.util.UUID openLeader = open.leader();
                joinable.add(new HubPayloads.PartyRef(open.id, open.name,
                        openLeader == null ? "?" : open.memberNames.getOrDefault(openLeader, "?"),
                        open.members.size(), maxSize));
            }
            // Solo players invite too — accepting creates the party.
            List<HubPayloads.PartyMember> candidates =
                    inviteCandidates(server, manager, player, null);
            List<HubPayloads.PartyMember> outgoing = new ArrayList<>();
            manager.soloOutgoingInvites(player.getUUID()).forEach((uuid, invite) ->
                    outgoing.add(new HubPayloads.PartyMember(uuid, invite.targetName(),
                            0, false, "", false)));
            return new HubPayloads.PartyData(busy, false, "", "", 0, 0, 0, maxSize,
                    List.of(), outgoing, candidates, List.of(), myInvites, joinable);
        }

        List<HubPayloads.PartyMember> members = new ArrayList<>();
        java.util.Set<UUID> viewerFriends = MinestarHook.friendUuids(player.getUUID());
        party.members.forEach((uuid, rank) -> {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            members.add(new HubPayloads.PartyMember(uuid,
                    party.memberNames.getOrDefault(uuid, "?"), rank.level,
                    online != null,
                    online != null ? online.level().dimension().location().getPath() : "", false,
                    manager.friendlyFireProtectionSuspended(uuid),
                    net.ledok.eden_ld.hub.profile.ProfileServer.mayView(server, player.getUUID(), uuid,
                            viewerFriends.contains(uuid))));
        });
        members.sort((a, b) -> b.rank() - a.rank());

        List<HubPayloads.PartyMember> outgoing = new ArrayList<>();
        manager.outgoingInvites(party.id).forEach((uuid, invite) ->
                outgoing.add(new HubPayloads.PartyMember(uuid, invite.targetName(),
                        0, false, "", false)));

        List<HubPayloads.PartyMember> candidates = party.mayInvite(player.getUUID())
                ? inviteCandidates(server, manager, player, party)
                : List.of();

        List<HubPayloads.ChatEntry> chat = new ArrayList<>(party.chatHistory.size());
        for (Party.ChatLine line : party.chatHistory) {
            chat.add(new HubPayloads.ChatEntry(line.sender(), line.name(), line.text()));
        }

        return new HubPayloads.PartyData(busy, true, party.tag, party.name,
                party.rank(player.getUUID()).level,
                party.invitePolicy.ordinal(), party.joinPolicy.ordinal(), maxSize,
                members, outgoing, candidates, chat, List.of(), List.of());
    }

    /** Online, party-less players this player hasn't already invited. */
    private static List<HubPayloads.PartyMember> inviteCandidates(MinecraftServer server,
            PartyManager manager, ServerPlayer player, Party party) {
        java.util.Set<UUID> friends = MinestarHook.friendUuids(player.getUUID());
        List<HubPayloads.PartyMember> candidates = new ArrayList<>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online != player && manager.inviteState(player, online) == PartyManager.InviteState.OK) {
                candidates.add(new HubPayloads.PartyMember(online.getUUID(),
                        online.getGameProfile().getName(), 0, true, "",
                        friends.contains(online.getUUID())));
            }
        }
        return candidates;
    }

    /** {@link PartyManager#inviteState} plus the BusyLib gate the invite action applies. */
    private static PartyManager.InviteState inviteState(PartyManager manager, ServerPlayer inviter, ServerPlayer target) {
        return BusyState.isBusy(inviter) ? PartyManager.InviteState.BUSY : manager.inviteState(inviter, target);
    }

    private static HubPayloads.PartyData emptyPartyData(boolean busy) {
        return new HubPayloads.PartyData(busy, false, "", "", 0, 0, 0, 2,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static void handlePartyAction(ServerPlayer player, HubPayloads.PartyAction action) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PartyManager manager = PartyManager.get(server);
        Party party = manager.partyOf(player.getUUID());
        HubPayloads.PartyAction.Action kind = action.kind();

        // Busy players may not grow a party (send invites, accept invites, join);
        // leaving/kicking/settings stay available.
        boolean growsParty = switch (kind) {
            case INVITE, ACCEPT_INVITE, JOIN -> true;
            default -> false;
        };
        if (growsParty && BusyState.isBusy(player)) {
            fail(player, Component.translatable("message.eden_ld.party.busy"));
            return;
        }

        Component result = switch (kind) {
            case INVITE -> {
                // Solo players invite too — the party is created on acceptance.
                ServerPlayer target = server.getPlayerList().getPlayer(action.target());
                if (target == null) {
                    yield Component.translatable("message.eden_ld.party.not_online");
                }
                Component err = manager.invite(player, target);
                if (err == null) {
                    notifyInvited(target, party, player);
                }
                yield err;
            }
            case CANCEL_INVITE -> {
                manager.cancelInvite(player.getUUID(), action.target());
                yield null;
            }
            case ACCEPT_INVITE -> manager.acceptInvite(player, action.target());
            case DECLINE_INVITE -> {
                manager.declineInvite(player.getUUID(), action.target());
                yield null;
            }
            case JOIN -> manager.joinDirect(player, action.target());
            case LEAVE -> manager.leave(player);
            case KICK -> party == null ? null : manager.kick(server, party, player.getUUID(), action.target());
            case PROMOTE -> party == null ? null : manager.promote(party, player.getUUID(), action.target());
            case DEMOTE -> party == null ? null : manager.demote(party, player.getUUID(), action.target());
            case TRANSFER -> party == null ? null : manager.transfer(party, player.getUUID(), action.target());
            case DISBAND -> party == null ? null : manager.disband(server, party, player.getUUID());
            case RENAME -> party == null ? null : manager.rename(party, player.getUUID(), action.text());
            case SET_INVITE_POLICY -> party == null ? null : manager.setInvitePolicy(party, player.getUUID(),
                    Party.InvitePolicy.values()[Math.floorMod(action.value(), Party.InvitePolicy.values().length)]);
            case SET_JOIN_POLICY -> party == null ? null : manager.setJoinPolicy(party, player.getUUID(),
                    Party.JoinPolicy.values()[Math.floorMod(action.value(), Party.JoinPolicy.values().length)]);
            case CHAT -> ChatChannels.send(player, ChatChannels.Channel.PARTY, action.text());
        };

        if (result != null) {
            fail(player, result);
        }
        if (kind != HubPayloads.PartyAction.Action.CHAT) {
            syncParties(server);
        }
    }

    // ---------------------------------------------------------------- dailies

    /** Sends the Dailies tab data (also keeps the Home card live). */
    public static void pushDailies(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || player.hasDisconnected()) {
            return;
        }
        DailyQuests.State state = DailyQuests.state(server, player.getUUID());
        // Guild XP shows on the cards only for players in a guild.
        boolean inGuild = net.ledok.eden_ld.hub.guild.GuildManager.get(server).guildOf(player.getUUID()) != null;
        List<HubPayloads.DailyEntry> entries = new ArrayList<>();
        for (int i = 0; i < state.slots().size(); i++) {
            DailyQuests.Slot slot = state.slots().get(i);
            var quest = slot.quest();
            if (quest == null) {
                entries.add(new HubPayloads.DailyEntry(i, Component.empty(), Component.empty(),
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("barrier"),
                        slot.progress(), 1, slot.claimed(), DailyReward.NONE.view(player), 0));
                continue;
            }
            // Titles/objectives are translate components: the client localizes game names,
            // ServerLang fills in server-authored keys for this player's language.
            entries.add(new HubPayloads.DailyEntry(i,
                    ServerLang.localize(player, quest.cardTitle()),
                    quest.name().isPresent()
                            ? ServerLang.localize(player, DailyObjectives.goal(quest.objective(), quest.target()))
                            : Component.empty(),
                    quest.icon(), slot.progress(), quest.count(), slot.claimed(),
                    quest.rewards().view(player), inGuild ? quest.guildXpOrDefault() : 0));
        }
        int freeLeft = DailyQuests.freeRerollsLeft(player, state);
        long cost = DailyQuests.nextRerollCost(player, state);
        HubPayloads.RewardView bonus = DailyQuests.bonusReward().view(player);
        // The balance is only needed to price a paid reroll; skip the lookup otherwise.
        if (freeLeft > 0 || cost <= 0 || !EconomyCompat.available()) {
            ServerPlayNetworking.send(player, new HubPayloads.DailiesData(DailyQuests.millisUntilReset(), freeLeft,
                    cost, EconomyCompat.available(), -1, entries, bonus, state.bonusClaimed()));
            return;
        }
        EconomyCompat.fetchBalance(player, (hasEconomy, balance) -> {
            if (!player.hasDisconnected()) {
                ServerPlayNetworking.send(player, new HubPayloads.DailiesData(DailyQuests.millisUntilReset(),
                        freeLeft, cost, hasEconomy, hasEconomy ? balance : -1, entries, bonus, state.bonusClaimed()));
            }
        });
    }

    public static void pushDailies(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            pushDailies(online);
        }
    }

    private static void handleDailyAction(ServerPlayer player, HubPayloads.DailyAction action) {
        // Rewards and rerolls wait until the run or the tutorial is over.
        if (BusyLocks.refuse(player)) {
            pushDailies(player);
            return;
        }
        switch (action.kind()) {
            case CLAIM -> {
                Component error = DailyQuests.claim(player, action.slot());
                if (error != null) {
                    fail(player, error);
                }
                pushDailies(player);
            }
            case CLAIM_BONUS -> {
                Component error = DailyQuests.claimBonus(player);
                if (error != null) {
                    fail(player, error);
                }
                pushDailies(player);
            }
            case REROLL -> DailyQuests.reroll(player, action.slot(), error -> {
                if (error != null) {
                    fail(player, error);
                }
                pushDailies(player);
            });
        }
    }

    // ---------------------------------------------------------------- waystones

    private static volatile MinecraftServer currentServer;

    /** Everyone's destinations changed (a stone edited or removed, guild membership changed). */
    public static void refreshWaystones(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(HubServer::sendWaystones);
    }

    private static void sendWaystones(ServerPlayer player) {
        if (!WaystonesHook.LOADED || player.hasDisconnected()) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        java.util.Set<UUID> friends = MinestarHook.friendUuids(player.getUUID());
        net.ledok.eden_ld.hub.guild.Guild guild = net.ledok.eden_ld.hub.guild.GuildManager.get(server)
                .guildOf(player.getUUID());
        List<HubPayloads.WaystoneEntry> entries = new ArrayList<>();
        for (WaystonesHook.Stone stone : WaystonesHook.targets(player)) {
            boolean sameDimension = stone.dimension().equals(player.level().dimension().location());
            int distance = sameDimension
                    ? (int) Math.round(Math.sqrt(player.blockPosition().distSqr(stone.pos())))
                    : -1;
            // Section precedence: yours, a guildmate's, a friend's, global, then anything else you activated.
            int category = player.getUUID().equals(stone.owner()) ? HubPayloads.WaystoneEntry.MINE
                    : stone.owner() != null && guild != null && guild.isMember(stone.owner()) ? HubPayloads.WaystoneEntry.GUILD
                    : stone.owner() != null && friends.contains(stone.owner()) ? HubPayloads.WaystoneEntry.FRIEND
                    : stone.global() ? HubPayloads.WaystoneEntry.GLOBAL
                    : HubPayloads.WaystoneEntry.ACTIVATED;
            String ownerName = stone.owner() == null ? "" : ownerName(server, stone);
            entries.add(new HubPayloads.WaystoneEntry(stone.id(),
                    stone.alias() != null ? stone.alias() : stone.name(),
                    stone.alias() != null ? stone.name() : Component.empty(),
                    stone.dimension().toString(), distance, category, ownerName, stone.favorite()));
        }
        entries.sort(java.util.Comparator.comparing(e -> e.name().getString().toLowerCase(java.util.Locale.ROOT)));
        ServerPlayNetworking.send(player, new HubPayloads.WaystonesData(BusyState.isBusy(player),
                HubTeleportRules.cooldownMillisLeft(player.getUUID()),
                HubTeleportRules.pvpMillisLeft(player.getUUID()), entries, favoriteOrder(server, player.getUUID())));
    }

    /** Where the player's own order of favorite waystones is kept (hub data). */
    private static final String WAYSTONE_ORDER = "waystone_order";

    /** The player's order of their favorite waystones (ids; favorites not in it follow, by name). */
    static List<java.util.UUID> favoriteOrder(MinecraftServer server, java.util.UUID player) {
        net.minecraft.nbt.ListTag list = HubData.get(server).peek(player)
                .getList(WAYSTONE_ORDER, net.minecraft.nbt.Tag.TAG_INT_ARRAY);
        List<java.util.UUID> order = new ArrayList<>(list.size());
        for (net.minecraft.nbt.Tag tag : list) {
            order.add(net.minecraft.nbt.NbtUtils.loadUUID(tag));
        }
        return order;
    }

    private static void setFavoriteOrder(MinecraftServer server, java.util.UUID player, List<java.util.UUID> order) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        for (java.util.UUID id : order) {
            if (list.size() >= HubPayloads.WaystoneOrder.MAX) {
                break;
            }
            if (seen.add(id)) {
                list.add(net.minecraft.nbt.NbtUtils.createUUID(id));
            }
        }
        HubData.get(server).player(player).put(WAYSTONE_ORDER, list);
    }

    /** Live name when online, else Waystones' remembered name, else the server's profile cache. */
    private static String ownerName(MinecraftServer server, WaystonesHook.Stone stone) {
        ServerPlayer online = server.getPlayerList().getPlayer(stone.owner());
        if (online != null) {
            return online.getGameProfile().getName();
        }
        if (!stone.ownerName().isEmpty()) {
            return stone.ownerName();
        }
        return server.getProfileCache() == null ? ""
                : server.getProfileCache().get(stone.owner()).map(com.mojang.authlib.GameProfile::getName).orElse("");
    }

    /** Eden's rules first (busy, PvP block, cooldown); Waystones then only validates and moves the player. */
    private static void teleportToWaystone(ServerPlayer player, UUID waystone) {
        if (!WaystonesHook.LOADED) {
            return;
        }
        Component refused = BusyState.isBusy(player)
                ? BusyState.getReasons(player.getUUID()).contains(BusyLocks.TUTORIAL) ? BusyLocks.reason(player)
                : Component.translatable("message.eden_ld.waystones.busy")
                : HubTeleportRules.pvpMillisLeft(player.getUUID()) > 0
                ? Component.translatable("message.eden_ld.waystones.pvp")
                : HubTeleportRules.cooldownMillisLeft(player.getUUID()) > 0
                ? Component.translatable("message.eden_ld.waystones.cooldown")
                : null;
        if (refused != null) {
            ServerPlayNetworking.send(player, new HubPayloads.WaystoneTeleportResult(false, refused));
            sendWaystones(player);
            return;
        }
        WaystonesHook.teleport(player, waystone, error -> {
            if (player.hasDisconnected()) {
                return;
            }
            if (error == null) {
                HubTeleportRules.markTeleported(player);
            }
            ServerPlayNetworking.send(player, new HubPayloads.WaystoneTeleportResult(error == null,
                    error == null ? Component.empty() : error));
            if (error != null) {
                sendWaystones(player);
            }
        });
    }

    // ---------------------------------------------------------------- settings

    /** Settings (and with them the hub theme) to an online player — on join, unlock, or lock. */
    public static void pushSettings(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            sendSettings(online);
        }
    }

    private static void sendSettings(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerPlayNetworking.send(player, new HubPayloads.SettingsData(
                HubSettings.partyInvitesFrom(server, player.getUUID()).ordinal(), MinestarHook.LOADED,
                HubSettings.theme(server, player.getUUID()),
                HubSettings.availableThemes(server, player.getUUID()),
                net.ledok.eden_ld.hub.guild.GuildManager.get(server).guildOf(player.getUUID()) != null,
                HubSettings.showGuildTag(server, player.getUUID()),
                HubSettings.messagesFrom(server, player.getUUID(), HubSettings.CHAT_DEATHS).ordinal(),
                HubSettings.messagesFrom(server, player.getUUID(), HubSettings.CHAT_ADVANCEMENTS).ordinal(),
                HubSettings.hiddenMask(server, player.getUUID()),
                HubSettings.hidden(server, player.getUUID(), HubSettings.CHAT_FOCUS),
                HubSettings.mentionVolume(server, player.getUUID()),
                HubSettings.profileVisibleTo(server, player.getUUID()).ordinal(),
                HubSettings.intSetting(server, player.getUUID(), HubSettings.NOTIFY_STYLE),
                HubSettings.intSetting(server, player.getUUID(), HubSettings.NOTIFY_COLOR),
                HubSettings.intSetting(server, player.getUUID(), HubSettings.NOTIFY_OFF),
                HubSettings.intSetting(server, player.getUUID(), HubSettings.SEEN_TABS),
                HubSettings.intSetting(server, player.getUUID(), HubSettings.HUB_LANGUAGE),
                net.ledok.eden_ld.hub.profile.LookRules.get(server, net.ledok.eden_ld.hub.profile.LookRules.Kind.THEME)
                        .offersFor(HubSettings.availableThemes(server, player.getUUID())),
                HubSettings.themeIconsOff(server, player.getUUID())));
    }

    private static void changeSetting(ServerPlayer player, HubPayloads.SettingChange change) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        boolean changed = HubSettings.set(server, player.getUUID(), change.key(), change.value());
        if (changed && (HubSettings.PARTY_INVITES_FROM.equals(change.key()) || HubSettings.SHOW_GUILD_TAG.equals(change.key()))) {
            // Who may invite this player changed: everyone's candidate lists and buttons follow.
            syncParties(server);
            syncNameStyles(server);
        }
        if (changed && HubSettings.PROFILE_VISIBLE_TO.equals(change.key())) {
            // Everyone's Profile buttons for this player follow (Friends, party and guild lists).
            syncParties(server);
            net.ledok.eden_ld.hub.guild.GuildServer.syncAll(server);
        }
        sendSettings(player);
        if (changed && HubSettings.HUB_LANGUAGE.equals(change.key())) {
            // Server-side texts (daily quest names, reward notes) follow the hub language.
            pushDailies(player);
        }
    }

    // ---------------------------------------------------------------- friends

    /**
     * Fetches the player's friends (throttled unless {@code force}) and sends the tab data;
     * {@code then} runs on the server thread after sending.
     */
    private static void sendFriendsData(ServerPlayer player, boolean force, @Nullable Runnable then) {
        MinestarHook.fetchFriends(player, force, snapshot -> {
            if (player.hasDisconnected()) {
                return;
            }
            ServerPlayNetworking.send(player, buildFriendsData(player, snapshot));
            if (then != null) {
                then.run();
            }
        });
    }

    /**
     * Server population changed: re-send friends data built from each player's cached
     * profile (no platform calls) so "on this server" markers stay live.
     */
    private static void pushCachedFriends(MinecraftServer server) {
        if (!MinestarHook.LOADED) {
            return;
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            FriendSnapshot snapshot = MinestarHook.cachedFriends(online.getUUID());
            if (snapshot != null) {
                ServerPlayNetworking.send(online, buildFriendsData(online, snapshot));
            }
        }
    }

    private static HubPayloads.FriendsData buildFriendsData(ServerPlayer player, @Nullable FriendSnapshot snapshot) {
        MinecraftServer server = player.getServer();
        PartyManager manager = server == null ? null : PartyManager.get(server);
        List<HubPayloads.FriendEntry> friends = new ArrayList<>();
        List<HubPayloads.RequestEntry> incoming = new ArrayList<>();
        List<HubPayloads.RequestEntry> outgoing = new ArrayList<>();
        java.util.Map<UUID, Integer> relations = new java.util.HashMap<>();

        if (snapshot != null) {
            for (FriendSnapshot.Friend friend : snapshot.friends()) {
                ServerPlayer inGame = server == null ? null : server.getPlayerList().getPlayer(friend.uuid());
                int presence = inGame != null ? HubPayloads.FriendEntry.ON_SERVER
                        : friend.online() ? HubPayloads.FriendEntry.ONLINE
                        : HubPayloads.FriendEntry.OFFLINE;
                String packSearch = (String.join(" ", friend.playing()) + " "
                        + String.join(" ", friend.playingIds())).toLowerCase(java.util.Locale.ROOT);
                friends.add(new HubPayloads.FriendEntry(friend.uuid(), friend.nickname(), presence,
                        friend.lastSeenMillis(), String.join(", ", friend.playing()), packSearch,
                        inGame != null ? inGame.level().dimension().location().toString() : "",
                        inGame != null ? inviteState(manager, player, inGame).ordinal()
                                : PartyManager.InviteState.IN_PARTY.ordinal(),
                        server != null && net.ledok.eden_ld.hub.profile.ProfileServer.mayView(
                                server, player.getUUID(), friend.uuid(), true),
                        server == null ? "" : HubSettings.background(server, friend.uuid())));
                relations.put(friend.uuid(), HubPayloads.ServerPlayerEntry.FRIEND);
            }
            for (FriendSnapshot.Request request : snapshot.incoming()) {
                incoming.add(new HubPayloads.RequestEntry(request.uuid(), request.nickname(), request.sentAtMillis()));
                relations.put(request.uuid(), HubPayloads.ServerPlayerEntry.INCOMING);
            }
            for (FriendSnapshot.Request request : snapshot.outgoing()) {
                outgoing.add(new HubPayloads.RequestEntry(request.uuid(), request.nickname(), request.sentAtMillis()));
                relations.put(request.uuid(), HubPayloads.ServerPlayerEntry.OUTGOING);
            }
        }
        // On this server first, then online elsewhere, then offline; by name within each.
        friends.sort(java.util.Comparator
                .comparingInt((HubPayloads.FriendEntry f) -> -f.presence())
                .thenComparing(f -> f.name().toLowerCase(java.util.Locale.ROOT)));

        List<HubPayloads.ServerPlayerEntry> players = new ArrayList<>();
        if (server != null) {
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                if (online != player) {
                    int relation = relations.getOrDefault(online.getUUID(), HubPayloads.ServerPlayerEntry.NONE);
                    players.add(new HubPayloads.ServerPlayerEntry(online.getUUID(),
                            online.getGameProfile().getName(), relation,
                            inviteState(manager, player, online).ordinal(),
                            net.ledok.eden_ld.hub.profile.ProfileServer.mayView(server, player.getUUID(),
                                    online.getUUID(), relation == HubPayloads.ServerPlayerEntry.FRIEND),
                            // The card shows their background whatever their privacy setting (that guards the page).
                            HubSettings.background(server, online.getUUID())));
                }
            }
        }
        players.sort(java.util.Comparator.comparing(e -> e.name().toLowerCase(java.util.Locale.ROOT)));
        return new HubPayloads.FriendsData(snapshot != null, friends, incoming, outgoing, players);
    }

    private static void handleFriendAction(ServerPlayer player, HubPayloads.FriendAction action) {
        MinecraftServer server = player.getServer();
        UUID target = action.target();
        if (server == null || !MinestarHook.LOADED || target.equals(player.getUUID())) {
            return;
        }
        String targetName = friendName(server, player, target);
        HubPayloads.FriendAction.Action kind = action.kind();

        MinestarHook.FriendCallback done = (error, accepted) -> {
            if (error != null) {
                fail(player, error);
            } else {
                player.sendSystemMessage(friendMessage(kind, accepted, targetName)
                        .withStyle(ChatFormatting.GREEN));
                ServerPlayer other = server.getPlayerList().getPlayer(target);
                if (other != null && (kind == HubPayloads.FriendAction.Action.ADD
                        || kind == HubPayloads.FriendAction.Action.ACCEPT)) {
                    notifyFriendTarget(other, player, accepted);
                }
            }
            refreshAfterFriendChange(server, player, target);
        };

        switch (kind) {
            case ADD, ACCEPT -> MinestarHook.addFriend(player, target, targetName, done);
            case DECLINE, CANCEL, REMOVE -> MinestarHook.removeFriend(player, target, done);
        }
    }

    private static net.minecraft.network.chat.MutableComponent friendMessage(
            HubPayloads.FriendAction.Action kind, boolean accepted, String name) {
        String key = switch (kind) {
            case ADD, ACCEPT -> accepted ? "now_friends" : "request_sent";
            case DECLINE -> "declined";
            case CANCEL -> "cancelled";
            case REMOVE -> "removed";
        };
        return Component.translatable("message.eden_ld.friends." + key, name);
    }

    private static void notifyFriendTarget(ServerPlayer target, ServerPlayer actor, boolean accepted) {
        String actorName = actor.getGameProfile().getName();
        MinecraftServer server = target.getServer();
        if (!accepted && server != null && IgnoreList.ignores(server, target.getUUID(), actor.getUUID())) {
            // A request from someone they ignore waits in the panel without a notice.
            return;
        }
        if (accepted) {
            target.sendSystemMessage(Component.translatable("message.eden_ld.friends.accepted_you", actorName)
                    .withStyle(ChatFormatting.GREEN));
            return;
        }
        target.sendSystemMessage(Component.translatable("message.eden_ld.friends.incoming", actorName)
                .withStyle(ChatFormatting.GOLD)
                .append(Component.translatable("message.eden_ld.party.invited_open")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style.withClickEvent(
                                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hub")))));
    }

    /**
     * Both sides re-fetch (the change lands in both profiles) and get fresh tab data; party
     * data follows, since friend flags drive invite sorting and the friends-only join mode.
     */
    private static void refreshAfterFriendChange(MinecraftServer server, ServerPlayer player, UUID target) {
        sendFriendsData(player, true, () -> syncParties(server));
        ServerPlayer other = server.getPlayerList().getPlayer(target);
        if (other != null) {
            // Their "friends only" invite setting may have just flipped for this pair.
            sendFriendsData(other, true, () -> syncParties(server));
        }
    }

    private static String friendName(MinecraftServer server, ServerPlayer player, UUID target) {
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        FriendSnapshot snapshot = MinestarHook.cachedFriends(player.getUUID());
        String name = snapshot == null ? null : snapshot.nicknameOf(target);
        return name != null ? name : "?";
    }

    private static void notifyInvited(ServerPlayer target, Party party, ServerPlayer inviter) {
        Component text = party != null
                ? Component.translatable("message.eden_ld.party.invited",
                        inviter.getGameProfile().getName(), party.title())
                : Component.translatable("message.eden_ld.party.invited_solo",
                        inviter.getGameProfile().getName());
        target.sendSystemMessage(text.copy()
                .withStyle(ChatFormatting.GOLD)
                .append(Component.translatable("message.eden_ld.party.invited_open")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style.withClickEvent(
                                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hub")))));
    }

    /** Red chat line, plus a toast on the hub if it's open (chat is hidden behind it). */
    private static void fail(ServerPlayer player, Component message) {
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
        ServerPlayNetworking.send(player, new HubPayloads.HubNotice(message));
    }
}
