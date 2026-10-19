package com.chat.server;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChatRoom implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String roomId;
    private final String roomName;
    private final String type;
    private final ConcurrentHashMap.KeySetView<String, Boolean> members = ConcurrentHashMap.newKeySet();
    private final ArrayList<ChatMessage> history = new ArrayList<>();

    public ChatRoom(String roomId, String roomName, String type) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.type = type;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public String getType() {
        return type;
    }

    public Set<String> getMembers() {
        return members;
    }

    public List<ChatMessage> getHistory() {
        synchronized (this) {
            return new ArrayList<>(history);
        }
    }

    public synchronized void addMember(String username) {
        members.add(username);
    }

    public synchronized void addMessage(ChatMessage message) {
        history.add(message);
    }

    public synchronized boolean isMember(String username) {
        return members.contains(username);
    }
}
