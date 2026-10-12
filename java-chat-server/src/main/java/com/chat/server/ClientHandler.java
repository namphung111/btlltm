package com.chat.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            default -> sendError("Unsupported action: " + action);
        }
    }

    private void handleRegister(Map<?, ?> request) {
        String username = String.valueOf(request.get("username"));
        String password = String.valueOf(request.get("password"));
        Map<String, Object> response = new HashMap<>();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
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
        String username = String.valueOf(request.get("username"));
        String password = String.valueOf(request.get("password"));
        Map<String, Object> response = new HashMap<>();
        if (server.login(username, password, this)) {
            response.put("status", "success");
            response.put("message", "Login successful");
            response.put("username", username);
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

    private void handleCreateRoom(Map<?, ?> request) {
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

    private void handleJoinRoom(Map<?, ?> request) {
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
        response.put("room", room);
        response.put("roomId", room.getRoomId());
        send(response);
    }

    private void handleSendMessage(Map<?, ?> request) {
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

    private void handleSendFile(Map<?, ?> request) {
        if (username == null) {
            sendError("Please login first");
            return;
        }
        String roomId = String.valueOf(request.get("roomId"));
        String fileName = String.valueOf(request.get("fileName"));
        byte[] fileData = (byte[]) request.get("fileData");
        ChatMessage message = new ChatMessage(roomId, username, "[FILE] " + fileName, fileName, fileData);
        boolean sent = server.sendMessage(roomId, message);

        Map<String, Object> response = new HashMap<>();
        response.put("status", sent ? "success" : "error");
        response.put("message", sent ? "File sent" : "Could not send file");
        send(response);
    }

    private void handleGetHistory(Map<?, ?> request) {
        String roomId = String.valueOf(request.get("roomId"));
        List<ChatMessage> history = server.getHistory(roomId);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("history", history);
        send(response);
    }

    private void sendError(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", message);
        send(response);
    }
}
