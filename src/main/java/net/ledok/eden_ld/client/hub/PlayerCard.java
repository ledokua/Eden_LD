package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.profile.ProfileBackgrounds;
import net.ledok.eden_ld.hub.HubPayloads.FriendEntry;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.CanvasRenderContext;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.ContextMenu;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A player on the Friends tab (a friend, or someone on this server), Steam-style: the face flush at
 * the left, a status stripe beside it (green on this server, blue online elsewhere, grey offline),
 * the name and a status line in the status color — and no buttons. Always the theme's card (one
 * palette for everyone); their profile picture shows in the hover card. A click opens their
 * profile; right-click holds the rest (add friend, invite to party, ignore, remove friend…); hovering
 * shows a mini profile card next to it. An ignored player's name is marked and dimmed.
 */
final class PlayerCard extends Widget {

    static final float HEIGHT = 24;
    private static final float FACE = 24;
    private static final float STRIPE = 2;
    private static final float HOVER_W = 160;

    /** Status colors on dark cards (Steam's) and on light themes (darker, to read on paper). */
    private static final int HERE_DARK = 0xFF8FD14F;
    private static final int HERE_LIGHT = 0xFF3B7413;
    private static final int ONLINE_DARK = 0xFF57CBDE;
    private static final int ONLINE_LIGHT = 0xFF1C6A86;

    private final HubScreen screen;
    private final java.util.UUID uuid;
    private final String name;
    /** A {@link FriendEntry} presence: the stripe and text color. */
    private final int presence;
    private final String background;
    private final boolean profile;
    private final Component status;
    private final Component fullStatus;
    private final boolean alt;
    private final List<ContextMenu.Item> menu = new ArrayList<>();
    private CanvasNode hoverCard;

    /**
     * {@code status}: the card's second line; {@code fullStatus}: the hover card's (may say more);
     * {@code profile}: their privacy lets this player open their page.
     */
    PlayerCard(HubScreen screen, java.util.UUID uuid, String name, int presence, String background, boolean profile,
               Component status, Component fullStatus, float width, boolean alt) {
        super(width, HEIGHT);
        this.screen = screen;
        this.uuid = uuid;
        this.name = name;
        this.presence = presence;
        this.background = background;
        this.profile = profile;
        this.status = status;
        this.fullStatus = fullStatus;
        this.alt = alt;
        sizing(Sizing.fixed(width), Sizing.fixed(HEIGHT));
    }

    /** A right-click action (after "Open profile"). */
    PlayerCard action(Component label, Runnable action, boolean enabled) {
        menu.add(new ContextMenu.Item(label, action, enabled));
        return this;
    }

    /** The bright status color: the stripe, and the hover card. */
    static int stripeColor(int presence) {
        return switch (presence) {
            case FriendEntry.ON_SERVER -> HERE_DARK;
            case FriendEntry.ONLINE -> ONLINE_DARK;
            default -> 0xFF7D7D7D;
        };
    }

    /** Text in the status color, readable on the theme's own cards. */
    private static int textColor(int presence) {
        boolean light = luminance(EdenParchment.ROW_BG) > 0.5f;
        return switch (presence) {
            case FriendEntry.ON_SERVER -> light ? HERE_LIGHT : HERE_DARK;
            case FriendEntry.ONLINE -> light ? ONLINE_LIGHT : ONLINE_DARK;
            default -> EdenParchment.INK_DIM;
        };
    }

    private static float luminance(int argb) {
        return (0.2126f * ((argb >> 16) & 0xFF) + 0.7152f * ((argb >> 8) & 0xFF) + 0.0722f * (argb & 0xFF)) / 255f;
    }

