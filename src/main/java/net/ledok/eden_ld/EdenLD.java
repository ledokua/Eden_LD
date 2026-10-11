package net.ledok.eden_ld;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.ledok.eden_ld.command.EdenCommands;
import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.ledok.eden_ld.config.EdenConfig;
import net.ledok.eden_ld.flight.FlightControl;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.mana.LiquidMana;
import net.ledok.eden_ld.legacy.LegacyItems;
import net.ledok.eden_ld.minestar.MinestarHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EdenLD implements ModInitializer {
    public static final String MOD_ID = "eden_ld";
    /**
     * Content carried over from Yggdrasil_LD keeps this namespace so
     * existing worlds, quests, and Puffish trees keep their references.
     */
    public static final String LEGACY_NAMESPACE = "yggdrasil_ld";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** The running server (null between worlds), for code without one at hand (e.g. text built for a quest). */
    private static volatile net.minecraft.server.MinecraftServer server;

    @org.jetbrains.annotations.Nullable
    public static net.minecraft.server.MinecraftServer server() {
        return server;
    }
    public static EdenConfig CONFIG;

    @Override
    public void onInitialize() {
        CONFIG = EdenConfig.load();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(started -> server = started);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);

        LegacyItems.initialize();
        LiquidMana.initialize();
        EdenCreativeTab.initialize();
        FlightControl.initialize();
        HubServer.initialize();
        net.ledok.eden_ld.hub.mail.MailHub.initialize();
        net.ledok.eden_ld.hub.mail.MailStaff.initialize();
        net.ledok.eden_ld.hub.moderation.ModerationMenu.register();
        net.ledok.eden_ld.hub.news.NewsBoard.initialize();
        net.ledok.eden_ld.hub.profile.LookRules.initialize();
        net.ledok.eden_ld.hub.moderation.AdminTools.initialize();
        net.ledok.eden_ld.hub.moderation.PunishStaff.initialize();
        // Starter daily quests, shipped disabled: /datapack enable "eden_ld:test_dailies".
        net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container ->
                net.fabricmc.fabric.api.resource.ResourceManagerHelper.registerBuiltinResourcePack(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, "test_dailies"),
                        container, net.minecraft.network.chat.Component.literal("Eden test daily quests"),
                        net.fabricmc.fabric.api.resource.ResourcePackActivationType.NORMAL));
        // Starter guild quests, shipped disabled too: /datapack enable "eden_ld:test_guild_quests".
        net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container ->
                net.fabricmc.fabric.api.resource.ResourceManagerHelper.registerBuiltinResourcePack(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, "test_guild_quests"),
                        container, net.minecraft.network.chat.Component.literal("Eden test guild quests"),
                        net.fabricmc.fabric.api.resource.ResourcePackActivationType.NORMAL));
        net.ledok.eden_ld.combat.CombatTag.initialize();
        CombatTeamsCompat.initialize();
        net.ledok.eden_ld.compat.PlaceholdersCompat.initialize();
        net.ledok.eden_ld.compat.opac.OpacCompat.initialize();
        net.ledok.eden_ld.compat.FtbTeamsCompat.initialize();
        net.ledok.eden_ld.world.WorldRules.initialize();
        net.ledok.eden_ld.world.Hazards.initialize();
        net.ledok.eden_ld.tutorial.Tutorial.initialize();

        net.ledok.eden_ld.command.EdenPermissions.initialize();
        net.ledok.eden_ld.command.OpOnlyCommands.initialize();
        net.ledok.eden_ld.command.BusyBlockedCommands.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                EdenCommands.register(dispatcher));

        MinestarHook.initialize();

        LOGGER.info("Eden LD initialized.");
    }
}
