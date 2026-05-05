# Java Multi-threaded File Synchronization Tool

## Description
A robust client-server file synchronization tool built in Java. It allows real-time monitoring of a local directory and synchronizes any file changes (creations, modifications, and deletions) with a remote server. The tool ensures data integrity through hashing and uses multi-threading to handle operations efficiently.

## Features
- **Client-Server Architecture**: Seamless synchronization of files from the client to the server.
- **Real-time Monitoring**: Automatically detects file modifications, creations, and deletions using file watchers.
- **Data Integrity**: Uses cryptographic hashing (MD5/SHA) to verify that files are successfully transferred without corruption.
- **Multi-threading**: Employs multiple threads to handle concurrent file operations and client connections on the server side.
- **Socket Programming**: Reliable TCP/IP communication between the client and server.
- **GUI**: Interactive graphical user interface to manage file synchronization and view logs.

## Setup & Compilation

1. Ensure you have the Java Development Kit (JDK) installed on your system.
2. Clone or download this repository.
3. Open a terminal or command prompt in the root directory of the project.
4. Compile the source code by running the following command:
   ```bash
   javac -d out src/*.java
   ```
   *(Note: This creates compiled classes in an `out` folder to keep your root directory clean. If you prefer, you can just run `javac src/*.java` to compile them alongside the source).*

## How to Run

This tool requires starting the server first, followed by the client.

### 1. Start the Server
Run the server to start listening for incoming client connections.
```bash
# If compiled to the 'out' directory
java -cp out FileSyncServer

# If compiled directly in 'src'
java -cp src FileSyncServer
```

### 2. Start the Client
Run the client GUI to connect to the server and begin synchronization.
```bash
# If compiled to the 'out' directory
java -cp out GUIClient

# If compiled directly in 'src'
java -cp src GUIClient
```

## Basic Working
- **Server**: Upon starting, it listens on a designated port for incoming synchronization requests. It maintains a `server_sync/` directory where it stores the synchronized files.
- **Client**: Connects to the server and monitors a local directory. Any files placed in or modified within this directory are hashed (for integrity checks) and transferred to the server automatically.
- **Real-time Synchronization**: The integrated file watcher detects changes and immediately pushes updates to the server to ensure both directories remain consistent.
