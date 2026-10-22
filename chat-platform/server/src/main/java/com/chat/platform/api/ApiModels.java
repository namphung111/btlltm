package com.chat.platform.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ApiModels {
    private ApiModels() {
    }

    public record Credentials(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 8, max = 128) String password) {
    }

    public record AuthResponse(String username, String token) {
    }

    public record CreateRoomRequest(
            @NotBlank @Size(max = 100) String name,
            List<@Size(min = 3, max = 64) String> members) {
    }

    public record AddRoomMembersRequest(List<@Size(min = 3, max = 64) String> members) { }

    public record LeaveRoomRequest(@Size(min = 3, max = 64) String successorUsername) { }

    public record TransferLeadershipRequest(
            @NotBlank @Size(min = 3, max = 64) String successorUsername) { }

    public record RoomInfo(String roomId, String name, String createdBy, long createdAt) {
    }

    public record RoomMember(String username, boolean online, String role) {
    }

    public record DirectConversationInfo(String conversationId, String username, boolean online) {
    }

    public record MessageInfo(long messageId, String roomId, String sender, String content,
                              long timestamp, String fileId, String fileName,
                              String contentType, List<String> readBy) {
    }

    public record FileInfo(String fileId, String name, String contentType) {
    }
}
