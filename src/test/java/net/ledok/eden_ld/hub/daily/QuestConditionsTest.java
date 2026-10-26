package net.ledok.eden_ld.hub.daily;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may draw a daily (QuestConditions): the loot stage range and the pool's fallback when too few are left. */
class QuestConditionsTest {

    @Test
    void stageRange() {
        QuestConditions.StageRange any = QuestConditions.StageRange.ANY;
        assertTrue(any.atLeastMin(1) && any.belowMax(1) && any.belowMax(1000), "no range = everyone");

        QuestConditions.StageRange nether = new QuestConditions.StageRange(20, 0);
        assertFalse(nether.atLeastMin(19));
        assertTrue(nether.atLeastMin(20));
        assertTrue(nether.belowMax(500), "max 0 = no top");

        QuestConditions.StageRange easy = new QuestConditions.StageRange(0, 29);
        assertTrue(easy.belowMax(29));
        assertFalse(easy.belowMax(30));
    }

    /** A quest as the pool sees it: a stage range only. */
    private record Quest(String id, QuestConditions.StageRange range) {
    }

    private static List<String> poolFor(List<Quest> all, int stage, int needed) {
        Predicate<Quest> strict = quest -> quest.range().atLeastMin(stage) && quest.range().belowMax(stage);
        Predicate<Quest> relaxed = quest -> quest.range().atLeastMin(stage);
        return QuestConditions.pool(all, strict, relaxed, needed).stream().map(Quest::id).toList();
    }

    @Test
    void highStagesLeaveTheEasyQuestsUnlessTooFewAreLeft() {
        List<Quest> all = List.of(
                new Quest("zombies", new QuestConditions.StageRange(0, 29)),
                new Quest("skeletons", new QuestConditions.StageRange(0, 29)),
                new Quest("witches", QuestConditions.StageRange.ANY),
                new Quest("dungeon", new QuestConditions.StageRange(5, 0)),
                new Quest("blazes", new QuestConditions.StageRange(20, 0)));

        assertEquals(List.of("zombies", "skeletons", "witches"), poolFor(all, 1, 3), "stage 1: no dungeon or Nether yet");
        assertEquals(List.of("witches", "dungeon", "blazes"), poolFor(all, 30, 3), "stage 30: the easy ones drop out");
        assertEquals(List.of("zombies", "skeletons", "witches", "dungeon", "blazes"), poolFor(all, 30, 4),
                "too few left: the stage tops are dropped, the minimums still hold");
        assertEquals(List.of("zombies", "skeletons", "witches"), poolFor(all, 1, 6),
                "the fallback never lets a quest in below its minimum");
    }
}
