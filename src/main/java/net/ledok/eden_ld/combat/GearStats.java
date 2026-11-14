package net.ledok.eden_ld.combat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Gear rework 2 ({@code Balance/GEAR-REWORK-2-EDEN-LD-SPEC.md}):
 * <ul>
 *   <li>{@code eden_ld:max_health_percent}: armor (and shields) carry plain percent points (Dungeon Difficulty can only
 *   write flat values); Eden turns the player's total into one max-health modifier, {@code eden_ld:max_health_percent},
 *   ADD_MULTIPLIED_BASE, value / 100 — so 20 + every flat bonus (skill nodes, Limit Break, jewelry) is multiplied.</li>
 *   <li>Shields in the off-hand give {@code eden_ld:damage_reduction} by tag ({@code #falleneden:shield_reduction/6},
 *   {@code /8}, {@code /10}; Eden Loot's datapack), as the transient modifier {@code eden_ld:shield_reduction}. It adds
 *   to the armor's before the cap and the PvP share, so {@link DamageReduction} is unchanged.</li>
 * </ul>
 * Checked every tick against what's on the player (cheap: cached attribute values), and rewritten only when it
 * changed. The max-health modifier is a <b>saved</b> one, unlike the spec's transient: health is read back before the
 * first tick on login, and vanilla caps it at the max health of that moment, so a transient bonus would cost the
 * player their extra health on every relog. Attributes are read before health, so the saved modifier is in place.
 */
public final class GearStats {

    public static final ResourceLocation MAX_HEALTH_PERCENT_ID =
            ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "max_health_percent");
    private static final ResourceLocation SHIELD_ID = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "shield_reduction");
    /** Percent points of max health (166 = +166%). Registered with vanilla's attributes ({@code AttributesRegisterMixin}). */
    public static Holder<Attribute> MAX_HEALTH_PERCENT;

    private static final int[] SHIELD_STEPS = {10, 8, 6};
    private static final TagKey<Item>[] SHIELD_TAGS = shieldTags();

    private GearStats() {
    }

    @SuppressWarnings("unchecked")
    private static TagKey<Item>[] shieldTags() {
        TagKey<Item>[] tags = new TagKey[SHIELD_STEPS.length];
        for (int i = 0; i < SHIELD_STEPS.length; i++) {
            tags[i] = TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("falleneden", "shield_reduction/" + SHIELD_STEPS[i]));
        }
        return tags;
    }

    public static void registerAttributes() {
        if (MAX_HEALTH_PERCENT == null) {
            MAX_HEALTH_PERCENT = Registry.registerForHolder(BuiltInRegistries.ATTRIBUTE, MAX_HEALTH_PERCENT_ID,
                    new RangedAttribute("attribute.name.eden_ld.max_health_percent", 0.0, 0.0, 100000.0)
                            .setSyncable(true));
        }
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                update(player);
            }
        });
    }

    private static void update(ServerPlayer player) {
        if (MAX_HEALTH_PERCENT != null && player.getAttributes().hasAttribute(MAX_HEALTH_PERCENT)) {
            double percent = player.getAttributeValue(MAX_HEALTH_PERCENT);
            set(player.getAttribute(Attributes.MAX_HEALTH), MAX_HEALTH_PERCENT_ID, percent / 100.0,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE, true);
        }
        if (DamageReduction.ATTRIBUTE != null && player.getAttributes().hasAttribute(DamageReduction.ATTRIBUTE)) {
            double shield = EdenLD.CONFIG.combat.shield_reduction_enabled ? shieldReduction(player.getOffhandItem()) : 0;
            set(player.getAttribute(DamageReduction.ATTRIBUTE), SHIELD_ID, shield,
                    AttributeModifier.Operation.ADD_VALUE, false);
        }
    }

    /** The off-hand item's damage reduction: its highest {@code #falleneden:shield_reduction/N} tag, else 0. */
    public static int shieldReduction(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < SHIELD_TAGS.length; i++) {
            if (stack.is(SHIELD_TAGS[i])) {
                return SHIELD_STEPS[i];
            }
        }
        return 0;
    }

    /** Puts the modifier at {@code amount} (none at 0), touching the attribute only when it differs. */
    private static void set(AttributeInstance attribute, ResourceLocation id, double amount,
                            AttributeModifier.Operation operation, boolean saved) {
        if (attribute == null) {
            return;
        }
        AttributeModifier current = attribute.getModifier(id);
        if (amount == 0) {
            if (current != null) {
                attribute.removeModifier(id);
            }
            return;
        }
        if (current != null && current.amount() == amount && current.operation() == operation) {
            return;
        }
        if (current != null) {
            attribute.removeModifier(id);
        }
        AttributeModifier modifier = new AttributeModifier(id, amount, operation);
        if (saved) {
            attribute.addPermanentModifier(modifier);
        } else {
            attribute.addTransientModifier(modifier);
        }
    }
}
