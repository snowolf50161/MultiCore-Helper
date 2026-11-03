package com.example.multicorehelper.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class ColdBackupTest {
    @TempDir Path temp;
    private Path root() throws Exception {
        return Files.createDirectories(temp.resolve("multicore-helper-paper-26.3/test-server"));
    }
    @Test void refusesRunningProcess() throws Exception {
        var root = root();
        Files.writeString(root.resolve("server.pid"), Long.toString(ProcessHandle.current().pid()));
        assertThrows(IllegalStateException.class, () -> ColdBackup.create(root));
    }
    @Test void refusesLiveWorldLock() throws Exception {
        var root = root();
        var world = Files.createDirectories(root.resolve("world"));
        try (var channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThrows(IllegalStateException.class, () -> ColdBackup.create(root));
        }
    }
    @Test void archiveContainsSavedBytesAndCanBeRestored() throws Exception {
        var root = root();
        Files.createDirectories(root.resolve("world/region"));
        Files.writeString(root.resolve("world/region/fixture.mca"), "saved-world-fixture");
        var zip = ColdBackup.create(root);
        try (var archive = new ZipFile(zip.toFile())) {
            var bytes = archive.getInputStream(archive.getEntry("world/region/fixture.mca")).readAllBytes();
            var restored = temp.resolve("restored-fixture.mca");
            Files.write(restored, bytes);
            assertEquals("saved-world-fixture", Files.readString(restored));
        }
        assertTrue(Files.isRegularFile(Path.of(zip + ".sha256")));
    }
    @Test void refusesNonTestDirectory() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ColdBackup.create(temp));
    }
}
