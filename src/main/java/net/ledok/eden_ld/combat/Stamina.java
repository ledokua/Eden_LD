package net.ledok.eden_ld.combat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.config.EdenConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stamina for Combat Roll ({@code Balance/COMBAT-REWORK.md} section 6): a roll costs stamina instead of a charge. A player
 * can roll while stamina is above 0; a roll that takes it to 0 exhausts them (no rolls, no regen) for the exhaustion
 * delay. Every roll pauses regen briefly. The server keeps the real value ({@link StaminaRolls} checks each roll) and
 * sends it to the player's client, which predicts between updates so rolls stay instant.
 *
 * <p>The worn armor decides the dodge type: pieces in {@code falleneden:armor_roll/heavy} count 3, {@code /medium} 2,
 * anything else (and an empty slot) 1; 4–6 is fast, 7–9 medium, 10–12 heavy. The type's numbers ({@code combat.json}
 * {@code roll_types}) are added to the four stamina attributes and Combat Roll's distance as modifiers.
 */
public final class Stamina {

    public static Holder<Attribute> MAX_STAMINA;
    public static Holder<Attribute> STAMINA_REGEN;
    public static Holder<Attribute> ROLL_COST;
    public static Holder<Attribute> EXHAUSTION_DELAY;

    private static final TagKey<Item> HEAVY = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("falleneden", "armor_roll/heavy"));
    private static final TagKey<Item> MEDIUM = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("falleneden", "armor_roll/medium"));
    private static final ResourceLocation TYPE_MODIFIER = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "roll_type");
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    /** Combat Roll's attributes (their base values: 1 roll, recharge 20). */
    private static final ResourceLocation ROLL_COUNT = ResourceLocation.fromNamespaceAndPath("combat_roll", "count");
    private static final ResourceLocation ROLL_RECHARGE = ResourceLocation.fromNamespaceAndPath("combat_roll", "recharge");
    private static final ResourceLocation ROLL_DISTANCE = ResourceLocation.fromNamespaceAndPath("combat_roll", "distance");

    private static final Map<UUID, State> STATES = new HashMap<>();

    public enum DodgeType { FAST, MEDIUM, HEAVY }

    private static final class State {
        double value = -1;
        long exhaustedUntil;
        long regenAfter;
        long lastRoll = Long.MIN_VALUE / 2;
        DodgeType type;
        double sentValue = -2;
        boolean sentExhausted;
        int sinceSync;
    }

    private Stamina() {
    }

    /** From vanilla's {@code Attributes} static init, like {@link DamageReduction#register}. */
    public static void registerAttributes() {
        if (MAX_STAMINA != null) {
            return;
        }
        MAX_STAMINA = attribute("max_stamina", 100, 0, 1000);
        STAMINA_REGEN = attribute("stamina_regen", 30, 0, 1000);
        ROLL_COST = attribute("roll_cost", 25, 1, 1000);
        EXHAUSTION_DELAY = attribute("exhaustion_delay", 2, 0, 60);
    }

    private static Holder<Attribute> attribute(String name, double base, double min, double max) {
        return Registry.registerForHolder(BuiltInRegistries.ATTRIBUTE, ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, name),
                new RangedAttribute("attribute.name.eden_ld." + name, base, min, max).setSyncable(true));
    }

    public static boolean enabled() {
        return EdenLD.CONFIG.combat.stamina_enabled && FabricLoader.getInstance().isModLoaded("combat_roll");
    }

    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(Sync.TYPE, Sync.CODEC);
        if (!FabricLoader.getInstance().isModLoaded("combat_roll")) {
            return;
        }
        StaminaRolls.register();
        ServerTickEvents.END_SERVER_TICK.register(Stamina::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> STATES.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATES.clear());
    }

    // ---------------------------------------------------------------- numbers

    /** Max stamina, with each extra Combat Roll charge (jewelry, relics) worth one more roll. */
    public static double max(Player player) {
        return player.getAttributeValue(MAX_STAMINA) + Math.max(0, value(player, ROLL_COUNT, 1) - 1) * cost(player);
    }

    /** Regen per second, scaled by Combat Roll's recharge speed (base 20). */
    public static double regen(Player player) {
        return player.getAttributeValue(STAMINA_REGEN) * value(player, ROLL_RECHARGE, 20) / 20.0;
    }

    public static double cost(Player player) {
        return player.getAttributeValue(ROLL_COST);
    }

    public static int exhaustionTicks(Player player) {
        return (int) Math.round(player.getAttributeValue(EXHAUSTION_DELAY) * 20);
    }

    private static double value(Player player, ResourceLocation id, double fallback) {
        return BuiltInRegistries.ATTRIBUTE.getHolder(id)
                .map(holder -> player.getAttributes().hasAttribute(holder) ? player.getAttributeValue(holder) : fallback)
                .orElse(fallback);
    }

    public static DodgeType type(Player player) {
        int weight = 0;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = player.getItemBySlot(slot);
            weight += stack.is(HEAVY) ? 3 : stack.is(MEDIUM) ? 2 : 1;
        }
        return weight >= 10 ? DodgeType.HEAVY : weight >= 7 ? DodgeType.MEDIUM : DodgeType.FAST;
    }

    public static EdenConfig.Combat.RollType numbers(DodgeType type) {
        EdenConfig.Combat config = EdenLD.CONFIG.combat;
        return switch (type) {
            case FAST -> config.roll_fast;
            case MEDIUM -> config.roll_medium;
            case HEAVY -> config.roll_heavy;
        };
    }

    // ---------------------------------------------------------------- server

    private static void tick(MinecraftServer server) {
        if (!enabled()) {
            return;
        }
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            State state = STATES.computeIfAbsent(player.getUUID(), id -> new State());
            if (state.type == null || now % 10 == 0) {
                applyType(player, state);
            }
            double max = max(player);
            if (state.value < 0) {
                state.value = max;
            }
            if (now >= state.exhaustedUntil && now >= state.regenAfter && state.value < max) {
                state.value = Math.min(max, state.value + regen(player) / 20.0);
            }
            state.value = Math.min(state.value, max);
            sync(player, state, now, false);
        }
    }

    private static void applyType(ServerPlayer player, State state) {
        DodgeType type = type(player);
        if (type == state.type) {
            return;
        }
        state.type = type;
        EdenConfig.Combat.RollType numbers = numbers(type);
        modify(player, ROLL_COST, numbers.cost);
        modify(player, STAMINA_REGEN, numbers.regen);
        modify(player, EXHAUSTION_DELAY, numbers.exhaustion_delay);
        BuiltInRegistries.ATTRIBUTE.getHolder(ROLL_DISTANCE).ifPresent(distance -> modify(player, distance, numbers.distance));
    }

    private static void modify(Player player, Holder<Attribute> attribute, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        if (amount == 0) {
            instance.removeModifier(TYPE_MODIFIER);
        } else {
            instance.addOrUpdateTransientModifier(new AttributeModifier(TYPE_MODIFIER, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /**
     * A roll {@code player} started (Combat Roll's server event): pays its stamina and returns the invulnerability ticks
     * it gets, or 0 when the server's stamina refuses it (exhausted, empty, or too soon after the last one).
     */
    static int roll(ServerPlayer player) {
        long now = player.server.getTickCount();
        State state = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        if (state.type == null) {
            applyType(player, state);
        }
        if (state.value < 0) {
            state.value = max(player);
        }
        EdenConfig.Combat config = EdenLD.CONFIG.combat;
        // A tick of slack for the client's timing.
        if (state.value <= 0 || now < state.exhaustedUntil || now - state.lastRoll < config.roll_gap_ticks - 1) {
            sync(player, state, now, true);
            return 0;
        }
        state.value -= cost(player);
        state.lastRoll = now;
        state.regenAfter = now + config.stamina_regen_pause_ticks;
        if (state.value <= 0) {
            state.value = 0;
            state.exhaustedUntil = now + exhaustionTicks(player);
        }
        sync(player, state, now, true);
        return numbers(state.type).invulnerable_ticks;
    }

    /** Sends the player's stamina when it changed: at once for rolls and exhaustion, else every 4 ticks. */
    private static void sync(ServerPlayer player, State state, long now, boolean force) {
        boolean exhausted = now < state.exhaustedUntil;
        state.sinceSync++;
        boolean changed = Math.abs(state.value - state.sentValue) > 0.01 || exhausted != state.sentExhausted;
        if (!force && !(changed && (state.sinceSync >= 4 || exhausted != state.sentExhausted))) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, Sync.TYPE)) {
            return;
        }
        state.sentValue = state.value;
        state.sentExhausted = exhausted;
        state.sinceSync = 0;
        ServerPlayNetworking.send(player, new Sync((float) state.value, (int) Math.max(0, state.exhaustedUntil - now),
                (int) Math.max(0, state.regenAfter - now)));
    }

    /** S2C: the player's stamina, ticks of exhaustion left and ticks before regen resumes. */
    public record Sync(float value, int exhaustedTicks, int pauseTicks) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "stamina"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeFloat(p.value);
                    buf.writeVarInt(p.exhaustedTicks);
                    buf.writeVarInt(p.pauseTicks);
                },
                buf -> new Sync(buf.readFloat(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
