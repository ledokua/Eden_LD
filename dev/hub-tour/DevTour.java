package net.ledok.eden_ld.client.hub;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.client.theme.HubThemes;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * TEMPORARY dev tool (remove after the hub critique): with {@code -Deden_ld.devTour=<folder>} the dev
 * client walks every hub tab, the help page and every Moderation section in each theme and language,
 * saving a screenshot of each to that folder. Off unless the property is set.
 */
public final class DevTour {

    private static final String OUT = System.getProperty("eden_ld.devTour");
    private static final List<String> LANGS = list("eden_ld.devTourLangs", "en_us,uk_ua");
    private static final List<String> THEMES = list("eden_ld.devTourThemes", "parchment,dark,overgrown");
    /** {@code -Deden_ld.devTourSizes=min,560x310,max}: hub sizes to shoot each tab at (default: as saved). */
    private static final List<String> SIZES = list("eden_ld.devTourSizes", "saved");
    /** {@code -Deden_ld.devTourTabs=friends,activities}: only these tabs (no help shot); unset = all. */
    private static final List<String> ONLY = System.getProperty("eden_ld.devTourTabs") == null ? List.of()
            : List.of(System.getProperty("eden_ld.devTourTabs").toLowerCase(Locale.ROOT).split(","));

    private static final Deque<Runnable> STEPS = new ArrayDeque<>();
    private static int wait = 900;
    private static boolean planned;

    private DevTour() {
    }

    private static List<String> list(String property, String fallback) {
        return List.of(System.getProperty(property, fallback).toLowerCase(Locale.ROOT).split(","));
    }

