package net.ledok.eden_ld.client.hub;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.arenas_ld.api.client.ArenasClientApi;
import net.ledok.vectorlib.client.canvas.CanvasNode;

/**
 * The only class using Arenas_LD 4.35.0's client hosting API: Arenas' lobby view shows inside the hub's Activities tab
 * instead of Arenas' own screen (LeDok 2026-11-16: with the hub, players use only the hub's screens). Arenas still opens
 * its server menu exactly as before; only where the view shows changes. Without a 4.35.0+ Arenas_LD nothing here loads.
 */
final class ArenasLobbyHosting {

    private ArenasLobbyHosting() {
    }

    /** Registers the hub as Arenas' lobby host when the running Arenas_LD has the API. */
    static void initialize() {
        if (!FabricLoader.getInstance().isModLoaded("arenas_ld")) {
            return;
        }
        try {
            Class.forName("net.ledok.arenas_ld.api.client.ArenasClientApi$LobbyHost");
        } catch (ClassNotFoundException | LinkageError e) {
            return;
        }
        Register.run();
    }

    /** Kept apart so Arenas' types load only after the check above. */
    private static final class Register {
        static void run() {
            ArenasClientApi.setLobbyHost((activity, name, view) -> LobbyBrowser.host(activity, name, new Hosted(view)));
        }
    }

    /** Arenas' view behind Eden's own interface. */
    private record Hosted(ArenasClientApi.HostedLobby view) implements LobbyBrowser.Hosted {
        @Override
        public CanvasNode node() {
            return view.node();
        }

        @Override
        public void resize(float width, float height) {
            view.resize(width, height);
        }

        @Override
        public boolean keyPressed(int key, int scanCode, int modifiers) {
            return view.keyPressed(key, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char c, int modifiers) {
            return view.charTyped(c, modifiers);
        }

        @Override
        public void mouseClicked(float x, float y) {
            view.mouseClicked(x, y);
        }

        @Override
        public void close() {
            view.close();
        }

        @Override
        public boolean closed() {
            return view.closed();
        }
    }
}
