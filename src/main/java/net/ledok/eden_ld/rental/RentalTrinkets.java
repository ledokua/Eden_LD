package net.ledok.eden_ld.rental;

import dev.emi.trinkets.api.TrinketInventory;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The player's Trinkets slots for the rental stash (only loaded when Trinkets is installed). */
final class RentalTrinkets {

    private RentalTrinkets() {
    }

    /** Every worn trinket as {group, slot, index, item}; nothing changes. */
    static ListTag save(ServerPlayer player) {
        return save(player, stack -> true);
    }

    /** The worn trinkets the test picks as {group, slot, index, item}; nothing changes. */
    static ListTag save(ServerPlayer player, java.util.function.Predicate<ItemStack> which) {
        ListTag saved = new ListTag();
        HolderLookup.Provider registries = player.registryAccess();
        forEachSlot(player, (inventory, group, slot) -> {
            for (int index = 0; index < inventory.getContainerSize(); index++) {
                ItemStack stack = inventory.getItem(index);
                if (stack.isEmpty() || !which.test(stack)) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.putString("group", group);
                entry.putString("slot", slot);
                entry.putInt("index", index);
                entry.put("item", stack.save(registries));
                saved.add(entry);
            }
        });
        return saved;
    }

    /** Every worn trinket, its slot emptied, handed to {@code taken}. */
    static void clear(ServerPlayer player, Consumer<ItemStack> taken) {
        forEachSlot(player, (inventory, group, slot) -> {
            for (int index = 0; index < inventory.getContainerSize(); index++) {
                ItemStack stack = inventory.getItem(index);
                if (!stack.isEmpty()) {
                    inventory.setItem(index, ItemStack.EMPTY);
                    taken.accept(stack);
                }
            }
        });
    }

    /** Every worn trinket, in place (to tag or check them). */
    static void forEach(ServerPlayer player, Consumer<ItemStack> visit) {
        forEachSlot(player, (inventory, group, slot) -> {
            for (int index = 0; index < inventory.getContainerSize(); index++) {
                ItemStack stack = inventory.getItem(index);
                if (!stack.isEmpty()) {
                    visit.accept(stack);
                }
            }
        });
    }

    /** Removes worn trinkets the test matches; how many. */
    static int removeIf(ServerPlayer player, java.util.function.Predicate<ItemStack> test) {
        int[] removed = {0};
        forEachSlot(player, (inventory, group, slot) -> {
            for (int index = 0; index < inventory.getContainerSize(); index++) {
                ItemStack stack = inventory.getItem(index);
                if (!stack.isEmpty() && test.test(stack)) {
                    inventory.setItem(index, ItemStack.EMPTY);
                    removed[0]++;
                }
            }
        });
        return removed[0];
    }

    /** Back in their slots; when a slot is taken or gone, handed to {@code leftover}. */
    static void giveBack(ServerPlayer player, ListTag saved, Consumer<ItemStack> leftover) {
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
            } else {
                leftover.accept(stack);
            }
        }
    }

    /** The entity wearing this Trinkets slot container, or null for any other container. */
    static LivingEntity wearer(Container container) {
        return container instanceof TrinketInventory inventory ? inventory.getComponent().getEntity() : null;
    }

    private interface SlotVisitor {
        void visit(TrinketInventory inventory, String group, String slot);
    }

    private static void forEachSlot(ServerPlayer player, SlotVisitor visitor) {
        TrinketsApi.getTrinketComponent(player).ifPresent(component -> {
            for (Map.Entry<String, Map<String, TrinketInventory>> group : component.getInventory().entrySet()) {
                for (Map.Entry<String, TrinketInventory> slot : List.copyOf(group.getValue().entrySet())) {
                    visitor.visit(slot.getValue(), group.getKey(), slot.getKey());
                }
            }
        });
    }
}
