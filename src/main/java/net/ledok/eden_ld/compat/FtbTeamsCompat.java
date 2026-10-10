package net.ledok.eden_ld.compat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.network.chat.Component;

/**
 * FTB Teams stays for FTB Quests, but parties are Eden's: while config {@code hub.ftb_teams_parties}
 * is off, FTB Teams refuses new parties — its Create party button greys out with the reason, and
 * {@code /ftbteams party create} says it — so FTB Quests' progress and rewards stay per player.
 * Parties made before stay until disbanded ({@code /ftbteams force-disband <team>}).
 * The only class touching FTB Teams types is {@link Api}.
 */
public final class FtbTeamsCompat {

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("ftbteams");
    /** Once per game: a singleplayer world started again reuses FTB Teams' validator list. */
    private static boolean registered;

    private FtbTeamsCompat() {
    }

    public static void initialize() {
        if (LOADED) {
            // FTB Teams sets its API up in its own initializer; by server start it's there.
            ServerLifecycleEvents.SERVER_STARTING.register(server -> Api.register());
        }
    }

    private static final class Api {
        static void register() {
            if (registered) {
                return;
            }
            registered = true;
            dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().addPartyCreationValidator(player ->
                    EdenLD.CONFIG.hub.ftb_teams_parties
                            ? dev.ftb.mods.ftbteams.api.PartyCreationValidator.PASS
                            : dev.ftb.mods.ftbteams.api.PartyCreationValidator.CreationResult.fail(
                                    Component.translatable("message.eden_ld.ftbteams.parties_off",
                                            Component.keybind("key.eden_ld.hub"))));
        }
    }
}
