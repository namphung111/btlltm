package com.chat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
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
    private final DatabaseStore database;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Set<ClientHandler>> onlineClients = new ConcurrentHashMap<>();
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();

    public ChatServer(int port) throws SQLException {
        this.port = port;
        database = new DatabaseStore();
        for (User user : database.loadUsers()) {
            users.put(user.getUsername(), user);
        }
        for (ChatRoom room : database.loadRooms()) {
            rooms.put(room.getRoomId(), room);
        }
        initializeSampleData();
    }

    public static void main(String[] args) throws IOException, SQLException {
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

    private void initializeSampleData() throws SQLException {
        for (String username : List.of("alice", "bob", "charlie")) {
            if (!users.containsKey(username)) {
                User sampleUser = new User(username, SAMPLE_PASSWORD);
                if (database.insertUser(sampleUser)) {
                    users.put(username, sampleUser);
                }
            }
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
        addSampleRoomIfMissing(generalRoom, "alice");

        ChatRoom teamRoom = new ChatRoom("demo-room-team", "Nhóm bài tập", "GROUP");
        teamRoom.addMember("alice");
        teamRoom.addMember("bob");
        teamRoom.addMessage(new ChatMessage(
                teamRoom.getRoomId(), "alice", "Đây là lịch sử mẫu của nhóm bài tập."));
        teamRoom.addMessage(new ChatMessage(
                teamRoom.getRoomId(), "bob", "Hãy nhập ID phòng này để tham gia: demo-room-team"));
        addSampleRoomIfMissing(teamRoom, "alice");
    }

    private void addSampleRoomIfMissing(ChatRoom room, String owner) throws SQLException {
        if (!rooms.containsKey(room.getRoomId())) {
            database.insertRoom(room, owner);
            rooms.put(room.getRoomId(), room);
        }
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

    public boolean registerUser(String username, String password) throws SQLException {
        String normalized = normalizeUsername(username);
        if (normalized.isEmpty() || normalized.length() > 64) {
            return false;
        }
        synchronized (users) {
            if (users.containsKey(normalized)) {
                return false;
            }
            User user = new User(normalized, password);
            if (!database.insertUser(user)) {
                return false;
            }
            users.put(normalized, user);
            return true;
        }
    }

    public boolean login(String username, String password, ClientHandler clientHandler) {
        String normalized = normalizeUsername(username);
        User user = users.get(normalized);
        if (user == null || !user.matchesPassword(password)) {
            return false;
        }
        onlineClients.compute(normalized, (key, sessions) -> {
            Set<ClientHandler> activeSessions = sessions == null
                    ? ConcurrentHashMap.newKeySet() : sessions;
            activeSessions.add(clientHandler);
            user.setOnline(true);
            return activeSessions;
        });
        clientHandler.setUsername(normalized);
        return true;
    }

    public void logout(ClientHandler clientHandler) {
        String username = clientHandler.getUsername();
        if (username != null) {
            onlineClients.computeIfPresent(username, (key, sessions) -> {
                sessions.remove(clientHandler);
                return sessions.isEmpty() ? null : sessions;
            });
            User user = users.get(username);
            if (user != null) {
                user.setOnline(onlineClients.containsKey(username));
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
        List<String> result = new ArrayList<>(onlineClients.keySet());
        Collections.sort(result);
        return result;
    }

    public List<String> getAllUsers() {
        List<String> names = new ArrayList<>(users.keySet());
        Collections.sort(names);
        return names;
    }

    public String createRoom(String roomName, String type, String owner, List<?> members) throws SQLException {
        if (roomName == null || roomName.isBlank() || roomName.length() > 100) {
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
        database.insertRoom(room, owner);
        rooms.put(roomId, room);
        return roomId;
    }

    public ChatRoom joinRoom(String roomId, String username) throws SQLException {
        ChatRoom room = rooms.get(roomId);
        if (room == null) {
            return null;
        }
        database.insertRoomMember(roomId, username);
        room.addMember(username);
        return room;
    }

    public boolean sendMessage(String roomId, ChatMessage message) throws SQLException {
        ChatRoom room = rooms.get(roomId);
        if (room == null || !room.isMember(message.getSender())) {
            return false;
        }
        database.insertMessage(message);
        room.addMessage(message);
        broadcastToRoom(roomId, message, null);
        return true;
    }

    public boolean markMessageRead(String roomId, long messageId, String reader) throws SQLException {
        ChatRoom room = rooms.get(roomId);
        if (room == null || !room.isMember(reader)) {
            return false;
        }
        ChatMessage message = room.getHistory().stream()
                .filter(candidate -> candidate.getMessageId() == messageId)
                .findFirst()
                .orElse(null);
        if (message == null) {
            return false;
        }
        if (message.getReadBy().contains(reader)) {
            return true;
        }
        for (String username : database.markMessageRead(messageId, roomId, reader)) {
            message.addReader(username);
        }
        broadcastReadReceipt(roomId, message);
        return true;
    }

    public boolean isRoomMember(String roomId, String username) {
        ChatRoom room = rooms.get(roomId);
        return room != null && room.isMember(username);
    }

    public List<ChatMessage> getHistory(String roomId) {
        ChatRoom room = rooms.get(roomId);
        return room == null ? Collections.emptyList() : new ArrayList<>(room.getHistory());
    }

    public void broadcastOnlineUsers() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "onlineUsers");
        payload.put("users", getOnlineUsers());
        for (Set<ClientHandler> sessions : onlineClients.values()) {
            for (ClientHandler client : sessions) {
                client.send(payload);
            }
        }
    }

    public void broadcastToRoom(String roomId, ChatMessage message, ClientHandler excludedClient) {
        ChatRoom room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "newMessage");
        payload.put("roomId", roomId);
        payload.put("message", message);

        for (String username : room.getMembers()) {
            Set<ClientHandler> sessions = onlineClients.get(username);
            if (sessions != null) {
                for (ClientHandler client : sessions) {
                    if (client != excludedClient) {
                        client.send(payload);
                    }
                }
            }
        }
    }

    private void broadcastReadReceipt(String roomId, ChatMessage message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "messageRead");
        payload.put("roomId", roomId);
        payload.put("messageId", message.getMessageId());
        payload.put("readBy", new ArrayList<>(message.getReadBy()));
        for (String member : rooms.get(roomId).getMembers()) {
            Set<ClientHandler> sessions = onlineClients.get(member);
            if (sessions != null) {
                for (ClientHandler client : sessions) {
                    client.send(payload);
                }
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
