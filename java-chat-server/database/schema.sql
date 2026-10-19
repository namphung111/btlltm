CREATE DATABASE IF NOT EXISTS chat_app
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_bin;

USE chat_app;

CREATE TABLE IF NOT EXISTS users (
    username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    password_hash VARCHAR(88) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (username)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS rooms (
    room_id VARCHAR(36) NOT NULL,
    room_name VARCHAR(100) NOT NULL,
    room_type VARCHAR(20) NOT NULL,
    created_by VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (room_id),
    KEY idx_rooms_created_by (created_by),
    CONSTRAINT fk_rooms_created_by
        FOREIGN KEY (created_by) REFERENCES users (username)
        ON UPDATE CASCADE
        ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS room_members (
    room_id VARCHAR(36) NOT NULL,
    username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    joined_at BIGINT NOT NULL,
    PRIMARY KEY (room_id, username),
    KEY idx_room_members_username (username),
    CONSTRAINT fk_room_members_room
        FOREIGN KEY (room_id) REFERENCES rooms (room_id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    CONSTRAINT fk_room_members_user
        FOREIGN KEY (username) REFERENCES users (username)
        ON UPDATE CASCADE
        ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS messages (
    message_id BIGINT NOT NULL AUTO_INCREMENT,
    room_id VARCHAR(36) NOT NULL,
    sender_username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    content TEXT NOT NULL,
    file_name VARCHAR(255) NULL,
    file_data MEDIUMBLOB NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (message_id),
    KEY idx_messages_room_time (room_id, message_id),
    KEY idx_messages_sender (sender_username),
    CONSTRAINT fk_messages_room
        FOREIGN KEY (room_id) REFERENCES rooms (room_id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender
        FOREIGN KEY (sender_username) REFERENCES users (username)
        ON UPDATE CASCADE
        ON DELETE RESTRICT
) ENGINE = InnoDB;

INSERT IGNORE INTO users (username, password_hash, created_at) VALUES
    ('alice', 'aQUT/UbklxXjOm1PDIz20Z8du7qUPfog4paPRnUNk2w=', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)),
    ('bob', 'aQUT/UbklxXjOm1PDIz20Z8du7qUPfog4paPRnUNk2w=', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)),
    ('charlie', 'aQUT/UbklxXjOm1PDIz20Z8du7qUPfog4paPRnUNk2w=', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED));

INSERT IGNORE INTO rooms (room_id, room_name, room_type, created_by, created_at) VALUES
    ('demo-room-general', 'Phòng chung', 'GROUP', 'alice', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)),
    ('demo-room-team', 'Nhóm bài tập', 'GROUP', 'alice', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED));

INSERT IGNORE INTO room_members (room_id, username, joined_at) VALUES
    ('demo-room-general', 'alice', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)),
    ('demo-room-general', 'bob', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)),
    ('demo-room-general', 'charlie', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)),
    ('demo-room-team', 'alice', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)),
    ('demo-room-team', 'bob', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED));

INSERT INTO messages (room_id, sender_username, content, created_at)
SELECT 'demo-room-general', 'alice', 'Chào mọi người, đây là phòng chat mẫu.',
       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE NOT EXISTS (
    SELECT 1 FROM messages
    WHERE room_id = 'demo-room-general'
      AND sender_username = 'alice'
      AND content = 'Chào mọi người, đây là phòng chat mẫu.'
);

INSERT INTO messages (room_id, sender_username, content, created_at)
SELECT 'demo-room-general', 'bob', 'Mình đã nhận được tin nhắn realtime.',
       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE NOT EXISTS (
    SELECT 1 FROM messages
    WHERE room_id = 'demo-room-general'
      AND sender_username = 'bob'
      AND content = 'Mình đã nhận được tin nhắn realtime.'
);

INSERT INTO messages (room_id, sender_username, content, created_at)
SELECT 'demo-room-general', 'charlie', 'Có thể gửi tin nhắn mới để thử nhé!',
       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE NOT EXISTS (
    SELECT 1 FROM messages
    WHERE room_id = 'demo-room-general'
      AND sender_username = 'charlie'
      AND content = 'Có thể gửi tin nhắn mới để thử nhé!'
);

INSERT INTO messages (room_id, sender_username, content, created_at)
SELECT 'demo-room-team', 'alice', 'Đây là lịch sử mẫu của nhóm bài tập.',
       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE NOT EXISTS (
    SELECT 1 FROM messages
    WHERE room_id = 'demo-room-team'
      AND sender_username = 'alice'
      AND content = 'Đây là lịch sử mẫu của nhóm bài tập.'
);

INSERT INTO messages (room_id, sender_username, content, created_at)
SELECT 'demo-room-team', 'bob', 'Hãy nhập ID phòng này để tham gia: demo-room-team',
       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE NOT EXISTS (
    SELECT 1 FROM messages
    WHERE room_id = 'demo-room-team'
      AND sender_username = 'bob'
      AND content = 'Hãy nhập ID phòng này để tham gia: demo-room-team'
);
