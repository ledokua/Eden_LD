package net.ledok.eden_ld.world;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * World data files ({@code .dat}: every mod's {@code SavedData}, Lootr's per-chest files…) are written off the server
 * thread ({@code saving.json} {@code async_saved_data}). The live autosave froze the server 2–4 s every 5 minutes, two
 * thirds of it in vanilla's {@code SavedData#save(File)}, mostly gzip and a synced file write. Now the server thread only
 * builds the data (and copies it, so a mod that hands out its live tags can't change it mid-write; classes in
 * {@code no_copy_saved_data} build fresh tags and skip the copy) and clears the dirty
 * flag; one writer thread compresses each snapshot into {@code <world>/eden_ld_saving/} and moves it over the real file
 * in one step, so a file is always either the old or the new one. Writes stay in order; a file still waiting gets the
 * newer snapshot instead. A flushing save ({@code /save-all flush}, server stop) waits for the writer, and reading a
 * file waits for its pending write.
 */
public final class AsyncSavedData {

    private static final String TEMP_FOLDER = "eden_ld_saving";
    private static final long FLUSH_TIMEOUT_SECONDS = 300;

    /**
     * A file's snapshot to write; {@code backup} (player files: {@code .dat_old}) gets the file it replaces;
     * {@code temps} the folder its temp file goes in.
     */
    private record Job(CompoundTag tag, @org.jetbrains.annotations.Nullable Path backup, Path temps) {
    }

