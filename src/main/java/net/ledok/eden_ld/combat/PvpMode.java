package net.ledok.eden_ld.combat;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A dimension's PvP mode (docs/PLAN-pvp-toggle.md): {@link #OFF} — everyone there counts as PvP off (spawn);
 * {@link #TOGGLE} — each player's own choice ({@code /pvp}, the inventory button); {@link #ON} — everyone counts as
 * PvP on (a forced-PvP zone). A dimension without its own mode follows the server's {@code pvp}:
 * {@link #serverDefault}. Plain code, no game classes: the client and the tests use it too.
 */
public enum PvpMode {
    OFF("off"),
    TOGGLE("toggle"),
    ON("on");

    private static final PvpMode[] VALUES = values();

    /** The word in commands, {@code dimensions.json} and the World settings window. */
    public final String key;

    PvpMode(String key) {
        this.key = key;
    }

    /** A dimension without its own mode: each player's choice while the server's {@code pvp} is on, else off. */
    public static PvpMode serverDefault(boolean serverPvp) {
        return serverPvp ? TOGGLE : OFF;
    }

    /** A player's effective state here: the mode, or their own flag where the mode is {@link #TOGGLE}. */
    public boolean effective(boolean flag) {
        return this == ON || this == TOGGLE && flag;
    }

    /**
     * A saved or typed mode: {@code dimensions.json} keeps reading {@code true} (= on) and {@code false} (= off) from
     * before the toggle; {@code "on"}, {@code "off"} and {@code "toggle"} are read too (case and spaces don't matter).
     * Null when it is none of them.
     */
    @Nullable
    public static PvpMode parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on" -> ON;
            case "false", "off" -> OFF;
            case "toggle" -> TOGGLE;
            default -> null;
        };
    }

    /** By ordinal (the network form); {@link #OFF} for anything unknown. */
    public static PvpMode byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : OFF;
    }
}