    /** Puts the open hub at a tour size: "min", "max", "WxH", or "saved" (leave it). */
    private static void size(String size) {
        if (!(Minecraft.getInstance().screen instanceof HubScreen hub) || size.equals("saved")) {
            return;
        }
        if (size.equals("max")) {
            hub.maximized(true);
        } else {
            hub.maximized(false);
            String[] parts = size.equals("min") ? new String[]{"440", "262"} : size.split("x");
            hub.canvasSize(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]));
        }
        wait = 12;
    }

    private static String tag(String theme, String size) {
        return SIZES.size() == 1 && size.equals("saved") ? theme : theme + "_" + size;
    }

    /** The Friends tab needs Minestar; the tour fills it with made-up friends instead. */
    static boolean fakeFriends() {
        return OUT != null;
    }

    private static void fillFriends() {
        long now = System.currentTimeMillis();
        int ok = net.ledok.eden_ld.hub.party.PartyManager.InviteState.OK.ordinal();
        java.util.function.Function<String, java.util.UUID> id = name -> java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<HubPayloads.FriendEntry> friends = List.of(
                new HubPayloads.FriendEntry(id.apply("Alice"), "Alice", HubPayloads.FriendEntry.ON_SERVER, 0, "", "",
                        "minecraft:the_end", ok, true, ""),
                new HubPayloads.FriendEntry(id.apply("Hikari"), "Hikari", HubPayloads.FriendEntry.ONLINE, 0, "GardenMyth",
                        "gardenmyth", "", ok, true, "eden_ld:frieren_snow"),
                new HubPayloads.FriendEntry(id.apply("Lorp_000"), "Lorp_000", HubPayloads.FriendEntry.ONLINE, 0,
                        "ModernTech NEO", "moderntech", "", ok, true, ""),
                new HubPayloads.FriendEntry(id.apply("varovet"), "varovet", HubPayloads.FriendEntry.ONLINE, 0, "Vanilla",
                        "vanilla", "", ok, false, ""),
                new HubPayloads.FriendEntry(id.apply("Kiber_Kabachok"), "Kiber_Kabachok", HubPayloads.FriendEntry.OFFLINE,
                        now - 32 * 60_000L, "", "", "", ok, true, ""),
                new HubPayloads.FriendEntry(id.apply("Evora"), "Evora", HubPayloads.FriendEntry.OFFLINE,
                        now - 12 * 3_600_000L, "", "", "", ok, true, ""),
                new HubPayloads.FriendEntry(id.apply("denchee"), "denchee", HubPayloads.FriendEntry.OFFLINE,
                        now - 5 * 86_400_000L, "", "", "", ok, true, ""));
        List<HubPayloads.ServerPlayerEntry> players = List.of(
                new HubPayloads.ServerPlayerEntry(id.apply("Alice"), "Alice", HubPayloads.ServerPlayerEntry.FRIEND, ok, true, ""),
                new HubPayloads.ServerPlayerEntry(id.apply("Bob"), "Bob", HubPayloads.ServerPlayerEntry.NONE, ok, true, ""));
        HubState.friendsData = new HubPayloads.FriendsData(true, friends,
                List.of(new HubPayloads.RequestEntry(id.apply("Tori"), "Tori", now - 3_600_000L)), List.of(), players);
        if (Minecraft.getInstance().screen instanceof HubScreen hub) {
            hub.onFriendsData();
        }
    }

    public static void tick(Minecraft client) {
        if (OUT == null || client.player == null || client.getOverlay() != null) {
            return;
        }
        if (client.player.isDeadOrDying()) {
            client.player.respawn();
            return;
        }
        if (!planned) {
            planned = true;
            plan();
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Runnable step = STEPS.poll();
        if (step != null) {
            step.run();
        }
    }

    private static void plan() {
        for (String lang : LANGS) {
            STEPS.add(() -> language(lang));
            for (String theme : THEMES) {
                STEPS.add(() -> {
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, theme);
                    ClientPlayNetworking.send(new HubPayloads.ThemeSelect(id.toString()));
                    HubThemes.select(id);
                    wait = 30;
                });
                for (String size : SIZES) {
                String look = tag(theme, size);
                for (HubTab tab : HubTab.values()) {
                    if (!ONLY.isEmpty() && !ONLY.contains(tab.name().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    STEPS.add(() -> {
                        HubState.guildView = GuildView.MEMBERS;
                        HubScreen.open(tab.name().toLowerCase(Locale.ROOT));
                        wait = 40;
                    });
                    STEPS.add(() -> size(size));
                    if (tab == HubTab.FRIENDS) {
                        STEPS.add(() -> {
                            fillFriends();
                            wait = 15;
                        });
                    }
                    STEPS.add(() -> shot(lang, look, "tab_" + tab.name().toLowerCase(Locale.ROOT)));
                    if (tab == HubTab.ACTIVITIES) {
                        STEPS.add(() -> {
                            activitiesFilter("DUELS");
                            HubScreen.open("activities");
                            wait = 50;
                        });
                        STEPS.add(() -> size(size));
                        STEPS.add(() -> shot(lang, look, "tab_activities_duels"));
                        STEPS.add(() -> activitiesFilter("ALL"));
                    }
                    if (tab == HubTab.GUILD) {
                        // The bank (vault + inventory must fit the smallest hub), then its log window.
                        STEPS.add(() -> {
                            HubState.guildView = GuildView.BANK;
                            HubScreen.open("guild");
                            wait = 60;
                        });
                        STEPS.add(() -> size(size));
                        STEPS.add(() -> shot(lang, look, "tab_guild_bank"));
                        STEPS.add(() -> {
                            openBankLog();
                            wait = 20;
                        });
                        STEPS.add(() -> shot(lang, look, "tab_guild_bank_log"));
                        STEPS.add(() -> HubState.guildView = GuildView.MEMBERS);
                    }
                }
                if (!ONLY.isEmpty()) {
                    continue;
                }
                STEPS.add(() -> {
                    HubScreen.open("home");
                    wait = 20;
                });
                STEPS.add(() -> size(size));
                STEPS.add(() -> {
                    if (Minecraft.getInstance().screen instanceof HubScreen hub) {
                        HubHelp.open(hub, HubTab.HOME);
                    }
                    wait = 30;
                });
                STEPS.add(() -> shot(lang, look, "help_home"));
                }
            }
        }
        // Ends on Friends (sample data) so a card can be hovered by hand.
        STEPS.add(() -> {
            HubScreen.open("friends");
            wait = 40;
        });
        STEPS.add(() -> {
            fillFriends();
            EdenLD.LOGGER.info("DEV TOUR DONE");
        });
    }

    private static void openBankLog() {
        if (!(Minecraft.getInstance().screen instanceof HubScreen hub)) {
            return;
        }
        try {
            Method open = GuildBankView.class.getDeclaredMethod("openLog", HubScreen.class);
            open.setAccessible(true);
            open.invoke(null, hub);
        } catch (ReflectiveOperationException e) {
            EdenLD.LOGGER.warn("Dev tour: can't open the bank log", e);
        }
    }

    private static void activitiesFilter(String name) {
        try {
            java.lang.reflect.Field field = ActivitiesTab.class.getDeclaredField("filter");
            field.setAccessible(true);
            field.set(null, ActivitiesTab.Filter.valueOf(name));
        } catch (ReflectiveOperationException e) {
            EdenLD.LOGGER.warn("Dev tour: can't set the Activities filter", e);
        }
    }

    private static void language(String lang) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
        if (!lang.equals(mc.options.languageCode)) {
            mc.options.languageCode = lang;
            mc.getLanguageManager().setSelected(lang);
            mc.options.save();
            mc.reloadResourcePacks();
        }
        wait = 100;
    }

    private static void moderationSection(String name) {
        if (!(Minecraft.getInstance().screen instanceof ModerationScreen screen)) {
            return;
        }
        try {
            Class<?> sections = Class.forName(ModerationScreen.class.getName() + "$Section");
            Object section = sections.getMethod("valueOf", String.class).invoke(null, name);
            Method show = ModerationScreen.class.getDeclaredMethod("show", sections);
            show.setAccessible(true);
            show.invoke(screen, section);
        } catch (ReflectiveOperationException e) {
            EdenLD.LOGGER.warn("Dev tour: can't show {}", name, e);
        }
    }

    private static void shot(String lang, String theme, String name) {
        Minecraft mc = Minecraft.getInstance();
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            Path dir = Path.of(OUT);
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(lang + "_" + theme + "_" + name + ".png"));
        } catch (Exception e) {
            EdenLD.LOGGER.warn("Dev tour: screenshot {} failed", name, e);
        }
        wait = 2;
    }
}
