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

1. **Install JDK**: Ensure you have the Java Development Kit (JDK 8 or higher) installed.
2. **Open Terminal**: Navigate to the project root directory (`OOPJ_Project`).
3. **Compile**: Run the following command to compile the source code into a dedicated `out` folder:
   ```powershell
   javac -d out src/*.java
   ```

## How to Run

Follow these steps in order to start the synchronization tool:

### 1. Start the Server
Open a terminal and run the server. It will begin listening for client connections.
```powershell
java -cp out FileSyncServer
```

### 2. Start the Client
Open a **second** terminal and run the GUI client.
```powershell
java -cp out GUIClient
```

## Basic Working
- **Server**: Monitors the `server_sync/` directory. It accepts connections from clients and handles file uploads, deletions, and synchronization requests.
- **Client**: Provides a graphical interface to select a local folder to monitor. It automatically detects changes and syncs them to the server using multi-threading and socket communication.
- **Integrity**: Each file transfer is verified using hashing to ensure no data is corrupted during transmission.
