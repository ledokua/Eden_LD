package net.ledok.eden_ld.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The writer: newest snapshot wins, files are replaced whole, nothing is left in the temp folder. */
class AsyncSavedDataTest {

    @TempDir
    Path world;

    @Test
    void writesWhatWasQueuedAndLeavesNoTemps() throws IOException {
        Path data = world.resolve("data");
        Files.createDirectories(data.resolve("lootr/ab"));
        Path temps = world.resolve("eden_ld_saving");
        Path plain = data.resolve("guilds.dat");
        Path nested = data.resolve("lootr/ab/abcd.dat");
        Files.writeString(plain, "old");

        AsyncSavedData.queue(plain, tag(1), temps);
        AsyncSavedData.queue(nested, tag(7), temps);
        AsyncSavedData.flush();

        assertEquals(1, read(plain).getCompound("data").getInt("n"));
        assertEquals(7, read(nested).getCompound("data").getInt("n"));
        assertTrue(empty(temps));
    }

    @Test
    void theNewestSnapshotWins() throws IOException {
        Path temps = world.resolve("eden_ld_saving");
        Path file = world.resolve("hub.dat");
        for (int i = 0; i < 200; i++) {
            AsyncSavedData.queue(file, tag(i), temps);
        }
        AsyncSavedData.flush();
        assertEquals(199, read(file).getCompound("data").getInt("n"));
        assertTrue(empty(temps));
    }

    @Test
    void aBigFileRoundTrips() throws IOException {
        Path temps = world.resolve("eden_ld_saving");
        Path file = world.resolve("big.dat");
        CompoundTag big = tag(3);
        CompoundTag inner = big.getCompound("data");
        for (int i = 0; i < 20_000; i++) {
            inner.putLongArray("k" + i, new long[64]);
        }
        AsyncSavedData.queue(file, big.copy(), temps);
        AsyncSavedData.flush();
        assertEquals(big, read(file));
    }

    private static CompoundTag tag(int n) {
        CompoundTag data = new CompoundTag();
        data.putInt("n", n);
        CompoundTag tag = new CompoundTag();
        tag.put("data", data);
        tag.putInt("DataVersion", 3955);
        return tag;
    }

    private static CompoundTag read(Path file) throws IOException {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }

    private static boolean empty(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.findAny().isEmpty();
        }
    }
}
