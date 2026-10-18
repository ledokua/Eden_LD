package net.ledok.eden_ld.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which lines show in the System tab alone (1.3.2), checked on lines built as the game and the mods build them, each
 * first rendered to the very text the owner's client received (scratchpad sysonly/client-chat-samples.txt).
 */
class SystemOnlyRulesTest {

    /** The mods' en_us strings these lines use (vanilla's come from the game's own en_us.json). */
    private static final Map<String, String> MOD_STRINGS = Map.ofEntries(
            Map.entry("gui.xaero_claims_protection_interact_block",
                    "(%1$s) You are not allowed to interact with this block (%2$s)!"),
            Map.entry("gui.xaero_claims_protection_interact_block_try_empty",
                    "(%1$s) It might work if you try again empty-handed."),
            Map.entry("gui.xaero_claims_protection_use_item",
                    "(%1$s) You are not allowed to use this item this close to somebody else's claim (%2$s)! Try "
                            + "empty hand?"),
            Map.entry("gui.xaero_claims_protection_main_hand", "Main Hand"),
            Map.entry("gui.xaero_claims_server_mode_enabled", "Server claim mode enabled!"),
            Map.entry("text.observable.announce",
                    "This pack contains Observable for finding lag sources. See %s to learn how to use it."),
            Map.entry("chat.waystones.waystone_activated", "Activated Waystone: %s"),
            Map.entry("commands.waystones.forget.all.success.single", "Deactivated all waystones for %s"),
            // YIGD's file has %d: vanilla's client formats %s alone.
            Map.entry("text.yigd.message.grave_location", "Your grave is located at X: %s / Y: %s / Z: %s / %s"),
            Map.entry("text.yigd.message.grave_destroyed", "Your grave has been unexpectedly destroyed. With proper "
                    + "permissions graves can be restored by using /yigd commands"),
            Map.entry("crafttweaker.reload.start", "CraftTweaker reload starting!"),
            Map.entry("crafttweaker.reload.complete", "CraftTweaker reload complete!"),
            Map.entry("iris.shaders.toggled", "Toggled shaders to %s!"),
            Map.entry("iris.shaders.disabled", "Shaders disabled!"),
            Map.entry("chat.puffish_skills.new_point", "You have a new skill point. Press %s to open your skill tree."),
            Map.entry("message.voicechat.set_up", "Press %s to set up"),
            Map.entry("message.eden_ld.tutorial.started", "%s is in the tutorial now."),
            Map.entry("message.eden_ld.profile.level_up", "★ Minestar level %s!"));

    private static Language before;

