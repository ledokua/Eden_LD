package net.ledok.eden_ld.client.hub;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.arenas_ld.api.client.ArenasClientApi;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The only class using Arenas_LD 4.36.1's client settings (run HUD and loot reveal style, ally sidebar, lobby badge):
 * the hub's Settings → Arenas lists them (LeDok: players can switch back to the old run HUD / loot reveal). Arenas saves
 * them in its own {@code config/arenas_ld-client.json}. Without a 4.36.1+ Arenas_LD there are none.
 */
final class ArenasClientSettings {

    /** One setting in Eden's terms: the stored values with their labels, the current value and how to set it. */
    record Row(String id, Component name, Component description, List<String> values, List<Component> labels,
               Supplier<String> value, Consumer<String> set) {
    }

    private static final boolean PRESENT = present();

    private ArenasClientSettings() {
    }

    static List<Row> rows() {
        return PRESENT ? Read.rows() : List.of();
    }

    private static boolean present() {
        if (!FabricLoader.getInstance().isModLoaded("arenas_ld")) {
            return false;
        }
        try {
            Class.forName("net.ledok.arenas_ld.api.client.ArenasClientApi$Setting");
            ArenasClientApi.class.getMethod("settings");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /** Kept apart so Arenas' types load only after the check above. */
    private static final class Read {
        static List<Row> rows() {
            List<Row> rows = new ArrayList<>();
            for (ArenasClientApi.Setting setting : ArenasClientApi.settings()) {
                List<String> values = new ArrayList<>();
                List<Component> labels = new ArrayList<>();
                for (ArenasClientApi.Choice choice : setting.choices()) {
                    values.add(choice.value());
                    labels.add(choice.label());
                }
                rows.add(new Row(setting.id(), setting.name(), setting.description(), values, labels, setting.value(),
                        setting.set()));
            }
            return rows;
        }
    }
}
