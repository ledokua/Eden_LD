package net.ledok.eden_ld.mixin.phase;

import net.ledok.eden_ld.tutorial.phase.Phased;
import net.ledok.eden_ld.tutorial.phase.Phases;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Every entity's tutorial phase ({@link Phased}), saved as {@code EdenPhase}; no pushing across phases. */
@Mixin(Entity.class)
public abstract class PhaseEntityMixin implements Phased {

    @Unique
    @Nullable
    private UUID eden_ld$phase;

    @Override
    @Nullable
    public UUID eden_ld$phase() {
        return eden_ld$phase;
    }

    @Override
    public void eden_ld$setPhase(@Nullable UUID phase) {
        UUID before = eden_ld$phase;
        eden_ld$phase = phase;
        Phases.changed((Entity) (Object) this, before, phase);
    }

    @Inject(method = "saveWithoutId", at = @At("TAIL"))
    private void eden_ld$savePhase(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        if (eden_ld$phase != null) {
            tag.putUUID("EdenPhase", eden_ld$phase);
        }
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void eden_ld$loadPhase(CompoundTag tag, CallbackInfo ci) {
        if (tag.hasUUID("EdenPhase")) {
            eden_ld$setPhase(tag.getUUID("EdenPhase"));
        }
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void eden_ld$unindex(Entity.RemovalReason reason, CallbackInfo ci) {
        if (eden_ld$phase != null && reason.shouldDestroy()) {
            Phases.changed((Entity) (Object) this, eden_ld$phase, null);
        }
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void eden_ld$noPushAcross(Entity other, CallbackInfo ci) {
        if (!Phases.together((Entity) (Object) this, other)) {
            ci.cancel();
        }
    }
}