    @BeforeAll
    static void injectLanguage() {
        before = Language.getInstance();
        Language vanilla = before;
        Language.inject(new Language() {
            @Override
            public String getOrDefault(String key, String fallback) {
                String text = MOD_STRINGS.get(key);
                return text != null ? text : vanilla.getOrDefault(key, fallback);
            }

            @Override
            public boolean has(String key) {
                return MOD_STRINGS.containsKey(key) || vanilla.has(key);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public FormattedCharSequence getVisualOrder(FormattedText text) {
                return vanilla.getVisualOrder(text);
            }
        });
    }

    /** Other tests read untranslated keys. */
    @AfterAll
    static void restoreLanguage() {
        Language.inject(before);
    }

    // ---------------------------------------------------------------- building lines as the game does

    private static MutableComponent t(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static MutableComponent text(String text) {
        return Component.literal(text);
    }

    /** CommandSourceStack.broadcastToAdmins: what the other admins see of a command's feedback. */
    private static MutableComponent echo(String source, Component feedback) {
        return t("chat.type.admin", text(source), feedback).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }

    /** KeyboardHandler.debugComponent: F3's "[Debug]:" prefix, then the message. */
    private static MutableComponent debug(Component message) {
        return Component.empty().append(t("debug.prefix").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(" ").append(message);
    }

    /** Simple Voice Chat's ChatUtils.createModMessage. */
    private static MutableComponent voiceChat(Component message) {
        return Component.empty().append(t("chat.square_brackets", text("Simple Voice Chat"))
                .withStyle(ChatFormatting.GREEN)).append(" ").append(message);
    }

    private static MutableComponent opac(String key, Object... more) {
        Object[] args = new Object[more.length + 1];
        args[0] = t("gui.xaero_claims_protection_main_hand");
        System.arraycopy(more, 0, args, 1, more.length);
        return t(key, args);
    }

    /** The line reads as received (verbatim), then is filed as {@code systemOnly}. */
    private static void filed(SystemOnlyRules rules, Component line, String received, boolean systemOnly) {
        String text = line.getString();
        assertEquals(received, text);
        assertEquals(systemOnly, rules.matches(line, text), received);
    }

    private static void moves(Component line, String received) {
        filed(SystemOnlyRules.BUILT_IN, line, received, true);
    }

    private static void stays(Component line, String received) {
        filed(SystemOnlyRules.BUILT_IN, line, received, false);
    }

    // ---------------------------------------------------------------- the owner's two kinds

    @Test
    void otherAdminsCommandEchoesMoveTheirOwnFeedbackStays() {
        moves(echo("@", t("commands.function.scheduled.single",
                        text("stellarity:post_gen/end_city/decorations/1x"))),
                "[@: Running function stellarity:post_gen/end_city/decorations/1x]");
        moves(echo("Server", t("commands.stop.stopping")), "[Server: Stopping the server]");
        moves(echo("[Mod] Tend1tnuy", t("commands.reload.success")), "[[Mod] Tend1tnuy: Reloading!]");
        moves(echo("Kiber_Kabachok", t("commands.gamemode.success.self", t("gameMode.survival"))),
                "[Kiber_Kabachok: Set own game mode to Survival Mode]");
        moves(echo("[Mod] Tend1tnuy", t("commands.teleport.success.location.single", text("[Mod] Tend1tnuy"),
                        "1000.500000", "100.000000", "1000.500000")),
                "[[Mod] Tend1tnuy: Teleported [Mod] Tend1tnuy to 1000.500000, 100.000000, 1000.500000]");

        stays(t("commands.gamemode.success.self", t("gameMode.creative")), "Set own game mode to Creative Mode");
        stays(t("commands.teleport.success.location.single", text("[Admin] LeDok ✔"), "-919.500000", "101.000000",
                "-7.500000"), "Teleported [Admin] LeDok ✔ to -919.500000, 101.000000, -7.500000");
        stays(t("commands.function.scheduled.single", text("falleneden_testkit:help")),
                "Running function falleneden_testkit:help");
        stays(t("commands.reload.success"), "Reloading!");
    }

    @Test
    void keepCarvesOutOneKindOfEchoOrAllOfThem() {
        MutableComponent ban = echo("[Admin] LeDok ✔", t("commands.ban.success", text("non_pava"), text("test")));
        MutableComponent gameMode = echo("Kiber_Kabachok", t("commands.gamemode.success.self", t("gameMode.creative")));
        SystemOnlyRules bans = SystemOnlyRules.of(List.of(), List.of(), List.of("commands.ban.success"));
        filed(bans, ban, "[[Admin] LeDok ✔: Banned non_pava: test]", false);
        filed(bans, gameMode, "[Kiber_Kabachok: Set own game mode to Creative Mode]", true);
        // The echoed command's key only ever keeps: listing it never moves the player's own feedback… nor its echo.
        SystemOnlyRules listed = SystemOnlyRules.of(List.of("commands.ban.success"), List.of(), List.of());
        assertTrue(listed.matches(ban, ban.getString()));

        SystemOnlyRules noEchoes = SystemOnlyRules.of(List.of(), List.of(), List.of("chat.type.admin"));
        assertFalse(noEchoes.matches(ban, ban.getString()));
        assertFalse(noEchoes.matches(gameMode, gameMode.getString()));
    }

    @Test
    void f3FeedbackMovesButItsHelpAndCrashWarningsStay() {
        moves(debug(t("debug.show_hitboxes.on")), "[Debug]: Hitboxes: shown");
        moves(debug(t("debug.reload_resourcepacks.message")), "[Debug]: Reloaded resource packs");
        // F3 + Q: the header, then its list (no prefix).
        stays(debug(t("debug.help.message")), "[Debug]: Key bindings:");
        stays(t("debug.reload_chunks.help"), "F3 + A = Reload chunks");
        stays(debug(t("debug.crash.message")),
                "[Debug]: F3 + C is held down. This will crash the game unless released.");
        stays(debug(t("debug.crash.warning", 3)), "[Debug]: Crashing in 3...");
    }

    @Test
    void noticesMoveByTheirKey() {
        moves(t("block.minecraft.set_spawn"), "Respawn point set");
        MutableComponent file = text("2026-10-18_23.55.14.png").withStyle(ChatFormatting.UNDERLINE)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE,
                        "screenshots/2026-10-18_23.55.14.png")));
        moves(t("screenshot.success", file), "Saved screenshot as 2026-10-18_23.55.14.png");
        MutableComponent link = text("https://observable.tas.sh/").withStyle(style -> style.withClickEvent(
                new ClickEvent(ClickEvent.Action.OPEN_URL, "https://observable.tas.sh/")));
        moves(t("text.observable.announce", link),
                "This pack contains Observable for finding lag sources. See https://observable.tas.sh/ to learn how to "
                        + "use it.");
        moves(t("chat.waystones.waystone_activated", text("Spawn")), "Activated Waystone: Spawn");
        moves(t("text.yigd.message.grave_location", "120", "64", "-35", "minecraft:overworld"),
                "Your grave is located at X: 120 / Y: 64 / Z: -35 / minecraft:overworld");
        moves(opac("gui.xaero_claims_protection_interact_block", text("minecraft:soul_sand")),
                "(Main Hand) You are not allowed to interact with this block (minecraft:soul_sand)!");
        moves(opac("gui.xaero_claims_protection_interact_block_try_empty"),
                "(Main Hand) It might work if you try again empty-handed.");
        moves(t("crafttweaker.reload.start"), "CraftTweaker reload starting!");
        moves(t("crafttweaker.reload.complete"), "CraftTweaker reload complete!");
        moves(t("iris.shaders.toggled", "(off)"), "Toggled shaders to (off)!");
        moves(t("iris.shaders.disabled"), "Shaders disabled!");
    }

