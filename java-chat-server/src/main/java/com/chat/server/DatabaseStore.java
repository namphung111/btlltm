package com.chat.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class DatabaseStore {
    private final String url;
    private final String username;
    private final String password;

    public DatabaseStore() throws SQLException {
        url = setting("chat.db.url", "CHAT_DB_URL",
                "jdbc:mysql://localhost:3306/chat_app?serverTimezone=UTC");
        username = setting("chat.db.user", "CHAT_DB_USER", "root");
        password = setting("chat.db.password", "CHAT_DB_PASSWORD", "");
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL Connector/J is missing. Build the project with Maven.", e);
        }
        initializeSchema();
    }

    public List<User> loadUsers() throws SQLException {
        List<User> result = new ArrayList<>();
        String sql = "SELECT username, password_hash FROM users ORDER BY username";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(User.fromStoredHash(rows.getString("username"), rows.getString("password_hash")));
            }
        }
        return result;
    }

    public List<ChatRoom> loadRooms() throws SQLException {
        List<ChatRoom> result = new ArrayList<>();
        String roomSql = "SELECT room_id, room_name, room_type FROM rooms ORDER BY created_at, room_id";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(roomSql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new ChatRoom(rows.getString("room_id"),
                        rows.getString("room_name"), rows.getString("room_type")));
            }
        }

        for (ChatRoom room : result) {
            loadRoomMembers(room);
            loadRoomMessages(room);
        }
        return result;
    }

    public boolean insertUser(User user) throws SQLException {
        String sql = "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, user.getUsername());
            statement.setString(2, user.getPasswordHash());
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            if ("23000".equals(e.getSQLState()) && e.getErrorCode() == 1062) {
                return false;
            }
            throw e;
        }
    }

    public void insertRoom(ChatRoom room, String owner) throws SQLException {
        String roomSql = "INSERT INTO rooms (room_id, room_name, room_type, created_by, created_at) "
                + "VALUES (?, ?, ?, ?, ?)";
        String memberSql = "INSERT INTO room_members (room_id, username, joined_at) VALUES (?, ?, ?)";
        String messageSql = "INSERT INTO messages "
                + "(room_id, sender_username, content, file_name, file_data, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement roomStatement = connection.prepareStatement(roomSql);
                 PreparedStatement memberStatement = connection.prepareStatement(memberSql);
                 PreparedStatement messageStatement = connection.prepareStatement(messageSql, Statement.RETURN_GENERATED_KEYS)) {
                long now = System.currentTimeMillis();
                roomStatement.setString(1, room.getRoomId());
                roomStatement.setString(2, room.getRoomName());
                roomStatement.setString(3, room.getType());
                roomStatement.setString(4, owner);
                roomStatement.setLong(5, now);
                roomStatement.executeUpdate();

                for (String member : room.getMembers()) {
                    memberStatement.setString(1, room.getRoomId());
                    memberStatement.setString(2, member);
                    memberStatement.setLong(3, now);
                    memberStatement.addBatch();
                }
                memberStatement.executeBatch();

                for (ChatMessage message : room.getHistory()) {
                    setMessage(messageStatement, message);
                    messageStatement.executeUpdate();
                    try (ResultSet keys = messageStatement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Could not retrieve the ID for a sample message.");
                        }
                        message.setMessageId(keys.getLong(1));
                    }
                    message.addReader(message.getSender());
                    insertReadReceipt(connection, message.getMessageId(), message.getSender());
                }
                connection.commit();
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        }
    }

    public void insertRoomMember(String roomId, String username) throws SQLException {
        String sql = "INSERT INTO room_members (room_id, username, joined_at) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE joined_at = joined_at";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, roomId);
            statement.setString(2, username);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public void insertMessage(ChatMessage message) throws SQLException {
        String sql = "INSERT INTO messages "
                + "(room_id, sender_username, content, file_name, file_data, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                setMessage(statement, message);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Could not retrieve the ID for the saved message.");
                    }
                    message.setMessageId(keys.getLong(1));
                }
                message.addReader(message.getSender());
                insertReadReceipt(connection, message.getMessageId(), message.getSender());
                connection.commit();
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        }
    }

    public List<String> markMessageRead(long messageId, String roomId, String username) throws SQLException {
        String verifySql = "SELECT 1 FROM messages m "
                + "JOIN room_members rm ON rm.room_id = m.room_id AND rm.username = ? "
                + "WHERE m.message_id = ? AND m.room_id = ?";
        try (Connection connection = connect()) {
            try (PreparedStatement verify = connection.prepareStatement(verifySql)) {
                verify.setString(1, username);
                verify.setLong(2, messageId);
                verify.setString(3, roomId);
                try (ResultSet result = verify.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("Message does not exist in this room or user is not a member.");
                    }
                }
            }
            insertReadReceipt(connection, messageId, username);
            return loadReaders(connection, messageId);
        }
    }

    private void initializeSchema() throws SQLException {
        List<String> statements = List.of(
                """
                CREATE TABLE IF NOT EXISTS users (
                    username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
                    password_hash VARCHAR(88) NOT NULL,
                    created_at BIGINT NOT NULL
                ) ENGINE = InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS rooms (
                    room_id VARCHAR(36) NOT NULL PRIMARY KEY,
                    room_name VARCHAR(100) NOT NULL,
                    room_type VARCHAR(20) NOT NULL,
                    created_by VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
                    created_at BIGINT NOT NULL,
                    KEY idx_rooms_created_by (created_by),
                    CONSTRAINT fk_rooms_created_by
                        FOREIGN KEY (created_by) REFERENCES users (username)
                        ON UPDATE CASCADE ON DELETE RESTRICT
                ) ENGINE = InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS room_members (
                    room_id VARCHAR(36) NOT NULL,
                    username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
                    joined_at BIGINT NOT NULL,
                    PRIMARY KEY (room_id, username),
                    KEY idx_room_members_username (username),
                    CONSTRAINT fk_room_members_room
                        FOREIGN KEY (room_id) REFERENCES rooms (room_id)
                        ON UPDATE CASCADE ON DELETE CASCADE,
                    CONSTRAINT fk_room_members_user
                        FOREIGN KEY (username) REFERENCES users (username)
                        ON UPDATE CASCADE ON DELETE CASCADE
                ) ENGINE = InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS messages (
                    message_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    room_id VARCHAR(36) NOT NULL,
                    sender_username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
                    content TEXT NOT NULL,
                    file_name VARCHAR(255) NULL,
                    file_data MEDIUMBLOB NULL,
                    created_at BIGINT NOT NULL,
                    KEY idx_messages_room_time (room_id, message_id),
                    KEY idx_messages_sender (sender_username),
                    CONSTRAINT fk_messages_room
                        FOREIGN KEY (room_id) REFERENCES rooms (room_id)
                        ON UPDATE CASCADE ON DELETE CASCADE,
                    CONSTRAINT fk_messages_sender
                        FOREIGN KEY (sender_username) REFERENCES users (username)
                        ON UPDATE CASCADE ON DELETE RESTRICT
                ) ENGINE = InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS message_read_receipts (
                    message_id BIGINT NOT NULL,
                    username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
                    read_at BIGINT NOT NULL,
                    PRIMARY KEY (message_id, username),
                    KEY idx_message_read_receipts_username (username),
                    CONSTRAINT fk_message_read_receipts_message
                        FOREIGN KEY (message_id) REFERENCES messages (message_id)
                        ON UPDATE CASCADE ON DELETE CASCADE,
                    CONSTRAINT fk_message_read_receipts_user
                        FOREIGN KEY (username) REFERENCES users (username)
                        ON UPDATE CASCADE ON DELETE CASCADE
                ) ENGINE = InnoDB
                """
        );
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.executeUpdate(sql);
            }
        }
    }

    private void loadRoomMembers(ChatRoom room) throws SQLException {
        String sql = "SELECT username FROM room_members WHERE room_id = ? ORDER BY joined_at, username";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, room.getRoomId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    room.addMember(rows.getString("username"));
                }
            }
        }
    }

    private void loadRoomMessages(ChatRoom room) throws SQLException {
        String sql = "SELECT m.message_id, m.sender_username, m.content, m.file_name, m.file_data, m.created_at, "
                + "r.username AS reader_username FROM messages m "
                + "LEFT JOIN message_read_receipts r ON r.message_id = m.message_id "
                + "WHERE m.room_id = ? ORDER BY m.message_id, r.username";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, room.getRoomId());
            try (ResultSet rows = statement.executeQuery()) {
                ChatMessage current = null;
                long currentId = -1;
                while (rows.next()) {
                    long messageId = rows.getLong("message_id");
                    if (current == null || currentId != messageId) {
                        if (current != null) {
                            room.addMessage(current);
                        }
                        currentId = messageId;
                        current = new ChatMessage(room.getRoomId(),
                                rows.getString("sender_username"),
                                rows.getString("content"),
                                rows.getString("file_name"),
                                rows.getBytes("file_data"),
                                rows.getLong("created_at"),
                                messageId);
                    }
                    current.addReader(rows.getString("reader_username"));
                }
                if (current != null) {
                    room.addMessage(current);
                }
            }
        }
    }

    private void insertReadReceipt(Connection connection, long messageId, String username) throws SQLException {
        String sql = "INSERT IGNORE INTO message_read_receipts (message_id, username, read_at) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, messageId);
            statement.setString(2, username);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private List<String> loadReaders(Connection connection, long messageId) throws SQLException {
        String sql = "SELECT username FROM message_read_receipts WHERE message_id = ? ORDER BY read_at, username";
        List<String> readers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, messageId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    readers.add(rows.getString("username"));
                }
            }
        }
        return readers;
    }

    private void setMessage(PreparedStatement statement, ChatMessage message) throws SQLException {
        statement.setString(1, message.getRoomId());
        statement.setString(2, message.getSender());
        statement.setString(3, message.getContent());
        statement.setString(4, message.getFileName());
        statement.setBytes(5, message.getFileData());
        statement.setLong(6, message.getTimestamp());
    }

    private void rollback(Connection connection, SQLException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private static String setting(String property, String environment, String defaultValue) {
        String configured = System.getProperty(property);
        if (configured != null) {
            return configured;
        }
        return System.getenv().getOrDefault(environment, defaultValue);
    }
}
