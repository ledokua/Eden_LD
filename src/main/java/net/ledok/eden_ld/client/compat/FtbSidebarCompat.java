package net.ledok.eden_ld.client.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;

/**
 * FTB Library (FTB Quests / Teams) puts its sidebar buttons on every container screen — and the
 * Moderation window is one (for EMI). Its client API keeps a list of screens to skip; Eden's staff
 * screens go on it. Called through reflection, so Eden needs no FTB dependency.
 */
public final class FtbSidebarCompat {

    private FtbSidebarCompat() {
    }

    public static void initialize() {
        if (!FabricLoader.getInstance().isModLoaded("ftblibrary")) {
            return;
        }
        try {
            Class<?> api = Class.forName("dev.ftb.mods.ftblibrary.api.client.FTBLibraryClientApi");
            Object instance = api.getMethod("get").invoke(null);
            api.getMethod("addSidebarScreenBlacklist", String[].class).invoke(instance, (Object) new String[]{
                    net.ledok.eden_ld.client.hub.ModerationScreen.class.getName(),
                    "net.ledok.eden_ld.client.hub.MailTextScreen"});
        } catch (ReflectiveOperationException | LinkageError e) {
            EdenLD.LOGGER.warn("Couldn't hide FTB Library's sidebar on the Moderation window", e);
        }
    }
}
