package com.chat.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientHandler implements Runnable {
    private final ChatServer server;
    private final Socket socket;
    private final ObjectInputStream in;
    private final ObjectOutputStream out;
    private String username;

    public ClientHandler(ChatServer server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;

        ObjectOutputStream output = new ObjectOutputStream(socket.getOutputStream());
        output.flush();
        this.out = output;
        this.in = new ObjectInputStream(socket.getInputStream());
    }

    public String getUsername() {
        return username;
    }

    public Socket getSocket() {
        return socket;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void send(Object object) {
        try {
            synchronized (out) {
                out.writeObject(object);
                out.flush();
            }
        } catch (IOException e) {
            server.removeClient(this);
        }
    }

    @Override
    public void run() {
        try {
            while (!socket.isClosed()) {
                Object request = in.readObject();
                if (!(request instanceof Map<?, ?> map)) {
                    sendError("Invalid request format");
                    continue;
                }
                processRequest(map);
            }
        } catch (EOFException ignored) {
            // Client closed the connection normally.
        } catch (IOException | ClassNotFoundException e) {
            server.removeClient(this);
        } finally {
            server.removeClient(this);
        }
    }

    private void processRequest(Map<?, ?> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) request;
        String action = String.valueOf(map.get("action"));
        try {
            switch (action) {
                case "register" -> handleRegister(map);
                case "login" -> handleLogin(map);
                case "logout" -> handleLogout();
                case "listUsers" -> handleListUsers();
                case "createRoom" -> handleCreateRoom(map);
                case "joinRoom" -> handleJoinRoom(map);
                case "sendMessage" -> handleSendMessage(map);
                case "sendFile" -> handleSendFile(map);
                case "getHistory" -> handleGetHistory(map);
                case "markRead" -> handleMarkRead(map);
                default -> sendError("Unsupported action: " + action);
            }
        } catch (SQLException e) {
            System.err.println("Database operation failed for action " + action + ": " + e.getMessage());
            sendError("Database operation failed. Check the server database connection and logs.");
        }
    }

    private void handleRegister(Map<?, ?> request) throws SQLException {
        String username = request.get("username") instanceof String value ? value : "";
        String password = request.get("password") instanceof String value ? value : "";
        Map<String, Object> response = new HashMap<>();
        if (this.username != null) {
            sendError("Please logout before registering another account");
            return;
        }
        if (username.isBlank() || password.isBlank()) {
            response.put("status", "error");
            response.put("message", "Username and password are required");
            send(response);
            return;
        }

        boolean created = server.registerUser(username, password);
        response.put("status", created ? "success" : "error");
        response.put("message", created ? "User registered successfully" : "User already exists");
        send(response);
    }

    private void handleLogin(Map<?, ?> request) {
        String requestedUsername = request.get("username") instanceof String value ? value : "";
        String password = request.get("password") instanceof String value ? value : "";
        Map<String, Object> response = new HashMap<>();
        if (username != null) {
            sendError("Please logout before changing accounts");
            return;
        }
        if (server.login(requestedUsername, password, this)) {
            response.put("status", "success");
            response.put("message", "Login successful");
            response.put("username", getUsername());
            response.put("onlineUsers", server.getOnlineUsers());
            send(response);
            server.broadcastOnlineUsers();
        } else {
            response.put("status", "error");
            response.put("message", "Invalid username or password");
            send(response);
        }
    }

    private void handleLogout() {
        server.logout(this);
        Map<String, Object> response = new HashMap<>();
        response.put("action", "logout");
        response.put("status", "success");
        response.put("message", "Logged out");
        send(response);
        server.broadcastOnlineUsers();
    }

    private void handleListUsers() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("users", server.getAllUsers());
        response.put("onlineUsers", server.getOnlineUsers());
        send(response);
    }

    private void handleCreateRoom(Map<?, ?> request) throws SQLException {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) request;
        if (username == null) {
            sendError("Please login first");
            return;
        }

        String roomName = String.valueOf(map.get("roomName"));
        String type = String.valueOf(map.getOrDefault("type", "GROUP"));
        List<?> members = (List<?>) map.get("members");

        Map<String, Object> response = new HashMap<>();
        String roomId = server.createRoom(roomName, type, username, members);
        if (roomId == null) {
            response.put("status", "error");
            response.put("message", "Could not create room");
        } else {
            response.put("status", "success");
            response.put("message", "Room created");
            response.put("roomId", roomId);
            response.put("roomName", roomName);
        }
        send(response);
    }

    private void handleJoinRoom(Map<?, ?> request) throws SQLException {
        if (username == null) {
            sendError("Please login first");
            return;
        }
        String roomId = String.valueOf(request.get("roomId"));
        Map<String, Object> response = new HashMap<>();
        ChatRoom room = server.joinRoom(roomId, username);
        if (room == null) {
            response.put("status", "error");
            response.put("message", "Room not found");
            send(response);
            return;
        }

        response.put("status", "success");
        response.put("roomName", room.getRoomName());
        response.put("roomId", room.getRoomId());
        send(response);
    }

    private void handleSendMessage(Map<?, ?> request) throws SQLException {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) request;
        if (username == null) {
            sendError("Please login first");
            return;
        }
        String roomId = String.valueOf(map.get("roomId"));
        String content = String.valueOf(map.getOrDefault("content", ""));
        ChatMessage message = new ChatMessage(roomId, username, content);
        boolean sent = server.sendMessage(roomId, message);

        Map<String, Object> response = new HashMap<>();
        response.put("status", sent ? "success" : "error");
        response.put("message", sent ? "Message sent" : "Could not send message");
        send(response);
    }

    private void handleSendFile(Map<?, ?> request) throws SQLException {
        if (username == null) {
            sendError("Please login first");
            return;
        }
        String roomId = String.valueOf(request.get("roomId"));
        String fileName = request.get("fileName") instanceof String value ? value : "";
        Object requestedFileData = request.get("fileData");
        if (fileName.isBlank() || fileName.length() > 255
                || !(requestedFileData instanceof byte[] fileData) || fileData.length > 5 * 1024 * 1024) {
            sendError("File must be a byte array no larger than 5 MB");
            return;
        }
        String safeFileName = fileName.replace('\\', '/');
        safeFileName = safeFileName.substring(safeFileName.lastIndexOf('/') + 1);
        if (safeFileName.isBlank() || ".".equals(safeFileName) || "..".equals(safeFileName)) {
            sendError("Invalid attachment file name");
            return;
        }
        ChatMessage message = new ChatMessage(roomId, username, "[FILE] " + safeFileName,
                safeFileName, fileData);
        boolean sent = server.sendMessage(roomId, message);

        Map<String, Object> response = new HashMap<>();
        response.put("status", sent ? "success" : "error");
        response.put("message", sent ? "File sent" : "Could not send file");
        send(response);
    }

    private void handleGetHistory(Map<?, ?> request) {
        if (username == null) {
            sendError("Please login first");
            return;
        }
        String roomId = request.get("roomId") instanceof String value ? value : "";
        if (!server.isRoomMember(roomId, username)) {
            sendError("Join the room before loading its history");
            return;
        }
        List<ChatMessage> history = server.getHistory(roomId);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("history", history);
        send(response);
    }

    private void handleMarkRead(Map<?, ?> request) throws SQLException {
        if (username == null) {
            sendError("Please login first");
            return;
        }
        String roomId = request.get("roomId") instanceof String value ? value : "";
        Object requestedMessageId = request.get("messageId");
        if (!(requestedMessageId instanceof Number messageId)
                || !server.markMessageRead(roomId, messageId.longValue(), username)) {
            sendError("Message was not found in the room or access was denied");
        }
    }

    private void sendError(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", message);
        send(response);
    }
}
