package net.ledok.eden_ld.util;

import net.minecraft.server.MinecraftServer;

/**
 * Runs connection-event work on the server thread. Fabric's {@code ServerPlayConnectionEvents} JOIN and DISCONNECT
 * normally fire on it, but not always: a connection dropped under heavy lag can be handled from another thread, and
 * then a handler writing a plain map raced the server tick reading it. On live (2026-11-13 21:52, 10-20 s stalls,
 * players timing out) that corrupted a party's offline list and crashed the server in {@code PartyManager.tick}.
 * On the server thread the work runs at once, exactly as before; elsewhere it is queued for it.
 */
public final class OnServerThread {

    private OnServerThread() {
    }

    public static void run(MinecraftServer server, Runnable work) {
        if (server.isSameThread()) {
            work.run();
        } else {
            server.execute(work);
        }
    }
}
