package net.ledok.eden_ld.compat;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.spell_engine.SpellEngineMod;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.container.SpellContainer;
import net.spell_engine.api.spell.container.SpellContainerHelper;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.fx.SpellEngineSounds;
import net.spell_engine.item.ScrollItem;
import net.spell_engine.item.SpellEngineItems;
import net.spell_engine.spellbinding.SpellBinding;
import net.spell_engine.spellbinding.spellchoice.SpellChoices;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Spell Engine's binding table: unbinding gives the spell's scroll back (owner, 2026-10-08), and needs no second copy.
 * Scrolls are the only source of spells (no spell has {@code learn} any more), so with nothing but lapis in the second
 * slot the table offered nothing, and a book's spells could only be unbound by putting another scroll of the same spell
 * there, while the one bound first was gone. Now the table also offers every spell the book holds, free, which the
 * screen shows as "already bound" with its Unbind dialog; unbinding removes the spell and puts one scroll of it, made
 * like a loot scroll, in the player's inventory. A spell goes book → scroll → book, never both: the bind takes the
 * scroll, the unbind gives one. The bind's level cost isn't refunded. Only touched from Eden's mixins on Spell Engine.
 */
public final class SpellUnbinding {

    /** The table's offer slots (SpellBindingScreenHandler syncs 32). */
    private static final int MAX_OFFERS = 32;

    private SpellUnbinding() {
    }

    /** {@code SpellBinding.offersFor}: the book's own spells join what the table offers, for unbinding. */
    public static SpellBinding.OfferResult withBoundSpells(Level level, ItemStack book, SpellBinding.OfferResult result) {
        if (result == null || result.mode() != SpellBinding.Mode.SPELL || !SpellEngineMod.config.spell_binding_allow_unbinding
                || SpellChoices.from(book) != null) {
            return result;
        }
        SpellContainer container = SpellContainerHelper.containerFromItemStack(book);
        if (container == null || !container.isValid() || container.spell_ids().isEmpty()) {
            return result;
        }
        Registry<Spell> registry = SpellRegistry.from(level);
        List<SpellBinding.Offer> offers = new ArrayList<>(result.offers());
        Set<Integer> offered = new HashSet<>();
        offers.forEach(offer -> offered.add(offer.id()));
        boolean added = false;
        for (String idString : container.spell_ids()) {
            ResourceLocation id = ResourceLocation.tryParse(idString);
            Optional<Holder.Reference<Spell>> spell = id == null ? Optional.empty() : registry.getHolder(id);
            if (spell.isEmpty() || offers.size() >= MAX_OFFERS) {
                continue;
            }
            int rawId = registry.getId(spell.get().value());
            if (offered.add(rawId)) {
                // Free and "powered": the screen shows it as already bound and offers only Unbind.
                offers.add(new SpellBinding.Offer(rawId, 0, 0, 0, true));
                added = true;
            }
        }
        return added ? new SpellBinding.OfferResult(SpellBinding.Mode.SPELL, offers) : result;
    }

    /**
     * {@code SpellBindingScreenHandler.clickMenuButton} on the server: when the clicked offer is a spell the book holds,
     * unbinds it with the scroll refund and returns true (Spell Engine's own unbind is skipped); else false (Spell
     * Engine handles the click: binding, books).
     */
    public static boolean unbind(AbstractContainerMenu menu, ServerPlayer player, int mode, int rawSpellId, Container inventory,
                                 ContainerLevelAccess access) {
        Level level = player.level();
        if (mode != SpellBinding.Mode.SPELL.ordinal() || rawSpellId < 0 || !SpellEngineMod.config.spell_binding_allow_unbinding) {
            return false;
        }
        Optional<Holder.Reference<Spell>> spell = SpellRegistry.from(level).getHolder(rawSpellId);
        ItemStack book = inventory.getItem(0);
        if (spell.isEmpty() || book.isEmpty()) {
            return false;
        }
        ResourceLocation spellId = spell.get().key().location();
        if (SpellBinding.State.of(level, spellId, book, 0, 0, 0).state != SpellBinding.State.ApplyState.ALREADY_APPLIED) {
            return false;
        }
        access.execute((world, pos) -> {
            SpellContainerHelper.removeSpell(world, spellId, book);
            ItemStack scroll = new ItemStack(SpellEngineItems.SCROLL.get());
            ScrollItem.applySpell(scroll, spell.get(), ScrollItem.resolveSpellPool(world, spell.get()));
            player.getInventory().placeItemBackInInventory(scroll);
            inventory.setChanged();
            menu.slotsChanged(inventory);
            world.playSound(null, pos, SpellEngineSounds.UNBIND_SPELL.soundEvent(), SoundSource.BLOCKS, 1.0F,
                    world.random.nextFloat() * 0.1F + 0.9F);
            EdenLD.LOGGER.info("[Spells] {} unbound {} from {}: its scroll went back to them.",
                    player.getGameProfile().getName(), spellId, book.getHoverName().getString());
        });
        return true;
    }
}
