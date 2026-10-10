package net.ledok.eden_ld.client.compat;

import dev.emi.emi.runtime.EmiReloadManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * TEMPORARY dev test for {@link EmiReloadFix} (dev/emi-rejoin, not built). With
 * {@code -Deden_ld.devEmiRejoin=A,B,CMD,R} the dev client, once joined (quick play), runs these steps
 * one after another and logs PASS / FAIL for each (logger "EmiRejoinTest"), then quits:
 * <ul>
 * <li>A: three lone recipe syncs (as FallenEden sends after a join), the connection drops (the same
 *     path as a server stop), join again; EMI must finish reloading.</li>
 * <li>B: as A, but the stale "recipes" bit is set again right after the rejoin starts, as if Eden's
 *     reset had been missed: EMI's reload stops at "World is null" and must be redone.</li>
 * <li>CMD: {@code /emireload}. R: a server {@code /reload}.</li>
 * </ul>
 */
public final class DevEmiRejoin {

    private static final Logger LOG = LoggerFactory.getLogger("EmiRejoinTest");
    private static final String PLAN = System.getProperty("eden_ld.devEmiRejoin");
    private static final String ADDRESS = System.getProperty("eden_ld.devEmiRejoinServer", "localhost:25598");
    private static final int TIMEOUT = 20 * 120;

    private static final Deque<String> STEPS = new ArrayDeque<>(PLAN == null ? List.of() : Arrays.asList(PLAN.split(",")));
    private static final List<String> RESULTS = new ArrayList<>();
    private static String label = "first join";
    private static String variant;
    private static Phase phase = Phase.WAIT_LOADED;
    private static int ticks;
    private static boolean sawReload;
    private static int count;

    private enum Phase { WAIT_LOADED, LONE_SYNCS, WAIT_KICK, REJOIN, DONE }

    private DevEmiRejoin() {
    }

    public static void tick(Minecraft client) {
        if (PLAN == null || phase == Phase.DONE) {
            return;
        }
        ticks++;
        switch (phase) {
            case WAIT_LOADED -> {
                if (client.level == null) {
                    return;
                }
                if (EmiReloadManager.getStatus() != 2) {
                    sawReload = true;
                }
                if (sawReload && EmiReloadManager.isLoaded()) {
                    result(true, "EMI loaded");
                    next(client);
                } else if (ticks > TIMEOUT) {
                    result(false, "EMI stuck, status " + EmiReloadManager.getStatus() + " step '"
                            + EmiReloadManager.reloadStep.getString() + "'");
                    finish(client);
                }
            }
            case LONE_SYNCS -> {
                ClientPacketListener connection = client.getConnection();
                if (ticks == 1) {
                    for (int i = 0; i < 3; i++) {
                        connection.handleUpdateRecipes(new ClientboundUpdateRecipesPacket(
                                connection.getRecipeManager().getOrderedRecipes()));
                    }
                    LOG.info("sent 3 lone recipe syncs");
                } else if (ticks == 40) {
                    // Closing the channel ends in onDisconnect -> disconnect(Screen, boolean), like a server stop.
                    LOG.info("dropping the connection");
                    connection.getConnection().disconnect(net.minecraft.network.chat.Component.literal("EMI rejoin test"));
                    phase(Phase.WAIT_KICK);
                }
            }
            case WAIT_KICK -> {
                if (client.level != null || client.getConnection() != null) {
                    ticks = 0;
                } else if (ticks == 40) {
                    phase(Phase.REJOIN);
                }
            }
            case REJOIN -> {
                LOG.info("joining {} again", ADDRESS);
                ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(ADDRESS),
                        new ServerData("Dev", ADDRESS, ServerData.Type.OTHER), false, null);
                if (variant.equals("B")) {
                    LOG.info("setting the stale recipes bit again (a missed reset)");
                    EmiReloadManager.reloadRecipes();
                }
                waitLoaded("rejoin " + variant + " #" + count);
            }
            default -> {
            }
        }
    }

    private static void next(Minecraft client) {
        String step = STEPS.poll();
        if (step == null) {
            finish(client);
            return;
        }
        count++;
        switch (step) {
            case "A", "B" -> {
                variant = step;
                phase(Phase.LONE_SYNCS);
            }
            case "CMD" -> {
                LOG.info("running /emireload");
                client.getConnection().sendCommand("emireload");
                waitLoaded("/emireload #" + count);
            }
            case "R" -> {
                LOG.info("running server /reload");
                client.getConnection().sendCommand("reload");
                waitLoaded("server /reload #" + count);
            }
            default -> {
                LOG.warn("unknown step {}", step);
                next(client);
            }
        }
    }

    private static void waitLoaded(String name) {
        label = name;
        sawReload = false;
        phase(Phase.WAIT_LOADED);
    }

    private static void phase(Phase next) {
        phase = next;
        ticks = 0;
    }

    private static void result(boolean pass, String detail) {
        String line = (pass ? "PASS " : "FAIL ") + label + " (" + detail + ", " + ticks + " ticks)";
        RESULTS.add(line);
        LOG.info(line);
    }

    private static void finish(Minecraft client) {
        phase(Phase.DONE);
        LOG.info("SUMMARY\n  {}", String.join("\n  ", RESULTS));
        client.stop();
    }
}
