package net.ledok.eden_ld.rental;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;

/**
 * The spell book a player has equipped (the pack's class), read from Spell Engine's spell container sources by
 * reflection, so Eden runs without Spell Engine. Ported from Arenas_LD 4.41.1 ({@code stats.SpellBooks}).
 */
public final class RentalSpellBooks {
    /** The equipped book's item id and its spell pool id ("" when the container names none). */
    public record Book(String item, String pool, ItemStack stack) {
    }

    private static boolean looked;
    private static Method spellsOf, sources, itemStack, container, pool;

    private RentalSpellBooks() {
    }

    public static @Nullable Book of(Player player) {
        if (!lookup()) {
            return null;
        }
        try {
            Object result = spellsOf.invoke(null, player);
            if (result == null) {
                return null;
            }
            List<?> list = (List<?>) sources.invoke(result);
            Book fallback = null;
            for (Object source : list) {
                if (!(itemStack.invoke(source) instanceof ItemStack stack) || stack.isEmpty()) {
                    continue;
                }
                Object spellContainer = container.invoke(source);
                Object poolId = spellContainer == null ? null : pool.invoke(spellContainer);
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                Book book = new Book(id, poolId == null ? "" : poolId.toString(), stack);
                if (id.contains("book")) {
                    return book; // wizards:arcane_spell_book, rogues:…, paladins:…
                }
                boolean inHand = stack == player.getMainHandItem() || stack == player.getOffhandItem();
                if (fallback == null && !inHand && !book.pool().isEmpty()) {
                    fallback = book; // a book-like accessory with a pool that isn't named "…book"
                }
            }
            return fallback;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean lookup() {
        if (looked) {
            return spellsOf != null;
        }
        looked = true;
        try {
            Class<?> source = Class.forName("net.spell_engine.internals.container.SpellContainerSource");
            Class<?> result = Class.forName("net.spell_engine.internals.container.SpellContainerSource$Result");
            Class<?> sourced = Class.forName("net.spell_engine.internals.container.SpellContainerSource$SourcedContainer");
            Class<?> spellContainer = Class.forName("net.spell_engine.api.spell.container.SpellContainer");
            Method spellsOfMethod = source.getMethod("getSpellsOf", Player.class);
            sources = result.getMethod("sources");
            itemStack = sourced.getMethod("itemStack");
            container = sourced.getMethod("container");
            pool = spellContainer.getMethod("pool");
            spellsOf = spellsOfMethod;
        } catch (ClassNotFoundException e) {
            // No Spell Engine: no spell books.
        } catch (ReflectiveOperationException | RuntimeException e) {
            EdenLD.LOGGER.warn("[Rental] Couldn't read Spell Engine spell books", e);
        }
        return spellsOf != null;
    }
}
