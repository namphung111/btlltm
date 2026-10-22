package com.chat.platform.chat;

import com.chat.platform.api.ApiModels.MessageInfo;
import com.chat.platform.api.ApiModels.DirectConversationInfo;
import com.chat.platform.api.ApiModels.RoomInfo;
import com.chat.platform.api.ApiModels.RoomMember;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

@Service
public class ChatService {
    private static final int HISTORY_LIMIT = 200;
    private final JdbcTemplate jdbc;
    private final PresenceRegistry presence;

    public ChatService(JdbcTemplate jdbc, PresenceRegistry presence) {
        this.jdbc = jdbc;
        this.presence = presence;
    }

    public List<RoomInfo> roomsFor(String username) {
        return jdbc.query("""
                SELECT r.room_id, r.room_name, r.created_by, r.created_at
                FROM rooms r JOIN room_members rm ON rm.room_id = r.room_id
                WHERE rm.username = ? AND rm.left_at IS NULL AND r.deleted_at IS NULL
                ORDER BY r.created_at DESC
                """, (row, index) -> new RoomInfo(row.getString("room_id"), row.getString("room_name"),
                row.getString("created_by"), row.getLong("created_at")), username);
    }

    @Transactional
    public DirectConversationInfo openDirectConversation(String username, String otherUsername) {
        if (username.equals(otherUsername)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot start a chat with yourself.");
        }
        if (!userExists(otherUsername)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        }
        String participantA = username.compareTo(otherUsername) < 0 ? username : otherUsername;
        String participantB = username.compareTo(otherUsername) < 0 ? otherUsername : username;
        List<String> existing = jdbc.query("""
                SELECT conversation_id FROM direct_conversations
                WHERE participant_a = ? AND participant_b = ?
                """, (row, index) -> row.getString("conversation_id"), participantA, participantB);
        if (existing.isEmpty()) {
            String conversationId = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            try {
                if (isPostgreSql()) {
                    jdbc.update("""
                            INSERT INTO direct_conversations
                                (conversation_id, participant_a, participant_b, created_at, last_message_at)
                            VALUES (?, ?, ?, ?, ?)
                            ON CONFLICT (participant_a, participant_b) DO NOTHING
                            """, conversationId, participantA, participantB, now, now);
                } else {
                    jdbc.update("""
                            INSERT INTO direct_conversations
                                (conversation_id, participant_a, participant_b, created_at, last_message_at)
                            VALUES (?, ?, ?, ?, ?)
                            """, conversationId, participantA, participantB, now, now);
                }
            } catch (DuplicateKeyException concurrentCreate) {
                // A concurrent request may have created this unique participant pair.
            }
            existing = jdbc.query("""
                    SELECT conversation_id FROM direct_conversations
                    WHERE participant_a = ? AND participant_b = ?
                    """, (row, index) -> row.getString("conversation_id"), participantA, participantB);
        }
        if (existing.isEmpty()) {
            throw new IllegalStateException("Could not create or retrieve the direct conversation.");
        }
        return directConversation(existing.get(0), otherUsername);
    }

    public List<DirectConversationInfo> directConversationsFor(String username) {
        return jdbc.query("""
                SELECT conversation_id,
                       CASE WHEN participant_a = ? THEN participant_b ELSE participant_a END AS other_username
                FROM direct_conversations
                WHERE participant_a = ? OR participant_b = ?
                ORDER BY last_message_at DESC, conversation_id
                """, (row, index) -> directConversation(
                row.getString("conversation_id"), row.getString("other_username")),
                username, username, username);
    }

