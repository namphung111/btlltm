package com.chat.platform.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.demo-data", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DemoDataInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String username : new String[]{"alice", "bob", "charlie"}) {
            jdbc.update("""
                    INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)
                    ON CONFLICT (username) DO NOTHING
                    """, username, encoder.encode("ChatDemo123!"), System.currentTimeMillis());
        }
        ensureRoom("demo-room-general", "Phòng chung", "alice",
                new String[]{"alice", "bob", "charlie"}, "Chào mọi người, đây là phòng chat mẫu.");
        ensureRoom("demo-room-team", "Nhóm bài tập", "alice",
                new String[]{"alice", "bob"}, "Đây là lịch sử mẫu của nhóm bài tập.");
    }

    private void ensureRoom(String roomId, String name, String owner, String[] members, String firstMessage) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO rooms (room_id, room_name, created_by, created_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (room_id) DO NOTHING
                """, roomId, name, owner, now);
        for (String member : members) {
            jdbc.update("""
                    INSERT INTO room_members (room_id, username, joined_at, role) VALUES (?, ?, ?, ?)
                    ON CONFLICT (room_id, username) DO NOTHING
                    """, roomId, member, now, member.equals(owner) ? "LEADER" : "MEMBER");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE room_id = ? AND content = ?",
                Integer.class, roomId, firstMessage);
        if (count == null || count == 0) {
            Long messageId = jdbc.queryForObject("""
                    INSERT INTO messages (room_id, sender_username, content, created_at)
                    VALUES (?, ?, ?, ?) RETURNING message_id
                    """, Long.class, roomId, owner, firstMessage, now);
            jdbc.update("""
                    INSERT INTO message_read_receipts (message_id, username, read_at) VALUES (?, ?, ?)
                    """, messageId, owner, now);
        }
    }
}
