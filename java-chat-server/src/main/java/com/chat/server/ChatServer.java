package com.chat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatServer {
    private static final String SAMPLE_PASSWORD = "ChatDemo123!";

    private final int port;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, ClientHandler> onlineClients = new ConcurrentHashMap<>();
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> clientUserMap = new ConcurrentHashMap<>();

    public ChatServer(int port) {
        this.port = port;
        initializeSampleData();
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getProperty("server.port", "9001"));
        ChatServer server = new ChatServer(port);
        server.start();
    }

    public void start() throws IOException {
        System.out.println("Chat server started on port " + port);
        System.out.println("Demo accounts: alice, bob, charlie (password: " + SAMPLE_PASSWORD + ")");
        System.out.println("Demo rooms: demo-room-general, demo-room-team");
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleConnection(clientSocket));
            }
        }
    }

    private void initializeSampleData() {
        for (String username : List.of("alice", "bob", "charlie")) {
            users.putIfAbsent(username, new User(username, SAMPLE_PASSWORD));
        }

        ChatRoom generalRoom = new ChatRoom("demo-room-general", "Phòng chung", "GROUP");
        generalRoom.addMember("alice");
        generalRoom.addMember("bob");
        generalRoom.addMember("charlie");
        generalRoom.addMessage(new ChatMessage(
                generalRoom.getRoomId(), "alice", "Chào mọi người, đây là phòng chat mẫu."));
        generalRoom.addMessage(new ChatMessage(
                generalRoom.getRoomId(), "bob", "Mình đã nhận được tin nhắn realtime."));
        generalRoom.addMessage(new ChatMessage(
                generalRoom.getRoomId(), "charlie", "Có thể gửi tin nhắn mới để thử nhé!"));
        rooms.putIfAbsent(generalRoom.getRoomId(), generalRoom);

        ChatRoom teamRoom = new ChatRoom("demo-room-team", "Nhóm bài tập", "GROUP");
        teamRoom.addMember("alice");
        teamRoom.addMember("bob");
        teamRoom.addMessage(new ChatMessage(
                teamRoom.getRoomId(), "alice", "Đây là lịch sử mẫu của nhóm bài tập."));
        teamRoom.addMessage(new ChatMessage(
                teamRoom.getRoomId(), "bob", "Hãy nhập ID phòng này để tham gia: demo-room-team"));
        rooms.putIfAbsent(teamRoom.getRoomId(), teamRoom);
    }

    private void handleConnection(Socket clientSocket) {
        try {
            new ClientHandler(this, clientSocket).run();
        } catch (IOException e) {
            System.err.println("Rejected non-Java client from "
                    + clientSocket.getRemoteSocketAddress() + ": " + e.getMessage());
            try {
                clientSocket.close();
            } catch (IOException closeError) {
                System.err.println("Could not close rejected client socket: " + closeError.getMessage());
            }
        }
    }

    public boolean registerUser(String username, String password) {
        String normalized = normalizeUsername(username);
        if (normalized.isEmpty()) {
            return false;
        }
        synchronized (users) {
            if (users.containsKey(normalized)) {
                return false;
            }
            users.put(normalized, new User(normalized, password));
            return true;
        }
    }

    public boolean login(String username, String password, ClientHandler clientHandler) {
        String normalized = normalizeUsername(username);
        User user = users.get(normalized);
        if (user == null || !user.matchesPassword(password)) {
            return false;
        }
        synchronized (onlineClients) {
            onlineClients.put(normalized, clientHandler);
            clientUserMap.put(clientHandler.toString(), normalized);
            user.setOnline(true);
        }
        clientHandler.setUsername(normalized);
        return true;
    }

    public void logout(ClientHandler clientHandler) {
        String username = clientHandler.getUsername();
        if (username != null) {
            synchronized (onlineClients) {
                onlineClients.remove(username);
            }
            User user = users.get(username);
            if (user != null) {
                user.setOnline(false);
            }
        }
        clientHandler.setUsername(null);
    }

    public void removeClient(ClientHandler clientHandler) {
        boolean wasOnline = clientHandler.getUsername() != null;
        logout(clientHandler);
        try {
            if (clientHandler != null && clientHandler.getSocket() != null && !clientHandler.getSocket().isClosed()) {
                clientHandler.getSocket().close();
            }
        } catch (IOException ignored) {
            // Ignore socket-close errors during cleanup.
        }
        if (wasOnline) {
            broadcastOnlineUsers();
        }
    }

    public List<String> getOnlineUsers() {
        List<String> result = new ArrayList<>();
        for (String username : users.keySet()) {
            User user = users.get(username);
            if (user != null && user.isOnline()) {
                result.add(username);
            }
        }
        Collections.sort(result);
        return result;
    }

    public List<String> getAllUsers() {
        List<String> names = new ArrayList<>(users.keySet());
        Collections.sort(names);
        return names;
    }

    public String createRoom(String roomName, String type, String owner, List<?> members) {
        if (roomName == null || roomName.isBlank()) {
            return null;
        }
        String roomId = UUID.randomUUID().toString();
        ChatRoom room = new ChatRoom(roomId, roomName, type == null ? "GROUP" : type.toUpperCase());
        room.addMember(owner);
        if (members != null) {
            for (Object member : members) {
                if (member != null) {
                    room.addMember(String.valueOf(member));
                }
            }
        }
        rooms.put(roomId, room);
        return roomId;
    }

    public ChatRoom joinRoom(String roomId, String username) {
        ChatRoom room = rooms.get(roomId);
        if (room == null) {
            return null;
        }
        room.addMember(username);
        return room;
    }

    public boolean sendMessage(String roomId, ChatMessage message) {
        ChatRoom room = rooms.get(roomId);
        if (room == null || !room.isMember(message.getSender())) {
            return false;
        }
        room.addMessage(message);
        broadcastToRoom(roomId, message, message.getSender());
        return true;
    }

    public List<ChatMessage> getHistory(String roomId) {
        ChatRoom room = rooms.get(roomId);
        if (room == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(room.getHistory());
    }

    public void broadcastOnlineUsers() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "onlineUsers");
        payload.put("users", getOnlineUsers());
        for (ClientHandler client : onlineClients.values()) {
            client.send(payload);
        }
    }

    public void broadcastToRoom(String roomId, ChatMessage message, String excludedSender) {
        ChatRoom room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "newMessage");
        payload.put("roomId", roomId);
        payload.put("message", message);

        for (String username : room.getMembers()) {
            if (username.equals(excludedSender)) {
                continue;
            }
            ClientHandler client = onlineClients.get(username);
            if (client != null) {
                client.send(payload);
            }
        }
    }

    private String normalizeUsername(String input) {
        if (input == null) {
            return "";
        }
        return input.trim();
    }
}
