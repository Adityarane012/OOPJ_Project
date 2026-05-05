import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.function.*;

/**
 * FileSyncClient
 * --------------
 * Handles ALL network communication on the CLIENT side.
 * It is completely decoupled from the GUI — the GUI calls public methods here
 * and supplies callbacks (Consumer/Runnable lambdas) to receive events.
 *
 * Responsibilities:
 *  1. Connect to the FileSyncServer.
 *  2. Start a background reader thread that listens for incoming messages
 *     (FILE or DELETE) pushed by the server.
 *  3. Expose uploadFile() and deleteFile() for the GUI / FileWatcher to call.
 *  4. Write received files into the client's local sync directory.
 *
 * Wire protocol (mirrors ClientHandler on server side):
 *   Client → Server:
 *     UPLOAD    → "UPLOAD"  + fileName(UTF) + fileSize(long) + bytes
 *     DELETE    → "DELETE"  + fileName(UTF)
 *     DISCONNECT→ "DISCONNECT"
 *
 *   Server → Client:
 *     FILE      → "FILE"    + fileName(UTF) + fileSize(long) + bytes
 *     DELETE    → "DELETE"  + fileName(UTF)
 */
public class FileSyncClient {

    private final String host;
    private final int    port;
    private final Path   localSyncDir;

    // Callbacks supplied by GUIClient
    private final Consumer<String> onLog;          // log message
    private final Consumer<String> onFileReceived; // fileName received from server
    private final Consumer<String> onFileDeleted;  // fileName deleted by server

    private Socket           socket;
    private DataInputStream  dis;
    private DataOutputStream dos;

    private volatile boolean connected = false;
    private Thread readerThread;

    /**
     * @param host           server IP / hostname
     * @param port           server port (default 9090)
     * @param localSyncDir   directory where received files are stored locally
     * @param onLog          logging callback
     * @param onFileReceived called with fileName after a file is written to disk
     * @param onFileDeleted  called with fileName after a local file is removed
     */
    public FileSyncClient(String host, int port, Path localSyncDir,
                          Consumer<String> onLog,
                          Consumer<String> onFileReceived,
                          Consumer<String> onFileDeleted) {
        this.host           = host;
        this.port           = port;
        this.localSyncDir   = localSyncDir;
        this.onLog          = onLog;
        this.onFileReceived = onFileReceived;
        this.onFileDeleted  = onFileDeleted;
    }

    // =========================================================================
    // Connection management
    // =========================================================================

    /**
     * Open the socket connection and start the background reader thread.
     *
     * @throws IOException if the server is unreachable
     */
    public void connect() throws IOException {
        socket = new Socket(host, port);
        dis    = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        dos    = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        connected = true;

        // Ensure local directory exists
        Files.createDirectories(localSyncDir);

        onLog.accept("[CLIENT] Connected to server " + host + ":" + port);

        // Start listening for server-pushed messages
        readerThread = new Thread(this::readLoop, "ClientReader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Gracefully disconnect from the server.
     */
    public void disconnect() {
        if (!connected) return;
        try {
            connected = false;
            dos.writeUTF("DISCONNECT");
            dos.flush();
            socket.close();
            onLog.accept("[CLIENT] Disconnected from server.");
        } catch (IOException e) {
            onLog.accept("[CLIENT] Error during disconnect: " + e.getMessage());
        }
    }

    // =========================================================================
    // Outbound operations (Client → Server)
    // =========================================================================

    /**
     * Upload a file to the server.
     *
     * @param fileName relative file name
     * @param data     raw bytes
     */
    public synchronized void uploadFile(String fileName, byte[] data) {
        if (!connected) {
            onLog.accept("[CLIENT] Not connected — cannot upload " + fileName);
            return;
        }
        try {
            dos.writeUTF("UPLOAD");
            dos.writeUTF(fileName);
            dos.writeLong(data.length);
            dos.write(data);
            dos.flush();
            onLog.accept("[CLIENT] Uploaded: " + fileName + " (" + data.length + " bytes)");
        } catch (IOException e) {
            onLog.accept("[CLIENT] Upload failed for " + fileName + ": " + e.getMessage());
        }
    }

    /**
     * Notify the server that a file was deleted locally.
     *
     * @param fileName relative file name
     */
    public synchronized void deleteFile(String fileName) {
        if (!connected) {
            onLog.accept("[CLIENT] Not connected — cannot delete " + fileName);
            return;
        }
        try {
            dos.writeUTF("DELETE");
            dos.writeUTF(fileName);
            dos.flush();
            onLog.accept("[CLIENT] Sent DELETE: " + fileName);
        } catch (IOException e) {
            onLog.accept("[CLIENT] Delete failed for " + fileName + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // Inbound loop (Server → Client)
    // =========================================================================

    private void readLoop() {
        try {
            while (connected) {
                String command = dis.readUTF();

                switch (command) {
                    case "FILE":
                        receiveFile();
                        break;

                    case "DELETE":
                        receiveDelete();
                        break;

                    default:
                        onLog.accept("[CLIENT] Unknown server command: " + command);
                }
            }
        } catch (IOException e) {
            if (connected) {
                onLog.accept("[CLIENT] Connection lost: " + e.getMessage());
                connected = false;
            }
        }
    }

    private void receiveFile() throws IOException {
        String fileName = dis.readUTF();
        long   fileSize  = dis.readLong();
        byte[] data      = new byte[(int) fileSize];
        dis.readFully(data);

        Path dest = localSyncDir.resolve(fileName);
        Files.write(dest, data);

        onLog.accept(String.format(
                "[CLIENT] Received from server: %s (%d bytes)", fileName, fileSize));
        onFileReceived.accept(fileName);
    }

    private void receiveDelete() throws IOException {
        String fileName = dis.readUTF();
        Path   target    = localSyncDir.resolve(fileName);

        if (Files.exists(target)) {
            Files.delete(target);
            onLog.accept("[CLIENT] Server deleted: " + fileName);
        }
        onFileDeleted.accept(fileName);
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public boolean isConnected() {
        return connected;
    }

    public Path getLocalSyncDir() {
        return localSyncDir;
    }
}
