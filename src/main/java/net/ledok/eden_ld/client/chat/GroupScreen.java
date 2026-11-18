package net.ledok.eden_ld.client.chat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.chat.dm.DmRules;
import net.ledok.eden_ld.client.ScrollFocus;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.Theme;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.Toggle;
import net.ledok.vectorlib.client.presentation.CanvasScreen;
import net.ledok.vectorlib.client.presentation.Placement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The small form for group chats (1.3.94), opened from a chat tab's menu: New group… (a name and the online players to
 * add) or a group's Rename… (its creator). What it sends the server checks again ({@code chat.dm.DirectMessages}):
 * names, the member cap, who accepts being added. Closing it goes back to the chat.
 */
public final class GroupScreen extends CanvasScreen {

    /** A group's members at most (the server's cap; the menu greys Invite ▸ at it). */
    public static final int MAX_MEMBERS = 10;
    /** A group name's length at most (the server cuts it too). */
    public static final int NAME_MAX = 24;
    /** Online players listed at most. */
    private static final int LIST_MAX = 60;

    private static final float WIDTH = 220;
    private static final float LIST_HEIGHT = 120;
    private static final int BG = 0xF01A1D24;
    private static final int LINE = 0xFF3A3F4A;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int DIM = 0xFF9AA0A8;

    @Nullable
    private final String renaming;
    private String name;
    private final Set<UUID> picked = new LinkedHashSet<>();

    private GroupScreen(@Nullable String renaming, String name) {
        super(Component.translatable(renaming == null ? "chat.eden_ld.dm.group.new_title"
                : "chat.eden_ld.dm.group.rename_title"), VectorCanvas.create(WIDTH, height(renaming == null)),
                Placement.Screen.center());
        this.renaming = renaming;
        this.name = name;
        canvas().theme(Theme.MODERN);
        float height = height(renaming == null);
        Flex root = Flex.column().gap(4).padding(Insets.of(6, 8, 6, 8));
        root.sizing(Sizing.fixed(WIDTH), Sizing.fixed(height));
        root.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(BG).stroke(Stroke.of(LINE, 1)));
        root.item(TextNode.of(getTitle()).color(TEXT).shadow(false));

        float inner = WIDTH - 16;
        Button submit = new Button(70, Component.translatable(renaming == null ? "chat.eden_ld.dm.group.create"
                : "chat.eden_ld.dm.group.save"), this::done);
        TextField field = new TextField(inner, name, value -> {
            this.name = value;
            updateSubmit(submit);
        });
        updateSubmit(submit);
        field.size(inner, 16);
        field.hint(Component.translatable("chat.eden_ld.dm.group.name_hint").getString());
        field.maxLength(NAME_MAX);
        field.onSubmit(this::done);
        root.item(field);

        if (renaming == null) {
            Label count = Label.of(inner, playerCount()).color(DIM);
            root.item(count);
            Map<UUID, Toggle> choices = new LinkedHashMap<>();
            Flex list = Flex.column().gap(2);
            List<PlayerInfo> online = online(Set.of());
            if (online.isEmpty()) {
                list.item(TextNode.of(Component.translatable("chat.eden_ld.dm.group.nobody")).color(DIM).shadow(false));
            }
            for (PlayerInfo info : online) {
                UUID id = info.getProfile().getId();
                Toggle choice = new Toggle(inner - 12, Component.literal(info.getProfile().getName()), false, on -> {
                    if (on) {
                        picked.add(id);
                    } else {
                        picked.remove(id);
                    }
                    count.text(playerCount());
                    boolean atLimit = picked.size() >= MAX_MEMBERS - 1;
                    choices.forEach((candidate, toggle) -> toggle.enabled(!atLimit || picked.contains(candidate)));
                });
                choices.put(id, choice);
                list.item(choice);
            }
            root.item(new ScrollPanel(inner, LIST_HEIGHT, list));
        }

        Flex buttons = Flex.row().gap(4).alignItems(Align.CENTER);
        buttons.sizing(Sizing.fill(), Sizing.content());
        Flex spacer = Flex.row();
        spacer.sizing(Sizing.expand(), Sizing.fixed(1));
        buttons.item(spacer);
        buttons.item(new Button(70, Component.translatable("gui.cancel"), this::onClose));
        buttons.item(submit);
        root.item(buttons);
        canvas().add(root);
        root.layoutIn(WIDTH, height);
        canvas().focus(field);
    }

    private Component playerCount() {
        return Component.translatable("chat.eden_ld.dm.group.players_count", picked.size(), MAX_MEMBERS - 1);
    }

    private void updateSubmit(Button button) {
        boolean valid = DmRules.cleanName(name) != null;
        button.enabled(valid);
        if (!valid) {
            button.tooltip(Component.translatable("chat.eden_ld.dm.group.name_required"));
        } else {
            button.tooltip();
        }
    }

    private static float height(boolean create) {
        return create ? 12 + 12 + 4 + 16 + 4 + 12 + 4 + LIST_HEIGHT + 4 + 20 : 12 + 12 + 4 + 16 + 4 + 20;
    }

    /** New group…: the form for a new group chat. */
    public static void create() {
        if (Conversations.available()) {
            Minecraft.getInstance().setScreen(new GroupScreen(null, ""));
        }
    }

    /** Rename…: the group's name to change. */
    public static void rename(String conversation, String name) {
        Minecraft.getInstance().setScreen(new GroupScreen(conversation, name));
    }

    private void done() {
        String trimmed = DmRules.cleanName(name);
        if (trimmed == null || !ClientPlayNetworking.canSend(HubPayloads.GroupCreate.TYPE)) {
            return;
        }
        if (renaming == null) {
            ClientPlayNetworking.send(new HubPayloads.GroupCreate(trimmed, List.copyOf(picked)));
        } else {
            Conversations.action(renaming, HubPayloads.ConvAction.RENAME, trimmed);
        }
        onClose();
    }

    /** Back to the chat, where the group's tab shows. */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(new ChatScreen(""));
    }

    @Override
    protected void init() {
        super.init();
        float fit = Math.min(1f, Math.min((width - 8f) / WIDTH, (height - 8f) / height(renaming == null)));
        placement(Placement.Screen.center().scale(fit));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean used = super.keyPressed(keyCode, scanCode, modifiers);
        if (used && keyCode == GLFW.GLFW_KEY_TAB) {
            ScrollFocus.reveal(input().focusedNode());
        }
        return used;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The online players but the player and {@code except}, by name. */
    private static List<PlayerInfo> online(Set<UUID> except) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        List<PlayerInfo> players = new ArrayList<>();
        if (connection == null || minecraft.player == null) {
            return players;
        }
        UUID self = minecraft.player.getUUID();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            UUID id = info.getProfile().getId();
            if (!id.equals(self) && !except.contains(id)) {
                players.add(info);
            }
        }
        players.sort((a, b) -> a.getProfile().getName().compareToIgnoreCase(b.getProfile().getName()));
        return players.size() > LIST_MAX ? new ArrayList<>(players.subList(0, LIST_MAX)) : players;
    }

    /** The names of the online players but the player and {@code except} (a group's Invite ▸). */
    public static List<String> onlineNames(Set<UUID> except) {
        List<String> names = new ArrayList<>();
        for (PlayerInfo info : online(except)) {
            names.add(info.getProfile().getName());
        }
        return names;
    }
}
