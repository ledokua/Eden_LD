package net.ledok.eden_ld.rental;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
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

    /**
     * Marks the stack as a rental item of the session, and every stack inside it: a shulker's or bundle's contents, a
     * crossbow's loaded ammo, a Bundle API rune pouch's runes or quiver's arrows (review finding 2, 1.3.130: dumped
     * runes and shot arrows came out untagged and stayed with the renter).
     */
    public static void tag(ItemStack stack, String session) {
        if (!stack.isEmpty()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(TAG, session));
            for (DataComponentType<?> type : nestedTypes()) {
                rewrite(stack, type, tag -> tagStacks(tag, session));
            }
        }
    }

    /** Components that hold other item stacks; Bundle API's when it is installed. */
    private static List<DataComponentType<?>> nestedTypes() {
        List<DataComponentType<?>> types = nested;
        if (types == null) {
            types = new ArrayList<>(List.of(DataComponents.CONTAINER, DataComponents.BUNDLE_CONTENTS,
                    DataComponents.CHARGED_PROJECTILES));
            DataComponentType<?> bundleApi = BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                    ResourceLocation.fromNamespaceAndPath("bundleapi", "custom_bundle_contents"));
            if (bundleApi != null) {
                types.add(bundleApi);
            }
            nested = types = List.copyOf(types);
        }
        return types;
    }

    @Nullable
    private static List<DataComponentType<?>> nested;

    /**
     * Edits one component as NBT (its own codec, so any mod's container works) and sets it back when {@code edit}
     * changed anything; true when it did.
     */
    private static <T> boolean rewrite(ItemStack stack, DataComponentType<T> type, java.util.function.Predicate<Tag> edit) {
        T value = stack.get(type);
        net.minecraft.server.MinecraftServer server = net.ledok.eden_ld.EdenLD.server();
        if (value == null || type.codec() == null || server == null) {
            return false;
        }
        RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag tag = type.codec().encodeStart(ops, value).result().orElse(null);
        if (tag == null || !edit.test(tag)) {
            return false;
        }
        T edited = type.codec().parse(ops, tag).result().orElse(null);
        if (edited == null) {
            net.ledok.eden_ld.EdenLD.LOGGER.warn("[Rental] Couldn't write back {} of {}.", BuiltInRegistries
                    .DATA_COMPONENT_TYPE.getKey(type), stack.getItem());
            return false;
        }
        stack.set(type, edited);
        return true;
    }

    /** An encoded item stack: {id, count, components}. */
    private static boolean isStack(Tag tag) {
        if (!(tag instanceof CompoundTag compound) || !compound.contains("id", Tag.TAG_STRING)) {
            return false;
        }
        for (String key : compound.getAllKeys()) {
            if (!key.equals("id") && !key.equals("count") && !key.equals("components")) {
                return false;
            }
        }
        return true;
    }

    private static final String CUSTOM_DATA = "minecraft:custom_data";

    /** Tags every encoded item stack in {@code tag}, at any depth; true when there was one. */
    private static boolean tagStacks(Tag tag, String session) {
        boolean changed = false;
        if (isStack(tag)) {
            CompoundTag stack = (CompoundTag) tag;
            CompoundTag components = stack.getCompound("components");
            for (String key : components.getAllKeys()) {
                if (!key.equals(CUSTOM_DATA)) {
                    tagStacks(components.get(key), session);
                }
            }
            CompoundTag data = components.getCompound(CUSTOM_DATA);
            data.putString(TAG, session);
            components.put(CUSTOM_DATA, data);
            stack.put("components", components);
            return true;
        }
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                changed |= tagStacks(compound.get(key), session);
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                changed |= tagStacks(element, session);
            }
        }
        return changed;
    }

    private static boolean isRentalStack(@Nullable Tag tag) {
        return tag != null && isStack(tag)
                && ((CompoundTag) tag).getCompound("components").getCompound(CUSTOM_DATA).contains(TAG);
    }

    /** Removes every encoded rental stack from the lists in {@code tag}, at any depth; true when it did. */
    private static boolean stripStacks(Tag tag) {
        boolean changed = false;
        if (tag instanceof ListTag list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Tag element = list.get(i);
                if (isRentalStack(element) || element instanceof CompoundTag entry && !isStack(entry)
                        && entry.getAllKeys().stream().anyMatch(key -> isRentalStack(entry.get(key)))) {
                    // A rental stack, or an entry holding one ({slot, item} in containers).
                    list.remove(i);
                    changed = true;
                } else {
                    changed |= stripStacks(element);
                }
            }
        } else if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                changed |= stripStacks(compound.get(key));
            }
        }
        return changed;
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
     * Rental items hidden inside a container item (shulker box, bundle, a Bundle API pouch) removed; true when anything was. Container
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
        // Bundle API pouches and quivers (any depth). A crossbow's loaded rental arrows stay: shot, they come back
        // tagged and leave like any rental item; unloading them every second would surprise a fighter.
        for (DataComponentType<?> type : nestedTypes()) {
            if (type != DataComponents.CONTAINER && type != DataComponents.BUNDLE_CONTENTS
                    && type != DataComponents.CHARGED_PROJECTILES && rewrite(stack, type, RentalItems::stripStacks)) {
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
