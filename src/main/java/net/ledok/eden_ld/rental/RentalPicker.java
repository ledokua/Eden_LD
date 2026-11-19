package net.ledok.eden_ld.rental;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.api.rental.EdenRentalApi.Pick;
import net.ledok.eden_ld.api.rental.EdenRentalApi.PickerRequest;
import net.ledok.eden_ld.compat.KitsCompat;
import net.ledok.eden_ld.compat.PuffishCompat;
import net.ledok.eden_ld.rental.RentalPickerPayloads.CloseRentalPicker;
import net.ledok.eden_ld.rental.RentalPickerPayloads.Kit;
import net.ledok.eden_ld.rental.RentalPickerPayloads.OpenRentalPicker;
import net.ledok.eden_ld.rental.RentalPickerPayloads.RentalPickerChoice;
import net.ledok.eden_ld.rental.RentalPickerPayloads.RentalPickerClosed;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rental picker (LeDok 2026-11-18: one picker that Duels_LD ranked and Arenas_LD runs both open, ported from
 * Arenas_LD 4.41.1). The caller asks for a kit level; the player sees every preset there ({@link RentalPresets}) with
 * its items and its book's spell choice, their skill-tree class first, and picks one. Server thread; other mods use
 * {@link net.ledok.eden_ld.api.rental.EdenRentalApi}.
 * <ul>
 *   <li>One open picker per player. Each has a request id that the client's answers carry: an answer for an older
 *       picker is ignored, and a pick is checked against the kit list that picker showed.</li>
 *   <li>A pick keeps the request (the caller may open the picker again to let them change it); closing it without a
 *       pick, {@link #close}, a disconnect (no callback) and the server stop drop it.</li>
 *   <li>{@link #begin} lends the kit and then puts the picked spells into the lent book: not in the hand-out's tick
 *       (Spell Engine's sources then still hold the player's own book, Arenas 4.40.6), but once a second until Spell
 *       Engine reads them back. After a {@code reequip} they go in again.</li>
 * </ul>
 */
public final class RentalPicker {

    /** Ticks between tries to put the picked spells into the lent book. */
    private static final int APPLY_PERIOD_TICKS = 20;
    /** Tries before giving up (with a WARN). */
    private static final int APPLY_TRIES = 10;

    /** An open picker: its id, the level and kits it showed, and where the answer goes. */
    private record Open(int id, int level, List<String> kits, PickerRequest request) {
    }

    /** A renter's picked spells for their session's book; kept while the session runs (for {@link #afterReequip}). */
    private static final class Spells {
        final String session;
        final String pool;
        final List<String> spells;
        int dueTick;
        int tries;
        boolean done;

        Spells(String session, String pool, List<String> spells, int dueTick) {
            this.session = session;
            this.pool = pool;
            this.spells = spells;
            this.dueTick = dueTick;
        }
    }

    private static final Map<UUID, Open> OPEN = new ConcurrentHashMap<>();
    private static final Map<UUID, Spells> SPELLS = new ConcurrentHashMap<>();
    private static int nextId;

    private RentalPicker() {
    }

    public static void initialize() {
        RentalPickerPayloads.register();
        ServerPlayNetworking.registerGlobalReceiver(RentalPickerChoice.TYPE,
                (payload, context) -> onChoice(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(RentalPickerClosed.TYPE,
                (payload, context) -> onClosed(context.player(), payload.requestId()));
        // A player who leaves has no picker any more; the caller sees that through its own disconnect handling.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> OPEN.remove(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            OPEN.clear();
            SPELLS.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!SPELLS.isEmpty()) {
                applyDue(server);
            }
        });
    }

    // ---------------------------------------------------------------- presets

    public static List<String> presets(int level) {
        return RentalPresets.forLevel(level);
    }

    /** The preset class recommended for the player: their skill-tree class, through {@code class_presets}; "" for none. */
    static String recommendedClass(ServerPlayer player) {
        String tree = EdenLD.CONFIG.rental.class_tree;
        ResourceLocation id = tree == null || tree.isBlank() ? null : ResourceLocation.tryParse(tree.trim());
        if (id == null) {
            return "";
        }
        String branch;
        try {
            branch = PuffishCompat.topBranch(player, id);
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
        if (branch.isEmpty()) {
            return "";
        }
        Map<String, String> map = EdenLD.CONFIG.rental.class_presets;
        String mapped = map == null ? null : map.get(branch);
        return (mapped == null || mapped.isBlank() ? branch : mapped).toLowerCase(Locale.ROOT);
    }

    /** The kit's book's spell choice, or null when it has none (or Spell Engine is missing). */
    private static @Nullable RentalSpells.Choice choiceOf(ServerPlayer player, String kitId) {
        ResourceLocation kit = ResourceLocation.tryParse(kitId);
        return kit == null ? null
                : RentalSpells.forKit(player.registryAccess(), KitsCompat.contents(kit), RentalPresets.kitLevel(kitId));
    }

    /** {@code picked} cut to what the book allows; nothing picked (or nothing left): the prefill. */
    private static List<String> spellsFor(ServerPlayer player, String kitId, List<String> picked, RentalSpells.Choice book) {
        List<String> out = picked == null || picked.isEmpty() ? List.of() : RentalSpells.validate(picked, book);
        return out.isEmpty() ? RentalSpells.prefill(player, RentalPresets.presetName(kitId), book) : out;
    }

    public static @Nullable Pick recommended(ServerPlayer player, int level) {
        List<String> kits = presets(level);
        if (kits.isEmpty()) {
            return null;
        }
        String wanted = recommendedClass(player);
        String kit = kits.stream().filter(k -> !wanted.isEmpty() && RentalPresets.classOf(k).equalsIgnoreCase(wanted))
                .findFirst().orElse(kits.get(0));
        RentalSpells.Choice book = choiceOf(player, kit);
        return new Pick(kit, book == null ? List.of() : RentalSpells.prefill(player, RentalPresets.presetName(kit), book));
    }

    public static @Nullable Pick validate(ServerPlayer player, int level, @Nullable Pick pick) {
        if (pick == null || pick.kitId() == null || !presets(level).contains(pick.kitId())) {
            return null;
        }
        RentalSpells.Choice book = choiceOf(player, pick.kitId());
        return new Pick(pick.kitId(), book == null ? List.of() : spellsFor(player, pick.kitId(), pick.spells(), book));
    }

    // ---------------------------------------------------------------- the picker

    public static boolean open(ServerPlayer player, PickerRequest request) {
        if (!ServerPlayNetworking.canSend(player, OpenRentalPicker.TYPE)) {
            return false;
        }
        List<Kit> kits = kits(player, request.level(), request.current());
        if (kits.isEmpty()) {
            return false;
        }
        int id = ++nextId;
        OPEN.put(player.getUUID(), new Open(id, request.level(), kits.stream().map(Kit::kitId).toList(), request));
        // The header names the kits' level: the request's level can sit between folders (level 27 gets the 25 kits).
        int shown = kits.stream().mapToInt(Kit::level).max().orElse(request.level());
        Component title = request.title() == null ? Component.empty() : request.title();
        ServerPlayNetworking.send(player, new OpenRentalPicker(id, title, Optional.ofNullable(request.subtitle()),
                shown, kits, request.current() == null ? "" : request.current().kitId(), Math.max(0, request.deadlineMs())));
        return true;
    }

    public static void close(ServerPlayer player) {
        Open open = OPEN.remove(player.getUUID());
        if (open != null && ServerPlayNetworking.canSend(player, CloseRentalPicker.TYPE)) {
            ServerPlayNetworking.send(player, new CloseRentalPicker(open.id()));
        }
    }

    public static boolean isOpen(ServerPlayer player) {
        return OPEN.containsKey(player.getUUID());
    }

    /** Every preset at the level with what it hands out, the player's class first (stable otherwise). */
    private static List<Kit> kits(ServerPlayer player, int level, @Nullable Pick current) {
        String wanted = recommendedClass(player);
        List<Kit> kits = new ArrayList<>();
        for (String kit : presets(level)) {
            ResourceLocation id = ResourceLocation.tryParse(kit);
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack stack : id == null ? List.<ItemStack>of() : KitsCompat.contents(id)) {
                if (stack != null && !stack.isEmpty() && items.size() < RentalPickerPayloads.MAX_ITEMS && encodes(player, stack)) {
                    items.add(stack.copy());
                }
            }
            boolean recommended = !wanted.isEmpty() && RentalPresets.classOf(kit).equalsIgnoreCase(wanted);
            RentalSpells.Choice book = RentalSpells.forKit(player.registryAccess(), items, RentalPresets.kitLevel(kit));
            List<String> chosen = List.of();
            if (book != null) {
                chosen = current != null && current.kitId().equals(kit)
                        ? spellsFor(player, kit, current.spells(), book)
                        : RentalSpells.prefill(player, RentalPresets.presetName(kit), book);
            }
            List<Integer> tiers = book == null || !book.perTier() ? List.of()
                    : book.allowed().stream().map(s -> book.tiers().get(s)).toList();
            kits.add(new Kit(kit, RentalPresets.displayName(kit).getString(), RentalPresets.kitLevel(kit), items,
                    recommended, book == null ? List.of() : cap(book.allowed()), book == null ? 0 : book.max(),
                    cap(chosen), tiers.size() > RentalPickerPayloads.MAX_SPELLS ? List.of() : tiers));
        }
        kits.sort(Comparator.comparing(k -> !k.recommended()));
        if (kits.size() > RentalPickerPayloads.MAX_KITS) {
            EdenLD.LOGGER.warn("[Rental] {} presets at level {}: the picker shows the first {}", kits.size(), level,
                    RentalPickerPayloads.MAX_KITS);
            return List.copyOf(kits.subList(0, RentalPickerPayloads.MAX_KITS));
        }
        return kits;
    }

    private static List<String> cap(List<String> spells) {
        return spells.size() > RentalPickerPayloads.MAX_SPELLS ? spells.subList(0, RentalPickerPayloads.MAX_SPELLS) : spells;
    }

    /**
     * Whether the stack can be sent: one that can't (an empty stack nested in a container component, e.g. a drained
     * shared rune pouch) is left out of the picker instead of breaking the player's connection.
     */
    private static boolean encodes(ServerPlayer player, ItemStack stack) {
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buf, stack);
            return true;
        } catch (RuntimeException e) {
            EdenLD.LOGGER.warn("[Rental] Picker: left out {} (can't be sent: {})", stack, e.getMessage());
            return false;
        } finally {
            buf.release();
        }
    }

    private static void onChoice(ServerPlayer player, RentalPickerChoice payload) {
        Open open = OPEN.get(player.getUUID());
        if (open == null || open.id() != payload.requestId() || !open.kits().contains(payload.kitId())) {
            return; // an older picker's answer, or a kit it never showed
        }
        Pick pick = validate(player, open.level(), new Pick(payload.kitId(), payload.spells()));
        if (pick == null) {
            return;
        }
        if (!pick.spells().isEmpty()) {
            RentalSpells.Store.get(player.server).put(player.getUUID(), RentalPresets.presetName(pick.kitId()), pick.spells());
        }
        try {
            open.request().onPick().accept(pick);
        } catch (RuntimeException e) {
            EdenLD.LOGGER.error("[Rental] Picker: the onPick of {} failed", player.getScoreboardName(), e);
        }
    }

    private static void onClosed(ServerPlayer player, int requestId) {
        Open open = OPEN.get(player.getUUID());
        if (open == null || open.id() != requestId) {
            return;
        }
        OPEN.remove(player.getUUID());
        Runnable onClose = open.request().onClose();
        if (onClose != null) {
            try {
                onClose.run();
            } catch (RuntimeException e) {
                EdenLD.LOGGER.error("[Rental] Picker: the onClose of {} failed", player.getScoreboardName(), e);
            }
        }
    }

    // ---------------------------------------------------------------- lending

    public static boolean begin(ServerPlayer player, String session, Pick pick) {
        ResourceLocation kit = pick == null ? null : ResourceLocation.tryParse(pick.kitId());
        if (kit == null) {
            return false;
        }
        // Before the hand-out: the prefill (no spells picked) reads the player's own book.
        RentalSpells.Choice book = choiceOf(player, pick.kitId());
        List<String> spells = book == null ? List.of() : spellsFor(player, pick.kitId(), pick.spells(), book);
        if (!RentalStash.begin(player, session, kit)) {
            return false;
        }
        UUID uuid = player.getUUID();
        SPELLS.remove(uuid);
        if (book != null && !spells.isEmpty()) {
            RentalSpells.Store.get(player.server).put(uuid, RentalPresets.presetName(pick.kitId()), spells);
            SPELLS.put(uuid, new Spells(session, book.pool(), List.copyOf(spells),
                    player.server.getTickCount() + APPLY_PERIOD_TICKS));
        }
        EdenLD.LOGGER.info("[Rental] {} rents {} for {} (spells {})", player.getScoreboardName(), pick.kitId(), session, spells);
        return true;
    }

    /** After the kit was handed out again (its own book with it): the picked spells go in again. */
    public static void afterReequip(ServerPlayer player) {
        Spells pending = SPELLS.get(player.getUUID());
        if (pending != null && pending.session.equals(RentalStash.sessionOf(player))) {
            pending.done = false;
            pending.tries = 0;
            pending.dueTick = player.server.getTickCount() + APPLY_PERIOD_TICKS;
        }
    }

    /** Puts due spell picks into the lent books; drops those whose session ended. */
    private static void applyDue(MinecraftServer server) {
        int now = server.getTickCount();
        SPELLS.entrySet().removeIf(entry -> !entry.getValue().session.equals(RentalStash.sessionOf(entry.getKey())));
        for (Map.Entry<UUID, Spells> entry : SPELLS.entrySet()) {
            Spells pending = entry.getValue();
            if (pending.done || now < pending.dueTick) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue; // offline: tried again once they're back (if the session still runs)
            }
            if (RentalSpells.apply(player, pending.pool, pending.spells)) {
                pending.done = true;
            } else if (++pending.tries >= APPLY_TRIES) {
                pending.done = true;
                EdenLD.LOGGER.warn("[Rental] {}: no lent {} book to put the picked spells {} in", player.getScoreboardName(),
                        pending.pool, pending.spells);
            } else {
                pending.dueTick = now + APPLY_PERIOD_TICKS;
            }
        }
    }
}