    @Test
    void nearMissesStay() {
        stays(t("gui.xaero_claims_server_mode_enabled"), "Server claim mode enabled!");
        stays(t("commands.waystones.forget.all.success.single", text("non_pava")),
                "Deactivated all waystones for non_pava");
        stays(t("text.yigd.message.grave_destroyed"), "Your grave has been unexpectedly destroyed. With proper "
                + "permissions graves can be restored by using /yigd commands");
        stays(t("chat.puffish_skills.new_point", text("J")),
                "You have a new skill point. Press J to open your skill tree.");
        stays(t("block.minecraft.spawn.not_valid"),
                "You have no home bed or charged respawn anchor, or it was obstructed");
        stays(text("[Axiom] The Editor UI keybind has conflicts:"), "[Axiom] The Editor UI keybind has conflicts:");
    }

    @Test
    void modsThatSendPlainTextMoveByHowTheLineStarts() {
        moves(text("[Axiom] Tip: Access the Axiom config by running /axiomconfig"),
                "[Axiom] Tip: Access the Axiom config by running /axiomconfig");
        moves(text("[ModBeacon]").withStyle(ChatFormatting.GOLD).append(text(" 8 updates available:")),
                "[ModBeacon] 8 updates available:");
        moves(text("[ModBeacon]").append(text(" 1 update available:")), "[ModBeacon] 1 update available:");
        // Its restart broadcasts and its other replies are announcements: they stay.
        stays(text("[ModBeacon] ").append(text("3 mod update(s) will be applied. Server restarting in 1m.")),
                "[ModBeacon] 3 mod update(s) will be applied. Server restarting in 1m.");
        stays(text("[ModBeacon] Restarting in 5 seconds!"), "[ModBeacon] Restarting in 5 seconds!");
        stays(text("[ModBeacon]").append(text(" All mods are up to date.")), "[ModBeacon] All mods are up to date.");
        moves(Component.empty().append(text("  ▸ ")).append(text("[MR]").withStyle(ChatFormatting.GREEN))
                        .append(text(" Lithostitched  1.8.0 -> 1.8.1-fabric-21.1  [^]  [!]")),
                "  ▸ [MR] Lithostitched  1.8.0 -> 1.8.1-fabric-21.1  [^]  [!]");
        moves(Component.empty().append(text("  ▸ ")).append(text("[PIN]")).append(text(" Collective  pinned at 8.47")),
                "  ▸ [PIN] Collective  pinned at 8.47");
        moves(voiceChat(t("message.voicechat.set_up", text("V"))), "[Simple Voice Chat] Press V to set up");
    }

