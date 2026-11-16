package net.ledok.eden_ld.api.rental;

import net.ledok.eden_ld.rental.RentalItems;
import net.ledok.eden_ld.rental.RentalStash;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

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
 */
public final class EdenRentalApi {

    private EdenRentalApi() {
    }

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
     * on). Rental items still held go first. False when they don't rent or the loadout wasn't a kit.
     */
    public static boolean reequip(ServerPlayer player) {
        return RentalStash.reequip(player);
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
