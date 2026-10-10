package net.ledok.eden_ld.client.compat;

import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.widget.Bounds;
import fuzs.stylisheffects.api.v1.client.EffectScreenHandler;
import net.minecraft.client.renderer.Rect2i;

/**
 * Stylish Effects' effect widgets as EMI exclusion areas, so EMI's panels (favorites on the left) flow
 * around them instead of drawing over them — Stylish Effects only tells JEI and REI where they are.
 * Apart from {@link EdenEmiPlugin}, so its classes only load with Stylish Effects.
 */
final class StylishEffectsEmi {

    private StylishEffectsEmi() {
    }

    static void register(EmiRegistry registry) {
        registry.addGenericExclusionArea((screen, consumer) -> {
            for (Rect2i area : EffectScreenHandler.INSTANCE.getInventoryRenderAreas(screen)) {
                consumer.accept(new Bounds(area.getX(), area.getY(), area.getWidth(), area.getHeight()));
            }
        });
    }
}
