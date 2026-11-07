package net.ledok.eden_ld.world;

/** Whether the server thread is inside {@code PlayerList.saveAll} (the autosave, /save-all, the stop's save). */
public final class PlayerSaves {

    private static boolean inSaveAll;

    private PlayerSaves() {
    }

    public static boolean inSaveAll() {
        return inSaveAll;
    }

    public static void setInSaveAll(boolean value) {
        inSaveAll = value;
    }
}
