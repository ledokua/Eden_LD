package net.ledok.eden_ld.compat.duels;

import net.ledok.duels_ld.api.DuelsApi;
import net.ledok.duels_ld.api.WagerResult;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.WagerAction;
import net.ledok.eden_ld.hub.HubPayloads.WagerData;
import net.ledok.eden_ld.hub.HubPayloads.WagerListingView;
import net.ledok.eden_ld.hub.HubPayloads.WagerOfferView;
import net.ledok.eden_ld.hub.HubPayloads.WagerRecordView;
import net.ledok.eden_ld.hub.HubPayloads.WagerStakeView;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Duels_LD's wager duels (DuelsApi 1.6.0+, "Wager duels" in its API.md) for the hub's Activities → Duels pages. The
 * only class using the wager types: {@link DuelsHook} calls in here only after {@link DuelsIntegration} found them, so
 * an older Duels_LD never loads it. Names are resolved here, so the client gets them ready to draw.
 * <p>
 * Duels' screens that move items ({@code openCreate}, {@code openTerms}, {@code openReview}) aren't in 1.6.0: they
 * are looked up by reflection and called through it, so the hub's buttons light up with the Duels_LD that has them.
 */
final class DuelsWagers {

    /** History rows the page asks for. */
    private static final int HISTORY = 50;
    private static final int MAX_COMMENT = 256;

    @Nullable
    private static final Method OPEN_CREATE = open("openCreate", ServerPlayer.class, UUID.class);
    @Nullable
    private static final Method OPEN_TERMS = open("openTerms", ServerPlayer.class, int.class);
    @Nullable
    private static final Method OPEN_REVIEW = open("openReview", ServerPlayer.class, int.class);

    private DuelsWagers() {
    }

