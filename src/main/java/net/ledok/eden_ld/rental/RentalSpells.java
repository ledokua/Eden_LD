package net.ledok.eden_ld.rental;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Choosing the spells of a rental kit's spell book (ported from Arenas_LD 4.41.1, LeDok 2026-11-18: one picker for
 * Arenas and Duels). A kit's book is a Spell Engine {@code spell_container} with a {@code pool} (a spell tag) and up to
 * {@code max_spell_count} {@code spell_ids}; in the picker the player chooses which spells of the pool it holds, the
 * pick is kept per player and preset, and once the kit is lent (and after a {@code reequip}) the lent book's
 * {@code spell_ids} become the pick.
 * <p>
 * Spell Engine is read by reflection (its own classes, not remapped), so Eden runs without it: then no book has spells.
 * The rules come from {@code rental.json}: {@code spell_tiers} and {@code spell_max_tier}.
 */
public final class RentalSpells {
    /**
     * A kit book's spell choice.
     *
     * @param pool     the book's pool (spell tag id, no "#")
     * @param max      how many spells it holds
     * @param defaults the kit's own spell_ids
     * @param allowed  the pool's spells the kit level may pick, in tag order
     * @param tiers    each allowed spell's tier when the picker is one spell per tier ({@code spell_tiers});
     *                 empty: a free choice of {@code allowed}, up to {@code max}
     */
    public record Choice(String pool, int max, List<String> defaults, List<String> allowed, Map<String, Integer> tiers) {
        public Choice(String pool, int max, List<String> defaults, List<String> allowed) {
            this(pool, max, defaults, allowed, Map.of());
        }

        /** One spell per tier. */
        public boolean perTier() {
            return !tiers.isEmpty();
        }

        /** The tiers with a row, in order. */
        public List<Integer> tierRows() {
            return tiers.values().stream().distinct().sorted().toList();
        }
    }

    private static boolean looked;
    private static @Nullable DataComponentType<Object> component;
    private static @Nullable Method copyWith, poolOf, maxOf, idsOf, setDirty;
    private static @Nullable ResourceKey<? extends Registry<?>> spellRegistry;
    private static @Nullable Field tierField;

    private RentalSpells() {
    }

    // ── reading ──────────────────────────────────────────────────────────────

    /** The spell choice of the first spell book among {@code items} (a kit's preview), or null when it has none. */
    public static @Nullable Choice forKit(RegistryAccess access, List<ItemStack> items, int kitLevel) {
        for (ItemStack stack : items) {
            Choice choice = of(access, stack, kitLevel);
            if (choice != null) {
                return choice;
            }
        }
        return null;
    }

