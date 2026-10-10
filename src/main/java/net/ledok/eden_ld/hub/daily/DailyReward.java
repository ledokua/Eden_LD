package net.ledok.eden_ld.hub.daily;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * What a daily quest (or the all-quests bonus) pays out. {@code text} is a note for rewards
 * the tab can't show by itself (commands); it may be a translate component resolved through
 * the server lang tables.
 */
public record DailyReward(List<ItemStack> items, int xp, long lc, List<String> commands,
                          Optional<Component> text) {

    public static final DailyReward NONE = new DailyReward(List.of(), 0, 0, List.of(), Optional.empty());

    public static final Codec<DailyReward> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(DailyReward::items),
            Codec.INT.optionalFieldOf("xp", 0).forGetter(DailyReward::xp),
            Codec.LONG.optionalFieldOf("lc", 0L).forGetter(DailyReward::lc),
            Codec.STRING.listOf().optionalFieldOf("commands", List.of()).forGetter(DailyReward::commands),
            ComponentSerialization.CODEC.optionalFieldOf("text").forGetter(DailyReward::text)
    ).apply(instance, DailyReward::new));

    /** The display form for one player: note localized, LC hidden when Economy_LD can't pay it. */
    public net.ledok.eden_ld.hub.HubPayloads.RewardView view(ServerPlayer player) {
        return new net.ledok.eden_ld.hub.HubPayloads.RewardView(items, xp,
                EconomyCompat.available() ? lc : 0,
                text.map(note -> net.ledok.eden_ld.hub.lang.ServerLang.localize(player, note))
                        .orElse(Component.empty()));
    }

    /** Items into the inventory (dropped when full), XP, LC, then commands as the server. */
    public void grant(ServerPlayer player) {
        for (ItemStack stack : items) {
            ItemStack copy = stack.copy();
            if (!player.getInventory().add(copy) && !copy.isEmpty()) {
                player.drop(copy, false);
            }
        }
        if (xp > 0) {
            player.giveExperiencePoints(xp);
        }
        EconomyCompat.give(player, lc);
        MinecraftServer server = player.getServer();
        if (server != null) {
            for (String command : commands) {
                try {
                    server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack().withSuppressedOutput(),
                            command.replace("{player}", player.getGameProfile().getName()));
                } catch (Exception e) {
                    EdenLD.LOGGER.error("[Dailies] Reward command failed: {}", command, e);
                }
            }
        }
    }
}
