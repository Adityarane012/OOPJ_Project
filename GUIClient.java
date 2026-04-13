import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;

/**
 * GUIClient
 * ---------
 * The Swing-based graphical front-end for the File Sync client.
 *
 * Layout (BorderLayout):
 *  ┌──────────────────────────────────── NORTH (Config Panel) ──────────────┐
 *  │  Server IP  [_________]  Port [____]  Watch Dir [____________] [Browse]│
 *  │  [  Connect  ]  [ Disconnect ]  Status: ●                               │
 *  ├──────────────────────────────────── CENTER (Split) ────────────────────┤
 *  │ LEFT: Synced Files List           RIGHT: Live Sync Log                  │
 *  ├──────────────────────────────────── SOUTH (Status Bar) ────────────────┤
 *  │  Ready | Files: 0                                                        │
 *  └─────────────────────────────────────────────────────────────────────────┘
 *
 * Threading model:
 *  - All Swing mutations go through SwingUtilities.invokeLater().
 *  - FileSyncClient runs its reader on a daemon thread.
 *  - FileWatcher runs on a dedicated daemon thread.
 */
public class GUIClient extends JFrame {

    // =========================================================================
    // Constants / colour palette
    // =========================================================================
    private static final Color BG_DARK       = new Color(18,  18,  28);
    private static final Color BG_PANEL      = new Color(28,  28,  44);
    private static final Color BG_CARD       = new Color(36,  36,  56);
    private static final Color ACCENT        = new Color(99,  102, 241); // indigo
    private static final Color ACCENT_HOVER  = new Color(79,  82,  221);
    private static final Color SUCCESS       = new Color(34,  197, 94);
    private static final Color DANGER        = new Color(239, 68,  68);
    private static final Color TEXT_PRIMARY  = new Color(241, 241, 255);
    private static final Color TEXT_MUTED    = new Color(148, 148, 180);
    private static final Color BORDER_COLOR  = new Color(55,  55,  80);

    private static final Font  FONT_MAIN  = new Font("Segoe UI",        Font.PLAIN,  13);
    private static final Font  FONT_MONO  = new Font("Cascadia Code",   Font.PLAIN,  12);
    private static final Font  FONT_TITLE = new Font("Segoe UI Semibold", Font.BOLD, 16);
    private static final Font  FONT_SMALL = new Font("Segoe UI",        Font.PLAIN,  11);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    // =========================================================================
    // UI Components
    // =========================================================================
    private JTextField   tfHost;
    private JTextField   tfPort;
    private JTextField   tfWatchDir;
    private JButton      btnConnect;
    private JButton      btnDisconnect;
    private JButton      btnBrowse;
    private JLabel       lblStatus;
    private JTextArea    taLog;
    private DefaultListModel<String> fileListModel;
    private JList<String>            fileList;
    private JLabel       lblStatusBar;
    private JLabel       lblFileCount;

    // =========================================================================
    // Backend
    // =========================================================================
    private FileSyncClient syncClient;
    private Thread         watcherThread;
    private FileWatcher    fileWatcher;

    // =========================================================================
    // Constructor
    // =========================================================================
    public GUIClient() {
        super("FileSyncPro — Client");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1080, 680);
        setMinimumSize(new Dimension(820, 560));
        setLocationRelativeTo(null);