    public void requireDirectParticipant(String conversationId, String username) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM direct_conversations
                WHERE conversation_id = ? AND (participant_a = ? OR participant_b = ?)
                """, Integer.class, conversationId, username, username);
        if (count == null || count == 0) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM direct_conversations WHERE conversation_id = ?",
                    Integer.class, conversationId);
            if (exists == null || exists == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not a participant in this conversation.");
        }
    }

    public List<String> directParticipants(String conversationId) {
        return jdbc.query("""
                SELECT participant_a, participant_b
                FROM direct_conversations WHERE conversation_id = ?
                """, (row, index) -> List.of(row.getString("participant_a"), row.getString("participant_b")),
                conversationId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found."));
    }

    public List<MessageInfo> directHistory(String conversationId, String username) {
        requireDirectParticipant(conversationId, username);
        List<MessageInfo> messages = jdbc.query("""
                SELECT message_id, sender_username, content, created_at
                FROM direct_messages
                WHERE conversation_id = ? ORDER BY message_id DESC LIMIT ?
                """, (row, index) -> directMessage(row.getLong("message_id"), conversationId,
                row.getString("sender_username"), row.getString("content"), row.getLong("created_at")),
                conversationId, HISTORY_LIMIT);
        java.util.Collections.reverse(messages);
        return messages;
    }

    @Transactional
    public MessageInfo saveDirectText(String conversationId, String username, String content) {
        requireDirectParticipant(conversationId, username);
        long createdAt = System.currentTimeMillis();
        Number id;
        if (isPostgreSql()) {
            id = jdbc.queryForObject("""
                    INSERT INTO direct_messages (conversation_id, sender_username, content, created_at)
                    VALUES (?, ?, ?, ?) RETURNING message_id
                    """, Long.class, conversationId, username, content, createdAt);
        } else {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO direct_messages (conversation_id, sender_username, content, created_at)
                        VALUES (?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, conversationId);
                statement.setString(2, username);
                statement.setString(3, content);
                statement.setLong(4, createdAt);
                return statement;
            }, keyHolder);
            id = keyHolder.getKey();
        }
        if (id == null) {
            throw new IllegalStateException("Database did not return the new direct message ID.");
        }
        jdbc.update("""
                INSERT INTO direct_message_read_receipts (message_id, username, read_at)
                VALUES (?, ?, ?)
                """, id.longValue(), username, createdAt);
        jdbc.update("UPDATE direct_conversations SET last_message_at = ? WHERE conversation_id = ?",
                createdAt, conversationId);
        return directMessage(id.longValue(), conversationId, username, content, createdAt);
    }

    @Transactional
    public List<String> markDirectRead(String conversationId, long messageId, String username) {
        requireDirectParticipant(conversationId, username);
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM direct_messages
                WHERE message_id = ? AND conversation_id = ?
                """, Integer.class, messageId, conversationId);
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Message not found in this conversation.");
        }
        if (isPostgreSql()) {
            jdbc.update("""
                    INSERT INTO direct_message_read_receipts (message_id, username, read_at)
                    VALUES (?, ?, ?) ON CONFLICT (message_id, username) DO NOTHING
                    """, messageId, username, System.currentTimeMillis());
        } else {
            try {
                jdbc.update("""
                        INSERT INTO direct_message_read_receipts (message_id, username, read_at)
                        VALUES (?, ?, ?)
                        """, messageId, username, System.currentTimeMillis());
            } catch (DuplicateKeyException alreadyRead) {
                return directReaders(messageId);
            }
        }
        return directReaders(messageId);
    }

    @Transactional
    public RoomInfo createRoom(String name, String username, List<String> requestedMembers) {
        String roomId = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        jdbc.update("INSERT INTO rooms (room_id, room_name, created_by, created_at) VALUES (?, ?, ?, ?)",
                roomId, name.trim(), username, createdAt);
        jdbc.update("INSERT INTO room_members (room_id, username, joined_at, role) VALUES (?, ?, ?, 'LEADER')",
                roomId, username, createdAt);
        if (requestedMembers != null) {
            for (String requested : requestedMembers.stream().filter(java.util.Objects::nonNull)
                    .map(String::trim).filter(member -> !member.isBlank()).distinct().toList()) {
                if (requested.equals(username)) {
                    continue;
                }
                if (!userExists(requested)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "User not found: " + requested);
                }
                jdbc.update("INSERT INTO room_members (room_id, username, joined_at, role) "
                                + "VALUES (?, ?, ?, 'MEMBER')",
                        roomId, requested, createdAt);
            }
        }
        return new RoomInfo(roomId, name.trim(), username, createdAt);
    }

    public List<String> activeMemberUsernames(String roomId) {
        return jdbc.query("SELECT username FROM room_members WHERE room_id = ? AND left_at IS NULL",
                (row, index) -> row.getString("username"), roomId);
    }

    @Transactional
    public void addMembers(String roomId, String username, List<String> requestedMembers) {
        lockActiveRoom(roomId);
        requireLeader(roomId, username);
        if (requestedMembers == null) return;
        long now = System.currentTimeMillis();
        for (String requested : requestedMembers.stream().filter(value -> value != null)
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList()) {
            if (!userExists(requested)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + requested);
            }
            if (isMember(roomId, requested)) continue;
            Integer previous = jdbc.queryForObject("SELECT COUNT(*) FROM room_members "
                    + "WHERE room_id = ? AND username = ?", Integer.class, roomId, requested);
            if (previous != null && previous > 0) {
                jdbc.update("UPDATE room_members SET joined_at = ?, left_at = NULL, role = 'MEMBER' "
                        + "WHERE room_id = ? AND username = ?", now, roomId, requested);
            } else {
                jdbc.update("INSERT INTO room_members (room_id, username, joined_at, role) "
                        + "VALUES (?, ?, ?, 'MEMBER')", roomId, requested, now);
            }
        }
    }

    @Transactional
    public void leaveRoom(String roomId, String username, String successorUsername) {
        lockActiveRoom(roomId);
        requireMember(roomId, username);
        String role = jdbc.queryForObject("SELECT role FROM room_members "
                + "WHERE room_id = ? AND username = ? AND left_at IS NULL FOR UPDATE",
                String.class, roomId, username);
        long now = System.currentTimeMillis();
        if ("LEADER".equals(role)) {
            Integer memberCount = jdbc.queryForObject("SELECT COUNT(*) FROM room_members "
                    + "WHERE room_id = ? AND left_at IS NULL", Integer.class, roomId);
            if (memberCount != null && memberCount <= 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The last group member cannot leave. Delete the group instead.");
            }
            if (successorUsername == null || successorUsername.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Choose a successor before leaving as the group leader.");
            }
            if (successorUsername.equals(username) || !lockActiveMember(roomId, successorUsername)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The successor must be another active group member.");
            }
            jdbc.update("UPDATE room_members SET role = 'MEMBER' WHERE room_id = ? AND username = ?",
                    roomId, username);
            jdbc.update("UPDATE room_members SET role = 'LEADER' WHERE room_id = ? AND username = ?",
                    roomId, successorUsername);
            jdbc.update("UPDATE rooms SET created_by = ? WHERE room_id = ?", successorUsername, roomId);
        } else if (successorUsername != null && !successorUsername.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only the group leader can appoint a successor.");
        }
        jdbc.update("UPDATE room_members SET left_at = ?, role = 'MEMBER' "
                + "WHERE room_id = ? AND username = ? AND left_at IS NULL", now, roomId, username);
    }

    @Transactional
    public void transferLeadership(String roomId, String username, String successorUsername) {
        lockActiveRoom(roomId);
        requireLeader(roomId, username);
        if (successorUsername == null || successorUsername.isBlank()
                || successorUsername.equals(username) || !lockActiveMember(roomId, successorUsername)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose another active group member as the new leader.");
        }
        jdbc.update("UPDATE room_members SET role = 'MEMBER' "
                + "WHERE room_id = ? AND username = ? AND left_at IS NULL", roomId, username);
        jdbc.update("UPDATE room_members SET role = 'LEADER' "
                + "WHERE room_id = ? AND username = ? AND left_at IS NULL", roomId, successorUsername);
        jdbc.update("UPDATE rooms SET created_by = ? WHERE room_id = ?", successorUsername, roomId);
    }

    @Transactional
    public void deleteRoom(String roomId, String username) {
        lockActiveRoom(roomId);
        requireLeader(roomId, username);
        jdbc.update("UPDATE rooms SET deleted_at = ? WHERE room_id = ? AND deleted_at IS NULL",
                System.currentTimeMillis(), roomId);
    }

    public void requireLeader(String roomId, String username) {
        requireMember(roomId, username);
        String role = jdbc.queryForObject("SELECT role FROM room_members "
                        + "WHERE room_id = ? AND username = ? AND left_at IS NULL FOR UPDATE",
                String.class, roomId, username);
        if (!"LEADER".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the current group leader can perform this action.");
        }
    }

    @Transactional
    public RoomInfo joinRoom(String roomId, String username) {
        RoomInfo room = room(roomId);
        if (isPostgreSql()) {
            jdbc.update("INSERT INTO room_members (room_id, username, joined_at, role) VALUES (?, ?, ?, 'MEMBER') "
                            + "ON CONFLICT (room_id, username) DO UPDATE SET joined_at = EXCLUDED.joined_at, "
                            + "left_at = NULL, role = CASE WHEN room_members.role = 'LEADER' "
                            + "THEN 'LEADER' ELSE 'MEMBER' END",
                    roomId, username, System.currentTimeMillis());
            return room;
        }
        try {
            jdbc.update("INSERT INTO room_members (room_id, username, joined_at, role) "
                            + "VALUES (?, ?, ?, 'MEMBER')",
                    roomId, username, System.currentTimeMillis());
        } catch (DuplicateKeyException alreadyMember) {
            jdbc.update("UPDATE room_members SET joined_at = ?, left_at = NULL "
                            + "WHERE room_id = ? AND username = ?",
                    System.currentTimeMillis(), roomId, username);
        }
        return room;
    }

    public RoomInfo room(String roomId) {
        return jdbc.query("SELECT room_id, room_name, created_by, created_at FROM rooms "
                        + "WHERE room_id = ? AND deleted_at IS NULL",
                        (row, index) -> new RoomInfo(row.getString("room_id"), row.getString("room_name"),
                                row.getString("created_by"), row.getLong("created_at")), roomId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found."));
    }

    public List<RoomMember> members(String roomId, String username) {
        requireMember(roomId, username);
        return jdbc.query("SELECT username, role FROM room_members "
                        + "WHERE room_id = ? AND left_at IS NULL ORDER BY username",
                (row, index) -> {
                    String member = row.getString("username");
                    return new RoomMember(member, presence.isOnline(member), row.getString("role"));
                }, roomId);
    }

    public List<MessageInfo> history(String roomId, String username) {
        requireMember(roomId, username);
        List<MessageInfo> messages = jdbc.query("""
                SELECT m.message_id, m.room_id, m.sender_username, m.content, m.created_at,
                       f.file_id, f.original_name, f.content_type
                FROM messages m LEFT JOIN chat_files f ON f.file_id = m.file_id
                WHERE m.room_id = ? ORDER BY m.message_id DESC LIMIT ?
                """, (row, index) -> {
            long id = row.getLong("message_id");
            return new MessageInfo(id, row.getString("room_id"), row.getString("sender_username"),
                    row.getString("content"), row.getLong("created_at"), row.getString("file_id"),
                    row.getString("original_name"), row.getString("content_type"), readers(id));
        }, roomId, HISTORY_LIMIT);
        java.util.Collections.reverse(messages);
        return messages;
    }

    @Transactional
    public MessageInfo saveText(String roomId, String username, String content) {
        requireMember(roomId, username);
        return saveMessage(roomId, username, content, null);
    }

    @Transactional
    public MessageInfo saveAttachment(String roomId, String username, String fileId, String fileName,
                                      String contentType) {
        requireMember(roomId, username);
        jdbc.update("INSERT INTO chat_files (file_id, room_id, uploaded_by, original_name, content_type, "
                        + "storage_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                fileId, roomId, username, fileName, contentType, fileId, System.currentTimeMillis());
        return saveMessage(roomId, username, "", fileId);
    }

    @Transactional
    public List<String> markRead(String roomId, long messageId, String username) {
        requireMember(roomId, username);
        Integer exists = jdbc.query("SELECT COUNT(*) FROM messages WHERE message_id = ? AND room_id = ?",
                result -> result.next() ? result.getInt(1) : 0, messageId, roomId);
        if (exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found in this room.");
        }
        if (isPostgreSql()) {
            jdbc.update("INSERT INTO message_read_receipts (message_id, username, read_at) VALUES (?, ?, ?) "
                            + "ON CONFLICT (message_id, username) DO NOTHING",
                    messageId, username, System.currentTimeMillis());
        } else {
            try {
                jdbc.update("INSERT INTO message_read_receipts (message_id, username, read_at) VALUES (?, ?, ?)",
                        messageId, username, System.currentTimeMillis());
            } catch (DuplicateKeyException alreadyRead) {
                return readers(messageId);
            }
        }
        return readers(messageId);
    }

    public boolean isMember(String roomId, String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM room_members WHERE room_id = ? AND username = ? AND left_at IS NULL",
                Integer.class, roomId, username);
        return count != null && count > 0;
    }

    public void requireMember(String roomId, String username) {
        if (!roomIsActive(roomId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found.");
        }
        if (!isMember(roomId, username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not a member of this room.");
        }
    }

    private boolean roomIsActive(String roomId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rooms WHERE room_id = ? AND deleted_at IS NULL",
                Integer.class, roomId);
        return count != null && count > 0;
    }

    private void lockActiveRoom(String roomId) {
        List<String> rows = jdbc.query("SELECT room_id FROM rooms "
                        + "WHERE room_id = ? AND deleted_at IS NULL FOR UPDATE",
                (row, index) -> row.getString("room_id"), roomId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found.");
        }
    }

    private boolean lockActiveMember(String roomId, String username) {
        List<String> rows = jdbc.query("SELECT username FROM room_members "
                        + "WHERE room_id = ? AND username = ? AND left_at IS NULL FOR UPDATE",
                (row, index) -> row.getString("username"), roomId, username);
        return !rows.isEmpty();
    }

    public FileRecord file(String fileId, String username) {
        List<FileRecord> files = jdbc.query("""
                SELECT file_id, room_id, original_name, content_type, storage_name
                FROM chat_files WHERE file_id = ?
                """, (row, index) -> new FileRecord(row.getString("file_id"), row.getString("room_id"),
                row.getString("original_name"), row.getString("content_type"), row.getString("storage_name")),
                fileId);
        FileRecord file = files.stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found."));
        requireMember(file.roomId(), username);
        return file;
    }

    private MessageInfo saveMessage(String roomId, String username, String content, String fileId) {
        long createdAt = System.currentTimeMillis();
        Number id;
        if (isPostgreSql()) {
            id = jdbc.queryForObject("""
                    INSERT INTO messages (room_id, sender_username, content, file_id, created_at)
                    VALUES (?, ?, ?, ?, ?) RETURNING message_id
                    """, Long.class, roomId, username, content, fileId, createdAt);
        } else {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO messages (room_id, sender_username, content, file_id, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, roomId);
                statement.setString(2, username);
                statement.setString(3, content);
                statement.setString(4, fileId);
                statement.setLong(5, createdAt);
                return statement;
            }, keyHolder);
            id = keyHolder.getKey();
        }
        if (id == null) {
            throw new IllegalStateException("Database did not return the new message ID.");
        }
        jdbc.update("INSERT INTO message_read_receipts (message_id, username, read_at) VALUES (?, ?, ?)",
                id.longValue(), username, createdAt);
        return message(id.longValue(), roomId, username, content, createdAt, fileId);
    }

    private MessageInfo message(long id, String roomId, String sender, String content, long timestamp,
                                String fileId) {
        String name = null;
        String contentType = null;
        if (fileId != null) {
            var fileInfo = jdbc.query("SELECT original_name, content_type FROM chat_files WHERE file_id = ?",
                    (row, index) -> new String[]{row.getString("original_name"), row.getString("content_type")},
                    fileId).get(0);
            name = fileInfo[0];
            contentType = fileInfo[1];
        }
        return new MessageInfo(id, roomId, sender, content, timestamp, fileId, name, contentType,
                List.of(sender));
    }

    private List<String> readers(long messageId) {
        return jdbc.query("SELECT username FROM message_read_receipts WHERE message_id = ? ORDER BY read_at",
                (row, index) -> row.getString("username"), messageId);
    }

    private List<String> directReaders(long messageId) {
        return jdbc.query("""
                SELECT username FROM direct_message_read_receipts
                WHERE message_id = ? ORDER BY read_at
                """, (row, index) -> row.getString("username"), messageId);
    }

    private MessageInfo directMessage(long id, String conversationId, String sender,
                                      String content, long timestamp) {
        return new MessageInfo(id, conversationId, sender, content, timestamp,
                null, null, null, directReaders(id));
    }

    private DirectConversationInfo directConversation(String conversationId, String otherUsername) {
        return new DirectConversationInfo(conversationId, otherUsername, presence.isOnline(otherUsername));
    }

    private boolean userExists(String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?",
                Integer.class, username);
        return count != null && count > 0;
    }

    private boolean isPostgreSql() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                connection -> "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    public record FileRecord(String fileId, String roomId, String originalName, String contentType,
                             String storageName) {
    }
}
