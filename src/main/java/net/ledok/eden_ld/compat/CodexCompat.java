package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * Codex_LD: its chat hints hear every channel Eden delivers. Codex only listens to Fabric's CHAT_MESSAGE, which Eden
 * fires for typed Global chat alone; Codex filters channels, scores questions and drops the doubled Global line itself.
 * The only class touching Codex_LD types is {@link Api}.
 */
public final class CodexCompat {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("codex_ld");

    private CodexCompat() {
    }

    /** A player's chat line (prefix-free text) on {@code channel}: global, local, party or guild. */
    public static void onChat(ServerPlayer player, String text, String channel) {
        if (LOADED) {
            Api.onChat(player, text, channel);
        }
    }

    private static final class Api {
        static void onChat(ServerPlayer player, String text, String channel) {
            net.ledok.codexld.api.Codex.onChat(player, text, channel);
        }
    }
}