    /** The stack's spell choice: a container with a pool, room for spells and pool members; else null. */
    public static @Nullable Choice of(RegistryAccess access, ItemStack stack, int kitLevel) {
        Object container = container(stack);
        if (container == null) {
            return null;
        }
        try {
            String pool = clean((String) poolOf.invoke(container));
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) idsOf.invoke(container);
            int max = capacity((int) maxOf.invoke(container), ids);
            if (pool.isEmpty() || max <= 0) {
                return null;
            }
            Map<String, Integer> tiers = poolSpells(access, pool, maxTier(kitLevel));
            List<Integer> offered = EdenLD.CONFIG.rental.spell_tiers;
            List<String> defaults = ids == null ? List.of() : List.copyOf(ids);
            if (offered == null || offered.isEmpty()) {
                List<String> allowed = List.copyOf(tiers.keySet());
                return allowed.isEmpty() ? null : new Choice(pool, max, defaults, allowed);
            }
            return perTier(pool, defaults, tiers, offered);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * How many spells a book holds: its {@code max_spell_count}, or, when that is unset (Spell Engine's codec makes it
     * 0, as in Fallen Eden's rental kits), the number of its {@code spell_ids}. The book itself is never changed to say
     * so: a container with a max could become editable at a binding table.
     */
    public static int capacity(int maxSpellCount, @Nullable List<String> spellIds) {
        return maxSpellCount > 0 ? maxSpellCount : spellIds == null ? 0 : spellIds.size();
    }

    /**
     * A copy of {@code stack} whose spell container holds {@code spells} (only {@code spell_ids} changes), for showing a
     * pick; the stack itself when it has no container or nothing is picked.
     */
    public static ItemStack withSpells(ItemStack stack, List<String> spells) {
        Object container = container(stack);
        if (container == null || spells.isEmpty()) {
            return stack;
        }
        try {
            ItemStack copy = stack.copy();
            copy.set(component, copyWith.invoke(container, List.copyOf(spells)));
            return copy;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return stack;
        }
    }

    /** Whether the stack has a Spell Engine spell container (a spell book). */
    public static boolean hasContainer(ItemStack stack) {
        return container(stack) != null;
    }

    /** The spell_ids of the stack's container (empty without one). */
    public static List<String> spellsOf(ItemStack stack) {
        Object container = container(stack);
        if (container == null) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) idsOf.invoke(container);
            return ids == null ? List.of() : List.copyOf(ids);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    /** The pool of the stack's container ("" without one). */
    public static String poolOf(ItemStack stack) {
        Object container = container(stack);
        if (container == null) {
            return "";
        }
        try {
            return clean((String) poolOf.invoke(container));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /**
     * What the picker starts with: the player's saved pick for this preset, else the spells of their own book when it
     * has the same pool, else the kit's own; always cut to what {@code choice} allows.
     */
    public static List<String> prefill(ServerPlayer player, String preset, Choice choice) {
        List<String> saved = Store.get(player.server).pick(player.getUUID(), preset);
        List<String> out = saved == null ? List.of() : validate(saved, choice);
        if (out.isEmpty()) {
            var own = RentalSpellBooks.of(player);
            if (own != null && clean(own.pool()).equals(choice.pool())) {
                out = validate(spellsOf(own.stack()), choice);
            }
        }
        if (out.isEmpty()) {
            out = validate(choice.defaults(), choice);
        }
        return out;
    }

    /**
     * The one-spell-per-tier choice of a pool whose spells have {@code tiers}: only the {@code offered} tiers, one row
     * each; null when none of them has a spell.
     */
    public static @Nullable Choice perTier(String pool, List<String> defaults, Map<String, Integer> tiers, List<Integer> offered) {
        Map<String, Integer> kept = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : tiers.entrySet()) {
            if (offered.contains(entry.getValue())) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        // Allowed in tier order, tag order within a tier: the picker's rows.
        List<String> allowed = kept.entrySet().stream()
            .sorted(java.util.Comparator.comparingInt(Map.Entry::getValue)).map(Map.Entry::getKey).toList();
        int rows = (int) kept.values().stream().distinct().count();
        return new Choice(pool, rows, defaults, allowed, Map.copyOf(kept));
    }

    /**
     * {@code picked} cut to what {@code choice} allows. Free choice: the allowed spells, no repeats, at most {@code max}.
     * One per tier: the first pick of each tier row, and every row filled (the kit's own spell of that tier, else the
     * row's first), so the book always holds one of each pair.
     */
    public static List<String> validate(List<String> picked, Choice choice) {
        if (choice.perTier()) {
            Map<Integer, String> byTier = new java.util.TreeMap<>();
            for (String id : picked) {
                Integer tier = id == null ? null : choice.tiers().get(id);
                if (tier != null) {
                    byTier.putIfAbsent(tier, id);
                }
            }
            for (int tier : choice.tierRows()) {
                if (byTier.containsKey(tier)) {
                    continue;
                }
                String fill = choice.defaults().stream().filter(d -> Integer.valueOf(tier).equals(choice.tiers().get(d)))
                    .findFirst().orElseGet(() -> choice.allowed().stream()
                        .filter(a -> choice.tiers().get(a) == tier).findFirst().orElse(null));
                if (fill != null) {
                    byTier.put(tier, fill);
                }
            }
            return List.copyOf(byTier.values());
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : picked) {
            if (id != null && choice.allowed().contains(id) && out.size() < choice.max()) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    // ── applying ─────────────────────────────────────────────────────────────

    /**
     * Puts {@code spells} into the player's equipped book of {@code pool} (the lent one). Returns whether a book was
     * changed; Spell Engine re-reads the player's containers.
     */
    public static boolean apply(ServerPlayer player, String pool, List<String> spells) {
        if (spells.isEmpty() || !lookup()) {
            return false;
        }
        // Fresh sources first: Spell Engine caches them, and right after a gear swap the cache still holds the old
        // book's stack (Arenas 4.40.6: the pick went into the player's own, put-aside book of the same pool).
        refreshSources(player);
        var book = RentalSpellBooks.of(player);
        if (book == null || !poolOf(book.stack()).equals(clean(pool))) {
            return false;
        }
        Object container = container(book.stack());
        if (container == null) {
            return false;
        }
        try {
            List<String> before = spellsOf(book.stack());
            if (before.equals(spells)) {
                EdenLD.LOGGER.info("[Rental] {}'s {} book already holds {}", player.getScoreboardName(),
                    clean(pool), spells);
                return true;
            }
            book.stack().set(component, copyWith.invoke(container, List.copyOf(spells)));
            refreshSources(player);
            if (setDirty != null) {
                setDirty.invoke(null, player);
            }
            var check = RentalSpellBooks.of(player);
            boolean ok = check != null && spellsOf(check.stack()).equals(spells);
            EdenLD.LOGGER.info("[Rental] {}'s {} book {} -> {}{}", player.getScoreboardName(), clean(pool),
                before, spells, ok ? "" : " (not read back yet; trying again)");
            return ok;
        } catch (ReflectiveOperationException | RuntimeException e) {
            EdenLD.LOGGER.warn("[Rental] Couldn't set the spells of {}'s book", player.getScoreboardName(), e);
            return false;
        }
    }

    private static @Nullable Method sourceCache, sourceUpdate;
    private static boolean sourcesLooked;

    /** Drops Spell Engine's cached spell sources of the player and builds them again from what they wear now. */
    private static void refreshSources(ServerPlayer player) {
        if (!sourcesLooked) {
            sourcesLooked = true;
            try {
                Class<?> source = Class.forName("net.spell_engine.internals.container.SpellContainerSource");
                Class<?> owner = Class.forName("net.spell_engine.internals.container.SpellContainerSource$Owner");
                sourceCache = owner.getMethod("spellContainerCache");
                sourceUpdate = source.getMethod("update", net.minecraft.world.entity.player.Player.class);
            } catch (ReflectiveOperationException | RuntimeException e) {
                EdenLD.LOGGER.warn("[Rental] Couldn't reach Spell Engine's source cache", e);
            }
        }
        if (sourceCache == null || sourceUpdate == null) {
            return;
        }
        try {
            if (sourceCache.invoke(player) instanceof Map<?, ?> cache) {
                cache.clear();
            }
            sourceUpdate.invoke(null, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            EdenLD.LOGGER.warn("[Rental] Couldn't refresh {}'s spell sources", player.getScoreboardName(), e);
        }
    }

    // ── Spell Engine ─────────────────────────────────────────────────────────

    private static @Nullable Object container(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !lookup()) {
            return null;
        }
        return stack.get(component);
    }

    /** The pool's spells (registry tag) with their tiers, those above {@code maxTier} left out (-1: all). */
    private static Map<String, Integer> poolSpells(RegistryAccess access, String pool, int maxTier) {
        ResourceLocation id = ResourceLocation.tryParse(pool);
        if (id == null || spellRegistry == null) {
            return Map.of();
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        var registry = access.registry((ResourceKey) spellRegistry);
        if (registry.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Registry<Object> spells = (Registry<Object>) registry.get();
        var tag = spells.getTag(TagKey.create(spells.key(), id));
        if (tag.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Holder<Object> holder : tag.get()) {
            String spellId = holder.unwrapKey().map(k -> k.location().toString()).orElse(null);
            int tier = tierOf(holder.value());
            if (spellId != null && (maxTier < 0 || tier <= maxTier)) {
                out.putIfAbsent(spellId, tier);
            }
        }
        return out;
    }

    private static int tierOf(Object spell) {
        try {
            return tierField == null ? 0 : tierField.getInt(spell);
        } catch (IllegalAccessException | RuntimeException e) {
            return 0;
        }
    }

    /** The highest spell tier the kit level may pick, from {@code spell_max_tier}; -1 = the whole pool. */
    public static int maxTierFor(int kitLevel) {
        return maxTier(kitLevel);
    }

    static int maxTier(int kitLevel) {
        Map<String, Integer> rule = EdenLD.CONFIG.rental.spell_max_tier;
        if (rule == null || rule.isEmpty()) {
            return -1;
        }
        int bestLevel = Integer.MIN_VALUE;
        int tier = -1;
        for (Map.Entry<String, Integer> entry : rule.entrySet()) {
            try {
                int level = Integer.parseInt(entry.getKey().trim());
                if (level <= Math.max(0, kitLevel) && level > bestLevel && entry.getValue() != null) {
                    bestLevel = level;
                    tier = entry.getValue();
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return tier;
    }

    private static String clean(@Nullable String pool) {
        if (pool == null) {
            return "";
        }
        String out = pool.trim();
        return out.startsWith("#") ? out.substring(1) : out;
    }

    @SuppressWarnings("unchecked")
    private static boolean lookup() {
        if (looked) {
            return component != null;
        }
        looked = true;
        try {
            Class<?> components = Class.forName("net.spell_engine.api.spell.SpellDataComponents");
            Class<?> container = Class.forName("net.spell_engine.api.spell.container.SpellContainer");
            Object type = components.getField("SPELL_CONTAINER").get(null);
            copyWith = container.getMethod("copyWith", List.class);
            poolOf = container.getMethod("pool");
            maxOf = container.getMethod("max_spell_count");
            idsOf = container.getMethod("spell_ids");
            try {
                Class<?> registry = Class.forName("net.spell_engine.api.spell.registry.SpellRegistry");
                spellRegistry = (ResourceKey<? extends Registry<?>>) registry.getField("KEY").get(null);
                tierField = Class.forName("net.spell_engine.api.spell.Spell").getField("tier");
            } catch (ReflectiveOperationException | RuntimeException e) {
                EdenLD.LOGGER.warn("[Rental] Couldn't read Spell Engine's spell registry; no spell choice", e);
            }
            try {
                setDirty = Class.forName("net.spell_engine.internals.container.SpellContainerSource")
                    .getMethod("setDirtyServerSide", net.minecraft.world.entity.player.Player.class);
            } catch (ReflectiveOperationException | RuntimeException e) {
                setDirty = null;
            }
            component = (DataComponentType<Object>) type;
        } catch (ClassNotFoundException e) {
            // No Spell Engine: no spell choice.
        } catch (ReflectiveOperationException | RuntimeException e) {
            EdenLD.LOGGER.warn("[Rental] Couldn't read Spell Engine spell containers; no spell choice", e);
        }
        return component != null;
    }

    // ── saved picks ──────────────────────────────────────────────────────────

    /** Each player's last spell pick per preset name ("terra_quake"), so the next picker starts with it. */
    public static final class Store extends SavedData {
        private static final String DATA_ID = "eden_ld_rental_spells";
        private static final Factory<Store> FACTORY = new Factory<>(Store::new, Store::load, null);
        private final Map<UUID, Map<String, List<String>>> picks = new HashMap<>();

        public static Store get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
        }

        public @Nullable List<String> pick(UUID player, String preset) {
            Map<String, List<String>> own = picks.get(player);
            return own == null ? null : own.get(preset);
        }

        public void put(UUID player, String preset, List<String> spells) {
            picks.computeIfAbsent(player, u -> new LinkedHashMap<>()).put(preset, List.copyOf(spells));
            setDirty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            CompoundTag players = new CompoundTag();
            picks.forEach((uuid, presets) -> {
                CompoundTag own = new CompoundTag();
                presets.forEach((preset, spells) -> {
                    ListTag list = new ListTag();
                    spells.forEach(s -> list.add(StringTag.valueOf(s)));
                    own.put(preset, list);
                });
                players.put(uuid.toString(), own);
            });
            tag.put("players", players);
            return tag;
        }

        private static Store load(CompoundTag tag, HolderLookup.Provider registries) {
            Store store = new Store();
            CompoundTag players = tag.getCompound("players");
            for (String key : players.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    CompoundTag own = players.getCompound(key);
                    Map<String, List<String>> presets = new LinkedHashMap<>();
                    for (String preset : own.getAllKeys()) {
                        ListTag list = own.getList(preset, Tag.TAG_STRING);
                        List<String> spells = new ArrayList<>();
                        for (int i = 0; i < list.size(); i++) {
                            spells.add(list.getString(i));
                        }
                        presets.put(preset, spells);
                    }
                    store.picks.put(uuid, presets);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return store;
        }
    }
}
