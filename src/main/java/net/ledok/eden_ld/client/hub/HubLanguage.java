package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.hub.HubSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The hub's own language (Settings → Visual): while the hub is open, translations come from the
 * chosen language instead of the game's; closing the hub puts the game's back. Everything else
 * — chat, other screens, the HUD — keeps the game's language.
 */
public final class HubLanguage {

    /** The loaded languages (en_us under the chosen one, like the game), dropped on resource reload. */
    private static final Map<String, ClientLanguage> LOADED = new HashMap<>();
    /** The game's language while ours is in place. */
    @Nullable
    private static Language previous;
    @Nullable
    private static Language applied;

    private HubLanguage() {
    }

    /** The chosen code, or "" to follow the game. */
    static String chosen() {
        int index = HubState.settingsData == null ? 0 : HubState.settingsData.hubLanguage();
        return index > 0 && index < HubSettings.HUB_LANGUAGES.size() ? HubSettings.HUB_LANGUAGES.get(index) : "";
    }

    /** The language the hub reads in: the chosen one, else the game's. Picks news and mail versions. */
    static String code() {
        String code = chosen();
        return code.isEmpty() ? Minecraft.getInstance().getLanguageManager().getSelected() : code;
    }

    /** Puts the chosen language in place (or the game's back, when the choice is the game's). */
    static void apply() {
        String code = chosen();
        Minecraft minecraft = Minecraft.getInstance();
        if (code.isEmpty() || code.equals(minecraft.getLanguageManager().getSelected())) {
            restore();
            return;
        }
        ClientLanguage language = LOADED.computeIfAbsent(code,
                c -> ClientLanguage.loadFrom(minecraft.getResourceManager(), List.of("en_us", c), false));
        if (Language.getInstance() == language) {
            return;
        }
        if (applied == null || Language.getInstance() != applied) {
            previous = Language.getInstance();
        }
        applied = language;
        Language.inject(language);
    }

    /** The game's language back (unless something else replaced ours meanwhile, e.g. F3+T). */
    static void restore() {
        if (applied != null && Language.getInstance() == applied && previous != null) {
            Language.inject(previous);
        }
        applied = null;
        previous = null;
    }

    /** Resource packs reloaded: languages are read again next time. */
    public static void reloaded() {
        LOADED.clear();
        applied = null;
        previous = null;
    }
}
