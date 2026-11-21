package net.ledok.eden_ld.api.rental;

import net.ledok.eden_ld.rental.RentalItems;
import net.ledok.eden_ld.rental.RentalPicker;
import net.ledok.eden_ld.rental.RentalPresets;
import net.ledok.eden_ld.rental.RentalStash;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Rental builds for other mods (Duels_LD ranked, Arenas_LD runs; source-stable). Server thread.
 *
 * <p>{@link #begin} puts the player's own items (inventory, armor, offhand, Trinkets, cursor, crafting grid) aside
 * (their Pufferfish skill tree too, only when Eden's {@code rental.keep_skill_tree} is off; kept by default since 1.3.97), hands out a loadout and marks every loadout item {@code custom_data {eden_rental:
 * <session>}}. {@link #end} removes every rental item and gives everything back exactly. Between the two, rental items
 * can't leave the player (no dropping, containers, item frames, trades, graves), the player picks nothing up from the
 * ground, and a death drops nothing. A crash or restart during a session is undone at the player's next login.
 *
 * <p>Rental presets are Kits_LD's hidden kits {@code falleneden:rental/<level>/<preset>}. {@link #openPicker} shows the
 * player every preset at a kit level (items, the book's spell choice, their skill-tree class first) and hands the pick
 * to the caller; {@link #begin(ServerPlayer, String, Pick)} lends it with the picked spells (1.3.120+).
 */
public final class EdenRentalApi {

    private EdenRentalApi() {
    }

    /** A rental pick: a preset kit id (falleneden:rental/<level>/<preset>) and the spells for its book (may be empty). */
    public record Pick(String kitId, List<String> spells) {
        public Pick {
            spells = spells == null ? List.of() : List.copyOf(spells);
        }
    }

    /**
     * What the picker shows and where the answer goes.
     *
     * @param level      the kit level folder cap (presets via RentalPresets.forLevel(level))
     * @param title      heading line
     * @param subtitle   hint line under it, or null
     * @param current    the pick to highlight as current, or null
     * @param deadlineMs milliseconds until the caller stops waiting (shown as a draining bar + "N s"), 0 = no deadline
     * @param onPick     called with a validated pick each time the player picks (server thread)
     * @param onClose    called when the player closes the picker without picking (Esc/Close), or null
     */
    public record PickerRequest(int level, Component title, @Nullable Component subtitle, @Nullable Pick current,
                                long deadlineMs, Consumer<Pick> onPick, @Nullable Runnable onClose) {
    }

    // ---------------------------------------------------------------- the picker (1.3.120+)

    /**
     * Opens the picker for the player (replacing an open one, whose onClose is NOT called). False, nothing sent, when
     * no preset exists at that level or the client lacks Eden's payload.
     * <p>
     * A pick keeps the request: onPick runs for every pick until {@link #closePicker}, the player closes it without
     * picking (onClose), or they disconnect (no callback). The client closes its picker after a pick; open it again
     * (with the pick as {@code current}) to let them change it. The deadline is only shown: closing at 0 is the caller's.
     */
    public static boolean openPicker(ServerPlayer player, PickerRequest request) {
        return RentalPicker.open(player, request);
    }

    /** Closes the player's picker (client side too) without calling onClose. */
    public static void closePicker(ServerPlayer player) {
        RentalPicker.close(player);
    }

    /** Whether the player has a picker request (open, or picked from and not closed since). */
    public static boolean hasPicker(ServerPlayer player) {
        return RentalPicker.isOpen(player);
    }

    /** Kit ids offered at this level (RentalPresets.forLevel). */
    public static List<String> presets(int level) {
        return RentalPicker.presets(level);
    }

    /**
     * The recommended pick: the first preset at that level whose class matches the player's skill-tree class
     * (class_presets mapping), else the first preset; spells prefilled (saved pick → own book of same pool → kit
     * default). Null when there are no presets.
     */
    @Nullable
    public static Pick recommended(ServerPlayer player, int level) {
        return RentalPicker.recommended(player, level);
    }

    /** The pick checked against what the level offers: null when the kit isn't offered; spells validated (filled per tier). */
    @Nullable
    public static Pick validate(ServerPlayer player, int level, Pick pick) {
        return RentalPicker.validate(player, level, pick);
    }

    /**
     * begin(player, session, kit) + puts the pick's spells into the lent book; the spells are retried once a second
     * (server tick) until Spell Engine reads them back, giving up with a WARN after 10 tries or when the rental ended.
     * Saves the spell pick per player+preset. {@link #reequip} puts them in again. The pick isn't checked against a
     * level: {@link #validate} it first.
     */
    public static boolean begin(ServerPlayer player, String session, Pick pick) {
        return RentalPicker.begin(player, session, pick);
    }

    /** "paladin" from ".../25/paladin_holy". */
    public static String classOf(String kitId) {
        return RentalPresets.classOf(kitId);
    }

    /** The kit level folder (25 from ".../25/paladin_holy"), -1 when none. */
    public static int kitLevel(String kitId) {
        return RentalPresets.kitLevel(kitId);
    }

    // ---------------------------------------------------------------- sessions

    /**
     * Starts a rental session with a Kits_LD kit (0.3.0+; hidden kits like {@code falleneden:rental/warrior/10}) as
     * the loadout. {@code session} is the caller's match or run id (any non-empty text). False and nothing changed
     * when the player already rents, is dead, or the kit is missing.
     */
    public static boolean begin(ServerPlayer player, String session, ResourceLocation kit) {
        return RentalStash.begin(player, session, kit);
    }

    /**
     * As {@link #begin(ServerPlayer, String, ResourceLocation)} with any loadout: {@code giveLoadout} fills the empty
     * inventory (everything in it afterwards becomes the session's). It may throw to refuse: everything is given back.
     */
    public static boolean begin(ServerPlayer player, String session, Consumer<ServerPlayer> giveLoadout) {
        return RentalStash.begin(player, session, null, giveLoadout);
    }

    /**
     * Ends the player's session: rental items removed, their own items (and a skill tree put aside) back; anything else they got
     * meanwhile is kept. False when they weren't renting.
     */
    public static boolean end(ServerPlayer player) {
        return RentalStash.end(player);
    }

    /**
     * As {@link #end(ServerPlayer)}, for a caller that restores the player's health itself (review finding
     * 64): {@link #end(ServerPlayer)} sets health a few ticks later, once their own gear's max health is in, to the
     * share of max they had during the session, which overwrote health set by the caller right after it. Here those
     * ticks later their health becomes {@code health} instead (capped at their max then: set right after end, the
     * loadout's max health would still cap it), or with {@code Float.NaN} isn't touched at all. A player who is dead
     * gets it at the respawn, with their items. False when they weren't renting.
     */
    public static boolean end(ServerPlayer player, float health) {
        return RentalStash.end(player, health);
    }

    /** Ends a session of a player who may be offline: then everything comes back at their next login. */
    public static boolean end(MinecraftServer server, UUID player) {
        return RentalStash.end(server, player);
    }

    public static boolean isRenting(Player player) {
        return RentalStash.isRenting(player);
    }

    /** The player's running session id, or null. */
    @Nullable
    public static String sessionOf(Player player) {
        return RentalStash.sessionOf(player);
    }

    /**
     * Hands the session's kit out again (after a death in a run: a death empties the inventory and the session goes
     * on). Rental items still held go first; spells picked through {@link #begin(ServerPlayer, String, Pick)} go into
     * the new book again. False when they don't rent or the loadout wasn't a kit.
     */
    public static boolean reequip(ServerPlayer player) {
        boolean given = RentalStash.reequip(player);
        if (given) {
            RentalPicker.afterReequip(player); // the kit's own book came back: the picked spells go in again
        }
        return given;
    }

    /**
     * Keeps an item (a reward earned during the session) to give with their own items at the end, safe from the
     * cleanup. False when they don't rent: then give it normally.
     */
    public static boolean stashItem(ServerPlayer player, ItemStack stack) {
        return RentalStash.stashItem(player, stack);
    }

    /** Whether the stack is a rental item (of any session). */
    public static boolean isRentalItem(ItemStack stack) {
        return RentalItems.isRental(stack);
    }
}
