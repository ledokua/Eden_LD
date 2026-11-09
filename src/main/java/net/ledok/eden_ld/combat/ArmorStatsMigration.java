package net.ledok.eden_ld.combat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.ArrayList;
import java.util.List;

/**
 * Old RPG Series armor gets the current set stats ({@code Balance/COMBAT-REWORK.md}). Dungeon Difficulty and Limit Break
 * write the whole attribute list onto a piece when they touch it (to add its health), so a piece looted before the
 * rework kept its old armor points and evasion and has no {@code eden_ld:damage_reduction}. Every few seconds the pieces
 * in each player's inventory are rebuilt: the item's current stats ({@code minecraft:armor.*} modifiers, from the class
 * mod's config) plus everything other mods added (Dungeon Difficulty's health, Limit Break's bonuses), kept as they are.
 * {@code combat.json} {@code update_old_armor_stats}.
 */
public final class ArmorStatsMigration {

    private static final TagKey<Item> RPG_ARMOR = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("falleneden", "gear/armor/all"));
    private static final int INTERVAL_TICKS = 40;

    private ArmorStatsMigration() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(ArmorStatsMigration::tick);
    }

    private static void tick(MinecraftServer server) {
        if (!EdenLD.CONFIG.combat.update_old_armor_stats || server.getTickCount() % INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (update(stack)) {
                    inventory.setChanged();
                }
            }
        }
    }

    /** Rebuilds the piece's attribute list when it carries old set stats; true when it changed. */
    static boolean update(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(RPG_ARMOR)) {
            return false;
        }
        var patched = stack.getComponentsPatch().get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (patched == null || patched.isEmpty()) {
            return false; // no copy on the stack: the item's own (current) stats apply
        }
        ItemAttributeModifiers current = patched.get();
        // The class mods' armor gives its stats through Item#getDefaultAttributeModifiers (Spell Engine's configurable
        // attributes), not the item's default components.
        @SuppressWarnings("deprecation")
        ItemAttributeModifiers defaults = stack.getItem().getDefaultAttributeModifiers();
        if (defaults.modifiers().isEmpty()) {
            return false; // nothing to rebuild from: leave the piece alone
        }
        List<ItemAttributeModifiers.Entry> copiedSetStats = new ArrayList<>();
        List<ItemAttributeModifiers.Entry> added = new ArrayList<>();
        for (ItemAttributeModifiers.Entry entry : current.modifiers()) {
            (isSetStat(entry) ? copiedSetStats : added).add(entry);
        }
        if (copiedSetStats.equals(defaults.modifiers())) {
            return false;
        }
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        for (ItemAttributeModifiers.Entry entry : defaults.modifiers()) {
            builder.add(entry.attribute(), entry.modifier(), entry.slot());
        }
        for (ItemAttributeModifiers.Entry entry : added) {
            builder.add(entry.attribute(), entry.modifier(), entry.slot());
        }
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build().withTooltip(current.showInTooltip()));
        return true;
    }

    /** The class mod's own set stats all use vanilla's per-slot ids ({@code minecraft:armor.chest} and so on). */
    private static boolean isSetStat(ItemAttributeModifiers.Entry entry) {
        ResourceLocation id = entry.modifier().id();
        return id.getNamespace().equals("minecraft") && id.getPath().startsWith("armor.");
    }
}
