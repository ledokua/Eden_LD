package net.ledok.eden_ld.mixin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Reads 1.21.2+ attribute ids in saved entity data. Minecraft 1.21.2 dropped the "generic." /
 * "player." / "zombie." prefixes ({@code minecraft:max_health} instead of
 * {@code minecraft:generic.max_health}); mods that ship structure files, functions or entity NBT
 * written for newer versions (Moog's structures, Stellarity, …) place mobs whose attributes 1.21.1
 * didn't know: it logged "Ignoring unknown attribute" and dropped them, so those mobs spawned with
 * default health, armor, damage and speed. Here an unknown {@code minecraft:<name>} is read as the
 * prefixed 1.21.1 id when one exists; everything else is untouched.
 */
@Mixin(AttributeMap.class)
public class AttributeMapAliasMixin {

    @Unique
    private static final String[] EDEN_LD$OLD_PREFIXES = {"generic.", "player.", "zombie."};

    @ModifyArg(method = "load", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/resources/ResourceLocation;tryParse(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"))
    private String eden_ld$aliasNewAttributeIds(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !"minecraft".equals(rl.getNamespace()) || rl.getPath().indexOf('.') >= 0
                || BuiltInRegistries.ATTRIBUTE.containsKey(rl)) {
            return id;
        }
        for (String prefix : EDEN_LD$OLD_PREFIXES) {
            ResourceLocation old = ResourceLocation.withDefaultNamespace(prefix + rl.getPath());
            if (BuiltInRegistries.ATTRIBUTE.containsKey(old)) {
                return old.toString();
            }
        }
        return id;
    }
}
