package com.chat.platform.chat;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PresenceRegistry {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    public void add(WebSocketSession session) {
        sessions.add(session);
    }

    public void remove(WebSocketSession session) {
        sessions.remove(session);
        roomSessions.values().forEach(subscribers -> subscribers.remove(session));
        roomSessions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void subscribe(String roomId, WebSocketSession session) {
        roomSessions.computeIfAbsent(roomId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unsubscribe(String roomId, WebSocketSession session) {
        Set<WebSocketSession> subscribers = roomSessions.get(roomId);
        if (subscribers != null) {
            subscribers.remove(session);
            if (subscribers.isEmpty()) {
                roomSessions.remove(roomId, subscribers);
            }
        }
    }

    public Set<WebSocketSession> subscribers(String roomId) {
        return roomSessions.getOrDefault(roomId, Set.of());
    }

    public Set<WebSocketSession> sessionsFor(Set<String> usernames) {
        return sessions.stream()
                .filter(session -> session.isOpen()
                        && usernames.contains(session.getAttributes().get("username")))
                .collect(Collectors.toSet());
    }

    public boolean isOnline(String username) {
        return sessions.stream().anyMatch(session -> session.isOpen()
                && username.equals(session.getAttributes().get("username")));
    }
}
