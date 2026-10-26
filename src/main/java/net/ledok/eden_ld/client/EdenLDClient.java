package net.ledok.eden_ld.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.ledok.eden_ld.EdenLD;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler;
import net.ledok.eden_ld.client.hub.HubScreen;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.mana.LiquidMana;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.glfw.GLFW;

public class EdenLDClient implements ClientModInitializer {

    /** Violet-blue mana tint applied to the water sprites. */
    private static final int LIQUID_MANA_COLOR = 0xFF7E3FE2;

    public static KeyMapping hubKey;

    @Override
    public void onInitializeClient() {
        // Headings and {size:} spans in Markdown (mail) draw at their size.
        net.ledok.vectorlib.client.text.TextSizes.register();
        // FTB Library's sidebar buttons stay off the Moderation window.
        net.ledok.eden_ld.client.compat.FtbSidebarCompat.initialize();
        // EMI no longer hangs on "Reloading..." after a rejoin; /emireload.
        net.ledok.eden_ld.client.compat.EmiReloadFix.initialize();
        // The hub language's tables and the hub icons are read again after a resource reload (F3+T, pack changes),
        // and chat lines are wrapped again (the font may have changed).
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
                .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                    @Override
                    public net.minecraft.resources.ResourceLocation getFabricId() {
                        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(net.ledok.eden_ld.EdenLD.MOD_ID, "hub_language");
                    }

                    @Override
                    public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
                        net.ledok.eden_ld.client.hub.HubLanguage.reloaded();
                        net.ledok.eden_ld.client.hub.HubIcons.clear();
                        net.ledok.eden_ld.client.chat.panel.ChatEntry.fontChanged();
                    }
                });
        // Waystones' "Visible to Team" means the owner's guild here (see WaystonesIntegration).
        // A built-in pack, not Eden's own lang files: mod assets load A→Z, so "waystones" would
        // override "eden_ld"; built-in packs sit on top. Always on, so the wording can't drift.
        if (FabricLoader.getInstance().isModLoaded("waystones")) {
            FabricLoader.getInstance().getModContainer(EdenLD.MOD_ID).ifPresent(container ->
                    ResourceManagerHelper.registerBuiltinResourcePack(
                            ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "waystones_guild"), container,
                            Component.literal("Eden: Waystones team = guild"), ResourcePackActivationType.ALWAYS_ENABLED));
        }

        // The old mod never registered a render handler, so the fluid
        // rendered with missing textures. Reuse the water sprites, tinted.
        FluidRenderHandlerRegistry.INSTANCE.register(
                LiquidMana.STILL_LIQUID_MANA, LiquidMana.FLOWING_LIQUID_MANA,
                SimpleFluidRenderHandler.coloredWater(LIQUID_MANA_COLOR));

        BlockRenderLayerMap.INSTANCE.putFluids(RenderType.translucent(),
                LiquidMana.STILL_LIQUID_MANA, LiquidMana.FLOWING_LIQUID_MANA);

        registerHub();
    }

    private void registerHub() {
        hubKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.eden_ld.hub", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F4, "category.eden_ld.main"));

        net.ledok.eden_ld.client.hub.InventoryHubButton.register();
        net.ledok.eden_ld.client.hub.InventoryPvpButton.register();
        // Named Hub parts for tutorial spotlights (NPCs_LD dialog lines point at them).
        net.ledok.eden_ld.client.hub.HubSpotlights.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (hubKey.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    HubScreen.open();
                }
            }
            // A chat channel held back after a pick applies once its hold runs out (ChatChannelState).
            net.ledok.eden_ld.client.chat.ClientChatChannel.tick();
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            HubState.resetOnJoin();
            ClientCombatTag.reset();
            net.ledok.eden_ld.client.hub.ClientPvp.reset();
            net.ledok.eden_ld.client.hub.PlayerNames.reset();
            net.ledok.eden_ld.client.chat.ClientChatChannel.reset();
            // Only the new look keeps system-only lines apart (the System tab), so only it caps them.
            net.ledok.eden_ld.client.chat.panel.ChatStore.capSystemOnly(net.ledok.eden_ld.client.chat.ui.ChatLook::enabled);
            // Eden's own system-only rules until this server sends its list (a 1.3.1 or older one never does).
            net.ledok.eden_ld.client.chat.panel.ChatStore.rules(net.ledok.eden_ld.chat.SystemOnlyRules.BUILT_IN);
            net.ledok.eden_ld.client.chat.ClientTabList.clear();
            net.ledok.eden_ld.client.chat.EmojiFont.reset();
            net.ledok.eden_ld.client.hub.RemoteImages.clear();
            net.ledok.eden_ld.client.theme.HubThemes.select(net.ledok.eden_ld.client.theme.HubTheme.PARCHMENT_ID);
        });
        ClientPlayNetworking.registerGlobalReceiver(net.ledok.eden_ld.combat.CombatTag.Sync.TYPE,
                (payload, context) -> ClientCombatTag.onSync(payload.millis()));
        // The PvP toggle: the player's own state, and who else has PvP on or is dueling (targeting, nameplates).
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.PvpState.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ClientPvp.onState(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.PvpPlayers.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ClientPvp.onPlayers(payload));

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.OpenHub.TYPE, (payload, context) -> {
            // "moderation:<section>" (/hub reports): the Moderation window instead, on that section.
            if (payload.tab().startsWith("moderation:")) {
                net.ledok.eden_ld.client.hub.ModerationScreen.openSection(payload.tab().substring("moderation:".length()));
            } else {
                HubScreen.open(payload.tab());
            }
        });

        net.ledok.eden_ld.client.profile.ProfileBackgrounds.initialize();
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ProfileData.TYPE, (payload, context) -> {
            // Only the page the Profile tab asks for (an admin unlock sends the player's own).
            if (!payload.uuid().equals(HubState.profileSubject())) {
                return;
            }
            HubState.profileData = payload;
            if (context.client().screen instanceof HubScreen screen) {
                screen.onProfileData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.HomeData.TYPE, (payload, context) -> {
            HubState.homeData = payload;
            if (context.client().screen instanceof HubScreen screen) {
                screen.onHomeData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.KitsData.TYPE, (payload, context) -> {
            HubState.kitsData = payload;
            HubState.kitsReceivedAt = System.currentTimeMillis();
            if (context.client().screen instanceof HubScreen screen) {
                screen.onKitsData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.Balance.TYPE, (payload, context) -> {
            if (HubState.homeData != null) {
                HubState.homeData = HubState.homeData.withBalance(payload.balance());
            }
            if (HubState.mailData != null) {
                HubState.mailData = new HubPayloads.MailData(HubState.mailData.mails(),
                        HubState.mailData.purchases(), payload.balance());
            }
            if (context.client().screen instanceof HubScreen screen) {
                screen.onBalance();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.PartyData.TYPE, (payload, context) -> {
            HubState.onPartyData(payload);
            if (context.client().screen instanceof HubScreen screen) {
                screen.onPartyData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.FriendsData.TYPE, (payload, context) -> {
            HubState.friendsData = payload;
            if (context.client().screen instanceof HubScreen screen) {
                screen.onFriendsData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.HubTours.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.HubHelp.onTours(payload.tabs()));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.IgnoreData.TYPE, (payload, context) -> {
            HubState.onIgnoreData(payload);
            // Their lines hide (or come back) in the chat window at once.
            net.ledok.eden_ld.client.chat.ChatFilter.refresh();
            if (context.client().screen instanceof HubScreen screen) {
                screen.onIgnoreData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.WaystonesData.TYPE, (payload, context) -> {
            HubState.waystonesData = payload;
            HubState.waystonesReceivedAt = System.currentTimeMillis();
            if (context.client().screen instanceof HubScreen screen) {
                screen.onWaystonesData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.WaystoneTeleportResult.TYPE, (payload, context) -> {
            HubState.waystoneMessage = payload.success() ? null : payload.message();
            HubState.waystoneMessageAt = System.currentTimeMillis();
            // A refusal shows on the tab's status line (the next tick picks it up).
            if (payload.success() && context.client().screen instanceof HubScreen screen) {
                screen.onClose();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ChatChannelState.TYPE, (payload, context) ->
                net.ledok.eden_ld.client.chat.ClientChatChannel.onState(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.EmojiList.TYPE, (payload, context) ->
                net.ledok.eden_ld.client.chat.EmojiFont.onList(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.EmojiImages.TYPE, (payload, context) ->
                net.ledok.eden_ld.client.chat.EmojiFont.onImages(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.EmojiGuests.TYPE, (payload, context) ->
                net.ledok.eden_ld.client.chat.EmojiFont.onGuests(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.TabOrder.TYPE, (payload, context) ->
                net.ledok.eden_ld.client.chat.ClientTabList.onOrder(payload));

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.NameStyles.TYPE, (payload, context) -> {
            net.ledok.eden_ld.client.hub.PlayerNames.onStyles(payload);
            if (context.client().screen instanceof HubScreen screen) {
                screen.onNameStyles();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.HubNotice.TYPE, (payload, context) -> {
            if (context.client().screen instanceof HubScreen screen) {
                net.ledok.vectorlib.client.canvas.widget.Toast.text(screen.canvas(), payload.message(),
                        payload.ok() ? EdenParchment.GOOD : EdenParchment.DANGER);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ModInfo.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ModerationScreen.onInfo(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.MailSendCheck.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ModerationScreen.onCheck(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.MailLog.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ModerationScreen.onLog(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ModNotice.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ModerationScreen.onNotice(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.PunishPlayers.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ModerationScreen.onPlayers(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.PunishPage.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ModerationScreen.onPage(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.AdminData.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ModerationScreen.onAdminData(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ActivityLive.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ModerationScreen.onActivityLive(payload));
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.PunishLog.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.ModerationScreen.onPunishLog(payload));

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.MailData.TYPE, (payload, context) -> {
            HubState.mailData = payload;
            if (context.client().screen instanceof HubScreen screen) {
                screen.onMailData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.DailiesData.TYPE, (payload, context) -> {
            HubState.dailiesData = payload;
            HubState.dailiesReceivedAt = System.currentTimeMillis();
            if (context.client().screen instanceof HubScreen screen) {
                screen.onDailiesData();
            }
        });

        // Comes right before ActivitiesData; the cards rebuild when that arrives.
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ActivitiesClosed.TYPE, (payload, context) -> {
            java.util.Set<String> closed = new java.util.HashSet<>();
            payload.ids().forEach(id -> closed.add(id.toString()));
            HubState.closedActivities = java.util.Set.copyOf(closed);
            HubState.closedBypass = payload.bypass();
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ActivitiesData.TYPE, (payload, context) -> {
            HubState.activitiesData = payload;
            if (context.client().screen instanceof HubScreen screen) {
                screen.onActivitiesData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.WardrobeData.TYPE, (payload, context) -> {
            HubState.wardrobeData = payload;
            if (context.client().screen instanceof HubScreen screen) {
                screen.onWardrobeData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.DuelsStatus.TYPE, (payload, context) -> {
            HubState.duelsStatus = payload;
            HubState.duelsStatusAt = System.currentTimeMillis();
            if (context.client().screen instanceof HubScreen screen) {
                screen.onDuelsStatus();
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.DuelsAlert.TYPE,
                (payload, context) -> net.ledok.eden_ld.client.hub.DuelsAlerts.matchStarting(payload.mode()));

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.DuelsData.TYPE, (payload, context) -> {
            HubState.duelsData = payload;
            HubState.duelsReceivedAt = System.currentTimeMillis();
            if (context.client().screen instanceof HubScreen screen) {
                screen.onDuelsData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ActivityUnlocks.TYPE, (payload, context) -> {
            HubState.activityUnlocks = java.util.List.copyOf(payload.names());
            if (context.client().screen instanceof HubScreen screen) {
                screen.onActivityUnlocks();
            }
        });

        net.ledok.eden_ld.client.hub.ActivityReturn.initialize();
        net.ledok.eden_ld.client.hub.ModerationReturn.initialize();
        net.ledok.eden_ld.client.theme.HubThemes.initialize();
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ChatFlags.TYPE, (payload, context) -> {
            HubState.chatFlags = payload.flags();
            // The tabs' switches (All tab, system lines in channel tabs) apply at once.
            net.ledok.eden_ld.client.chat.ChatFilter.refresh();
            net.ledok.eden_ld.client.chat.ChatTabActions.refresh();
            if (context.client().screen instanceof HubScreen screen) {
                screen.onSettingsData();
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ChatSystemOnly.TYPE, (payload, context) -> {
            // The server's system-only list on top of Eden's: lines already in the chat are filed again at once.
            net.ledok.eden_ld.client.chat.panel.ChatStore.rules(net.ledok.eden_ld.chat.SystemOnlyRules.of(
                    payload.keys(), payload.text(), payload.keep()));
            net.ledok.eden_ld.client.chat.ChatFilter.refresh();
        });
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.SettingsData.TYPE, (payload, context) -> {
            int languageBefore = HubState.settingsData == null ? 0 : HubState.settingsData.hubLanguage();
            boolean iconsBefore = HubState.settingsData != null && HubState.settingsData.themeIconsOff();
            HubState.settingsData = payload;
            // Chat settings apply to the chat window at once (hidden lines come back or go).
            net.ledok.eden_ld.client.chat.ChatFilter.refresh();
            net.ledok.eden_ld.client.chat.ClientChatChannel.onSettings();
            net.minecraft.resources.ResourceLocation theme = net.minecraft.resources.ResourceLocation.tryParse(payload.theme());
            boolean restyled = net.ledok.eden_ld.client.theme.HubThemes.select(
                    theme != null ? theme : net.ledok.eden_ld.client.theme.HubTheme.PARCHMENT_ID);
            if (context.client().screen instanceof HubScreen screen) {
                if (payload.hubLanguage() != languageBefore) {
                    screen.onLanguageChanged();
                } else if (restyled || payload.themeIconsOff() != iconsBefore) {
                    // A new theme, or its icons turned on/off: everything is drawn again.
                    screen.onThemeChanged();
                } else {
                    screen.onSettingsData();
                }
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.GuildData.TYPE, (payload, context) -> {
            java.util.UUID guildBefore = HubState.guildData != null && HubState.guildData.inGuild()
                    ? HubState.guildData.guild().id() : null;
            HubState.onGuildData(payload);
            java.util.UUID guildNow = payload.inGuild() ? payload.guild().id() : null;
            if (!java.util.Objects.equals(guildBefore, guildNow)) {
                // Founded, joined, or left a guild: the level and board belong to another guild now.
                HubState.guildQuests = null;
                if (guildNow != null) {
                    ClientPlayNetworking.send(HubPayloads.GuildQuestsRequest.INSTANCE);
                }
            }
            if (context.client().screen instanceof HubScreen screen) {
                screen.onGuildData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.MentionPing.TYPE, (payload, context) -> {
            // The channel's notifications decide (the server already skips Nothing; this follows a change at once).
            if (net.ledok.eden_ld.client.chat.ChatActivity.notify(net.ledok.eden_ld.chat.ChatChannels.Channel.of(
                    payload.channel())) != net.ledok.eden_ld.hub.HubSettings.ChannelNotify.NOTHING) {
                net.ledok.eden_ld.client.chat.MentionSound.play();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.GuildQuestsData.TYPE, (payload, context) -> {
            HubState.guildQuests = payload;
            HubState.guildQuestsReceivedAt = System.currentTimeMillis();
            if (context.client().screen instanceof HubScreen screen) {
                screen.onGuildQuests();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.BankData.TYPE, (payload, context) -> {
            HubState.bankData = payload;
            if (context.client().screen instanceof HubScreen screen) {
                screen.onGuildData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.GuildChatLine.TYPE, (payload, context) -> {
            HubState.guildChat.add(payload.line());
            if (context.client().screen instanceof HubScreen screen) {
                screen.onGuildData();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.PartyChatLine.TYPE, (payload, context) -> {
            HubState.partyChat.add(payload.line());
            if (context.client().screen instanceof HubScreen screen) {
                screen.onPartyData();
            }
        });
    }
}
