package net.ledok.eden_ld.rental;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The {@code custom_data {eden_rental: <session>}} mark on rental items. */
public final class RentalItems {

    public static final String TAG = "eden_rental";

    private RentalItems() {
    }

    /** Marks the stack as a rental item of the session. */
    public static void tag(ItemStack stack, String session) {
        if (!stack.isEmpty()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(TAG, session));
        }
    }

    /** The session a rental item belongs to, or null for any other stack. */
    @Nullable
    public static String sessionOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(TAG)) {
            return null;
        }
        CompoundTag tag = data.getUnsafe();
        return tag.getString(TAG);
    }

    public static boolean isRental(ItemStack stack) {
        return sessionOf(stack) != null;
    }

    /**
     * Rental items hidden inside a container item (shulker box, bundle) removed; true when anything was. Container
     * items with their own storage (modded backpacks) aren't seen here: their menus' slots keep rental items out.
     */
    public static boolean stripNested(ItemStack stack) {
        boolean changed = false;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            // Positional: each item keeps its slot.
            net.minecraft.core.NonNullList<ItemStack> slots = net.minecraft.core.NonNullList.withSize(256, ItemStack.EMPTY);
            container.copyInto(slots);
            boolean found = false;
            for (int i = 0; i < slots.size(); i++) {
                ItemStack inner = slots.get(i);
                if (isRental(inner)) {
                    slots.set(i, ItemStack.EMPTY);
                    found = true;
                } else if (!inner.isEmpty() && stripNested(inner)) {
                    found = true;
                }
            }
            if (found) {
                stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
                changed = true;
            }
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            List<ItemStack> kept = new ArrayList<>();
            boolean found = false;
            for (ItemStack inner : bundle.itemCopyStream().toList()) {
                if (isRental(inner)) {
                    found = true;
                } else {
                    kept.add(inner);
                }
            }
            if (found) {
                stack.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(kept));
                changed = true;
            }
        }
        return changed;
    }
}
