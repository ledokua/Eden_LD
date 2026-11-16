package net.ledok.eden_ld.rental;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.KitsCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Rental builds ({@code Balance/RENTAL-BUILDS.md}): a player's own items (and skill tree, only when
 * {@code rental.keep_skill_tree} is off) put aside for a duel or a run,
 * a loadout handed out instead, and everything back after. Server thread. Other mods use
 * {@link net.ledok.eden_ld.api.rental.EdenRentalApi}.
 *
 * <p>Crash safety: the stash lives in the player's own save (a Fabric attachment), next to the inventory it belongs
 * with, so any save of the player is consistent: from before the swap (their own items, no stash) or during it (the
 * loadout and the stash). After a crash or restart the session is no longer running, and the next login ends it: rental
 * items removed, the stash given back. A copy of each stash also goes to {@code <world>/eden_ld/rental/<uuid>.dat}
 * (the latest per player; for staff, never read back).
 */
public final class RentalStash {

    private static final boolean TRINKETS = FabricLoader.getInstance().isModLoaded("trinkets");
    private static final boolean SKILLS = FabricLoader.getInstance().isModLoaded("puffish_skills");

    /** The stash, saved with the player and kept through death (the session's owner decides what death means). */
    private static final AttachmentType<CompoundTag> STASH = AttachmentRegistry.<CompoundTag>builder()
            .persistent(CompoundTag.CODEC)
            .copyOnDeath()
            .buildAndRegister(ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "rental_stash"));

    /** Running sessions by player (kept while they're offline; a restart forgets them, and the login ends them). */
    private static final Map<UUID, String> LIVE = new ConcurrentHashMap<>();
    /**
     * Health to set once the swapped gear's attributes are in (equipment and Trinkets apply their modifiers on the
     * player's next ticks): the share of max health, by player.
     */
    private static final Map<UUID, Heal> HEAL = new ConcurrentHashMap<>();
    /** Ticks to wait before {@link #HEAL}: past the equipment tick and Trinkets' own. */
    private static final int HEAL_DELAY_TICKS = 3;

    private record Heal(float share, int dueTick) {
    }

    private static int ticks;
    /** While a renter dies: anything dropped on the ground then (by any mod) is removed. */
    private static int dying;

    private RentalStash() {
    }

    public static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                net.ledok.eden_ld.util.OnServerThread.run(server, () -> onJoin(handler.getPlayer())));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LIVE.clear());
        // Once a second: rental items only ever stay with their renter (anything that slipped past the guards goes).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ++ticks;
            if (!HEAL.isEmpty()) {
                healDue(server);
            }
            if (ticks % 20 == 0) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    sweep(player);
                }
            }
        });
        // Rental items never lie on the ground: dropped by any path the guards missed, they're gone.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ItemEntity item && (dying > 0 || RentalItems.isRental(item.getItem()))
                    || dying > 0 && entity instanceof net.minecraft.world.entity.ExperienceOrb) {
                entity.discard();
            }
        });
        // Not into item frames, onto armor stands or to allays.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                !level.isClientSide() && RentalItems.isRental(player.getItemInHand(hand)) && holdsItems(entity)
                        ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    private static boolean holdsItems(Entity entity) {
        return entity instanceof HangingEntity || entity instanceof ArmorStand || entity instanceof Allay;
    }

    // ---------------------------------------------------------------- the API

    public static boolean isRenting(Player player) {
        return LIVE.containsKey(player.getUUID());
    }

    @Nullable
    public static String sessionOf(Player player) {
        return LIVE.get(player.getUUID());
    }

    @Nullable
    static String sessionOf(UUID player) {
        return LIVE.get(player);
    }

    /** As {@link #begin(ServerPlayer, String, ResourceLocation, Consumer)} with a Kits_LD kit as the loadout. */
    public static boolean begin(ServerPlayer player, String session, ResourceLocation kit) {
        return begin(player, session, kit, target -> {
            if (!KitsCompat.giveHidden(target, kit, stack -> RentalItems.tag(stack, session))) {
                throw new IllegalStateException("kit " + kit + " missing (or Kits_LD older than 0.3.0)");
            }
        });
    }

    /**
     * Puts the player's items (inventory, armor, offhand, Trinkets, cursor, crafting grid) and, with
     * {@code rental.keep_skill_tree} off, their skill tree aside, then
     * {@code giveLoadout} hands out the loadout; every item they hold afterwards is marked as the session's. False
     * (and nothing changed) when they already rent, or when the loadout fails (then everything is back already).
     */
    public static boolean begin(ServerPlayer player, String session, @Nullable ResourceLocation kit,
                                Consumer<ServerPlayer> giveLoadout) {
        if (session == null || session.isEmpty() || LIVE.containsKey(player.getUUID()) || player.hasAttached(STASH)
                || !player.isAlive()) {
            return false;
        }
        player.closeContainer();
        // The record first, then the changes (one tick: a save of the player sees all of it or none).
        CompoundTag record = new CompoundTag();
        record.putString("session", session);
        if (kit != null) {
            record.putString("kit", kit.toString());
        }
        record.putLong("started", System.currentTimeMillis());
        record.putString("name", player.getGameProfile().getName());
        ListTag inventory = new ListTag();
        player.getInventory().save(inventory);
        record.put("inventory", inventory);
        if (TRINKETS) {
            record.put("trinkets", RentalTrinkets.save(player));
        }
        // The tree is kept unless the config says otherwise (rental.keep_skill_tree, 1.3.97).
        boolean asideSkills = SKILLS && !EdenLD.CONFIG.rental.keep_skill_tree;
        if (asideSkills) {
            record.put("skills", RentalSkills.save(player));
        }
        ListTag extra = new ListTag();
        takeLoose(player, stack -> extra.add(stack.save(player.registryAccess())));
        record.put("extra", extra);
        writeCopy(player, record);
        player.setAttached(STASH, record);
        LIVE.put(player.getUUID(), session);

        int own = inventory.size() + (TRINKETS ? record.getList("trinkets", Tag.TAG_COMPOUND).size() : 0) + extra.size();
        player.getInventory().clearContent();
        if (TRINKETS) {
            RentalTrinkets.removeIf(player, stack -> true);
        }
        if (asideSkills) {
            RentalSkills.clearAll(player);
        }
        try {
            giveLoadout.accept(player);
        } catch (RuntimeException e) {
            EdenLD.LOGGER.error("[Rental] {}'s loadout for {} failed; everything is given back.",
                    player.getGameProfile().getName(), session, e);
            end(player);
            return false;
        }
        int given = tagAll(player, session);
        player.inventoryMenu.broadcastChanges();
        // The loadout's armor changes max health: they start the fight full.
        healLater(player, 1.0f);
        EdenLD.LOGGER.info("[Rental] {} began rental session {} ({}): {} own stacks put aside{}, {} loadout stacks given.",
                player.getGameProfile().getName(), session, kit == null ? "custom loadout" : kit, own,
                asideSkills ? " with the skill tree" : "", given);
        return true;
    }

    /**
     * Ends the player's rental: every rental item goes (inventory, armor, offhand, Trinkets, cursor, crafting grid,
     * inside shulker boxes and bundles), their own items and skill tree come back exactly, and anything else they got
     * meanwhile (loot from chests, rewards) is kept: into free slots, or dropped at their feet. False when they
     * weren't renting.
     */
    public static boolean end(ServerPlayer player) {
        CompoundTag record = player.getAttached(STASH);
        String live = LIVE.remove(player.getUUID());
        if (record == null) {
            return live != null;
        }
        String session = record.getString("session");
        List<ItemStack> kept = new ArrayList<>();
        int[] removed = {0};
        Consumer<ItemStack> sort = stack -> {
            if (RentalItems.isRental(stack)) {
                removed[0] += stack.getCount();
            } else {
                RentalItems.stripNested(stack);
                kept.add(stack);
            }
        };
        player.closeContainer();
        takeLoose(player, sort);
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                sort.accept(stack);
            }
        }
        if (TRINKETS) {
            RentalTrinkets.clear(player, sort);
        }
        // Their own items back in their slots (load empties the inventory first).
        inventory.load(record.getList("inventory", Tag.TAG_COMPOUND));
        if (TRINKETS && record.contains("trinkets", Tag.TAG_LIST)) {
            RentalTrinkets.giveBack(player, record.getList("trinkets", Tag.TAG_COMPOUND), kept::add);
        }
        if (SKILLS && record.contains("skills", Tag.TAG_COMPOUND)) {
            RentalSkills.giveBack(player, record.getCompound("skills"));
        }
        for (Tag tag : record.getList("extra", Tag.TAG_COMPOUND)) {
            ItemStack.parse(player.registryAccess(), tag).ifPresent(kept::add);
        }
        int deathXp = record.getInt("death_xp");
        if (deathXp > 0) {
            player.giveExperiencePoints(deathXp);
        }
        player.removeAttached(STASH);
        // Their own gear's max health: the same share of it as they had of the loadout's.
        healLater(player, player.getHealth() / Math.max(1.0f, player.getMaxHealth()));
        int dropped = 0;
        for (ItemStack stack : kept) {
            if (!inventory.add(stack) && !stack.isEmpty()) {
                player.drop(stack, false);
                dropped++;
            }
        }
        player.inventoryMenu.broadcastChanges();
        EdenLD.LOGGER.info("[Rental] {} ended rental session {}{}: {} rental items removed, own items back, {} other "
                        + "stacks kept ({} dropped at their feet: no room).", player.getGameProfile().getName(), session,
                live == null ? " (after a restart, crash or end while offline)" : "", removed[0], kept.size(), dropped);
        return true;
    }

    /** Ends the session of an online or offline player; an offline one gets everything back at their next login. */
    public static boolean end(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return end(online);
        }
        return LIVE.remove(player) != null;
    }

    /**
     * The session's loadout handed out again (after a death in a run, say): rental items they still hold go first.
     * False when they don't rent, or the session's loadout wasn't a kit.
     */
    public static boolean reequip(ServerPlayer player) {
        CompoundTag record = player.getAttached(STASH);
        String session = LIVE.get(player.getUUID());
        ResourceLocation kit = record == null ? null : ResourceLocation.tryParse(record.getString("kit"));
        if (session == null || kit == null || record.getString("kit").isEmpty()) {
            return false;
        }
        removeRental(player, stack -> true);
        // Tagged as they're given (anything else they hold now, loot from chests say, stays theirs).
        boolean given = KitsCompat.giveHidden(player, kit, stack -> RentalItems.tag(stack, session));
        player.inventoryMenu.broadcastChanges();
        healLater(player, 1.0f);
        return given;
    }

    /**
     * Keeps a stack for the end of the session (a reward earned while renting): it's given with their own items then,
     * safe from the rental cleanup. False (nothing kept) when they don't rent.
     */
    public static boolean stashItem(ServerPlayer player, ItemStack stack) {
        CompoundTag record = player.getAttached(STASH);
        if (record == null || stack.isEmpty() || RentalItems.isRental(stack)) {
            return false;
        }
        ListTag extra = record.getList("extra", Tag.TAG_COMPOUND);
        extra.add(stack.save(player.registryAccess()));
        record.put("extra", extra);
        player.setAttached(STASH, record);
        return true;
    }

    /** What staff see: the session, its kit and when it began, or null. */
    @Nullable
    public static CompoundTag record(ServerPlayer player) {
        CompoundTag record = player.getAttached(STASH);
        return record == null ? null : record.copy();
    }

    // ---------------------------------------------------------------- guards

    /** Whether the slot's container may take this stack: rental items only into their renter's own slots. */
    public static boolean mayHold(Container container, ItemStack stack) {
        String session = RentalItems.sessionOf(stack);
        if (session == null) {
            return true;
        }
        Entity owner = container instanceof Inventory inventory ? inventory.player
                : TRINKETS ? RentalTrinkets.wearer(container) : null;
        // A player's own slots on their client: the server decides (the client doesn't know sessions).
        return owner != null && (owner.level().isClientSide() || session.equals(LIVE.get(owner.getUUID())));
    }

    /**
     * A renter's dropped rental item (Q, thrown out of a screen): back into their hands or inventory; any other
     * holder's (or with no room) is simply gone. True when the drop is taken over.
     */
    public static boolean onDrop(ServerPlayer player, ItemStack stack) {
        String session = RentalItems.sessionOf(stack);
        if (session == null) {
            return false;
        }
        if (session.equals(LIVE.get(player.getUUID()))) {
            Inventory inventory = player.getInventory();
            if (inventory.getItem(inventory.selected).isEmpty()) {
                inventory.setItem(inventory.selected, stack);
            } else {
                inventory.add(stack);
            }
            // The client already took it away: show it back.
            player.containerMenu.sendAllDataToRemote();
        }
        return true;
    }

    /** Renters pick up nothing from the ground but their own session's items. */
    public static boolean mayPickUp(Player player, ItemStack stack) {
        String session = LIVE.get(player.getUUID());
        return session == null || session.equals(RentalItems.sessionOf(stack));
    }

    /**
     * A renter dies: nothing drops (no items, no grave): rental items go, anything else of theirs is kept for the end.
     * The session goes on; its owner decides (end it, or {@link #reequip} after the respawn).
     */
    public static void onDeath(ServerPlayer player) {
        CompoundTag record = player.getAttached(STASH);
        if (record == null) {
            return;
        }
        ListTag extra = record.getList("extra", Tag.TAG_COMPOUND);
        Consumer<ItemStack> keep = stack -> {
            if (!RentalItems.isRental(stack)) {
                RentalItems.stripNested(stack);
                extra.add(stack.save(player.registryAccess()));
            }
        };
        takeLoose(player, keep);
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                keep.accept(stack);
            }
        }
        inventory.clearContent();
        if (TRINKETS) {
            RentalTrinkets.clear(player, keep);
        }
        record.put("extra", extra);
        // Their experience doesn't drop either: given back at the end (the respawn starts at 0, as after any death).
        record.putInt("death_xp", record.getInt("death_xp") + experiencePoints(player));
        player.setAttached(STASH, record);
    }

    /** Sets the player's health to {@code share} of their max health a few ticks from now (see {@link #HEAL}). */
    private static void healLater(ServerPlayer player, float share) {
        HEAL.put(player.getUUID(), new Heal(Math.max(0.0f, Math.min(1.0f, share)), ticks + HEAL_DELAY_TICKS));
    }

    private static void healDue(MinecraftServer server) {
        HEAL.entrySet().removeIf(entry -> {
            if (entry.getValue().dueTick() > ticks) {
                return false;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.isAlive()) {
                // Never 0: a share of a sliver still leaves them alive.
                player.setHealth(Math.max(1.0f, entry.getValue().share() * player.getMaxHealth()));
            }
            return true;
        });
    }

    /** Marks a renter's death in progress (see {@link #onDeath}). */
    public static void dying(boolean start) {
        dying = Math.max(0, dying + (start ? 1 : -1));
    }

    /** The player's experience as points (level and progress). */
    private static int experiencePoints(ServerPlayer player) {
        long points = 0;
        for (int level = 0; level < player.experienceLevel; level++) {
            points += xpNeededAt(level);
        }
        points += Math.round(player.experienceProgress * xpNeededAt(player.experienceLevel));
        return (int) Math.min(Integer.MAX_VALUE, points);
    }

    /** As {@code Player.getXpNeededForNextLevel} for a level. */
    private static int xpNeededAt(int level) {
        return level >= 30 ? 112 + (level - 30) * 9 : level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
    }

    // ---------------------------------------------------------------- inside

    private static void onJoin(ServerPlayer player) {
        CompoundTag record = player.getAttached(STASH);
        if (record != null && !record.getString("session").equals(LIVE.get(player.getUUID()))) {
            // A stash with no running session: a crash or restart during it, or it ended while they were away.
            end(player);
        } else if (record == null) {
            LIVE.remove(player.getUUID());
        }
        sweep(player);
    }

    /** Removes rental items that aren't the player's running session's (anywhere they hold items). */
    private static void sweep(ServerPlayer player) {
        String session = LIVE.get(player.getUUID());
        int removed = removeRental(player, stack -> !RentalItems.sessionOf(stack).equals(session));
        if (removed > 0) {
            player.containerMenu.sendAllDataToRemote();
            EdenLD.LOGGER.warn("[Rental] Removed {} rental items {} held outside their session.", removed,
                    player.getGameProfile().getName());
        }
    }

    /** Removes the player's rental items the test picks; how many stacks. Nested ones go too. */
    private static int removeRental(ServerPlayer player, java.util.function.Predicate<ItemStack> which) {
        int removed = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (RentalItems.isRental(stack)) {
                if (which.test(stack)) {
                    inventory.setItem(slot, ItemStack.EMPTY);
                    removed++;
                }
            } else if (RentalItems.stripNested(stack)) {
                removed++;
            }
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (RentalItems.isRental(carried) && which.test(carried)) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            removed++;
        }
        CraftingContainer grid = player.inventoryMenu.getCraftSlots();
        for (int slot = 0; slot < grid.getContainerSize(); slot++) {
            ItemStack stack = grid.getItem(slot);
            if (RentalItems.isRental(stack) && which.test(stack)) {
                grid.setItem(slot, ItemStack.EMPTY);
                removed++;
            }
        }
        if (TRINKETS) {
            removed += RentalTrinkets.removeIf(player, stack -> RentalItems.isRental(stack) && which.test(stack));
        }
        return removed;
    }

    /** The cursor and the 2x2 crafting grid emptied, each stack handed over. */
    private static void takeLoose(ServerPlayer player, Consumer<ItemStack> taken) {
        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty()) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            taken.accept(carried);
        }
        CraftingContainer grid = player.inventoryMenu.getCraftSlots();
        for (int slot = 0; slot < grid.getContainerSize(); slot++) {
            ItemStack stack = grid.removeItemNoUpdate(slot);
            if (!stack.isEmpty()) {
                taken.accept(stack);
            }
        }
    }

    /** Marks everything the player holds as the session's; how many stacks. */
    private static int tagAll(ServerPlayer player, String session) {
        int[] count = {0};
        Consumer<ItemStack> tag = stack -> {
            if (!stack.isEmpty()) {
                RentalItems.tag(stack, session);
                count[0]++;
            }
        };
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            tag.accept(inventory.getItem(slot));
        }
        if (TRINKETS) {
            RentalTrinkets.forEach(player, tag);
        }
        return count[0];
    }

    /** The stash's copy for staff, written now (small; a rename, so never half a file). */
    private static void writeCopy(ServerPlayer player, CompoundTag record) {
        Path folder = player.server.getWorldPath(LevelResource.ROOT).resolve("eden_ld").resolve("rental");
        Path target = folder.resolve(player.getUUID() + ".dat");
        try {
            Files.createDirectories(folder);
            Path temp = Files.createTempFile(folder, player.getUUID().toString(), ".tmp");
            NbtIo.writeCompressed(record, temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            EdenLD.LOGGER.error("[Rental] Can't write the stash copy {} (the stash itself is safe in the player's save).",
                    target, e);
        }
    }
}
