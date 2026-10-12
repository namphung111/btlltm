package com.chat.server;

import javax.swing.BorderFactory;
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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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

    private final String host;
    private final int port;
    private final JFrame frame = new JFrame("Realtime Chat");
    private final JTextArea chatArea = new JTextArea();
    private final JTextArea usersArea = new JTextArea();
    private final JTextField roomIdField = new JTextField(24);
    private final JTextField messageField = new JTextField();

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String username;
    private String activeRoomId;
    private String activeRoomName;
    private String pendingAuthenticationAction;

    private ChatClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9001;
        SwingUtilities.invokeLater(() -> new ChatClient(host, port).showLogin());
    }

    private void showLogin() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
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
        addFormRow(form, c, 0, "IP / Host server:", hostField);
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
            socket = new Socket();
            socket.connect(new InetSocketAddress(enteredHost, enteredPort), CONNECT_TIMEOUT_MS);
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());
            pendingAuthenticationAction = action;
            Thread reader = new Thread(this::readResponses, "chat-client-reader");
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

    private void readResponses() {
        try {
            while (!socket.isClosed()) {
                Object response = in.readObject();
                if (response instanceof Map<?, ?> map) {
                    handleResponse(map);
                } else {
                    appendChat("Server gửi dữ liệu không hợp lệ.");
                }
            }
        } catch (EOFException e) {
            connectionClosed("Server đã đóng kết nối.");
        } catch (IOException | ClassNotFoundException e) {
            if (socket != null && !socket.isClosed()) {
                connectionClosed("Mất kết nối tới server: " + e.getMessage());
            }
        }
    }

    private void handleResponse(Map<?, ?> response) {
        Object eventAction = response.get("action");
        if ("onlineUsers".equals(eventAction)) {
            Object onlineUsers = response.get("users");
            SwingUtilities.invokeLater(() -> usersArea.setText(formatList(onlineUsers)));
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
        if ("error".equals(status)) {
            showError(message);
            return;
        }

        if ("success".equals(status) && "register".equals(pendingAuthenticationAction)) {
            pendingAuthenticationAction = null;
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
            SwingUtilities.invokeLater(() -> usersArea.setText(formatList(onlineUsers)));
        }
        if ("success".equals(status) && response.containsKey("roomId")) {
            activeRoomId = String.valueOf(response.get("roomId"));
            activeRoomName = String.valueOf(response.containsKey("roomName")
                    ? response.get("roomName") : activeRoomId);
            roomIdField.setText(activeRoomId);
            appendChat("Đang ở phòng: " + activeRoomName + " (ID: " + activeRoomId + ")");
            Map<String, Object> historyRequest = actionRequest("getHistory");
            historyRequest.put("roomId", activeRoomId);
            send(historyRequest);
            return;
        }
        if ("success".equals(status) && response.get("history") instanceof List<?> history) {
            SwingUtilities.invokeLater(() -> {
                chatArea.setText("");
                for (Object item : history) {
                    if (item instanceof ChatMessage chatMessage) {
                        appendChatOnEdt(formatMessage(chatMessage));
                    }
                }
            });
            return;
        }
        if ("success".equals(status) && response.get("room") instanceof ChatRoom room) {
            activeRoomId = room.getRoomId();
            activeRoomName = room.getRoomName();
            roomIdField.setText(activeRoomId);
            appendChat("Đã tham gia phòng: " + activeRoomName + " (ID: " + activeRoomId + ")");
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
        frame.getContentPane().removeAll();
        frame.setTitle("Realtime Chat - " + username + " @ " + host + ":" + port);
        frame.setSize(900, 600);
        frame.setMinimumSize(new Dimension(700, 450));
        frame.setLayout(new BorderLayout(8, 8));

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        usersArea.setEditable(false);
        usersArea.setBorder(BorderFactory.createTitledBorder("Người dùng online"));
        usersArea.setPreferredSize(new Dimension(170, 0));

        JPanel roomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JTextField roomNameField = new JTextField(15);
        JButton createRoomButton = new JButton("Tạo phòng");
        JButton joinRoomButton = new JButton("Tham gia bằng ID");
        JButton refreshUsersButton = new JButton("Làm mới người dùng");
        roomPanel.add(new JLabel("Tên phòng:"));
        roomPanel.add(roomNameField);
        roomPanel.add(createRoomButton);
        roomPanel.add(new JLabel("ID phòng:"));
        roomPanel.add(roomIdField);
        roomPanel.add(joinRoomButton);
        roomPanel.add(refreshUsersButton);

        JPanel composer = new JPanel(new BorderLayout(6, 6));
        JButton sendButton = new JButton("Gửi");
        JButton fileButton = new JButton("Gửi file");
        JPanel composerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        composerButtons.add(fileButton);
        composerButtons.add(sendButton);
        composer.add(messageField, BorderLayout.CENTER);
        composer.add(composerButtons, BorderLayout.EAST);

        frame.add(roomPanel, BorderLayout.NORTH);
        frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        frame.add(usersArea, BorderLayout.EAST);
        frame.add(composer, BorderLayout.SOUTH);

        createRoomButton.addActionListener(event -> {
            String roomName = roomNameField.getText().trim();
            if (roomName.isBlank()) {
                showError("Nhập tên phòng trước khi tạo.");
                return;
            }
            Map<String, Object> request = actionRequest("createRoom");
            request.put("roomName", roomName);
            request.put("type", "GROUP");
            request.put("members", new ArrayList<>());
            send(request);
        });
        joinRoomButton.addActionListener(event -> {
            String roomId = roomIdField.getText().trim();
            if (roomId.isBlank()) {
                showError("Nhập ID phòng cần tham gia.");
                return;
            }
            Map<String, Object> request = actionRequest("joinRoom");
            request.put("roomId", roomId);
            send(request);
        });
        refreshUsersButton.addActionListener(event -> send(actionRequest("listUsers")));
        sendButton.addActionListener(event -> sendMessage());
        messageField.addActionListener(event -> sendMessage());
        fileButton.addActionListener(event -> sendFile());

        frame.revalidate();
        frame.repaint();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        appendChat("Đăng nhập thành công. Tạo phòng hoặc nhập ID phòng để tham gia.");
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
        appendChat("[" + TIME_FORMAT.format(Instant.now()) + "] " + username + ": " + content);
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
            Map<String, Object> request = actionRequest("sendFile");
            request.put("roomId", activeRoomId);
            request.put("fileName", path.getFileName().toString());
            request.put("fileData", Files.readAllBytes(path));
            send(request);
            appendChat("[" + TIME_FORMAT.format(Instant.now()) + "] Đã gửi file: " + path.getFileName());
        } catch (IOException e) {
            showError("Không đọc được file: " + e.getMessage());
        }
    }

    private void displayMessage(ChatMessage message) {
        appendChat(formatMessage(message));
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

    private String formatMessage(ChatMessage message) {
        String text = "[" + TIME_FORMAT.format(Instant.ofEpochMilli(message.getTimestamp())) + "] "
                + message.getSender() + ": " + message.getContent();
        return text;
    }

    private void send(Map<String, Object> request) {
        try {
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

    private String formatList(Object value) {
        if (value instanceof List<?> list) {
            return String.join(System.lineSeparator(), list.stream().map(String::valueOf).toList());
        }
        return "";
    }

    private void appendChat(String text) {
        SwingUtilities.invokeLater(() -> appendChatOnEdt(text));
    }

    private void appendChatOnEdt(String text) {
        chatArea.append(text + System.lineSeparator());
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
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
            appendChatOnEdt(message);
            closeConnection();
        });
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
