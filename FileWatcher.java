import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * FileWatcher
 * -----------
 * Monitors a local directory for file-system events using Java NIO's
 * WatchService API. Runs on its own daemon thread.
 *
 * Detected events
 *  - ENTRY_CREATE  → new file
 *  - ENTRY_MODIFY  → file content changed (verified via MD5 diff)
 *  - ENTRY_DELETE  → file removed
 *
 * Callbacks are provided as lambdas/functional interfaces so the watcher
 * is completely decoupled from network or GUI code.
 */
public class FileWatcher implements Runnable {

    /** Utility record that pairs a file name with its raw bytes. */
    public record FileEvent(String fileName, byte[] data) {}

    private final Path watchDir;
    private final Consumer<FileEvent>  onModified;   // CREATE or MODIFY
    private final Consumer<String>     onDeleted;    // DELETE
    private final Consumer<String>     onLog;        // logging callback

    /** MD5 cache: fileName → last known hash (detects spurious MODIFY events). */
    private final Map<String, String> hashCache = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private WatchService watchService;

    /**
     * @param watchDir   directory to monitor
     * @param onModified called with (fileName, bytes) for new/modified files
     * @param onDeleted  called with fileName when a file is deleted
     * @param onLog      receives human-readable log lines
     */
    public FileWatcher(Path watchDir,
                       Consumer<FileEvent> onModified,
                       Consumer<String>    onDeleted,
                       Consumer<String>    onLog) {
        this.watchDir   = watchDir;
        this.onModified = onModified;
        this.onDeleted  = onDeleted;
        this.onLog      = onLog;
    }

    @Override
    public void run() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            watchDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            // Seed the hash cache with files already present
            seedInitialHashes();

            onLog.accept("[WATCHER] Monitoring: " + watchDir.toAbsolutePath());

            while (running) {
                WatchKey key;
                try {
                    key = watchService.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path filePath = watchDir.resolve(pathEvent.context());
                    String fileName = pathEvent.context().toString();

                    if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        hashCache.remove(fileName);
                        onLog.accept("[WATCHER] Deleted: " + fileName);
                        onDeleted.accept(fileName);

                    } else {
                        // CREATE or MODIFY — only fire if content actually changed
                        File file = filePath.toFile();
                        if (!file.isFile()) continue;   // skip directories

                        // Small delay to let the OS finish writing
                        sleepQuietly(200);

                        try {
                            byte[] data = Files.readAllBytes(filePath);
                            String newHash = computeMD5(data);
                            String oldHash = hashCache.get(fileName);

                            if (!newHash.equals(oldHash)) {
                                hashCache.put(fileName, newHash);
                                String eventType = (kind == StandardWatchEventKinds.ENTRY_CREATE)
                                        ? "New" : "Modified";
                                onLog.accept(String.format(
                                        "[WATCHER] %s: %s (%d bytes) MD5=%s",
                                        eventType, fileName, data.length, newHash));
                                onModified.accept(new FileEvent(fileName, data));
                            }
                        } catch (IOException e) {
                            onLog.accept("[WATCHER ERROR] Cannot read " + fileName
                                    + ": " + e.getMessage());
                        }
                    }
                }

                key.reset();
            }

        } catch (IOException e) {
            onLog.accept("[WATCHER ERROR] " + e.getMessage());
        } finally {
            stopWatching();
        }
    }

    /** Gracefully stop the watcher thread. */
    public void stop() {
        running = false;
        stopWatching();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void seedInitialHashes() {
        File[] files = watchDir.toFile().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                try {
                    byte[] data = Files.readAllBytes(f.toPath());
                    hashCache.put(f.getName(), computeMD5(data));
                } catch (IOException ignored) {}
            }
        }
    }

    private String computeMD5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString();
        }
    }

    private void stopWatching() {
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