    @Test
    void chatAnnouncementsJoinsEdenAndTheTestKitAreNeverCaught() {
        stays(text("Fallen Eden test kit: click a test. Each one RESETS your falleneden:classes tree."),
                "Fallen Eden test kit: click a test. Each one RESETS your falleneden:classes tree.");
        stays(text("  ▶ STRPG-5 Ricocheting Net"), "  ▶ STRPG-5 Ricocheting Net");
        stays(text("─── STRPG-5: Ricocheting Net (ricochet range) ───"),
                "─── STRPG-5: Ricocheting Net (ricochet range) ───");
        stays(text("1. Hold the bow, cast Dual Shot at the husk and note how far it flies (markers)."),
                "1. Hold the bow, cast Dual Shot at the husk and note how far it flies (markers).");
        stays(t("chat.type.announcement", text("Server"), text("test")), "[Server] test");
        stays(t("multiplayer.player.joined", text("roi08551")).withStyle(ChatFormatting.YELLOW),
                "roi08551 joined the game");
        stays(t("multiplayer.player.left", text("[Mod] Tend1tnuy")), "[Mod] Tend1tnuy left the game");
        stays(text("§9[Discord] §bLeDok§7: §fБувай"), "§9[Discord] §bLeDok§7: §fБувай");
        stays(t("message.eden_ld.tutorial.started", text("[Prime] Evora")), "[Prime] Evora is in the tutorial now.");
        stays(t("message.eden_ld.profile.level_up", 14).append(" You got: Sticker slots +1. [Profile]"),
                "★ Minestar level 14! You got: Sticker slots +1. [Profile]");
        stays(text("[DataRewriter] recipes: 60 removed, 255 added (153 replacing)"),
                "[DataRewriter] recipes: 60 removed, 255 added (153 replacing)");
        stays(text("Player non_pava has been banned by [Admin] LeDok ✔!\nReason: test. Expires in: 1 day(s) "),
                "Player non_pava has been banned by [Admin] LeDok ✔!\nReason: test. Expires in: 1 day(s) ");
    }

    @Test
    void onlyEmptyWrappersAreLookedThroughAndOnlyTwoDeep() {
        MutableComponent spawn = t("block.minecraft.set_spawn");
        moves(Component.empty().append(Component.empty().append(spawn)), "Respawn point set");
        stays(Component.empty().append(Component.empty().append(Component.empty().append(spawn))), "Respawn point set");
        // A line with text of its own: a key in it isn't the line's.
        stays(text("x").append(spawn), "xRespawn point set");
        // Nor a key in someone else's translation arguments.
        stays(t("chat.type.announcement", text("Server"), spawn), "[Server] Respawn point set");
    }

    @Test
    void theServersListsAddToEdensAndAreCleaned() {
        SystemOnlyRules rules = SystemOnlyRules.of(
                List.of("chat.puffish_skills.new_point", "*", "", "ab*", "has space"),
                List.of("  [DataRewriter] ", "["),
                List.of("gui.xaero_claims_protection_*"));
        filed(rules, t("chat.puffish_skills.new_point", text("J")),
                "You have a new skill point. Press J to open your skill tree.", true);
        filed(rules, text("[DataRewriter] recipes: 60 removed"), "[DataRewriter] recipes: 60 removed", true);
        // The entries no rule allows match nothing.
        filed(rules, text("abc has space ["), "abc has space [", false);
        filed(rules, t("chat.type.announcement", text("Server"), text("test")), "[Server] test", false);
        // Keep switches off one of Eden's own.
        filed(rules, opac("gui.xaero_claims_protection_interact_block", text("minecraft:soul_sand")),
                "(Main Hand) You are not allowed to interact with this block (minecraft:soul_sand)!", false);
        // The others still apply.
        filed(rules, t("block.minecraft.set_spawn"), "Respawn point set", true);

        List<String> dropped = new ArrayList<>();
        assertEquals(List.of("chat.puffish_skills.new_point", "chat.waystones.*"), SystemOnlyRules.clean(
                java.util.Arrays.asList(" chat.puffish_skills.new_point ", "*", "", "ab*", "has space", "a*b", null,
                        "chat.puffish_skills.new_point", "x".repeat(SystemOnlyRules.MAX_LENGTH + 1),
                        "chat.waystones.*"),
                SystemOnlyRules.Part.KEYS, (entry, why) -> dropped.add(entry)));
        assertEquals(List.of("*", "", "ab*", "has space", "a*b", "null", "x".repeat(SystemOnlyRules.MAX_LENGTH + 1)),
                dropped);
        assertEquals(List.of("[DataRewriter]", "[ModBeacon]"), SystemOnlyRules.clean(
                List.of("  [DataRewriter] ", "§6[ModBeacon]", "[", "  §7 ", "ab"), SystemOnlyRules.Part.TEXT));
        // A keep entry may be a key (any exact one, a prefix) or a text start ("a b").
        assertEquals(List.of("commands.ban.success", "Player non_pava", "ab", "abc*", "a b"), SystemOnlyRules.clean(
                List.of("commands.ban.success", " Player non_pava ", "ab", "abc*", "*", "**", "a b"),
                SystemOnlyRules.Part.KEEP));

        List<String> many = new ArrayList<>();
        for (int i = 0; i < SystemOnlyRules.MAX_ENTRIES + 10; i++) {
            many.add("mod.key." + i);
        }
        List<String> capped = SystemOnlyRules.clean(many, SystemOnlyRules.Part.KEYS);
        assertEquals(SystemOnlyRules.MAX_ENTRIES, capped.size());
        assertEquals(many.subList(0, SystemOnlyRules.MAX_ENTRIES), capped);
    }

