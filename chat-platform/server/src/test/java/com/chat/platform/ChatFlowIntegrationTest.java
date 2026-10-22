package com.chat.platform;

import com.chat.platform.api.ApiModels.MessageInfo;
import com.chat.platform.chat.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@AutoConfigureMockMvc
class ChatFlowIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChatService chat;
    @Autowired
    private ObjectMapper mapper;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:h2:mem:chat_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        properties.add("spring.datasource.username", () -> "sa");
        properties.add("spring.datasource.password", () -> "");
        properties.add("app.upload-dir", () -> "target/test-uploads");
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM direct_message_read_receipts");
        jdbc.update("DELETE FROM direct_messages");
        jdbc.update("DELETE FROM direct_conversations");
        jdbc.update("DELETE FROM message_read_receipts");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM chat_files");
        jdbc.update("DELETE FROM room_members");
        jdbc.update("DELETE FROM rooms");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void usersCanOpenUniqueDirectConversationAndExchangeMessages() throws Exception {
        String aliceToken = registerAndGetToken("alice");
        String bobToken = registerAndGetToken("bob");

        String firstBody = mvc.perform(post("/api/direct-conversations/bob")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String conversationId = mapper.readTree(firstBody).path("conversationId").asText();
        String repeatedBody = mvc.perform(post("/api/direct-conversations/alice")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(repeatedBody).path("conversationId").asText())
                .isEqualTo(conversationId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rooms", Integer.class)).isZero();
        mvc.perform(post("/api/direct-conversations/alice")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
        String charlieToken = registerAndGetToken("charlie");
        mvc.perform(get("/api/direct-conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + charlieToken))
                .andExpect(status().isForbidden());

        MessageInfo saved = chat.saveDirectText(conversationId, "alice", "Chào bạn");
        List<String> readers = chat.markDirectRead(conversationId, saved.messageId(), "bob");
        String historyBody = mvc.perform(get("/api/direct-conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode history = mapper.readTree(historyBody);

        assertThat(readers).containsExactlyInAnyOrder("alice", "bob");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("content").asText()).isEqualTo("Chào bạn");
        assertThat(history.get(0).path("readBy").toString()).contains("\"alice\"", "\"bob\"");
    }

    private String registerAndGetToken(String username) throws Exception {
        String authBody = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of(
                                "username", username, "password", "correct-horse-123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(authBody).path("token").asText();
    }

    @Test
    void registersCreatesRoomSendsMessageAndPersistsReadReceipt() throws Exception {
        String authBody = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of(
                                "username", "alice", "password", "correct-horse-123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode auth = mapper.readTree(authBody);

        String roomBody = mvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + auth.path("token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("name", "Test room"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String roomId = mapper.readTree(roomBody).path("roomId").asText();
        jdbc.update("INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                "bob", "unused-test-hash", System.currentTimeMillis());
        jdbc.update("INSERT INTO room_members (room_id, username, joined_at) VALUES (?, ?, ?)",
                roomId, "bob", System.currentTimeMillis());

        MessageInfo message = chat.saveText(roomId, "alice", "Realtime message");
        List<String> readers = chat.markRead(roomId, message.messageId(), "bob");
        List<String> repeatedRead = chat.markRead(roomId, message.messageId(), "bob");
        chat.joinRoom(roomId, "bob");
        List<MessageInfo> history = chat.history(roomId, "alice");

        assertThat(readers).containsExactlyInAnyOrder("alice", "bob");
        assertThat(repeatedRead).containsExactlyInAnyOrder("alice", "bob");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).content()).isEqualTo("Realtime message");
        assertThat(history.get(0).readBy()).containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void logoutRevokesBearerToken() throws Exception {
        String authBody = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of(
                                "username", "alice", "password", "correct-horse-123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(authBody).path("token").asText();

        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usersCanUploadAndReadTheirAvatar() throws Exception {
        String authBody = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of(
                                "username", "alice", "password", "correct-horse-123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(authBody).path("token").asText();
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
        byte[] avatar = imageBytes.toByteArray();
        MockMultipartFile upload = new MockMultipartFile(
                "file", "avatar.png", MediaType.IMAGE_PNG_VALUE, avatar);

        mvc.perform(multipart("/api/profile/avatar").file(upload)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/alice/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(avatar));
    }
}
