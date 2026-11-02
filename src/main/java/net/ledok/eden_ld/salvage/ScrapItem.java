package net.ledok.eden_ld.salvage;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** Weapon or armor scrap of one tier: what a press leaves of loot gear in that tier's power-level band. */
public class ScrapItem extends Item {

    private final int tier;

    public ScrapItem(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public int tier() {
        return tier;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        int max = PowerLevels.bandMax(tier);
        Component line = max == PowerLevels.OPEN
                ? Component.translatable("item.eden_ld.scrap.tooltip_open", PowerLevels.bandMin(tier))
                : Component.translatable("item.eden_ld.scrap.tooltip", PowerLevels.bandMin(tier), max);
        tooltip.add(line.copy().withStyle(ChatFormatting.GRAY));
    }
}