    /** A static {@code WagerResult name(params)} on DuelsApi, or null when this Duels_LD has none (or another shape). */
    @Nullable
    private static Method open(String name, Class<?>... params) {
        try {
            Method method = DuelsApi.class.getMethod(name, params);
            return Modifier.isStatic(method.getModifiers()) && method.getReturnType() == WagerResult.class ? method : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    static void register(Consumer<UUID> onPlayer, Runnable onListings) {
        DuelsApi.addListener(new DuelsApi.Listener() {
            @Override
            public void onWagerChanged(UUID player) {
                onPlayer.accept(player);
            }

            @Override
            public void onListingsChanged() {
                onListings.run();
            }
        });
        EdenLD.LOGGER.info("[Duels] Wager pages ready (Duels' screens from the hub: create {}, terms {}, review {}).",
                OPEN_CREATE != null, OPEN_TERMS != null, OPEN_REVIEW != null);
    }

    // ---------------------------------------------------------------- data

    static WagerData data(ServerPlayer player, int parts) {
        MinecraftServer server = player.getServer();
        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();
        boolean enabled = DuelsApi.wagersEnabled();
        DuelsApi.WagerStats stats = DuelsApi.wagerStats(uuid);
        int actions = (OPEN_CREATE != null ? WagerData.CREATE : 0) | (OPEN_TERMS != null ? WagerData.TERMS : 0)
                | (OPEN_REVIEW != null ? WagerData.REVIEW : 0);
        List<WagerListingView> listings = new ArrayList<>();
        HubPayloads.WagerMineView mine = HubPayloads.WagerMineView.EMPTY;
        List<WagerRecordView> history = new ArrayList<>();
        if (enabled && (parts & (WagerData.LISTINGS | WagerData.MINE)) != 0) {
            List<DuelsApi.WagerListing> open = DuelsApi.listings(server, uuid);
            if ((parts & WagerData.LISTINGS) != 0) {
                for (DuelsApi.WagerListing listing : open) {
                    listings.add(listing(server, listing, now));
                }
            }
            if ((parts & WagerData.MINE) != 0) {
                mine = mine(server, uuid, open, now);
            }
        }
        if (enabled && (parts & WagerData.HISTORY) != 0) {
            for (DuelsApi.WagerRecord record : DuelsApi.wagerHistory(server, uuid, HISTORY)) {
                history.add(record(server, uuid, record));
            }
        }
        return new WagerData(parts, enabled, actions, DuelsApi.skipsLossWarning(server, uuid),
                stats.wins(), stats.losses(), stats.draws(), listings, mine, history);
    }

    private static HubPayloads.WagerMineView mine(MinecraftServer server, UUID uuid, List<DuelsApi.WagerListing> open, long now) {
        DuelsApi.MyWagers wagers = DuelsApi.myWagers(server, uuid);
        // The authors of the player's offers: the announcements they see (public ones and challenges to them).
        Map<Integer, String> authors = new HashMap<>();
        for (DuelsApi.WagerListing listing : open) {
            authors.put(listing.number(), name(server, listing.author(), listing.authorName()));
        }
        List<WagerOfferView> onMine = new ArrayList<>();
        for (DuelsApi.WagerOffer offer : wagers.offersOnMine()) {
            onMine.add(offer(server, offer, ""));
        }
        List<WagerOfferView> myOffers = new ArrayList<>();
        for (DuelsApi.WagerOffer offer : wagers.myOffers()) {
            myOffers.add(offer(server, offer, authors.getOrDefault(offer.number(), "")));
        }
        List<WagerListingView> challenges = new ArrayList<>();
        for (DuelsApi.WagerListing listing : wagers.challengesToMe()) {
            challenges.add(listing(server, listing, now));
        }
        return new HubPayloads.WagerMineView(wagers.listing() == null ? null : listing(server, wagers.listing(), now),
                onMine, myOffers, challenges, stake(wagers.inBank()));
    }

    private static WagerListingView listing(MinecraftServer server, DuelsApi.WagerListing l, long now) {
        // A paused timer (preparing or fighting) has no end time, only what's left.
        boolean paused = l.expiresAtMillis() < 0;
        long remaining = paused ? l.pausedRemainingMillis() : l.expiresAtMillis() - now;
        DuelsApi.WagerStats stats = l.authorStats();
        String comment = l.comment() == null ? "" : l.comment();
        UUID target = l.target();
        return new WagerListingView(l.number(), l.author(), name(server, l.author(), l.authorName()),
                stats == null ? 0 : stats.wins(), stats == null ? 0 : stats.losses(), stats == null ? 0 : stats.draws(),
                stake(l.stake()), comment.length() > MAX_COMMENT ? comment.substring(0, MAX_COMMENT) : comment,
                l.createdAtMillis(), Math.max(0, remaining), paused, l.offers(), listingState(l.state()),
                target, target == null ? "" : name(server, target, l.targetName()), l.mine());
    }

    private static WagerOfferView offer(MinecraftServer server, DuelsApi.WagerOffer o, String authorName) {
        return new WagerOfferView(o.id(), o.number(), o.challenger(), name(server, o.challenger(), o.challengerName()),
                stake(o.stake()), offerState(o.state()), o.online(), o.createdAtMillis(), authorName);
    }

    private static WagerRecordView record(MinecraftServer server, UUID viewer, DuelsApi.WagerRecord r) {
        boolean author = viewer.equals(r.author());
        UUID opponent = author ? r.challenger() : r.author();
        String opponentName = name(server, opponent, author ? r.challengerName() : r.authorName());
        int result = r.winner() == null ? WagerRecordView.DRAW : r.winner().equals(viewer) ? WagerRecordView.WON : WagerRecordView.LOST;
        return new WagerRecordView(r.number(), r.endedAtMillis(), Math.max(0, r.endedAtMillis() - r.startedAtMillis()),
                opponentName, stake(author ? r.authorStake() : r.challengerStake()),
                stake(author ? r.challengerStake() : r.authorStake()), result, r.reportedByViewer());
    }

    private static WagerStakeView stake(@Nullable DuelsApi.WagerStake stake) {
        if (stake == null) {
            return WagerStakeView.EMPTY;
        }
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack stack : stake.items()) {
            if (stack != null && !stack.isEmpty()) {
                items.add(stack.copy());
            }
        }
        return new WagerStakeView(items, Math.max(0, stake.coins()));
    }

    /** Duels' name, or the server's (online, then the profile cache) when it has none. */
    private static String name(MinecraftServer server, @Nullable UUID player, @Nullable String known) {
        if (known != null && !known.isBlank()) {
            return clip(known);
        }
        if (player == null) {
            return "?";
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        var cache = server.getProfileCache();
        return clip(cache == null ? "?" : cache.get(player).map(com.mojang.authlib.GameProfile::getName).orElse("?"));
    }

    private static String clip(String name) {
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    /** By name, so a state a newer Duels_LD adds shows as unknown (-1) rather than breaking the page. */
    private static int listingState(@Nullable DuelsApi.ListingState state) {
        return state == null ? -1 : switch (state.name()) {
            case "ACTIVE" -> WagerListingView.ACTIVE;
            case "PREPARING" -> WagerListingView.PREPARING;
            case "FIGHTING" -> WagerListingView.FIGHTING;
            case "FINISHED" -> WagerListingView.FINISHED;
            case "CANCELLED" -> WagerListingView.CANCELLED;
            case "EXPIRED" -> WagerListingView.EXPIRED;
            default -> -1;
        };
    }

    private static int offerState(@Nullable DuelsApi.OfferState state) {
        return state == null ? -1 : switch (state.name()) {
            case "PENDING" -> WagerOfferView.PENDING;
            case "READY_CHECK" -> WagerOfferView.READY_CHECK;
            case "FIGHTING" -> WagerOfferView.FIGHTING;
            case "WON" -> WagerOfferView.WON;
            case "LOST" -> WagerOfferView.LOST;
            case "DRAW" -> WagerOfferView.DRAW;
            case "DECLINED" -> WagerOfferView.DECLINED;
            case "WITHDRAWN" -> WagerOfferView.WITHDRAWN;
            case "CLOSED" -> WagerOfferView.CLOSED;
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- actions

    /**
     * Runs the action through DuelsApi and answers with its result as a toast. An opened Duels screen answers nothing:
     * it replaces the hub (no return to it is armed), and a toast would have no hub to land on.
     */
    @Nullable
    static HubPayloads.HubNotice act(ServerPlayer player, WagerAction action) {
        int id = action.id();
        WagerResult result = switch (action.action()) {
            case CANCEL_LISTING -> DuelsApi.cancelListing(player);
            case WITHDRAW_OFFER -> DuelsApi.withdrawOffer(player, id);
            case DECLINE_OFFER -> DuelsApi.declineOffer(player, id);
            case ACCEPT_OFFER -> DuelsApi.acceptOffer(player, id);
            case DECLINE_CHALLENGE -> DuelsApi.declineChallenge(player, id);
            case REPORT -> DuelsApi.report(player, id, action.text().strip());
            case OPEN_CREATE -> invoke(OPEN_CREATE, player, null);
            case OPEN_TERMS -> invoke(OPEN_TERMS, player, id);
            case OPEN_REVIEW -> invoke(OPEN_REVIEW, player, id);
            case VIEW, LEAVE -> null;
        };
        if (result == null) {
            boolean open = action.action() == WagerAction.Action.OPEN_CREATE || action.action() == WagerAction.Action.OPEN_TERMS
                    || action.action() == WagerAction.Action.OPEN_REVIEW;
            return open ? new HubPayloads.HubNotice(Component.translatable("message.eden_ld.wagers.open_failed")) : null;
        }
        boolean opened = result == WagerResult.OK && switch (action.action()) {
            case OPEN_CREATE, OPEN_TERMS, OPEN_REVIEW -> true;
            default -> false;
        };
        return opened ? null : new HubPayloads.HubNotice(result.message(), result.ok());
    }

    /** One of Duels' open calls; null when this Duels_LD has none or it failed (logged). */
    @Nullable
    private static WagerResult invoke(@Nullable Method method, ServerPlayer player, @Nullable Object argument) {
        if (method == null) {
            return null;
        }
        try {
            return (WagerResult) method.invoke(null, player, argument);
        } catch (IllegalAccessException | InvocationTargetException | ClassCastException | IllegalArgumentException e) {
            EdenLD.LOGGER.warn("[Duels] {} failed for {}", method.getName(), player.getGameProfile().getName(),
                    e instanceof InvocationTargetException target ? target.getCause() : e);
            return null;
        }
    }
}
