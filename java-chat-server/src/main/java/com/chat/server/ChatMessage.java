package com.chat.server;

import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChatMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String roomId;
    private final String sender;
    private final String content;
    private final String fileName;
    private final byte[] fileData;
    private final long timestamp;
    private long messageId;
    private final Set<String> readBy = ConcurrentHashMap.newKeySet();

    public ChatMessage(String roomId, String sender, String content) {
        this(roomId, sender, content, null, null);
    }

    public ChatMessage(String roomId, String sender, String content, String fileName, byte[] fileData) {
        this(roomId, sender, content, fileName, fileData, Instant.now().toEpochMilli());
    }

    public ChatMessage(String roomId, String sender, String content, String fileName, byte[] fileData,
                       long timestamp) {
        this(roomId, sender, content, fileName, fileData, timestamp, 0);
    }

    public ChatMessage(String roomId, String sender, String content, String fileName, byte[] fileData,
                       long timestamp, long messageId) {
        this.roomId = roomId;
        this.sender = sender;
        this.content = content;
        this.fileName = fileName;
        this.fileData = fileData;
        this.timestamp = timestamp;
        this.messageId = messageId;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getFileData() {
        return fileData;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public synchronized long getMessageId() {
        return messageId;
    }

    public synchronized void setMessageId(long messageId) {
        this.messageId = messageId;
    }

    public synchronized void addReader(String username) {
        if (username != null && !username.isBlank()) {
            readBy.add(username);
        }
    }

    public synchronized Set<String> getReadBy() {
        return new LinkedHashSet<>(readBy);
    }
}
