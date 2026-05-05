import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * FileSyncServer
 * --------------
 * The central server that:
 *  - Listens for incoming client connections on a configurable port.
 *  - Maintains a list of all connected ClientHandler threads.
 *  - Stores a "master" copy of every synced file in a designated server folder.
 *  - Broadcasts updated/new files to every connected client whenever any
 *    client pushes a change.
 *  - Notifies all clients to delete a file when a deletion event is received.
 */
public class FileSyncServer {

    public static final int PORT = 9090;
    public static final String SERVER_SYNC_DIR = "server_sync";

    // Thread-safe list of all currently connected client handlers
    private static final List<ClientHandler> clients =
            Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        // Ensure the server sync directory exists
        File syncDir = new File(SERVER_SYNC_DIR);
        if (!syncDir.exists()) {
            syncDir.mkdirs();
        }

        System.out.println("=== File Sync Server started on port " + PORT + " ===");
        System.out.println("Server sync directory: " + syncDir.getAbsolutePath());

        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[SERVER] New client connected: "
                        + clientSocket.getInetAddress().getHostAddress());

                ClientHandler handler = new ClientHandler(clientSocket, clients);
                clients.add(handler);
                pool.execute(handler);
            }
        } catch (IOException e) {
            System.err.println("[SERVER ERROR] " + e.getMessage());
        }
    }

    /**
     * Broadcast a file (bytes) to ALL connected clients except the sender.
     *
     * @param fileName   relative name of the file
     * @param data       raw bytes of the file content
     * @param senderHandler the ClientHandler that sent the file (excluded from broadcast)
     */
    public static void broadcastFile(String fileName, byte[] data,
                                     ClientHandler senderHandler) {
        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch != senderHandler) {
                    ch.sendFile(fileName, data);
                }
            }
        }
    }

    /**
     * Broadcast a DELETE command to ALL connected clients except the sender.
     *
     * @param fileName      the file to delete on each client
     * @param senderHandler excluded from broadcast
     */
    public static void broadcastDelete(String fileName,
                                       ClientHandler senderHandler) {
        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch != senderHandler) {
                    ch.sendDelete(fileName);
                }
            }
        }
    }

    /** Remove a disconnected handler from the global list. */
    public static void removeClient(ClientHandler handler) {
        clients.remove(handler);
        System.out.println("[SERVER] Client removed. Active clients: " + clients.size());
    }
}
