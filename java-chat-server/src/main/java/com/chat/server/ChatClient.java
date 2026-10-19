package com.chat.server;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ChatClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Color BRAND_BLUE = new Color(0, 104, 224);
    private static final Color CHAT_BACKGROUND = new Color(237, 239, 243);
    private static final Color TEXT_COLOR = new Color(39, 51, 70);

    private String host;
    private int port;
    private final JFrame frame = new JFrame("Realtime Chat");
    private final JTextField roomIdField = new JTextField(24);
    private final JTextField messageField = new JTextField();
    private final JPanel messageList = new JPanel();
    private final JLabel roomTitleLabel = new JLabel("Chưa chọn phòng");
    private final JLabel roomSubtitleLabel = new JLabel("Tạo phòng mới hoặc tham gia bằng ID");
    private final JPanel roomListPanel = new JPanel();
    private final JPanel onlineUsersPanel = new JPanel();

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile String username;
    private String activeRoomId;
    private String activeRoomName;
    private volatile String pendingAuthenticationAction;
    private volatile boolean logoutPending;
    private volatile boolean closeAfterLogout;

    private ChatClient(String host, int port) {
        this.host = host;
        this.port = port;
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (username != null) {
                    requestLogout(true);
                } else {
                    closeConnection();
                    frame.dispose();
                }
            }
        });
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9001;
        SwingUtilities.invokeLater(() -> new ChatClient(host, port).showLogin());
    }

    private void showLogin() {
        frame.getContentPane().removeAll();
        frame.setTitle("Realtime Chat");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(420, 270);
        frame.setLocationRelativeTo(null);

        JTextField hostField = new JTextField(host, 18);
        JTextField portField = new JTextField(String.valueOf(port), 18);
        JTextField usernameField = new JTextField(18);
        JPasswordField passwordField = new JPasswordField(18);
        JButton loginButton = new JButton("Đăng nhập");
        JButton registerButton = new JButton("Đăng ký");

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 8, 5, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        addFormRow(form, c, 0, "IP server (máy khác không dùng 127.0.0.1):", hostField);
        addFormRow(form, c, 1, "Port:", portField);
        addFormRow(form, c, 2, "Tên đăng nhập:", usernameField);
        addFormRow(form, c, 3, "Mật khẩu:", passwordField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttons.add(loginButton);
        buttons.add(registerButton);
        frame.add(form, BorderLayout.CENTER);
        frame.add(buttons, BorderLayout.SOUTH);

        loginButton.addActionListener(event -> {
            String enteredHost = hostField.getText().trim();
            String enteredUsername = usernameField.getText().trim();
            String password = new String(passwordField.getPassword());
            int enteredPort;
            try {
                enteredPort = Integer.parseInt(portField.getText().trim());
                if (enteredPort < 1 || enteredPort > 65535) {
                    throw new NumberFormatException("Port must be between 1 and 65535");
                }
            } catch (NumberFormatException e) {
                showError("Port không hợp lệ (1–65535).");
                return;
            }
            if (enteredHost.isBlank() || enteredUsername.isBlank() || password.isBlank()) {
                showError("Vui lòng nhập địa chỉ server, tên đăng nhập và mật khẩu.");
                return;
            }

            connect(enteredHost, enteredPort, enteredUsername, password, "login");
        });
        registerButton.addActionListener(event -> {
            String enteredHost = hostField.getText().trim();
            String enteredUsername = usernameField.getText().trim();
            String password = new String(passwordField.getPassword());
            int enteredPort;
            try {
                enteredPort = Integer.parseInt(portField.getText().trim());
                if (enteredPort < 1 || enteredPort > 65535) {
                    throw new NumberFormatException("Port must be between 1 and 65535");
                }
            } catch (NumberFormatException e) {
                showError("Port không hợp lệ.");
                return;
            }
            if (enteredHost.isBlank() || enteredUsername.isBlank() || password.isBlank()) {
                showError("Vui lòng nhập địa chỉ server, tên đăng nhập và mật khẩu.");
                return;
            }
            connect(enteredHost, enteredPort, enteredUsername, password, "register");
        });

        frame.setVisible(true);
    }

    private void connect(String enteredHost, int enteredPort, String enteredUsername,
                         String password, String action) {
        try {
            closeConnection();
            socket = new Socket();
            socket.connect(new InetSocketAddress(enteredHost, enteredPort), CONNECT_TIMEOUT_MS);
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());
            host = enteredHost;
            port = enteredPort;
            pendingAuthenticationAction = action;
            Socket connectedSocket = socket;
            ObjectInputStream connectedInput = in;
            Thread reader = new Thread(() -> readResponses(connectedSocket, connectedInput),
                    "chat-client-reader");
            reader.setDaemon(true);
            reader.start();

            Map<String, Object> request = new HashMap<>();
            request.put("action", action);
            request.put("username", enteredUsername);
            request.put("password", password);
            send(request);
        } catch (IOException e) {
            closeConnection();
            showError("Không kết nối được tới " + enteredHost + ":" + enteredPort + ".\n" + e.getMessage());
        }
    }

    private void readResponses(Socket connectedSocket, ObjectInputStream connectedInput) {
        try {
            while (!connectedSocket.isClosed()) {
                Object response = connectedInput.readObject();
                if (response instanceof Map<?, ?> map) {
                    handleResponse(map);
                } else {
                    appendChat("Server gửi dữ liệu không hợp lệ.");
                }
            }
        } catch (EOFException e) {
            if (socket == connectedSocket) {
                connectionClosed("Server đã đóng kết nối.");
            }
        } catch (IOException | ClassNotFoundException e) {
            if (socket == connectedSocket && !connectedSocket.isClosed()) {
                connectionClosed("Mất kết nối tới server: " + e.getMessage());
            }
        }
    }

    private void handleResponse(Map<?, ?> response) {
        Object eventAction = response.get("action");
        if ("onlineUsers".equals(eventAction)) {
            Object onlineUsers = response.get("users");
            SwingUtilities.invokeLater(() -> updateUsersList(onlineUsers));
            return;
        }
        if ("newMessage".equals(eventAction)) {
            Object message = response.get("message");
            if (message instanceof ChatMessage chatMessage) {
                displayMessage(chatMessage);
            }
            return;
        }

        String status = String.valueOf(response.get("status"));
        String message = String.valueOf(response.containsKey("message") ? response.get("message") : "");
        if (logoutPending && "logout".equals(response.get("action")) && "success".equals(status)) {
            SwingUtilities.invokeLater(this::completeLogout);
            return;
        }
        if ("error".equals(status)) {
            if (pendingAuthenticationAction != null) {
                pendingAuthenticationAction = null;
                closeConnection();
            }
            showError(message);
            return;
        }

        if ("success".equals(status) && "register".equals(pendingAuthenticationAction)) {
            pendingAuthenticationAction = null;
            closeConnection();
            showInfo(message + "\nBạn có thể đăng nhập bằng tài khoản vừa tạo.");
            return;
        }
        if ("success".equals(status) && "login".equals(pendingAuthenticationAction)
                && response.containsKey("username")) {
            pendingAuthenticationAction = null;
            username = String.valueOf(response.get("username"));
            SwingUtilities.invokeLater(this::showChat);
            send(actionRequest("listUsers"));
            return;
        }

        if ("success".equals(status) && response.containsKey("username")
                && response.containsKey("onlineUsers")) {
            username = String.valueOf(response.get("username"));
            SwingUtilities.invokeLater(this::showChat);
            send(actionRequest("listUsers"));
            return;
        }
        if (response.get("onlineUsers") instanceof List<?> onlineUsers) {
            SwingUtilities.invokeLater(() -> updateUsersList(onlineUsers));
        }
        if ("success".equals(status) && response.containsKey("roomId")) {
            activeRoomId = String.valueOf(response.get("roomId"));
            Object roomValue = response.get("room");
            if (roomValue instanceof ChatRoom room) {
                activeRoomName = room.getRoomName();
            } else {
                activeRoomName = String.valueOf(response.containsKey("roomName")
                        ? response.get("roomName") : activeRoomId);
            }
            SwingUtilities.invokeLater(this::updateActiveRoomUi);
            Map<String, Object> historyRequest = actionRequest("getHistory");
            historyRequest.put("roomId", activeRoomId);
            send(historyRequest);
            return;
        }
        if ("success".equals(status) && response.get("history") instanceof List<?> history) {
            SwingUtilities.invokeLater(() -> {
                messageList.removeAll();
                for (Object item : history) {
                    if (item instanceof ChatMessage chatMessage) {
                        appendMessageOnEdt(chatMessage);
                    }
                }
                messageList.revalidate();
                messageList.repaint();
                scrollMessagesToBottom();
            });
            return;
        }
        if ("success".equals(status) && response.get("room") instanceof ChatRoom room) {
            activeRoomId = room.getRoomId();
            activeRoomName = room.getRoomName();
            SwingUtilities.invokeLater(this::updateActiveRoomUi);
            Map<String, Object> historyRequest = actionRequest("getHistory");
            historyRequest.put("roomId", activeRoomId);
            send(historyRequest);
            return;
        }
        if (!message.isBlank()) {
            appendChat(message);
        }
    }

    private void showChat() {
        activeRoomId = null;
        activeRoomName = null;
        messageList.removeAll();
        messageField.setText("");
        frame.getContentPane().removeAll();
        frame.setTitle("Realtime Chat - " + username + " @ " + host + ":" + port);
        frame.setSize(1200, 760);
        frame.setMinimumSize(new Dimension(850, 560));
        frame.setLayout(new BorderLayout());

        JPanel workspace = new JPanel(new BorderLayout());
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(createNavigationRail(), BorderLayout.WEST);
        leftPanel.add(createSidebar(), BorderLayout.CENTER);
        workspace.add(leftPanel, BorderLayout.WEST);
        workspace.add(createConversationView(), BorderLayout.CENTER);
        frame.add(workspace, BorderLayout.CENTER);

        frame.revalidate();
        frame.repaint();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        appendChat("Đăng nhập thành công. Tạo phòng hoặc nhập ID phòng để tham gia.");
    }

    private JPanel createNavigationRail() {
        JPanel rail = new JPanel();
        rail.setBackground(BRAND_BLUE);
        rail.setPreferredSize(new Dimension(58, 0));
        rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
        rail.setBorder(BorderFactory.createEmptyBorder(14, 7, 14, 7));

        JLabel avatar = new JLabel(initials(username), JLabel.CENTER);
        avatar.setOpaque(true);
        avatar.setBackground(new Color(255, 255, 255, 225));
        avatar.setForeground(BRAND_BLUE);
        avatar.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        avatar.setAlignmentX(Component.CENTER_ALIGNMENT);
        avatar.setPreferredSize(new Dimension(42, 42));
        avatar.setMaximumSize(new Dimension(42, 42));
        avatar.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
        rail.add(avatar);
        rail.add(Box.createVerticalStrut(22));

        rail.add(createRailButton("●", "Tin nhắn", true));
        rail.add(Box.createVerticalStrut(8));
        rail.add(createRailButton("◉", "Người dùng online", false));
        rail.add(Box.createVerticalGlue());
        rail.add(createRailButton("⚙", "Tùy chọn", false));
        return rail;
    }

    private JButton createRailButton(String text, String tooltip, boolean selected) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.setMaximumSize(new Dimension(44, 44));
        button.setPreferredSize(new Dimension(44, 44));
        button.setForeground(Color.WHITE);
        button.setBackground(selected ? new Color(0, 82, 189) : BRAND_BLUE);
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(Color.WHITE);
        sidebar.setPreferredSize(new Dimension(300, 0));
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(222, 226, 232)));

        JPanel top = new JPanel(new BorderLayout(8, 10));
        top.setBackground(Color.WHITE);
        top.setBorder(BorderFactory.createEmptyBorder(18, 16, 14, 16));
        JLabel title = new JLabel("Tin nhắn");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        title.setForeground(TEXT_COLOR);
        top.add(title, BorderLayout.NORTH);

        JTextField searchField = new JTextField();
        searchField.setToolTipText("Tìm phòng hoặc người dùng");
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(224, 228, 234)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        searchField.putClientProperty("JTextField.placeholderText", "⌕  Tìm kiếm");
        top.add(searchField, BorderLayout.CENTER);

        JButton createButton = new JButton("+");
        createButton.setToolTipText("Tạo phòng chat");
        createButton.setForeground(BRAND_BLUE);
        createButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        createButton.setPreferredSize(new Dimension(38, 36));
        createButton.addActionListener(event -> promptCreateRoom());
        top.add(createButton, BorderLayout.EAST);
        sidebar.add(top, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setBackground(Color.WHITE);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(0, 10, 12, 10));

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 4));
        filters.setBackground(Color.WHITE);
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        filters.add(createFilterButton("Tất cả", true));
        filters.add(createFilterButton("Đang online", false));
        filters.add(Box.createHorizontalGlue());
        content.add(filters);

        roomListPanel.setBackground(Color.WHITE);
        roomListPanel.setLayout(new BoxLayout(roomListPanel, BoxLayout.Y_AXIS));
        roomListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(roomListPanel);
        content.add(Box.createVerticalStrut(8));

        JLabel onlineTitle = new JLabel("NGƯỜI DÙNG ONLINE");
        onlineTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        onlineTitle.setForeground(new Color(121, 132, 148));
        onlineTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        onlineTitle.setBorder(BorderFactory.createEmptyBorder(10, 8, 7, 0));
        content.add(onlineTitle);

        onlineUsersPanel.setBackground(Color.WHITE);
        onlineUsersPanel.setLayout(new BoxLayout(onlineUsersPanel, BoxLayout.Y_AXIS));
        onlineUsersPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(onlineUsersPanel);

        JButton joinButton = new JButton("Tham gia phòng bằng ID");
        joinButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        joinButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        joinButton.addActionListener(event -> promptJoinRoom());
        content.add(Box.createVerticalStrut(8));
        content.add(joinButton);
        sidebar.add(new JScrollPane(content), BorderLayout.CENTER);
        return sidebar;
    }

    private JButton createFilterButton(String text, boolean selected) {
        JButton button = new JButton(text);
        button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        button.setForeground(selected ? BRAND_BLUE : new Color(102, 113, 129));
        button.setBackground(selected ? new Color(231, 241, 255) : Color.WHITE);
        button.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        button.setFocusPainted(false);
        return button;
    }

    private JPanel createConversationView() {
        JPanel conversation = new JPanel(new BorderLayout());
        conversation.setBackground(Color.WHITE);

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(Color.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(224, 228, 234)),
                BorderFactory.createEmptyBorder(12, 18, 12, 14)));

        JPanel roomInfo = new JPanel();
        roomInfo.setBackground(Color.WHITE);
        roomInfo.setLayout(new BoxLayout(roomInfo, BoxLayout.Y_AXIS));
        roomTitleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        roomTitleLabel.setForeground(TEXT_COLOR);
        roomSubtitleLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        roomSubtitleLabel.setForeground(new Color(119, 129, 145));
        roomInfo.add(roomTitleLabel);
        roomInfo.add(Box.createVerticalStrut(4));
        roomInfo.add(roomSubtitleLabel);
        header.add(roomInfo, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setBackground(Color.WHITE);
        JButton roomIdButton = createHeaderButton("ID");
        roomIdButton.setToolTipText("Sao chép ID phòng");
        roomIdButton.addActionListener(event -> copyRoomId());
        JButton refreshButton = createHeaderButton("↻");
        refreshButton.setToolTipText("Làm mới danh sách người dùng");
        refreshButton.addActionListener(event -> send(actionRequest("listUsers")));
        JButton logoutButton = createHeaderButton("Đăng xuất");
        logoutButton.addActionListener(event -> requestLogout(false));
        actions.add(roomIdButton);
        actions.add(refreshButton);
        actions.add(logoutButton);
        header.add(actions, BorderLayout.EAST);
        conversation.add(header, BorderLayout.NORTH);

        messageList.setBackground(CHAT_BACKGROUND);
        messageList.setLayout(new BoxLayout(messageList, BoxLayout.Y_AXIS));
        messageList.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        JScrollPane messagesScroll = new JScrollPane(messageList);
        messagesScroll.setBorder(BorderFactory.createEmptyBorder());
        messagesScroll.getViewport().setBackground(CHAT_BACKGROUND);
        messagesScroll.getVerticalScrollBar().setUnitIncrement(18);
        conversation.add(messagesScroll, BorderLayout.CENTER);

        JPanel composer = createComposer();
        conversation.add(composer, BorderLayout.SOUTH);
        return conversation;
    }

    private JButton createHeaderButton(String text) {
        JButton button = new JButton(text);
        button.setForeground(new Color(82, 96, 116));
        button.setBackground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
        return button;
    }

    private JPanel createComposer() {
        JPanel composer = new JPanel(new BorderLayout());
        composer.setBackground(Color.WHITE);
        composer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(224, 228, 234)));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        toolbar.setBackground(Color.WHITE);
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 10, 0, 10));
        JButton emojiButton = createToolbarButton("☺", "Biểu tượng cảm xúc");
        JButton imageButton = createToolbarButton("▧", "Gửi hình ảnh hoặc file");
        JButton fileButton = createToolbarButton("📎", "Gửi file");
        JButton roomIdButton = createToolbarButton("ID", "Sao chép ID phòng");
        toolbar.add(emojiButton);
        toolbar.add(imageButton);
        toolbar.add(fileButton);
        toolbar.add(roomIdButton);
        fileButton.addActionListener(event -> sendFile());
        imageButton.addActionListener(event -> sendFile());
        roomIdButton.addActionListener(event -> copyRoomId());
        composer.add(toolbar, BorderLayout.NORTH);

        JPanel inputPanel = new JPanel(new BorderLayout(10, 0));
        inputPanel.setBackground(Color.WHITE);
        inputPanel.setBorder(BorderFactory.createEmptyBorder(7, 14, 12, 14));
        messageField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(225, 229, 235)),
                BorderFactory.createEmptyBorder(9, 11, 9, 11)));
        messageField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        messageField.setToolTipText("Nhập tin nhắn và nhấn Enter");
        JButton sendButton = new JButton("Gửi");
        sendButton.setForeground(Color.WHITE);
        sendButton.setBackground(BRAND_BLUE);
        sendButton.setFocusPainted(false);
        sendButton.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        sendButton.addActionListener(event -> sendMessage());
        messageField.addActionListener(event -> sendMessage());
        inputPanel.add(messageField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        composer.add(inputPanel, BorderLayout.CENTER);
        return composer;
    }

    private JButton createToolbarButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        button.setForeground(new Color(80, 95, 116));
        button.setBackground(Color.WHITE);
        button.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
        button.setFocusPainted(false);
        return button;
    }

    private void promptCreateRoom() {
        String roomName = JOptionPane.showInputDialog(frame, "Tên phòng chat:", "Tạo phòng",
                JOptionPane.PLAIN_MESSAGE);
        if (roomName == null) {
            return;
        }
        roomName = roomName.trim();
        if (roomName.isBlank()) {
            showError("Nhập tên phòng trước khi tạo.");
            return;
        }
        Map<String, Object> request = actionRequest("createRoom");
        request.put("roomName", roomName);
        request.put("type", "GROUP");
        request.put("members", new ArrayList<>());
        send(request);
    }

    private void promptJoinRoom() {
        String roomId = JOptionPane.showInputDialog(frame, "Nhập ID phòng cần tham gia:",
                "Tham gia phòng", JOptionPane.PLAIN_MESSAGE);
        if (roomId == null) {
            return;
        }
        roomId = roomId.trim();
        if (roomId.isBlank()) {
            showError("Nhập ID phòng cần tham gia.");
            return;
        }
        roomIdField.setText(roomId);
        Map<String, Object> request = actionRequest("joinRoom");
        request.put("roomId", roomId);
        send(request);
    }

    private void copyRoomId() {
        if (activeRoomId == null) {
            showError("Chưa chọn phòng chat.");
            return;
        }
        java.awt.datatransfer.StringSelection selection =
                new java.awt.datatransfer.StringSelection(activeRoomId);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        showInfo("Đã sao chép ID phòng: " + activeRoomId);
    }

    private void updateActiveRoomUi() {
        roomIdField.setText(activeRoomId == null ? "" : activeRoomId);
        roomTitleLabel.setText(activeRoomName == null ? "Chưa chọn phòng" : activeRoomName);
        roomSubtitleLabel.setText(activeRoomId == null
                ? "Tạo phòng mới hoặc tham gia bằng ID"
                : "Phòng nhóm  ·  ID: " + activeRoomId);
        roomListPanel.removeAll();
        if (activeRoomId != null) {
            JPanel roomRow = new JPanel(new BorderLayout(10, 0));
            roomRow.setBackground(new Color(232, 242, 255));
            roomRow.setBorder(BorderFactory.createEmptyBorder(10, 9, 10, 9));
            roomRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
            JLabel icon = new JLabel(initials(activeRoomName), JLabel.CENTER);
            icon.setOpaque(true);
            icon.setBackground(new Color(211, 229, 255));
            icon.setForeground(BRAND_BLUE);
            icon.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            icon.setPreferredSize(new Dimension(38, 38));
            JLabel name = new JLabel(activeRoomName);
            name.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            name.setForeground(TEXT_COLOR);
            roomRow.add(icon, BorderLayout.WEST);
            roomRow.add(name, BorderLayout.CENTER);
            roomListPanel.add(roomRow);
        }
        roomListPanel.revalidate();
        roomListPanel.repaint();
    }

    private void updateUsersList(Object onlineUsers) {
        onlineUsersPanel.removeAll();
        if (onlineUsers instanceof List<?> users) {
            for (Object user : users) {
                String name = String.valueOf(user);
                JPanel row = new JPanel(new BorderLayout(9, 0));
                row.setBackground(Color.WHITE);
                row.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
                JLabel avatar = new JLabel(initials(name), JLabel.CENTER);
                avatar.setOpaque(true);
                avatar.setBackground(new Color(232, 242, 255));
                avatar.setForeground(BRAND_BLUE);
                avatar.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
                avatar.setPreferredSize(new Dimension(28, 28));
                JLabel userLabel = new JLabel(name);
                userLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                userLabel.setForeground(TEXT_COLOR);
                JLabel onlineDot = new JLabel("●");
                onlineDot.setForeground(new Color(48, 181, 105));
                row.add(avatar, BorderLayout.WEST);
                row.add(userLabel, BorderLayout.CENTER);
                row.add(onlineDot, BorderLayout.EAST);
                onlineUsersPanel.add(row);
            }
        }
        onlineUsersPanel.revalidate();
        onlineUsersPanel.repaint();
        int count = onlineUsers instanceof List<?> list ? list.size() : 0;
        if (activeRoomId != null) {
            roomSubtitleLabel.setText("Phòng nhóm  ·  " + count + " người online  ·  ID: " + activeRoomId);
        }
    }

    private String initials(String value) {
        if (value == null || value.isBlank()) {
            return "?";
        }
        String[] words = value.trim().split("\\s+");
        if (words.length == 1) {
            return words[0].substring(0, Math.min(2, words[0].length())).toUpperCase();
        }
        return (words[0].substring(0, 1) + words[words.length - 1].substring(0, 1)).toUpperCase();
    }

    private void scrollMessagesToBottom() {
        SwingUtilities.invokeLater(() -> {
            java.awt.Container parent = messageList.getParent();
            if (parent instanceof javax.swing.JViewport viewport
                    && viewport.getParent() instanceof JScrollPane scrollPane) {
                scrollPane.getVerticalScrollBar().setValue(
                        scrollPane.getVerticalScrollBar().getMaximum());
            }
        });
    }

    private void appendMessageOnEdt(ChatMessage message) {
        boolean ownMessage = username != null && username.equals(message.getSender());
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        JPanel bubble = new JPanel();
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBackground(ownMessage ? new Color(220, 237, 255) : Color.WHITE);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ownMessage
                        ? new Color(189, 219, 255) : new Color(228, 231, 236)),
                BorderFactory.createEmptyBorder(9, 11, 8, 11)));
        bubble.setMaximumSize(new Dimension(460, Integer.MAX_VALUE));

        if (!ownMessage) {
            JLabel sender = new JLabel(message.getSender());
            sender.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            sender.setForeground(BRAND_BLUE);
            sender.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(sender);
            bubble.add(Box.createVerticalStrut(3));
        }

        String content = message.getFileName() == null
                ? message.getContent() : "Tệp đính kèm: " + message.getFileName();
        JTextArea text = new JTextArea(content == null ? "" : content);
        text.setColumns(36);
        text.setRows(Math.max(1, Math.min(8, (content == null ? 0 : content.length() + 35) / 36)));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setForeground(TEXT_COLOR);
        text.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.setMaximumSize(new Dimension(430, Integer.MAX_VALUE));
        bubble.add(text);

        JLabel time = new JLabel(TIME_FORMAT.format(Instant.ofEpochMilli(message.getTimestamp())));
        time.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        time.setForeground(new Color(126, 136, 151));
        time.setAlignmentX(Component.LEFT_ALIGNMENT);
        bubble.add(Box.createVerticalStrut(4));
        bubble.add(time);
        row.add(bubble, ownMessage ? BorderLayout.EAST : BorderLayout.WEST);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        messageList.add(row);
        messageList.add(Box.createVerticalStrut(2));
        messageList.revalidate();
        messageList.repaint();
        scrollMessagesToBottom();
    }

    private void appendSystemMessageOnEdt(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        label.setForeground(new Color(124, 133, 147));
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        messageList.add(label);
        messageList.revalidate();
        messageList.repaint();
        scrollMessagesToBottom();
    }

    private void appendMessage(ChatMessage message) {
        SwingUtilities.invokeLater(() -> appendMessageOnEdt(message));
    }

    private void appendChat(String text) {
        SwingUtilities.invokeLater(() -> appendChatOnEdt(text));
    }

    private void appendChatOnEdt(String text) {
        appendSystemMessageOnEdt(text);
    }

    private void sendMessage() {
        if (activeRoomId == null) {
            showError("Tạo hoặc tham gia phòng chat trước khi gửi tin nhắn.");
            return;
        }
        String content = messageField.getText().trim();
        if (content.isBlank()) {
            return;
        }
        Map<String, Object> request = actionRequest("sendMessage");
        request.put("roomId", activeRoomId);
        request.put("content", content);
        send(request);
        appendMessage(new ChatMessage(activeRoomId, username, content));
        messageField.setText("");
    }

    private void sendFile() {
        if (activeRoomId == null) {
            showError("Tạo hoặc tham gia phòng chat trước khi gửi file.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        try {
            long size = Files.size(path);
            if (size > MAX_FILE_SIZE) {
                showError("File vượt giới hạn 5 MB.");
                return;
            }
            byte[] fileData = Files.readAllBytes(path);
            Map<String, Object> request = actionRequest("sendFile");
            request.put("roomId", activeRoomId);
            request.put("fileName", path.getFileName().toString());
            request.put("fileData", fileData);
            send(request);
            appendMessage(new ChatMessage(activeRoomId, username, "[FILE] " + path.getFileName(),
                    path.getFileName().toString(), fileData));
        } catch (IOException e) {
            showError("Không đọc được file: " + e.getMessage());
        }
    }

    private void displayMessage(ChatMessage message) {
        appendMessage(message);
        if (message.getFileData() != null && message.getFileName() != null) {
            SwingUtilities.invokeLater(() -> {
                int choice = JOptionPane.showConfirmDialog(frame,
                        "Lưu file \"" + message.getFileName() + "\" từ " + message.getSender() + "?",
                        "Nhận file", JOptionPane.YES_NO_OPTION);
                if (choice == JOptionPane.YES_OPTION) {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setSelectedFile(Path.of(message.getFileName()).getFileName().toFile());
                    if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                        try {
                            Files.write(chooser.getSelectedFile().toPath(), message.getFileData());
                        } catch (IOException e) {
                            showError("Không lưu được file: " + e.getMessage());
                        }
                    }
                }
            });
        }
    }

    private void send(Map<String, Object> request) {
        try {
            if (out == null || socket == null || socket.isClosed()) {
                showError("Chưa kết nối tới server.");
                return;
            }
            synchronized (out) {
                out.writeObject(request);
                out.flush();
                out.reset();
            }
        } catch (IOException e) {
            connectionClosed("Không gửi được yêu cầu: " + e.getMessage());
        }
    }

    private Map<String, Object> actionRequest(String action) {
        Map<String, Object> request = new HashMap<>();
        request.put("action", action);
        return request;
    }

    private void addFormRow(JPanel panel, GridBagConstraints c, int row, String label, JTextField field) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, message,
                "Lỗi", JOptionPane.ERROR_MESSAGE));
    }

    private void showInfo(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, message,
                "Thông báo", JOptionPane.INFORMATION_MESSAGE));
    }

    private void connectionClosed(String message) {
        SwingUtilities.invokeLater(() -> {
            boolean shouldReturnToLogin = username != null || logoutPending;
            username = null;
            logoutPending = false;
            closeConnection();
            if (shouldReturnToLogin) {
                if (closeAfterLogout) {
                    frame.dispose();
                } else {
                    showLogin();
                    showError(message);
                }
            } else {
                appendChatOnEdt(message);
            }
        });
    }

    private void requestLogout(boolean closeFrame) {
        if (logoutPending) {
            return;
        }
        if (username == null || socket == null || socket.isClosed()) {
            username = null;
            closeConnection();
            if (closeFrame) {
                frame.dispose();
            } else {
                showLogin();
            }
            return;
        }
        closeAfterLogout = closeFrame;
        logoutPending = true;
        Map<String, Object> request = actionRequest("logout");
        send(request);
    }

    private void completeLogout() {
        username = null;
        logoutPending = false;
        activeRoomId = null;
        activeRoomName = null;
        closeConnection();
        if (closeAfterLogout) {
            frame.dispose();
        } else {
            onlineUsersPanel.removeAll();
            messageList.removeAll();
            showLogin();
        }
    }

    private void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Could not close chat socket: " + e.getMessage());
        }
    }
}