    @Override
    protected void rebuild() {
        add(Shapes.rect(0, 0, width, height).fill(alt ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG));
        if (hovered) {
            add(Shapes.rect(0, 0, width, height).fill(0x1CFFFFFF));
        }
        add(PlayerFace.of(uuid, name, FACE).at(0, 0));
        add(Shapes.rect(FACE, 0, STRIPE, height).fill(stripeColor(presence)));
        add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1)
                .stroke(Stroke.of(hovered ? EdenParchment.ACCENT : EdenParchment.HAIRLINE, 1)));

        float textX = FACE + STRIPE + 4;
        int textWidth = (int) (width - textX - 3);
        boolean ignored = HubState.ignores(uuid);
        int nameColor = ignored ? EdenParchment.INK_DIM
                : presence == FriendEntry.OFFLINE ? EdenParchment.INK_MID : textColor(presence);
        int statusColor = textColor(presence);
        boolean shadow = EdenParchment.TEXT_SHADOW;
        add(TextNode.of(Component.literal((ignored ? Ignoring.MARK : "") + name)).at(textX, 3).maxWidth(textWidth)
                .ellipsis(true).color(nameColor).shadow(shadow));
        add(TextNode.of(status).at(textX, 13).maxWidth(textWidth).ellipsis(true)
                .color(statusColor).shadow(shadow));
    }

    @Override
    public void onHoverChanged(boolean hovered) {
        super.onHoverChanged(hovered);
        if (hovered) {
            hoverCard = hoverCard();
            screen.showHoverCard(hoverCard);
        } else if (hoverCard != null) {
            screen.hideHoverCard(hoverCard);
            hoverCard = null;
        }
    }

    @Override
    public boolean onMouseDown(float x, float y, int button) {
        if (button == 0) {
            if (!profile) {
                return false;
            }
            UiSounds.click();
            screen.openProfile(uuid);
            return true;
        }
        if (button == 1) {
            ContextMenu context = ContextMenu.create();
            context.item(new ContextMenu.Item(Component.translatable("gui.eden_ld.hub.profile.open"),
                    () -> screen.openProfile(uuid), profile));
            menu.forEach(context::item);
            float[] at = localToRoot(x, y);
            context.open(screen.canvas(), at[0], at[1], this);
            return true;
        }
        return false;
    }

    @Override
    public Cursor cursor() {
        return profile ? Cursor.HAND : super.cursor();
    }

    // ---------------------------------------------------------------- the hover card

    /**
     * Their mini profile beside the card (right, or left when there's no room): their profile
     * picture with the face and name, then what they're doing, then the click hints. Dark like a
     * Minecraft tooltip, the same on every theme.
     */
    private CanvasNode hoverCard() {
        float header = 40;
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        // Ignored: said under what they're doing.
        Component fullStatus = HubState.ignores(uuid) ? this.fullStatus.copy().append("\n")
                .append(Component.translatable("gui.eden_ld.hub.ignore.card").withColor(0xE08A7A)) : this.fullStatus;
        List<net.minecraft.util.FormattedCharSequence> detail = font.split(fullStatus, (int) HOVER_W - 12);
        Component hint = Component.translatable(profile ? "gui.eden_ld.hub.player_link.hint"
                : "gui.eden_ld.hub.friends.private_menu_hint");
        List<net.minecraft.util.FormattedCharSequence> hints = font.split(hint, (int) HOVER_W - 12);
        float height = header + 6 + detail.size() * 10 + 4 + hints.size() * 10 + 5;

        float[] at = localToRoot(0, 0);
        float x = at[0] + width + 4;
        if (x + HOVER_W > 440 - 2) {
            x = at[0] - HOVER_W - 4;
        }
        float y = Math.max(2, Math.min(at[1], 256 - height - 2));

        CanvasGroup card = new HoverCard(this);
        card.pickable(false);
        card.add(Shapes.rect(0, 0, HOVER_W, height).fill(0xF0181A20));
        if (ProfileBackgrounds.shown(background)) {
            card.add(ProfileTab.picture(background, ProfileTab.Surface.CARD)
                    .apply(new Rect(0, 0, HOVER_W, header)));
        } else {
            card.add(Shapes.rect(0, 0, HOVER_W, header).fill(0xFF2A2E38));
        }
        int stripe = stripeColor(presence);
        card.add(Shapes.rect(5.5f, 5.5f, 29, 29).stroke(Stroke.of(stripe, 1)));
        card.add(PlayerFace.of(uuid, name, 28).at(6, 6));
        // A dark plate under the name and status: a bright picture must not swallow white text.
        Component presenceLabel = Component.translatable("gui.eden_ld.hub.friends.presence." + presence);
        float plateWidth = Math.min(HOVER_W - 42, Math.max(font.width(name), font.width(presenceLabel)) + 6);
        card.add(Shapes.roundRect(37, 6, plateWidth, 26, 3).fill(0xA0000000));
        card.add(TextNode.of(Component.literal(name)).at(40, 9).maxWidth((int) HOVER_W - 46).ellipsis(true)
                .color(0xFFFFFFFF).shadow(true));
        card.add(TextNode.of(presenceLabel)
                .at(40, 21).maxWidth((int) HOVER_W - 46).ellipsis(true).color(stripe).shadow(true));
        float lineY = header + 6;
        card.add(TextNode.of(fullStatus).at(6, lineY).maxWidth((int) HOVER_W - 12).color(0xFFE0E0E0));
        lineY += detail.size() * 10 + 4;
        card.add(TextNode.of(hint).at(6, lineY).maxWidth((int) HOVER_W - 12).color(0xFF8C919C));
        card.add(Shapes.rect(0.5f, 0.5f, HOVER_W - 1, height - 1).stroke(Stroke.of(0xFF3D4250, 1)));
        card.at(x, y);
        return card;
    }

    /** Draws only while its card is still on screen and hovered (a rebuilt list drops the card). */
    private static final class HoverCard extends CanvasGroup {
        private final PlayerCard owner;

        HoverCard(PlayerCard owner) {
            this.owner = owner;
        }

        @Override
        protected void draw(CanvasRenderContext ctx) {
            if (owner.canvas() == null || !owner.hovered) {
                return;
            }
            super.draw(ctx);
        }
    }
}
