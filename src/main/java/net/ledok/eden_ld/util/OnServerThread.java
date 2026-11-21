package net.ledok.eden_ld.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

/**
 * Runs connection-event work on the server thread. Fabric's {@code ServerPlayConnectionEvents} JOIN and DISCONNECT
 * normally fire on it, but not always: a connection dropped under heavy lag can be handled from another thread, and
 * then a handler writing a plain map raced the server tick reading it. On live (2026-11-13 21:52, 10-20 s stalls,
 * players timing out) that corrupted a party's offline list and crashed the server in {@code PartyManager.tick}.
 * On the server thread the work runs at once, exactly as before; elsewhere it is queued for it.
 * <p>
 * Queued with {@code tell}, never {@code execute} (review finding 33): once the server has started stopping, vanilla's
 * {@code execute} runs the task inline on the calling thread (its {@code scheduleExecutables} is false after
 * {@code stopped} is set), so a DISCONNECT fired from netty while {@code stopServer} flushed chunks ran its cleanup on
 * netty, next to the server thread. Queued at stop, the work runs if the stop still drains tasks (its chunk-flush
 * loop does) and is dropped otherwise; the maps it would clean are cleared on SERVER_STOPPED anyway.
 */
public final class OnServerThread {

    /** A queued {@link #hop}: exactly one of its work and its onRefused runs, whichever claims it first. */
    private static final class Hop {
        final MinecraftServer server;
        final Runnable onRefused;
        final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();

        Hop(MinecraftServer server, Runnable onRefused) {
            this.server = server;
            this.onRefused = onRefused;
        }
    }

    /** Hops queued and not run yet (review of 1.3.133): refused at SERVER_STOPPED instead of being dropped. */
    private static final java.util.Set<Hop> QUEUED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Before the default phase, so a refund still finds Economy_LD's database (it closes at SERVER_STOPPED). */
    private static final net.minecraft.resources.ResourceLocation REFUSE_PHASE =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("eden_ld", "refuse_queued_hops");

    static {
        net.fabricmc.fabric.api.event.Event<net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopped>
                stopped = net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED;
        stopped.addPhaseOrdering(REFUSE_PHASE, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        stopped.register(REFUSE_PHASE, OnServerThread::refuseQueued);
    }

    private OnServerThread() {
    }

    public static void run(MinecraftServer server, Runnable work) {
        if (server.isSameThread()) {
            work.run();
        } else {
            server.tell(new TickTask(server.getTickCount(), work));
        }
    }

    /**
     * A hop to the server thread for work whose loss must be undone (review finding 33): a shop delivery whose purchase
     * should stay pending, an economy callback that must refund. On the server thread it runs at once; while the server
     * runs it is queued; once it is stopping, {@code onRefused} runs instead, on the calling thread, and false is
     * returned. A queued hop the stop never ran (answered in the last laggy tick before {@code /stop}) gets its
     * {@code onRefused} at SERVER_STOPPED, on the server thread, ahead of other mods' SERVER_STOPPED work (review of
     * 1.3.133). Exactly one of {@code work} and {@code onRefused} runs.
     */
    public static boolean hop(MinecraftServer server, Runnable work, Runnable onRefused) {
        if (server.isSameThread()) {
            work.run();
            return true;
        }
        if (!server.isRunning() || server.isStopped()) {
            onRefused.run();
            return false;
        }
        Hop hop = new Hop(server, onRefused);
        QUEUED.add(hop);
        server.tell(new TickTask(server.getTickCount(), () -> {
            if (hop.claimed.compareAndSet(false, true)) {
                QUEUED.remove(hop);
                work.run();
            }
        }));
        // Stopped between the check above and the add: SERVER_STOPPED may already have gone through the queue.
        if (server.isStopped() && hop.claimed.compareAndSet(false, true)) {
            QUEUED.remove(hop);
            onRefused.run();
            return false;
        }
        return true;
    }

    /** SERVER_STOPPED: every hop of this server that never ran is refused now. */
    private static void refuseQueued(MinecraftServer server) {
        for (Hop hop : QUEUED) {
            if (hop.server != server) {
                continue;
            }
            QUEUED.remove(hop);
            if (hop.claimed.compareAndSet(false, true)) {
                try {
                    hop.onRefused.run();
                } catch (RuntimeException e) {
                    net.ledok.eden_ld.EdenLD.LOGGER.error("[Server] Undoing work the stop left unrun failed.", e);
                }
            }
        }
    }
}
