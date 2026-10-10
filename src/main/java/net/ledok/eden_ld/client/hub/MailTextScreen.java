package net.ledok.eden_ld.client.hub;

import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.Theme;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.presentation.CanvasScreen;
import net.ledok.vectorlib.client.presentation.Placement;
import net.ledok.vectorlib.client.text.Placeholder;
import net.ledok.vectorlib.client.text.RichTextEditor;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static net.ledok.eden_ld.client.hub.ModerationScreen.BG;
import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.LINE;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;

/**
 * One language of a text staff write — a mail (title and text) or the news (text only) — on a
 * screen of its own: the Markdown source (VectorLib's editor, with the server's placeholders) on
 * the left, and on the right the text exactly as players will see it. Edits go back as they're
 * typed; Back (or Esc) returns to the Moderation window.
 */
final class MailTextScreen extends CanvasScreen {

    /** What players will see for this title and text, {@code width} wide, filling the preview column. */
    interface Preview {
        Flex build(String title, String body, int width);
    }

    private static final float HEIGHT = 300;
    private static final float SOURCE = 270;

    /** The window: the source column, then the preview at the real panel's width. */
    private final float windowWidth;
    private final float previewWidth;
    private final Runnable onBack;
    private final Runnable autosave;
    private final Preview preview;
    private final Flex root;
    private final Flex previewBox;
    private String title;
    private String body;

    /**
     * @param heading what's being edited ("Mail text — Ukrainian")
     * @param title   the title, or null for a text without one (the news)
     * @param previewWidth the real panel's width (the Mail tab's open mail, the Home news), so the
     *                     preview wraps exactly as players will see it
     * @param preview what players will see
     * @param onEdit  every change (title, text)
     * @param autosave the Moderation window's autosave, ticked while this screen is open
     * @param onBack  back to the Moderation window
     */
    MailTextScreen(Component heading, @Nullable String title, String body, Supplier<List<Placeholder>> placeholders,
                   float previewWidth, Preview preview, BiConsumer<String, String> onEdit, Runnable autosave,
                   Runnable onBack) {
        super(Component.translatable("gui.eden_ld.moderation.text_title"),
                VectorCanvas.create(16 + SOURCE + 8 + previewWidth, HEIGHT), Placement.Screen.center());
        this.windowWidth = 16 + SOURCE + 8 + previewWidth;
        this.previewWidth = previewWidth;
        this.title = title == null ? "" : title;
        this.body = body;
        this.preview = preview;
        this.autosave = autosave;
        this.onBack = onBack;
        canvas().theme(Theme.MODERN);

        root = Flex.column().gap(4).padding(Insets.of(6, 8, 6, 8));
        root.sizing(Sizing.fixed(windowWidth), Sizing.fixed(HEIGHT));
        root.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(BG).stroke(Stroke.of(LINE, 1)));

        Flex header = Flex.row().gap(6).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.fixed(16));
        header.item(TextNode.of(heading).color(TEXT).shadow(false).maxWidth((int) (windowWidth - 110)).ellipsis(true));
        Flex spacer = Flex.row();
        spacer.sizing(Sizing.expand(), Sizing.fixed(1));
        header.item(spacer);
        header.item(ModerationScreen.small("gui.eden_ld.moderation.back", 70, this::onClose));
        root.item(header);

        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.expand());
        Flex source = Flex.column().gap(4);
        source.sizing(Sizing.fixed(SOURCE), Sizing.fill());
        float editorHeight = HEIGHT - 12 - 16 - 4;
        if (title != null) {
            TextField titleField = new TextField(SOURCE, title, value -> {
                this.title = value;
                onEdit.accept(this.title, this.body);
                refreshPreview();
            });
            titleField.size(SOURCE, 16);
            titleField.hint(Component.translatable("gui.eden_ld.moderation.title_hint").getString());
            titleField.maxLength(128);
            source.item(titleField);
            editorHeight -= 16 + 4;
        }
        RichTextEditor editor = new RichTextEditor(SOURCE, editorHeight, body)
                .sizes(true)
                .placeholders(placeholders)
                .onChange(value -> {
                    this.body = value;
                    onEdit.accept(this.title, this.body);
                    refreshPreview();
                });
        editor.hint(Component.translatable("gui.eden_ld.moderation.body_hint").getString());
        editor.maxLength(8000);
        editor.sizing(Sizing.fixed(SOURCE), Sizing.fixed(editorHeight));
        source.item(editor);
        columns.item(source);

        Flex right = Flex.column().gap(4);
        right.sizing(Sizing.expand(), Sizing.fill());
        right.item(TextNode.of(Component.translatable("gui.eden_ld.moderation.preview_caption")).color(DIM).shadow(false));
        previewBox = Flex.column();
        previewBox.sizing(Sizing.fill(), Sizing.expand());
        right.item(previewBox);
        columns.item(right);
        root.item(columns);
        canvas().add(root);
        root.layoutIn(windowWidth, HEIGHT);
        refreshPreview();
        root.layoutIn(windowWidth, HEIGHT);
        canvas().focus(editor.area());
    }


    private void refreshPreview() {
        previewBox.clear();
        previewBox.item(preview.build(title, body, (int) previewWidth));
        if (root != null && root.canvas() != null) {
            root.layoutIn(windowWidth, HEIGHT);
        }
    }

    @Override
    protected void init() {
        super.init();
        float fit = Math.min(1f, Math.min((width - 8f) / windowWidth, (height - 8f) / HEIGHT));
        placement(Placement.Screen.center().scale(fit));
    }

    @Override
    protected void onCanvasFrame(float partialTick) {
        autosave.run();
    }

    @Override
    public void onClose() {
        onBack.run();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
