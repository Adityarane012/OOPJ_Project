import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;

/**
 * ClientHandler
 * -------------
 * Runs on the SERVER side, one instance per connected client.
 * Responsibilities:
 *  - Reads protocol messages from the client over a DataInputStream.
 *  - Handles three command types: UPLOAD | DELETE | DISCONNECT
 *  - Persists received files to the server's sync directory.
 *  - Triggers broadcasts so all other clients receive the change.
 *
 * Wire protocol (all strings are UTF-8 via DataOutputStream):
 *   UPLOAD  → "UPLOAD"  + fileName(UTF) + fileSize(long) + bytes
 *   DELETE  → "DELETE"  + fileName(UTF)
 *   DISCONNECT → "DISCONNECT"
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final List<ClientHandler> allClients;
    private DataInputStream  dis;
    private DataOutputStream dos;

    public ClientHandler(Socket socket, List<ClientHandler> allClients) {
        this.socket     = socket;
        this.allClients = allClients;
    }

    @Override
    public void run() {
        try {
            dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // On first connect, push all existing server files to the new client
            pushExistingFiles();

            while (true) {
                String command = dis.readUTF();

                switch (command) {
                    case "UPLOAD":
                        handleUpload();
                        break;

                    case "DELETE":
                        handleDelete();
                        break;

                    case "DISCONNECT":
                        System.out.println("[SERVER] Client requested disconnect.");
                        return;

                    default:
                        System.out.println("[SERVER] Unknown command: " + command);
                }
            }

        } catch (IOException e) {
            System.out.println("[SERVER] Client disconnected: " + e.getMessage());
        } finally {
            FileSyncServer.removeClient(this);
            closeQuietly();
        }
    }

    // -------------------------------------------------------------------------
    // Protocol handlers
    // -------------------------------------------------------------------------

    private void handleUpload() throws IOException {
        String fileName = dis.readUTF();
        long   fileSize  = dis.readLong();
        byte[] data      = new byte[(int) fileSize];
        dis.readFully(data);

        String md5 = computeMD5(data);
        System.out.printf("[SERVER] Received UPLOAD: %s (%d bytes) MD5=%s%n",
                fileName, fileSize, md5);

        // Save to server sync directory
        File dest = new File(FileSyncServer.SERVER_SYNC_DIR, fileName);
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(data);
        }

        // Broadcast to every other client
        FileSyncServer.broadcastFile(fileName, data, this);
    }

    private void handleDelete() throws IOException {
        String fileName = dis.readUTF();
        System.out.println("[SERVER] Received DELETE: " + fileName);

        File target = new File(FileSyncServer.SERVER_SYNC_DIR, fileName);
        if (target.exists()) target.delete();

        FileSyncServer.broadcastDelete(fileName, this);
    }

    // -------------------------------------------------------------------------
    // Outbound helpers (called by FileSyncServer.broadcast*)
    // -------------------------------------------------------------------------

    /** Send a file to THIS client. */
    public synchronized void sendFile(String fileName, byte[] data) {
        try {
            dos.writeUTF("FILE");
            dos.writeUTF(fileName);
            dos.writeLong(data.length);
            dos.write(data);
            dos.flush();
        } catch (IOException e) {
            System.err.println("[SERVER] Failed to send file to client: " + e.getMessage());
        }
    }

    /** Tell THIS client to delete a file. */
    public synchronized void sendDelete(String fileName) {
        try {
            dos.writeUTF("DELETE");
            dos.writeUTF(fileName);
            dos.flush();
        } catch (IOException e) {
            System.err.println("[SERVER] Failed to send delete to client: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Push all files already on the server to a newly connected client. */
    private void pushExistingFiles() {
        File syncDir = new File(FileSyncServer.SERVER_SYNC_DIR);
        File[] files = syncDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isFile()) {
                try {
                    byte[] data = readAllBytes(f);
                    sendFile(f.getName(), data);
                } catch (IOException e) {
                    System.err.println("[SERVER] Could not push existing file: " + f.getName());
                }
            }
        }
    }

    private byte[] readAllBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            return fis.readAllBytes();
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
            return "N/A";
        }
    }

    private void closeQuietly() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
