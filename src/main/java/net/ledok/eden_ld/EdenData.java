package net.ledok.eden_ld;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Eden's world data: every store lives in its own folder, {@code <world>/data/eden_ld/<name>.dat}
 * (hub.dat, guilds.dat, claim_times.dat), so it's easy to find, back up, or move.
 * <p>
 * Vanilla resolves a saved-data name as a path under {@code data/}, so "eden_ld/guilds" works,
 * but it doesn't create missing folders (saving would fail) — this does, once per server.
 */
public final class EdenData {

    private static MinecraftServer preparedFor;

    private EdenData() {
    }

    /** The store {@code name} ("guilds" → data/eden_ld/guilds.dat), created on first use. */
    public static <T extends SavedData> T get(MinecraftServer server, SavedData.Factory<T> factory, String name) {
        prepare(server);
        return server.overworld().getDataStorage().computeIfAbsent(factory, EdenLD.MOD_ID + "/" + name);
    }

    private static synchronized void prepare(MinecraftServer server) {
        if (preparedFor == server) {
            return;
        }
        preparedFor = server;
        Path folder = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(EdenLD.MOD_ID).normalize();
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            EdenLD.LOGGER.error("Couldn't create the Eden world data folder {}", folder, e);
        }
    }
}
