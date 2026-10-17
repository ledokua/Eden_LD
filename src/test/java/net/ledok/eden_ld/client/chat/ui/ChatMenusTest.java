package net.ledok.eden_ld.client.chat.ui;

import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.widget.ContextMenu;
import net.ledok.vectorlib.client.canvas.widget.WidgetStyle;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The new look's menus: Eden's mirror of ContextMenu's private sizes, and where a message's menu opens. */
class ChatMenusTest {

    private static final ContextMenu.Entry HEADER = new ContextMenu.Header(Component.literal("Title"), 0xFFFFFFFF);
    private static final ContextMenu.Entry ITEM = new ContextMenu.Item(Component.literal("Row"), () -> {
    }, true);
    private static final ContextMenu.Entry OFF = new ContextMenu.Item(Component.literal("Greyed"), () -> {
    }, false);
    private static final ContextMenu.Entry SUBMENU = new ContextMenu.Submenu(Component.literal("More"),
            ContextMenu::create, true);
    private static final ContextMenu.Entry CHECK = new ContextMenu.Check(Component.literal("Choice"), true, () -> {
    }, true);
    private static final ContextMenu.Entry SEPARATOR = new ContextMenu.Separator();

    private static float vectorLib(String constant) throws ReflectiveOperationException {
        Field field = ContextMenu.class.getDeclaredField(constant);
        field.setAccessible(true);
        return field.getFloat(null);
    }

    /** ContextMenu's own height for these rows (its private size(), at a fixed width so no font is needed). */
    private static float vectorLibHeight(List<ContextMenu.Entry> entries) throws ReflectiveOperationException {
        ContextMenu menu = ChatMenus.build(entries).width(120);
        Method size = ContextMenu.class.getDeclaredMethod("size", VectorCanvas.class, WidgetStyle.class);
        size.setAccessible(true);
        float[] result = (float[]) size.invoke(menu, VectorCanvas.create(1000, 1000), WidgetStyle.MODERN);
        return result[1];
    }

    @SuppressWarnings("unchecked")
    private static List<ContextMenu.Entry> entriesOf(ContextMenu menu) throws ReflectiveOperationException {
        Field field = ContextMenu.class.getDeclaredField("entries");
        field.setAccessible(true);
        return (List<ContextMenu.Entry>) field.get(menu);
    }

    @Test
    void theMirrorMatchesVectorLibsConstants() throws ReflectiveOperationException {
        assertEquals(vectorLib("ROW_H"), ChatMenus.ROW_H, "row");
        assertEquals(vectorLib("SEPARATOR_H"), ChatMenus.SEPARATOR_H, "separator");
        assertEquals(vectorLib("HEADER_H"), ChatMenus.HEADER_H, "header");
    }

    @Test
    void heightIsContextMenusOwnForEveryKindOfRow() throws ReflectiveOperationException {
        List<List<ContextMenu.Entry>> samples = List.of(
                List.of(),
                List.of(ITEM),
                List.of(HEADER, SUBMENU, SUBMENU, OFF, SEPARATOR, ITEM),
                List.of(HEADER, HEADER, ITEM, SUBMENU, ITEM, SEPARATOR, ITEM),
                List.of(CHECK, CHECK, CHECK),
                List.of(HEADER, ITEM, ITEM, ITEM, ITEM, ITEM));
        for (List<ContextMenu.Entry> sample : samples) {
            assertEquals(vectorLibHeight(sample), ChatMenus.height(sample), "rows " + sample.size());
        }
    }

    @Test
    void heightOfTheChatsMenus() {
        assertEquals(4, ChatMenus.height(List.of()), "the plate's inset only");
        // A channel's menu, not muted: its name, Mute ▸, Notifications ▸, Mark as read, a line, Chat settings.
        assertEquals(77, ChatMenus.height(List.of(HEADER, SUBMENU, SUBMENU, ITEM, SEPARATOR, ITEM)));
        // Muted: its name, the muted line, Unmute, Notifications ▸, Mark as read, a line, Chat settings.
        assertEquals(89, ChatMenus.height(List.of(HEADER, HEADER, ITEM, SUBMENU, ITEM, SEPARATOR, ITEM)));
        // Someone else's message, for staff: the title and five rows.
        assertEquals(86, ChatMenus.height(List.of(HEADER, ITEM, ITEM, ITEM, ITEM, ITEM)));
    }

    @Test
    void aMessagesMenuEndsAboveThePointerWhenItFits() {
        // Its bottom 2 above the pointer: rising 4 into place, it starts with the pointer in its bottom inset.
        assertEquals(112, ChatMenus.top(200, 86));
        assertEquals(0, ChatMenus.top(88, 86), "exactly fits");
        // No room above: at the pointer (ContextMenu then clamps it into the canvas).
        assertEquals(87, ChatMenus.top(87, 86));
        assertEquals(10, ChatMenus.top(10, 86));
    }

    @Test
    void buildKeepsTheRowsInOrder() throws ReflectiveOperationException {
        List<ContextMenu.Entry> rows = List.of(HEADER, SUBMENU, CHECK, SEPARATOR, ITEM);
        List<ContextMenu.Entry> built = entriesOf(ChatMenus.build(rows));
        assertEquals(rows.size(), built.size());
        for (int i = 0; i < rows.size(); i++) {
            assertSame(rows.get(i), built.get(i));
        }
    }
}
