package net.ledok.eden_ld.tutorial;

import dev.emi.trinkets.api.TrinketInventory;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/** The player's trinkets put aside for the tutorial and given back (only loaded when Trinkets is installed). */
final class TutorialTrinkets {

    private TutorialTrinkets() {
    }

    /** Every worn trinket as {group, slot, index, item}, the slots emptied. */
    static ListTag takeAll(ServerPlayer player) {
        ListTag saved = new ListTag();
        HolderLookup.Provider registries = player.registryAccess();
        TrinketsApi.getTrinketComponent(player).ifPresent(component -> {
            for (Map.Entry<String, Map<String, TrinketInventory>> group : component.getInventory().entrySet()) {
                for (Map.Entry<String, TrinketInventory> slot : group.getValue().entrySet()) {
                    TrinketInventory inventory = slot.getValue();
                    for (int index = 0; index < inventory.getContainerSize(); index++) {
                        ItemStack stack = inventory.getItem(index);
                        if (stack.isEmpty()) {
                            continue;
                        }
                        CompoundTag entry = new CompoundTag();
                        entry.putString("group", group.getKey());
                        entry.putString("slot", slot.getKey());
                        entry.putInt("index", index);
                        entry.put("item", stack.save(registries));
                        saved.add(entry);
                        inventory.setItem(index, ItemStack.EMPTY);
                    }
                }
            }
        });
        return saved;
    }

    /** Back in their slots; when a slot is taken or gone, into the inventory (or dropped). */
    static void giveBack(ServerPlayer player, ListTag saved) {
        HolderLookup.Provider registries = player.registryAccess();
        var component = TrinketsApi.getTrinketComponent(player).orElse(null);
        for (Tag tag : saved) {
            CompoundTag entry = (CompoundTag) tag;
            ItemStack stack = ItemStack.parseOptional(registries, entry.getCompound("item"));
            if (stack.isEmpty()) {
                continue;
            }
            TrinketInventory inventory = component == null ? null
                    : component.getInventory().getOrDefault(entry.getString("group"), Map.of()).get(entry.getString("slot"));
            int index = entry.getInt("index");
            if (inventory != null && index < inventory.getContainerSize() && inventory.getItem(index).isEmpty()) {
                inventory.setItem(index, stack);
            } else if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }
}
