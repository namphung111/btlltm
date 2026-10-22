package com.chat.platform.chat;

import com.chat.platform.api.ApiModels.MessageInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private final ObjectMapper mapper;
    private final ChatService chat;
    private final PresenceRegistry presence;

    public ChatWebSocketHandler(ObjectMapper mapper, ChatService chat, PresenceRegistry presence) {
        this.mapper = mapper;
        this.chat = chat;
        this.presence = presence;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        presence.add(session);
        String username = (String) session.getAttributes().get("username");
        chat.roomsFor(username).forEach(room -> broadcastPresence(room.roomId(), username, true));
        chat.directConversationsFor(username).forEach(conversation ->
                broadcastDirectPresence(conversation.conversationId(), username, true));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        String username = (String) session.getAttributes().get("username");
        try {
            JsonNode request = mapper.readTree(message.getPayload());
            String type = requiredText(request, "type");
            switch (type) {
                case "subscribe" -> subscribe(session, requiredText(request, "roomId"), username);
                case "unsubscribe" -> presence.unsubscribe(requiredText(request, "roomId"), session);
                case "message" -> sendMessage(requiredText(request, "roomId"), username,
                        requiredText(request, "content"));
                case "read" -> sendReadReceipt(requiredText(request, "roomId"), username,
                        request.path("messageId").asLong(-1));
                case "direct_subscribe" -> subscribeDirect(session,
                        requiredText(request, "conversationId"), username);
                case "direct_message" -> sendDirectMessage(requiredText(request, "conversationId"),
                        username, requiredText(request, "content"));
                case "direct_read" -> sendDirectReadReceipt(requiredText(request, "conversationId"),
                        username, request.path("messageId").asLong(-1));
                default -> sendError(session, "Unsupported event type.");
            }
        } catch (IllegalArgumentException | ResponseStatusException e) {
            sendError(session, e.getMessage());
        } catch (DataAccessException e) {
            sendError(session, "The request could not be saved. Check the server database.");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            sendError(session, "Invalid JSON message.");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String username = (String) session.getAttributes().get("username");
        presence.remove(session);
        if (username != null && !presence.isOnline(username)) {
            chat.roomsFor(username).forEach(room -> broadcastPresence(room.roomId(), username, false));
            chat.directConversationsFor(username).forEach(conversation ->
                    broadcastDirectPresence(conversation.conversationId(), username, false));
        }
    }

    public void broadcastMessage(String roomId, MessageInfo message) {
        broadcast(roomId, Map.of("type", "message", "message", message));
    }

    public void notifyRoomMembers(Set<String> usernames, Map<String, Object> event) {
        for (WebSocketSession recipient : presence.sessionsFor(usernames)) {
            sendSafely(recipient, event);
        }
    }

    public void unsubscribeRoomUsers(String roomId, Set<String> usernames) {
        presence.sessionsFor(usernames).forEach(session -> presence.unsubscribe(roomId, session));
    }

    public void broadcastReadReceipt(String roomId, long messageId, List<String> readers) {
        broadcast(roomId, Map.of("type", "read", "roomId", roomId,
                "messageId", messageId, "readBy", readers));
    }

    private void broadcastPresence(String roomId, String username, boolean online) {
        broadcast(roomId, Map.of("type", "presence", "roomId", roomId,
                "username", username, "online", online));
    }

    private void broadcastDirectPresence(String conversationId, String username, boolean online) {
        broadcastDirect(conversationId, Map.of("type", "direct_presence",
                "conversationId", conversationId, "username", username, "online", online));
    }

    private void subscribe(WebSocketSession session, String roomId, String username) throws IOException {
        chat.requireMember(roomId, username);
        presence.subscribe(roomId, session);
        send(session, Map.of("type", "subscribed", "roomId", roomId));
    }

    private void sendMessage(String roomId, String username, String content) {
        if (content.isBlank() || content.length() > 10_000) {
            throw new IllegalArgumentException("Message must contain 1 to 10000 characters.");
        }
        MessageInfo saved = chat.saveText(roomId, username, content);
        broadcastMessage(roomId, saved);
    }

    private void subscribeDirect(WebSocketSession session, String conversationId, String username)
            throws IOException {
        chat.requireDirectParticipant(conversationId, username);
        presence.subscribe(directChannel(conversationId), session);
        send(session, Map.of("type", "direct_subscribed", "conversationId", conversationId));
    }

    private void sendDirectMessage(String conversationId, String username, String content) {
        if (content.isBlank() || content.length() > 10_000) {
            throw new IllegalArgumentException("Message must contain 1 to 10000 characters.");
        }
        MessageInfo saved = chat.saveDirectText(conversationId, username, content);
        broadcastDirect(conversationId, Map.of("type", "direct_message", "message", saved));
    }

    private void sendReadReceipt(String roomId, String username, long messageId) {
        if (messageId < 1) {
            throw new IllegalArgumentException("A valid message ID is required.");
        }
        List<String> readBy = chat.markRead(roomId, messageId, username);
        broadcastReadReceipt(roomId, messageId, readBy);
    }

    private void sendDirectReadReceipt(String conversationId, String username, long messageId) {
        if (messageId < 1) {
            throw new IllegalArgumentException("A valid message ID is required.");
        }
        List<String> readBy = chat.markDirectRead(conversationId, messageId, username);
        broadcastDirect(conversationId, Map.of("type", "direct_read",
                "conversationId", conversationId, "messageId", messageId, "readBy", readBy));
    }

    private void broadcastDirect(String conversationId, Map<String, Object> payload) {
        Set<WebSocketSession> recipients = new java.util.HashSet<>(
                presence.subscribers(directChannel(conversationId)));
        recipients.addAll(presence.sessionsFor(Set.copyOf(chat.directParticipants(conversationId))));
        for (WebSocketSession recipient : recipients) {
            if (recipient.isOpen()) {
                try {
                    send(recipient, payload);
                } catch (IOException e) {
                    LOGGER.warn("Removing an unresponsive WebSocket session {}: {}",
                            recipient.getId(), e.getMessage());
                    presence.remove(recipient);
                    try {
                        recipient.close(CloseStatus.SERVER_ERROR);
                    } catch (IOException closeError) {
                        LOGGER.debug("Failed to close WebSocket session {}: {}",
                                recipient.getId(), closeError.getMessage());
                    }
                }
            } else {
                presence.remove(recipient);
            }
        }
    }

    private void sendSafely(WebSocketSession recipient, Object payload) {
        if (!recipient.isOpen()) {
            presence.remove(recipient);
            return;
        }
        try {
            send(recipient, payload);
        } catch (IOException e) {
            LOGGER.warn("Removing an unresponsive WebSocket session {}: {}",
                    recipient.getId(), e.getMessage());
            presence.remove(recipient);
            try {
                recipient.close(CloseStatus.SERVER_ERROR);
            } catch (IOException closeError) {
                LOGGER.debug("Failed to close WebSocket session {}: {}",
                        recipient.getId(), closeError.getMessage());
            }
        }
    }

    private String directChannel(String conversationId) {
        return "direct:" + conversationId;
    }

    private void broadcast(String roomId, Map<String, Object> payload) {
        for (WebSocketSession subscriber : Set.copyOf(presence.subscribers(roomId))) {
            if (subscriber.isOpen()) {
                try {
                    send(subscriber, payload);
                } catch (IOException e) {
                    LOGGER.warn("Removing an unresponsive WebSocket session {}: {}",
                            subscriber.getId(), e.getMessage());
                    presence.remove(subscriber);
                    try {
                        subscriber.close(CloseStatus.SERVER_ERROR);
                    } catch (IOException closeError) {
                        LOGGER.debug("Failed to close WebSocket session {}: {}",
                                subscriber.getId(), closeError.getMessage());
                    }
                }
            } else {
                presence.remove(subscriber);
            }
        }
    }

    private void sendError(WebSocketSession session, String detail) throws IOException {
        send(session, Map.of("type", "error", "message", detail == null ? "Request failed." : detail));
    }

    private void send(WebSocketSession session, Object payload) throws IOException {
        if (!session.isOpen()) {
            return;
        }
        synchronized (session) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
        }
    }

    private String requiredText(JsonNode request, String key) {
        String value = request.path(key).asText("").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value;
    }
}
