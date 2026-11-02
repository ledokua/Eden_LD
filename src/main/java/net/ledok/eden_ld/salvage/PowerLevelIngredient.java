package net.ledok.eden_ld.salvage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredient;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredientSerializer;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;
import java.util.Optional;

/**
 * {@code eden_ld:power_level}: a stack matching {@code base} whose Dungeon Difficulty power level
 * ({@link PowerLevels}) is in [{@code min}, {@code max}]; no {@code max} means open-ended, gear without a
 * level never matches. A Fabric custom ingredient, so it goes anywhere a recipe takes an ingredient,
 * Create's processing recipes included:
 * <pre>{"fabric:type": "eden_ld:power_level", "base": {"tag": "falleneden:gear/weapons/all"}, "min": 11, "max": 20}</pre>
 * Recipe viewers see the base items; Eden's EMI plugin labels such recipes with the level range.
 */
public record PowerLevelIngredient(Ingredient base, int min, int max) implements CustomIngredient {

    public static final MapCodec<PowerLevelIngredient> CODEC = RecordCodecBuilder.<PowerLevelIngredient>mapCodec(i -> i.group(
            Ingredient.CODEC_NONEMPTY.fieldOf("base").forGetter(PowerLevelIngredient::base),
            Codec.INT.optionalFieldOf("min", 1).forGetter(PowerLevelIngredient::min),
            Codec.INT.optionalFieldOf("max").forGetter(PowerLevelIngredient::optionalMax)
    ).apply(i, (base, min, max) -> new PowerLevelIngredient(base, min, max.orElse(PowerLevels.OPEN))))
            .validate(ingredient -> ingredient.min() <= ingredient.max() ? DataResult.success(ingredient)
                    : DataResult.error(() -> "power_level: min " + ingredient.min() + " is above max " + ingredient.max()));

    public static final StreamCodec<RegistryFriendlyByteBuf, PowerLevelIngredient> STREAM_CODEC = StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC, PowerLevelIngredient::base,
            ByteBufCodecs.VAR_INT, PowerLevelIngredient::min,
            ByteBufCodecs.VAR_INT, PowerLevelIngredient::max,
            PowerLevelIngredient::new);

    public static final CustomIngredientSerializer<PowerLevelIngredient> SERIALIZER = new CustomIngredientSerializer<>() {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "power_level");

        @Override
        public ResourceLocation getIdentifier() {
            return ID;
        }

        @Override
        public MapCodec<PowerLevelIngredient> getCodec(boolean allowEmpty) {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, PowerLevelIngredient> getPacketCodec() {
            return STREAM_CODEC;
        }
    };

    /** The stack's power level, -1 without one. */
    @SuppressWarnings("deprecation") // getUnsafe: read-only, avoids copying the whole tag for every check
    public static int levelOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? -1 : PowerLevels.of(data.getUnsafe());
    }

    /** "11–20" or "51+", for labels. */
    public String rangeText() {
        return PowerLevels.rangeText(min, max);
    }

    private Optional<Integer> optionalMax() {
        return max == PowerLevels.OPEN ? Optional.empty() : Optional.of(max);
    }

    @Override
    public boolean test(ItemStack stack) {
        return PowerLevels.inRange(levelOf(stack), min, max) && base.test(stack);
    }

    @Override
    public List<ItemStack> getMatchingStacks() {
        return List.of(base.getItems());
    }

    @Override
    public boolean requiresTesting() {
        return true;
    }

    @Override
    public CustomIngredientSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
