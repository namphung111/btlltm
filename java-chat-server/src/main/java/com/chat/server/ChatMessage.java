package com.chat.server;

import java.io.Serializable;
import java.time.Instant;

public class ChatMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String roomId;
    private final String sender;
    private final String content;
    private final String fileName;
    private final byte[] fileData;
    private final long timestamp;

    public ChatMessage(String roomId, String sender, String content) {
        this(roomId, sender, content, null, null);
    }

    public ChatMessage(String roomId, String sender, String content, String fileName, byte[] fileData) {
        this(roomId, sender, content, fileName, fileData, Instant.now().toEpochMilli());
    }

    public ChatMessage(String roomId, String sender, String content, String fileName, byte[] fileData,
                       long timestamp) {
        this.roomId = roomId;
        this.sender = sender;
        this.content = content;
        this.fileName = fileName;
        this.fileData = fileData;
        this.timestamp = timestamp;
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
}
