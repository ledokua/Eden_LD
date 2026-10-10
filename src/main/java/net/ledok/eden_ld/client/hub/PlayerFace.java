package net.ledok.eden_ld.client.hub;

import com.mojang.authlib.GameProfile;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.ImageNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's skin face (base + hat layer) as a canvas node, for member lists,
 * chat lines, and pickers. Players in the tab list use the connection's skin.
 * Anyone else needs their full profile first — a bare uuid+name profile carries no
 * texture property, so it would only ever resolve to the default skin. The profile
 * comes from the same cached lookup vanilla player heads use (it follows whatever
 * session/skin server the launcher configured); the face shows the default skin
 * until it lands, then swaps its texture in place.
 */
final class PlayerFace {

    /** One lookup per uuid per session; completed futures double as the cache. */
    private static final Map<UUID, CompletableFuture<ResourceLocation>> SKINS = new ConcurrentHashMap<>();

    private PlayerFace() {
    }

    static CanvasNode of(UUID uuid, String name, float size) {
        ResourceLocation skin = immediateSkin(uuid);
        ResourceLocation initial = skin != null ? skin : DefaultPlayerSkin.get(uuid).texture();
        ImageNode base = ImageNode.of(initial, 0, 0, size, size, 64, 64).region(8, 8, 8, 8);
        ImageNode hat = ImageNode.of(initial, 0, 0, size, size, 64, 64).region(40, 8, 8, 8);
        CanvasGroup face = new CanvasGroup();
        face.add(base);
        face.add(hat);

        if (skin == null) {
            Minecraft mc = Minecraft.getInstance();
            lookup(uuid).thenAccept(loaded -> mc.execute(() -> {
                base.texture(loaded);
                hat.texture(loaded);
            }));
        }
        return face;
    }

    /** The skin when it is known right now, else null (a lookup is needed). */
    private static ResourceLocation immediateSkin(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
            if (info != null) {
                return info.getSkin().texture();
            }
        }
        CompletableFuture<ResourceLocation> known = SKINS.get(uuid);
        return known != null && known.isDone() && !known.isCompletedExceptionally()
                ? known.join()
                : null;
    }

    private static CompletableFuture<ResourceLocation> lookup(UUID uuid) {
        return SKINS.computeIfAbsent(uuid, id -> {
            Minecraft mc = Minecraft.getInstance();
            ResourceLocation fallback = DefaultPlayerSkin.get(id).texture();
            return SkullBlockEntity.fetchGameProfile(id)
                    .thenCompose((Optional<GameProfile> profile) -> profile.isPresent()
                            ? mc.getSkinManager().getOrLoad(profile.get()).thenApply(PlayerSkin::texture)
                            : CompletableFuture.completedFuture(fallback))
                    .exceptionally(error -> fallback);
        });
    }
}
