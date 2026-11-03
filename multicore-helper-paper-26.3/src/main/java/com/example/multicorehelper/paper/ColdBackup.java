package com.example.multicorehelper.paper;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Offline utility, not called by the plugin or from a running server. */
public final class ColdBackup {
    private ColdBackup() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply this project's test-server directory only");
        System.out.println("Verified cold backup: " + create(Path.of(args[0])));
    }
    public static Path create(Path input) throws Exception {
        Path root = input.toAbsolutePath().normalize();
        if (!root.getFileName().toString().equals("test-server")
                || !root.getParent().getFileName().toString().equals("multicore-helper-paper-26.3"))
            throw new IllegalArgumentException("Only multicore-helper-paper-26.3/test-server is allowed");
        if (!root.toRealPath().equals(root)) throw new IllegalArgumentException("Linked server roots are forbidden");
        var pidFile = root.resolve("server.pid");
        if (Files.exists(pidFile)) {
            long pid = Long.parseLong(Files.readString(pidFile).strip());
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                throw new IllegalStateException("Server process is still alive");
        }
        boolean running = ProcessHandle.allProcesses().anyMatch(p -> p.info().arguments()
                .map(arguments -> Arrays.asList(arguments).contains("-Dmulticorehelper.testRoot=" + root)).orElse(false));
        if (running) throw new IllegalStateException("Test server is running");
        List<Path> files;
        try (var walk = Files.walk(root)) { files = walk.sorted().toList(); }
        for (var file : files) if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(root))
            throw new IllegalArgumentException("Linked files are forbidden: " + file);
        var channels = new ArrayList<FileChannel>();
        var locks = new ArrayList<FileLock>();
        try {
            for (var file : files) if (file.getFileName().toString().equals("session.lock")) {
                var channel = FileChannel.open(file, StandardOpenOption.WRITE);
                channels.add(channel);
                try {
                    var lock = channel.tryLock();
                    if (lock == null) throw new IllegalStateException("World is in use: " + file);
                    locks.add(lock);
                } catch (OverlappingFileLockException e) { throw new IllegalStateException("World is in use", e); }
            }
            Path backups = root.getParent().resolve("backups");
            Files.createDirectories(backups);
            if (!backups.toRealPath().equals(backups)) throw new IllegalArgumentException("Linked backup directory forbidden");
            String name = "cold-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
            Path partial = backups.resolve(name + ".partial");
            var digests = new LinkedHashMap<String, String>();
            try (var zip = new ZipOutputStream(Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW))) {
                for (var file : files) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (relative.equals("server.pid") || relative.equals("paper.jar") || relative.endsWith("session.lock")
                            || relative.startsWith("cache/") || relative.startsWith("libraries/")
                            || relative.startsWith("versions/") || relative.startsWith("logs/")) continue;
                    zip.putNextEntry(new ZipEntry(relative));
                    var digest = MessageDigest.getInstance("SHA-256");
                    try (var stream = new DigestInputStream(Files.newInputStream(file), digest)) { stream.transferTo(zip); }
                    zip.closeEntry();
                    digests.put(relative, HexFormat.of().formatHex(digest.digest()));
                }
            }
            // Read back every entry; validate uncompressed bytes before committing the archive.
            try (var zip = new ZipFile(partial.toFile())) {
                for (var entry : digests.entrySet()) {
                    var digest = MessageDigest.getInstance("SHA-256");
                    try (var stream = new DigestInputStream(zip.getInputStream(zip.getEntry(entry.getKey())), digest)) {
                        stream.transferTo(OutputStream.nullOutputStream());
                    }
                    if (!entry.getValue().equals(HexFormat.of().formatHex(digest.digest())))
                        throw new IOException("Backup read-back failed: " + entry.getKey());
                }
            }
            Path archive = backups.resolve(name + ".zip");
            Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE);
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestInputStream(Files.newInputStream(archive), digest)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            Files.writeString(Path.of(archive + ".sha256"), HexFormat.of().formatHex(digest.digest()) + "  " + archive.getFileName() + "\n",
                    StandardOpenOption.CREATE_NEW);
            var manifest = new StringBuilder();
            digests.forEach((path, hash) -> manifest.append(hash).append("  ").append(path).append('\n'));
            Files.writeString(Path.of(archive + ".files.sha256"), manifest, StandardOpenOption.CREATE_NEW);
            return archive;
        } finally {
            for (var lock : locks) lock.release();
            for (var channel : channels) channel.close();
        }
    }
}
