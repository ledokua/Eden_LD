package net.ledok.eden_ld.hub.profile;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenData;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.command.EdenPermissions;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.LookRule;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.hub.HubSettings;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who may use each profile background and each hub theme, set in the Moderation window's
 * Customization section: open to everyone, or locked — then unlocked for chosen LuckPerms groups,
 * hidden from the picker or shown there, and for sale for LC when it has a price. Unlocks given with
 * {@code /hub background|theme unlock} or bought stay per player. A look with no rule is locked and
 * hidden, except its kind's {@link Kind#openByDefault}. Kept in the world's data, one store per kind.
 */
public final class LookRules extends SavedData {

    public enum Kind {
        BACKGROUND("background_rules", EdenPermissions.BACKGROUNDS, List.of("eden_ld:twilight", "eden_ld:parchment")),
        THEME("theme_rules", EdenPermissions.THEMES, List.of("eden_ld:parchment", "eden_ld:dark"));

        final String file;
        final String permission;
        /** Open to everyone until a rule says otherwise. */
        public final List<String> openByDefault;

        Kind(String file, String permission, List<String> openByDefault) {
            this.file = file;
            this.permission = permission;
            this.openByDefault = openByDefault;
        }

        public static Kind of(boolean theme) {
            return theme ? THEME : BACKGROUND;
        }
    }

    private static final long MAX_PRICE = 1_000_000_000L;

    private final Kind kind;
    private final Map<String, LookRule> rules = new LinkedHashMap<>();

    private LookRules(Kind kind) {
        this.kind = kind;
    }

    public static LookRules get(MinecraftServer server, Kind kind) {
        return EdenData.get(server, new Factory<>(() -> new LookRules(kind), (tag, registries) -> load(kind, tag), null),
                kind.file);
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.LookRuleSave.TYPE,
                (payload, context) -> context.server().execute(() -> save(context.player(), Kind.of(payload.theme()), payload.rule())));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.LookBuy.TYPE,
                (payload, context) -> context.server().execute(() -> buy(context.player(), Kind.of(payload.theme()), payload.id())));
    }

    /** The rule for a look (the default when none was set). */
    public LookRule rule(String id) {
        LookRule rule = rules.get(id);
        if (rule != null) {
            return rule;
        }
        return new LookRule(id, !kind.openByDefault.contains(id), List.of(), true, 0);
    }

    /** Every look with a rule, and the ones open by default, for the Moderation window. */
    public List<LookRule> all() {
        Map<String, LookRule> all = new LinkedHashMap<>();
        for (String id : kind.openByDefault) {
            all.put(id, rule(id));
        }
        all.putAll(rules);
        return List.copyOf(all.values());
    }

    /** The looks this player may use without a personal unlock: open ones, and those unlocked for their groups. */
    public List<String> openFor(UUID player) {
        List<String> open = new ArrayList<>();
        for (LookRule rule : all()) {
            if (!rule.locked() || rule.groups().stream().anyMatch(group -> LuckPermsCompat.inGroup(player, group))) {
                open.add(rule.id());
            }
        }
        return open;
    }

    /** Locked looks the player sees in the picker (not hidden, not theirs): id and price (0: not for sale). */
    public List<HubPayloads.LookOffer> offersFor(List<String> available) {
        List<HubPayloads.LookOffer> offers = new ArrayList<>();
        for (LookRule rule : rules.values()) {
            if (rule.locked() && !rule.hidden() && !available.contains(rule.id())) {
                offers.add(new HubPayloads.LookOffer(rule.id(), rule.price()));
            }
        }
        return offers;
    }

    private static void save(ServerPlayer admin, Kind kind, LookRule rule) {
        if (!LuckPermsCompat.hasPermission(admin, kind.permission) || rule.id().isBlank() || rule.id().length() > 128) {
            return;
        }
        LookRules rules = get(admin.server, kind);
        List<String> groups = rule.groups().stream().filter(g -> !g.isBlank()).distinct().limit(32).toList();
        rules.rules.put(rule.id(), new LookRule(rule.id(), rule.locked(), groups, rule.hidden(),
                Math.max(0, Math.min(MAX_PRICE, rule.price()))));
        rules.setDirty();
        EdenLD.LOGGER.info("{} set the {} rule of {}: locked {}, groups {}, hidden {}, price {}",
                admin.getGameProfile().getName(), kind.name().toLowerCase(java.util.Locale.ROOT), rule.id(),
                rule.locked(), groups, rule.hidden(), rule.price());
        // A theme opened or closed for everyone: online players' theme pickers follow at once.
        if (kind == Kind.THEME) {
            for (ServerPlayer online : admin.server.getPlayerList().getPlayers()) {
                HubServer.pushSettings(admin.server, online.getUUID());
            }
        }
    }

    private static void buy(ServerPlayer player, Kind kind, String id) {
        MinecraftServer server = player.server;
        LookRule rule = get(server, kind).rule(id);
        List<String> available = kind == Kind.THEME ? HubSettings.availableThemes(server, player.getUUID())
                : HubSettings.availableBackgrounds(server, player.getUUID());
        if (!rule.locked() || rule.hidden() || rule.price() <= 0 || available.contains(id)) {
            return;
        }
        String which = kind == Kind.THEME ? "theme" : "background";
        EconomyCompat.take(player, rule.price(), paid -> {
            if (!paid) {
                ServerPlayNetworking.send(player, new HubPayloads.HubNotice(
                        Component.translatable("message.eden_ld." + which + ".cant_afford", rule.price())));
                return;
            }
            if (kind == Kind.THEME) {
                HubSettings.setThemeUnlocked(server, player.getUUID(), id, true);
                HubSettings.selectTheme(server, player.getUUID(), id);
                HubServer.pushSettings(server, player.getUUID());
            } else {
                HubSettings.setBackgroundUnlocked(server, player.getUUID(), id, true);
                HubSettings.selectBackground(server, player.getUUID(), id);
                ProfileServer.send(player, player.getUUID());
            }
            EdenLD.LOGGER.info("{} bought the {} {} for {} LC", player.getGameProfile().getName(), which, id, rule.price());
            Wardrobe.send(player);
            ServerPlayNetworking.send(player, new HubPayloads.HubNotice(
                    Component.translatable("message.eden_ld." + which + ".bought"), true));
        });
    }

    private static LookRules load(Kind kind, CompoundTag tag) {
        LookRules rules = new LookRules(kind);
        ListTag list = tag.getList("rules", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            List<String> groups = new ArrayList<>();
            ListTag groupList = entry.getList("groups", Tag.TAG_STRING);
            for (int g = 0; g < groupList.size(); g++) {
                groups.add(groupList.getString(g));
            }
            String id = entry.getString("id");
            rules.rules.put(id, new LookRule(id, entry.getBoolean("locked"), groups,
                    entry.getBoolean("hidden"), entry.getLong("price")));
        }
        return rules;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (LookRule rule : rules.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", rule.id());
            entry.putBoolean("locked", rule.locked());
            ListTag groups = new ListTag();
            rule.groups().forEach(g -> groups.add(StringTag.valueOf(g)));
            entry.put("groups", groups);
            entry.putBoolean("hidden", rule.hidden());
            entry.putLong("price", rule.price());
            list.add(entry);
        }
        tag.put("rules", list);
        return tag;
    }
}
