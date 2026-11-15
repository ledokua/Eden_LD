package net.ledok.eden_ld.api.hub;

import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Client side (1.3.84+): other mods' suggestion kinds in the hub's «Звернення» form. Each registered starter adds a
 * choice after Eden's own (Баг / Скарга на гравця / Пропозиція / Інше); picking it shows a short hint and a button that
 * opens the mod's own editor (e.g. DataRewriter's recipe editor in suggest mode), which then files through
 * {@code EdenModerationApi.suggest} on the server. Plain JDK + Minecraft types, so mods can call it by reflection.
 * Client thread.
 */
public final class EdenReportsClientApi {

    /**
     * {@code category} what the mod files (e.g. {@code "datarewriter:recipe"}); {@code labelKey} the choice's lang key
     * (the mod ships it); {@code available} checked whenever the form builds (false hides the choice: the server lacks
     * the mod, or the player may not suggest); {@code open} gets the hub screen to return to.
     */
    public record Starter(String category, String labelKey, BooleanSupplier available, Consumer<Screen> open) {
    }

    private static final List<Starter> STARTERS = new CopyOnWriteArrayList<>();

    private EdenReportsClientApi() {
    }

    /** Adds (or replaces, by category) a suggestion starter. */
    public static void registerSuggestionStarter(String category, String labelKey, BooleanSupplier available,
                                                 Consumer<Screen> open) {
        STARTERS.removeIf(starter -> starter.category().equals(category));
        STARTERS.add(new Starter(category, labelKey, available, open));
    }

    /** The registered starters, in order (Eden's form reads them). */
    public static List<Starter> starters() {
        return List.copyOf(STARTERS);
    }
}