        buildUI();
        wireEvents();
        setVisible(true);
    }

    // =========================================================================
    // UI Construction
    // =========================================================================

    private void buildUI() {
        // Root pane
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG_DARK);
        setContentPane(root);

        JPanel topPanel = new JPanel(new BorderLayout(0, 0));
        topPanel.add(buildTitleBar(), BorderLayout.NORTH);
        topPanel.add(buildConfigPanel(), BorderLayout.CENTER);

        root.add(topPanel,           BorderLayout.NORTH);
        root.add(buildCentrePanel(), BorderLayout.CENTER);
        root.add(buildStatusBar(),   BorderLayout.SOUTH);
    }

    /** Gradient title bar at the very top. */
    private JPanel buildTitleBar() {
        JPanel bar = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(
                        0, 0, new Color(63, 63, 200),
                        getWidth(), 0, new Color(139, 92, 246));
                g2.setPaint(gp);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        bar.setPreferredSize(new Dimension(0, 52));
        bar.setLayout(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 20));

        JLabel title = new JLabel("FileSyncPro");
        title.setFont(FONT_TITLE);
        title.setForeground(Color.WHITE);

        JLabel subtitle = new JLabel("Real-time multi-client file synchronisation");
        subtitle.setFont(FONT_SMALL);
        subtitle.setForeground(new Color(200, 200, 255));

        JPanel left = new JPanel(new GridLayout(2, 1, 0, 2));
        left.setOpaque(false);
        left.add(title);
        left.add(subtitle);

        bar.add(left, BorderLayout.WEST);
        return bar;
    }

    /** Connection configuration row. */
    private JPanel buildConfigPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG_PANEL);
        outer.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));

        JPanel inner = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        inner.setBackground(BG_PANEL);

        // Host
        inner.add(label("Server IP:"));
        tfHost = styledField("localhost", 130);
        inner.add(tfHost);

        // Port
        inner.add(label("Port:"));
        tfPort = styledField("9090", 65);
        inner.add(tfPort);

        // Watch directory
        inner.add(label("Watch Dir:"));
        tfWatchDir = styledField("client_sync", 200);
        inner.add(tfWatchDir);

        btnBrowse = smallButton("Browse...");
        inner.add(btnBrowse);

        // Separator
        inner.add(Box.createHorizontalStrut(10));

        btnConnect    = accentButton("Connect");
        btnDisconnect = dangerButton("Disconnect");
        btnDisconnect.setEnabled(false);
        inner.add(btnConnect);
        inner.add(btnDisconnect);

        // Status indicator
        inner.add(Box.createHorizontalStrut(12));
        lblStatus = new JLabel("Offline");
        lblStatus.setFont(FONT_SMALL);
        lblStatus.setForeground(TEXT_MUTED);
        inner.add(lblStatus);

        outer.add(inner, BorderLayout.CENTER);
        return outer;
    }

    /** Split pane: file list on left, log on right. */
    private JSplitPane buildCentrePanel() {
        // ----- Left: file list -----
        fileListModel = new DefaultListModel<>();
        fileList = new JList<>(fileListModel);
        fileList.setBackground(BG_CARD);
        fileList.setForeground(TEXT_PRIMARY);
        fileList.setFont(FONT_MONO);
        fileList.setSelectionBackground(ACCENT);
        fileList.setSelectionForeground(Color.WHITE);
        fileList.setFixedCellHeight(28);
        fileList.setCellRenderer(new FileCellRenderer());

        JScrollPane listScroll = new JScrollPane(fileList);
        styleScrollPane(listScroll);

        JPanel leftPanel = cardPanel("Synced Files");
        leftPanel.add(listScroll, BorderLayout.CENTER);

        // Clear-list button at bottom
        JButton btnClear = smallButton("Clear List");
        btnClear.addActionListener(e -> fileListModel.clear());
        JPanel bottomLeft = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bottomLeft.setBackground(BG_CARD);
        bottomLeft.add(btnClear);
        leftPanel.add(bottomLeft, BorderLayout.SOUTH);

        // ----- Right: sync log -----
        taLog = new JTextArea();
        taLog.setEditable(false);
        taLog.setBackground(new Color(12, 12, 20));
        taLog.setForeground(new Color(80, 250, 123));   // terminal green
        taLog.setFont(FONT_MONO);
        taLog.setCaretColor(Color.WHITE);
        taLog.setLineWrap(true);
        taLog.setWrapStyleWord(true);

        JScrollPane logScroll = new JScrollPane(taLog);
        styleScrollPane(logScroll);

        JPanel rightPanel = cardPanel("Sync Log");
        rightPanel.add(logScroll, BorderLayout.CENTER);

        JButton btnClearLog = smallButton("Clear Log");
        btnClearLog.addActionListener(e -> taLog.setText(""));
        JPanel bottomRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bottomRight.setBackground(BG_CARD);
        bottomRight.add(btnClearLog);
        rightPanel.add(bottomRight, BorderLayout.SOUTH);

        // ----- Split -----
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                leftPanel, rightPanel);
        split.setDividerLocation(320);
        split.setDividerSize(6);
        split.setBackground(BG_DARK);
        split.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        split.setContinuousLayout(true);
        return split;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(15, 15, 25));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 14, 4, 14)));

        lblStatusBar = new JLabel("Ready — not connected");
        lblStatusBar.setFont(FONT_SMALL);
        lblStatusBar.setForeground(TEXT_MUTED);

        lblFileCount = new JLabel("Files synced: 0");
        lblFileCount.setFont(FONT_SMALL);
        lblFileCount.setForeground(TEXT_MUTED);

        bar.add(lblStatusBar, BorderLayout.WEST);
        bar.add(lblFileCount, BorderLayout.EAST);
        return bar;
    }

    // =========================================================================
    // Event Wiring
    // =========================================================================

    private void wireEvents() {
        btnBrowse.addActionListener(this::onBrowse);
        btnConnect.addActionListener(this::onConnect);
        btnDisconnect.addActionListener(this::onDisconnect);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onDisconnect(null);
                dispose();
                System.exit(0);
            }
        });
    }

    private void onBrowse(ActionEvent e) {
        JFileChooser chooser = new JFileChooser(".");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Watch Directory");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            tfWatchDir.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void onConnect(ActionEvent e) {
        String host = tfHost.getText().trim();
        int    port;
        try {
            port = Integer.parseInt(tfPort.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Port must be a number.");
            return;
        }

        Path watchDir = Path.of(tfWatchDir.getText().trim());

        // Build the client
        syncClient = new FileSyncClient(
                host, port, watchDir,
                this::appendLog,
                fileName -> SwingUtilities.invokeLater(() -> addFileToList(fileName)),
                fileName -> SwingUtilities.invokeLater(() -> removeFileFromList(fileName))
        );

        // Try connecting in a background thread so the UI doesn't freeze
        new Thread(() -> {
            try {
                syncClient.connect();
                SwingUtilities.invokeLater(this::onConnected);

                // Start the FileWatcher
                startWatcher(watchDir);

            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    showError("Could not connect: " + ex.getMessage());
                    appendLog("[ERROR] " + ex.getMessage());
                });
            }
        }, "ConnectThread").start();
    }

    private void onConnected() {
        btnConnect.setEnabled(false);
        btnDisconnect.setEnabled(true);
        tfHost.setEnabled(false);
        tfPort.setEnabled(false);
        tfWatchDir.setEnabled(false);
        btnBrowse.setEnabled(false);

        lblStatus.setText("Online");
        lblStatus.setForeground(SUCCESS);
        lblStatusBar.setText("Connected to " + tfHost.getText() + ":" + tfPort.getText());
        appendLog("=== Connected successfully ===");
    }

    private void onDisconnect(ActionEvent e) {
        if (syncClient != null && syncClient.isConnected()) {
            syncClient.disconnect();
        }
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
        if (watcherThread != null) {
            watcherThread.interrupt();
        }

        SwingUtilities.invokeLater(() -> {
            btnConnect.setEnabled(true);
            btnDisconnect.setEnabled(false);
            tfHost.setEnabled(true);
            tfPort.setEnabled(true);
            tfWatchDir.setEnabled(true);
            btnBrowse.setEnabled(true);

            lblStatus.setText("Offline");
            lblStatus.setForeground(TEXT_MUTED);
            lblStatusBar.setText("Disconnected");
            appendLog("=== Disconnected ===");
        });
    }

    private void startWatcher(Path watchDir) {
        try {
            Files.createDirectories(watchDir);
        } catch (Exception ex) {
            appendLog("[ERROR] Cannot create watch dir: " + ex.getMessage());
            return;
        }

        fileWatcher = new FileWatcher(
                watchDir,
                event -> {
                    // File modified/created → upload to server
                    if (syncClient != null && syncClient.isConnected()) {
                        syncClient.uploadFile(event.fileName(), event.data());
                    }
                },
                fileName -> {
                    // File deleted locally → tell server
                    if (syncClient != null && syncClient.isConnected()) {
                        syncClient.deleteFile(fileName);
                    }
                },
                this::appendLog
        );

        watcherThread = new Thread(fileWatcher, "FileWatcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    // =========================================================================
    // Log & List helpers
    // =========================================================================

    private void appendLog(String message) {
        String ts = LocalTime.now().format(TIME_FMT);
        SwingUtilities.invokeLater(() -> {
            taLog.append("[" + ts + "] " + message + "\n");
            taLog.setCaretPosition(taLog.getDocument().getLength());
        });
    }

    private void addFileToList(String fileName) {
        if (!fileListModel.contains(fileName)) {
            fileListModel.addElement(fileName);
        }
        updateFileCount();
    }

    private void removeFileFromList(String fileName) {
        fileListModel.removeElement(fileName);
        updateFileCount();
    }

    private void updateFileCount() {
        lblFileCount.setText("Files synced: " + fileListModel.size());
    }

    // =========================================================================
    // Styling helpers
    // =========================================================================

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_MAIN);
        l.setForeground(TEXT_MUTED);
        return l;
    }

    private JTextField styledField(String placeholder, int widthPx) {
        JTextField tf = new JTextField(placeholder) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG_CARD);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                super.paintComponent(g);
            }
        };
        tf.setPreferredSize(new Dimension(widthPx, 30));
        tf.setFont(FONT_MAIN);
        tf.setBackground(BG_CARD);
        tf.setForeground(TEXT_PRIMARY);
        tf.setCaretColor(TEXT_PRIMARY);
        tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        tf.setOpaque(false);
        return tf;
    }

    private JButton accentButton(String text) {
        return styledButton(text, ACCENT, ACCENT_HOVER, Color.WHITE);
    }

    private JButton dangerButton(String text) {
        return styledButton(text, DANGER, DANGER.darker(), Color.WHITE);
    }

    private JButton smallButton(String text) {
        return styledButton(text, BG_CARD, BORDER_COLOR, TEXT_PRIMARY);
    }

    private JButton styledButton(String text, Color bg, Color hoverBg, Color fg) {
        JButton btn = new JButton(text) {
            private Color currentBg = bg;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isEnabled() ? currentBg : BG_CARD);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                super.paintComponent(g);
            }

            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) {
                        if (isEnabled()) { currentBg = hoverBg; repaint(); }
                    }
                    @Override public void mouseExited(MouseEvent e) {
                        currentBg = bg; repaint();
                    }
                });
            }
        };
        btn.setFont(FONT_MAIN);
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JPanel cardPanel(String headerText) {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBackground(BG_CARD);
        panel.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));

        JLabel header = new JLabel("  " + headerText);
        header.setFont(FONT_MAIN);
        header.setForeground(TEXT_MUTED);
        header.setBackground(BG_PANEL);
        header.setOpaque(true);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));
        header.setPreferredSize(new Dimension(0, 30));

        panel.add(header, BorderLayout.NORTH);
        return panel;
    }

    private void styleScrollPane(JScrollPane sp) {
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(BG_CARD);
        sp.getVerticalScrollBar().setBackground(BG_CARD);
        sp.getHorizontalScrollBar().setBackground(BG_CARD);
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error",
                JOptionPane.ERROR_MESSAGE);
    }

    // =========================================================================
    // Custom cell renderer for file list
    // =========================================================================

    private static class FileCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {

            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, " " + value, index, isSelected, cellHasFocus);
            label.setBackground(isSelected ? ACCENT : (index % 2 == 0 ? BG_CARD : BG_PANEL));
            label.setForeground(isSelected ? Color.WHITE : TEXT_PRIMARY);
            label.setFont(FONT_MONO);
            label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            return label;
        }
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        // Apply dark title bar where supported
        System.setProperty("sun.java2d.opengl", "true");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        // Override specific UI defaults for a consistent dark feel
        UIManager.put("OptionPane.background",           BG_PANEL);
        UIManager.put("Panel.background",                BG_PANEL);
        UIManager.put("OptionPane.messageForeground",    TEXT_PRIMARY);

        SwingUtilities.invokeLater(GUIClient::new);
    }
}
