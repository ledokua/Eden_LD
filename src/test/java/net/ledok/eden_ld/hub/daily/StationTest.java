package net.ledok.eden_ld.hub.daily;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Craft quests' {@code station}: the open menu's type names the block things are made at. */
class StationTest {

    private static ResourceLocation id(String text) {
        return ResourceLocation.parse(text);
    }

    @Test
    void menusNameTheirBlock() {
        assertEquals(QuestConditions.CRAFTING_TABLE, QuestConditions.stationOf(id("minecraft:crafting")),
                "the crafting menu is the crafting table's");
        assertEquals(id("minecraft:smoker"), QuestConditions.stationOf(id("minecraft:smoker")));
        assertEquals(id("farmersdelight:cooking_pot"), QuestConditions.stationOf(id("farmersdelight:cooking_pot")));
        assertNull(QuestConditions.stationOf(null), "a menu without a type is no station");
    }
}