    @Test
    void textStartsSkipLeadingSpacesAndSectionCodes() {
        moves(text("§6  [ModBeacon] 8 updates"), "§6  [ModBeacon] 8 updates");
        moves(text("  §e§l[ModBeacon] 12 updates"), "  §e§l[ModBeacon] 12 updates");
        // Case counts, and the start must be the line's start.
        stays(text("[modbeacon] 8 updates"), "[modbeacon] 8 updates");
        stays(text("see [ModBeacon] 8 updates"), "see [ModBeacon] 8 updates");
    }

    @Test
    void aHashInATextStartIsANumber() {
        assertTrue(SystemOnlyRules.textStartsWith("[X] 12 updates", 0, "[X] # update"));
        assertTrue(SystemOnlyRules.textStartsWith("[X] 1 update", 0, "[X] # update"));
        assertFalse(SystemOnlyRules.textStartsWith("[X] many updates", 0, "[X] # update"));
        assertFalse(SystemOnlyRules.textStartsWith("[X]  update", 0, "[X] # update"));
        assertFalse(SystemOnlyRules.textStartsWith("[X] 12", 0, "[X] # update"));
        assertTrue(SystemOnlyRules.textStartsWith("  [X] 3 x", 2, "[X] # x"));
    }

    @Test
    void aKeyPrefixMatchesOnlyItsOwnKeys() {
        moves(opac("gui.xaero_claims_protection_use_item", text("minecraft:ender_pearl")),
                "(Main Hand) You are not allowed to use this item this close to somebody else's claim "
                        + "(minecraft:ender_pearl)! Try empty hand?");
        stays(t("gui.xaero_claims_server_mode_enabled"), "Server claim mode enabled!");
    }

    @Test
    void rulesAreEqualByTheServersListsAndEdensAreClean() {
        assertEquals(SystemOnlyRules.BUILT_IN, SystemOnlyRules.of(List.of(), List.of(), List.of()));
        assertEquals(SystemOnlyRules.BUILT_IN, SystemOnlyRules.of(null, null, null));
        assertEquals(SystemOnlyRules.of(List.of("a.b"), List.of("[X]"), List.of("c.d")),
                SystemOnlyRules.of(List.of(" a.b"), List.of("[X]  "), List.of("c.d")));
        assertEquals(SystemOnlyRules.of(List.of("a.b"), List.of(), List.of()).hashCode(),
                SystemOnlyRules.of(List.of("a.b", "a.b"), List.of(), List.of()).hashCode());
        assertNotEquals(SystemOnlyRules.BUILT_IN, SystemOnlyRules.of(List.of("a.b"), List.of(), List.of()));
        assertNotEquals(SystemOnlyRules.of(List.of(), List.of("a.b"), List.of()),
                SystemOnlyRules.of(List.of(), List.of(), List.of("a.b")));
        // Eden's own lists pass the rules the server's must.
        assertEquals(SystemOnlyRules.KEYS, SystemOnlyRules.clean(SystemOnlyRules.KEYS, SystemOnlyRules.Part.KEYS));
        assertEquals(SystemOnlyRules.TEXT, SystemOnlyRules.clean(SystemOnlyRules.TEXT, SystemOnlyRules.Part.TEXT));
        assertEquals(SystemOnlyRules.KEEP, SystemOnlyRules.clean(SystemOnlyRules.KEEP, SystemOnlyRules.Part.KEEP));
        assertEquals(Collections.emptyList(), SystemOnlyRules.clean(null, SystemOnlyRules.Part.TEXT));
    }
}
