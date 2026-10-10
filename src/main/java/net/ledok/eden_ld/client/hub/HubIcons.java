package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.EdenLD;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.ImageNode;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The hub's drawn icons ({@code assets/eden_ld/textures/gui/icons/<name>.png}, see docs/ICONS.md).
 * An icon whose file is missing stays null, so callers keep their placeholder; icons arrive one at
 * a time. Sizes are read from the files (any square size works).
 * <p>
 * A theme may bring its own: {@code <theme folder>/icons/<name>.png} wins over the default while
 * that theme is on — unless the player turned theme icons off (Settings → Visual). A theme without
 * an icon of that name keeps the default one.
 */
public final class HubIcons {

    /** A loaded icon: its texture and pixel size. */
    record Icon(ResourceLocation texture, int size) {
        CanvasNode node(float drawn) {
            return ImageNode.of(texture, 0, 0, drawn, drawn, size, size);
        }
    }

    private static final Map<String, Optional<Icon>> CACHE = new HashMap<>();

    private HubIcons() {
    }

    /** An icon by name — the theme's when it has one (and they're on), else the default; null when neither exists. */
    @Nullable
    static Icon get(String name) {
        ResourceLocation themed = themedFolder();
        if (themed != null) {
            Icon icon = CACHE.computeIfAbsent(themed + name,
                    key -> load(ResourceLocation.fromNamespaceAndPath(themed.getNamespace(), themed.getPath() + name + ".png")))
                    .orElse(null);
            if (icon != null) {
                return icon;
            }
        }
        return CACHE.computeIfAbsent(name, key -> load(
                ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "textures/gui/icons/" + name + ".png"))).orElse(null);
    }

    /** The active theme's icons folder, or null while theme icons are off. */
    @Nullable
    private static ResourceLocation themedFolder() {
        if (HubState.settingsData != null && HubState.settingsData.themeIconsOff()) {
            return null;
        }
        net.ledok.eden_ld.client.theme.HubTheme theme = net.ledok.eden_ld.client.theme.HubThemes
                .get(net.ledok.eden_ld.client.theme.HubThemes.selected());
        if (theme == null) {
            return null;
        }
        String folder = theme.textures().getPath();
        return ResourceLocation.fromNamespaceAndPath(theme.textures().getNamespace(),
                (folder.endsWith("/") ? folder : folder + "/") + "icons/");
    }

    /**
     * A tab's icon ({@code tab_<name>}); Dailies has a second one for "all claimed today", Mail one for
     * "unread mail waiting" (a sealed envelope; the open one once everything is read).
     */
    @Nullable
    static Icon tab(HubTab tab) {
        String name = "tab_" + tab.name().toLowerCase(Locale.ROOT);
        String state = tab == HubTab.DAILIES && dailiesDone() ? "_done"
                : tab == HubTab.MAIL && mailUnread() ? "_unread" : null;
        if (state != null) {
            Icon icon = get(name + state);
            if (icon != null) {
                return icon;
            }
        }
        return get(name);
    }

    /** Every daily quest of today claimed. */
    static boolean dailiesDone() {
        int[] progress = HubState.dailiesProgress();
        return progress[1] > 0 && progress[0] >= progress[1];
    }

    /** Mail not opened yet, or website purchases waiting (what the Mail badge counts, even with that badge off). */
    static boolean mailUnread() {
        return HubState.mailData != null && HubState.mailData.unread() > 0;
    }

    private static Optional<Icon> load(ResourceLocation texture) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(texture);
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = resource.get().open();
             com.mojang.blaze3d.platform.NativeImage image = com.mojang.blaze3d.platform.NativeImage.read(in)) {
            return Optional.of(new Icon(texture, Math.max(image.getWidth(), image.getHeight())));
        } catch (IOException e) {
            EdenLD.LOGGER.warn("Can't read hub icon {}", texture, e);
            return Optional.empty();
        }
    }

    /** Resource packs changed (F3+T): look the files up again. */
    public static void clear() {
        CACHE.clear();
    }
}