    /** The newest snapshot of each file not yet written. */
    private static final Map<Path, Job> PENDING = new ConcurrentHashMap<>();
    private static final AtomicLong TEMP_NAMES = new AtomicLong();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Eden_LD SavedData writer");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile MinecraftServer server;
    private static volatile Path tempFolder;
    /** The file the writer is on right now, if any. */
    private static volatile Path writing;
    /**
     * Held for each single file write, by the writer and by {@link #awaitFile} writing a file itself (review finding
     * 46). Fair, so a waiting server thread is next once the writer's current file is done, not after the whole queue.
     */
    private static final java.util.concurrent.locks.ReentrantLock WRITE_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);
    private static boolean logged;

    private AsyncSavedData() {
    }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(started -> {
            tempFolder = started.getWorldPath(LevelResource.ROOT).resolve(TEMP_FOLDER).normalize();
            clearTempFolder();
            server = started;
        });
        // The stop's own save already flushed (saveAllChunks with flush); this catches anything saved after it.
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            server = null;
            flush();
        });
    }

    /**
     * {@code SavedData#save(File, Provider)} on the server thread: builds and queues the file exactly as vanilla
     * would write it. False when vanilla should write it itself (off the server thread, no server, switched off).
     */
    public static boolean save(SavedData data, File file, HolderLookup.Provider registries) {
        MinecraftServer current = server;
        if (current == null || !current.isSameThread() || !EdenLD.CONFIG.saving.async_saved_data) {
            return false;
        }
        if (!data.isDirty()) {
            return true;
        }
        if (!logged) {
            logged = true;
            EdenLD.LOGGER.info("[Saving] World data (.dat) files are written off the server thread.");
        }
        CompoundTag tag = new CompoundTag();
        tag.put("data", data.save(new CompoundTag(), registries));
        NbtUtils.addCurrentDataVersion(tag);
        data.setDirty(false);
        java.util.List<String> noCopy = EdenLD.CONFIG.saving.no_copy_saved_data;
        boolean fresh = noCopy != null && noCopy.contains(data.getClass().getName());
        queue(file.toPath().toAbsolutePath().normalize(), fresh ? tag : tag.copy(), tempFolder);
        return true;
    }

    static void queue(Path target, CompoundTag tag, Path temps) {
        queue(target, new Job(tag, null, temps));
    }

    private static void queue(Path target, Job job) {
        // Only the first snapshot of a file queues a write; a newer one waiting replaces it and goes in that write.
        if (PENDING.put(target, job) == null) {
            WRITER.execute(() -> write(target));
        }
    }

    /**
     * Any other file Eden writes as gzipped NBT ({@code tag} is written as it is: hand over a copy if it can change):
     * queued for the writer, false when a save can't be queued now ({@link #canQueue}), and the caller writes it
     * itself. For files written in a tick where every millisecond counts (review finding 48: the rental stash copy).
     */
    public static boolean queueFile(Path target, CompoundTag tag) {
        Path temps = tempFolder;
        if (!canQueue() || temps == null) {
            return false;
        }
        queue(target.toAbsolutePath().normalize(), new Job(tag, null, temps));
        return true;
    }

    /** Whether a save now may be handed to the writer: on the server thread of a running server. */
    public static boolean canQueue() {
        MinecraftServer current = server;
        return current != null && current.isSameThread() && tempFolder != null;
    }

    /**
     * A player file ({@code PlayerDataStorage}): the tag built on the server thread, written by the writer the way
     * vanilla does (the old file becomes {@code backup}).
     */
    public static void queuePlayer(Path target, Path backup, CompoundTag tag) {
        queue(target.toAbsolutePath().normalize(), new Job(tag, backup.toAbsolutePath().normalize(), tempFolder));
    }

    /** The writer's task for one file: its newest snapshot, if {@link #awaitFile} hasn't written it already. */
    private static void write(Path target) {
        WRITE_LOCK.lock();
        try {
            writing = target;
            Job job = PENDING.remove(target);
            if (job != null) {
                writeJob(target, job);
            }
        } finally {
            writing = null;
            WRITE_LOCK.unlock();
        }
    }

    /** Writes one snapshot: to a temp file, then moved over the real one. Under {@link #WRITE_LOCK}. */
    private static void writeJob(Path target, Job job) {
        Path temps = job.temps();
        Path temp = temps.resolve(TEMP_NAMES.incrementAndGet() + "-" + target.getFileName());
        try {
            Files.createDirectories(temps);
            NbtIo.writeCompressed(job.tag(), temp);
            Files.createDirectories(target.getParent());
            if (job.backup() != null) {
                net.minecraft.Util.safeReplaceFile(target, temp, job.backup());
                return;
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable e) {
            EdenLD.LOGGER.error("[Saving] Could not save data {}", target, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    /** Waits until every queued file is written (a flushing save, server stop, Lootr's file walk). */
    public static void flush() {
        if (PENDING.isEmpty() && writing == null) {
            return;
        }
        try {
            // One writer thread: once this empty task has run, everything queued before it is on disk.
            WRITER.submit(() -> {
            }).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            EdenLD.LOGGER.error("[Saving] World data files still not written after {} s; going on without them.",
                    FLUSH_TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Saving] Waiting for world data files failed", e);
        }
    }

    /**
     * Before a data file is read: when that file still has a write coming, it is written now. Only that file (review
     * finding 46): this used to wait for the whole writer queue, so a logout or a saved-data read at the wrong moment
     * held the server thread until every player and data file of that autosave was on disk. Now it waits at most for
     * the file the writer is on (the fair lock), then writes its own snapshot here; the writer's task for it then
     * finds nothing left to do.
     */
    public static void awaitFile(File file) {
        if (PENDING.isEmpty() && writing == null) {
            return;
        }
        Path target = file.toPath().toAbsolutePath().normalize();
        if (!PENDING.containsKey(target) && !target.equals(writing)) {
            return;
        }
        try {
            // The same bound as a flush: a writer stuck on a file doesn't hold the server forever.
            if (!WRITE_LOCK.tryLock(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                EdenLD.LOGGER.error("[Saving] {} still not written after {} s; reading it as it is.", target,
                        FLUSH_TIMEOUT_SECONDS);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            // The writer finished whatever it was on (this file's older snapshot, maybe); this one's newest is ours.
            // Marked as being written meanwhile, as the writer does, so another reader of this file (another thread's
            // awaitFile) waits for it instead of reading a half-replaced state (review of 1.3.133).
            writing = target;
            Job job = PENDING.remove(target);
            if (job != null) {
                writeJob(target, job);
            }
        } finally {
            writing = null;
            WRITE_LOCK.unlock();
        }
    }

    /** Temp files a killed server left behind: the real files are whole (they're only ever replaced by a move). */
    private static void clearTempFolder() {
        Path temps = tempFolder;
        if (!Files.isDirectory(temps)) {
            return;
        }
        try (Stream<Path> files = Files.list(temps)) {
            files.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    EdenLD.LOGGER.warn("[Saving] Could not delete leftover {}", path);
                }
            });
        } catch (IOException e) {
            EdenLD.LOGGER.warn("[Saving] Could not clear {}", temps);
        }
    }
}
