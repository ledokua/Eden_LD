package net.ledok.eden_ld.rental;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * The rental picker's packets ({@link RentalPicker}). Each open picker has a request id: the client's answers carry it,
 * and an answer for an older request is ignored.
 */
public final class RentalPickerPayloads {

    /** Caps per packet: kits, items per kit, spells per kit (as Arenas_LD's preview had), spells in a pick. */
    static final int MAX_KITS = 64;
    static final int MAX_ITEMS = 128;
    static final int MAX_SPELLS = 64;
    static final int MAX_PICKED = 16;
    static final int MAX_ID = 256;

    private RentalPickerPayloads() {
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(OpenRentalPicker.TYPE, OpenRentalPicker.CODEC);
        PayloadTypeRegistry.playS2C().register(CloseRentalPicker.TYPE, CloseRentalPicker.CODEC);
        PayloadTypeRegistry.playC2S().register(RentalPickerChoice.TYPE, RentalPickerChoice.CODEC);
        PayloadTypeRegistry.playC2S().register(RentalPickerClosed.TYPE, RentalPickerClosed.CODEC);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, path);
    }

    /**
     * One preset: its kit id, display name, level (its folder) and items (copies); {@code recommended} for the player's
     * skill-tree class; its spell book's choice ({@code spells} the pool's allowed spells, up to {@code maxSpells},
     * {@code chosen} what the picker starts with; no spells: no choice). {@code spellTiers}: each spell's tier when the
     * book takes one spell per tier; empty: a free choice.
     */
    public record Kit(String kitId, String name, int level, List<ItemStack> items, boolean recommended,
                      List<String> spells, int maxSpells, List<String> chosen, List<Integer> spellTiers) {

        public boolean perTier() {
            return !spellTiers.isEmpty() && spellTiers.size() == spells.size();
        }

        static final StreamCodec<RegistryFriendlyByteBuf, Kit> CODEC = StreamCodec.of(
                (buf, k) -> {
                    buf.writeUtf(k.kitId());
                    buf.writeUtf(k.name());
                    buf.writeVarInt(k.level());
                    ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ITEMS)).encode(buf, k.items());
                    buf.writeBoolean(k.recommended());
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_SPELLS)).encode(buf, k.spells());
                    buf.writeVarInt(k.maxSpells());
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_SPELLS)).encode(buf, k.chosen());
                    ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_SPELLS)).encode(buf, k.spellTiers());
                },
                buf -> new Kit(buf.readUtf(), buf.readUtf(), buf.readVarInt(),
                        ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ITEMS)).decode(buf), buf.readBoolean(),
                        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_SPELLS)).decode(buf), buf.readVarInt(),
                        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_SPELLS)).decode(buf),
                        ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_SPELLS)).decode(buf)));
    }

    /**
     * S2C: open the picker (replacing an open one). {@code level} is the kits' level shown in the header ("Level 25
     * kits"); {@code currentKitId} the pick to highlight ("" for none); {@code deadlineMs} milliseconds left when sent,
     * 0 = no deadline.
     */
    public record OpenRentalPicker(int requestId, Component title, Optional<Component> subtitle, int level,
                                   List<Kit> kits, String currentKitId, long deadlineMs) implements CustomPacketPayload {
        public static final Type<OpenRentalPicker> TYPE = new Type<>(id("rental_picker_open"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Optional<Component>> SUBTITLE =
                ByteBufCodecs.optional(ComponentSerialization.TRUSTED_STREAM_CODEC);
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRentalPicker> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId());
                    ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, p.title());
                    SUBTITLE.encode(buf, p.subtitle());
                    buf.writeVarInt(p.level());
                    Kit.CODEC.apply(ByteBufCodecs.list(MAX_KITS)).encode(buf, p.kits());
                    buf.writeUtf(p.currentKitId());
                    buf.writeVarLong(p.deadlineMs());
                },
                buf -> new OpenRentalPicker(buf.readVarInt(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf),
                        SUBTITLE.decode(buf), buf.readVarInt(), Kit.CODEC.apply(ByteBufCodecs.list(MAX_KITS)).decode(buf),
                        buf.readUtf(), buf.readVarLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: close that picker (the caller stopped waiting); nothing is sent back. */
    public record CloseRentalPicker(int requestId) implements CustomPacketPayload {
        public static final Type<CloseRentalPicker> TYPE = new Type<>(id("rental_picker_close"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CloseRentalPicker> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, CloseRentalPicker::requestId, CloseRentalPicker::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the player picked {@code kitId} with these spells for its book (the server checks both). */
    public record RentalPickerChoice(int requestId, String kitId, List<String> spells) implements CustomPacketPayload {
        public static final Type<RentalPickerChoice> TYPE = new Type<>(id("rental_picker_choice"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RentalPickerChoice> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RentalPickerChoice::requestId,
                ByteBufCodecs.stringUtf8(MAX_ID), RentalPickerChoice::kitId,
                ByteBufCodecs.stringUtf8(MAX_ID).apply(ByteBufCodecs.list(MAX_PICKED)), RentalPickerChoice::spells,
                RentalPickerChoice::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the player closed the picker without picking (Esc, Close). Not sent after a pick or a server close. */
    public record RentalPickerClosed(int requestId) implements CustomPacketPayload {
        public static final Type<RentalPickerClosed> TYPE = new Type<>(id("rental_picker_closed"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RentalPickerClosed> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RentalPickerClosed::requestId, RentalPickerClosed::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
