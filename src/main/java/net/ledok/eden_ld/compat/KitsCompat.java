package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Kits_LD: the kits a player sees and claiming one, for the hub's Kits tab ({@code docs/PLAN-kits.md}). Kits_LD's
 * own rules decide everything — who sees a kit ({@code hidden_when_locked}), who may claim it (its permission), the
 * cooldown or one-time claim — and a claim goes through its one claim path ({@code KitService.tryClaim}: checks, items
 * into their saved slots, the kit's commands). The only class touching Kits_LD types is {@link Api}.
 */
public final class KitsCompat {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("kits_ld");

    private KitsCompat() {
    }

    /** Whether Kits_LD is installed and its kits are loaded (from its server start on). */
    public static boolean ready() {
        return LOADED && Api.ready();
    }

    /** The kits this player sees, in Kits_LD's order (by name); empty without Kits_LD. */
    public static List<HubPayloads.KitView> kits(ServerPlayer player) {
        return ready() ? Api.kits(player) : List.of();
    }

    /** What claiming said: {@code ok} when the kit was handed out. */
    public record Claim(boolean ok, Component message) {
    }

    /** Claims the kit for the player (checked again here: a stale tab can't get around anything). */
    public static Claim claim(ServerPlayer player, String id) {
        if (!ready()) {
            return new Claim(false, Component.translatable("message.eden_ld.kits.unavailable"));
        }
        return Api.claim(player, id);
    }

    private static final class Api {
        static boolean ready() {
            return net.ledok.kitsld.KitsLd.manager() != null;
        }

        static List<HubPayloads.KitView> kits(ServerPlayer player) {
            net.ledok.kitsld.kit.KitManager manager = net.ledok.kitsld.KitsLd.manager();
            net.ledok.kitsld.kit.PlayerKitState state = net.ledok.kitsld.kit.PlayerKitState.get(player.server);
            List<HubPayloads.KitView> views = new ArrayList<>();
            manager.kits().forEach((id, kit) -> {
                if (!net.ledok.kitsld.kit.KitService.canSee(player, id, kit)) {
                    return;
                }
                boolean locked = !net.ledok.kitsld.KitPerms.canClaim(player, id);
                long remaining = locked ? 0 : net.ledok.kitsld.kit.KitService.cooldownRemaining(state, player, id, kit);
                views.add(new HubPayloads.KitView(id, kit.displayLabel(id), new ItemStack(kit.icon()), remaining, locked,
                        kit.lockMessage().map(net.ledok.kitsld.kit.Kit::legacyColors).orElse(""),
                        kit.cooldownSeconds(), items(kit)));
            });
            return views;
        }

        /** The contents by where they land (never the kit's commands: those are for staff). */
        private static List<HubPayloads.KitItem> items(net.ledok.kitsld.kit.Kit kit) {
            List<HubPayloads.KitItem> items = new ArrayList<>();
            for (net.ledok.kitsld.kit.Kit.SlotStack entry : kit.armor()) {
                items.add(new HubPayloads.KitItem(HubPayloads.KitItem.ARMOR, entry.slot(), "", entry.stack().copy()));
            }
            kit.offhand().ifPresent(stack ->
                    items.add(new HubPayloads.KitItem(HubPayloads.KitItem.OFFHAND, 0, "", stack.copy())));
            for (net.ledok.kitsld.kit.Kit.SlotStack entry : kit.main()) {
                items.add(new HubPayloads.KitItem(HubPayloads.KitItem.MAIN, entry.slot(), "", entry.stack().copy()));
            }
            for (net.ledok.kitsld.kit.Kit.TrinketStack entry : kit.trinkets()) {
                items.add(new HubPayloads.KitItem(HubPayloads.KitItem.TRINKET, 0, entry.slot(), entry.stack().copy()));
            }
            return items;
        }

        static Claim claim(ServerPlayer player, String id) {
            net.ledok.kitsld.kit.Kit kit = net.ledok.kitsld.KitsLd.manager().kit(id);
            if (kit == null || !net.ledok.kitsld.kit.KitService.canSee(player, id, kit)) {
                return new Claim(false, Component.translatable("message.eden_ld.kits.unknown"));
            }
            Component name = kit.displayLabel(id);
            // Said in the player's language first; Kits_LD's own (English) check below still has the last word.
            if (!net.ledok.kitsld.KitPerms.canClaim(player, id)) {
                return new Claim(false, kit.lockMessage().isPresent()
                        ? Component.literal(net.ledok.kitsld.kit.Kit.legacyColors(kit.lockMessage().get()))
                        : Component.translatable("message.eden_ld.kits.locked", name));
            }
            long remaining = net.ledok.kitsld.kit.KitService.cooldownRemaining(
                    net.ledok.kitsld.kit.PlayerKitState.get(player.server), player, id, kit);
            if (remaining == -1L) {
                return new Claim(false, Component.translatable("message.eden_ld.kits.once", name));
            }
            if (remaining > 0) {
                return new Claim(false, Component.translatable("message.eden_ld.kits.cooldown", name,
                        net.ledok.kitsld.kit.KitService.formatDuration(remaining)));
            }
            net.ledok.kitsld.kit.KitService.ClaimResult result =
                    net.ledok.kitsld.kit.KitService.tryClaim(player.server, player, id);
            return result.success()
                    ? new Claim(true, Component.translatable("message.eden_ld.kits.claimed", name))
                    : new Claim(false, result.message());
        }
    }
}
