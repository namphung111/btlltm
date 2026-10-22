package com.chat.platform.api;

import com.chat.platform.api.ApiModels.CreateRoomRequest;
import com.chat.platform.api.ApiModels.AddRoomMembersRequest;
import com.chat.platform.api.ApiModels.LeaveRoomRequest;
import com.chat.platform.api.ApiModels.DirectConversationInfo;
import com.chat.platform.api.ApiModels.MessageInfo;
import com.chat.platform.api.ApiModels.RoomInfo;
import com.chat.platform.api.ApiModels.RoomMember;
import com.chat.platform.api.ApiModels.TransferLeadershipRequest;
import com.chat.platform.chat.ChatService;
import com.chat.platform.chat.ChatWebSocketHandler;
import com.chat.platform.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChatController {
    private static final long MAX_UPLOAD_SIZE = 10L * 1024 * 1024;
    private final ChatService chat;
    private final AppProperties properties;
    private final ChatWebSocketHandler webSocket;

    public ChatController(ChatService chat, AppProperties properties, ChatWebSocketHandler webSocket) {
        this.chat = chat;
        this.properties = properties;
        this.webSocket = webSocket;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok");
    }

    @GetMapping("/rooms")
    public List<RoomInfo> rooms(HttpServletRequest request) {
        return chat.roomsFor(username(request));
    }

    @GetMapping("/direct-conversations")
    public List<DirectConversationInfo> directConversations(HttpServletRequest request) {
        return chat.directConversationsFor(username(request));
    }

    @PostMapping("/direct-conversations/{otherUsername}")
    public DirectConversationInfo openDirectConversation(@PathVariable String otherUsername,
                                                         HttpServletRequest request) {
        return chat.openDirectConversation(username(request), otherUsername);
    }

    @GetMapping("/direct-conversations/{conversationId}/messages")
    public List<MessageInfo> directHistory(@PathVariable String conversationId,
                                           HttpServletRequest request) {
        return chat.directHistory(conversationId, username(request));
    }

    @PostMapping("/rooms")
    public RoomInfo createRoom(@Valid @RequestBody CreateRoomRequest body,
                               HttpServletRequest request) {
        RoomInfo room = chat.createRoom(body.name(), username(request), body.members());
        webSocket.notifyRoomMembers(Set.copyOf(chat.activeMemberUsernames(room.roomId())),
                Map.of("type", "group_created", "roomId", room.roomId()));
        return room;
    }

    @PostMapping("/rooms/{roomId}/leave")
    public void leaveRoom(@PathVariable String roomId,
                          @Valid @RequestBody(required = false) LeaveRoomRequest body,
                          HttpServletRequest request) {
        String leavingUser = username(request);
        Set<String> recipients = Set.copyOf(chat.activeMemberUsernames(roomId));
        chat.leaveRoom(roomId, leavingUser, body == null ? null : body.successorUsername());
        webSocket.notifyRoomMembers(recipients, Map.of("type", "group_member_left",
                "roomId", roomId, "username", leavingUser));
        webSocket.unsubscribeRoomUsers(roomId, Set.of(leavingUser));
    }

    @PostMapping("/rooms/{roomId}/transfer-leadership")
    public void transferLeadership(@PathVariable String roomId,
                                   @Valid @RequestBody TransferLeadershipRequest body,
                                   HttpServletRequest request) {
        chat.transferLeadership(roomId, username(request), body.successorUsername());
        webSocket.notifyRoomMembers(Set.copyOf(chat.activeMemberUsernames(roomId)),
                Map.of("type", "group_leader_changed", "roomId", roomId,
                        "username", body.successorUsername()));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/rooms/{roomId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRoom(@PathVariable String roomId, HttpServletRequest request) {
        Set<String> recipients = Set.copyOf(chat.activeMemberUsernames(roomId));
        chat.deleteRoom(roomId, username(request));
        webSocket.notifyRoomMembers(recipients,
                Map.of("type", "group_deleted", "roomId", roomId));
        webSocket.unsubscribeRoomUsers(roomId, recipients);
    }

    @PostMapping("/rooms/{roomId}/members")
    public void addMembers(@PathVariable String roomId,
                           @Valid @RequestBody AddRoomMembersRequest body,
                           HttpServletRequest request) {
        chat.addMembers(roomId, username(request), body.members());
        webSocket.notifyRoomMembers(Set.copyOf(chat.activeMemberUsernames(roomId)),
                Map.of("type", "group_members_changed", "roomId", roomId));
    }

    @PostMapping("/rooms/{roomId}/join")
    public RoomInfo joinRoom(@PathVariable String roomId, HttpServletRequest request) {
        return chat.joinRoom(roomId, username(request));
    }

    @GetMapping("/rooms/{roomId}/members")
    public List<RoomMember> members(@PathVariable String roomId, HttpServletRequest request) {
        return chat.members(roomId, username(request));
    }

    @GetMapping("/rooms/{roomId}/messages")
    public List<MessageInfo> history(@PathVariable String roomId, HttpServletRequest request) {
        return chat.history(roomId, username(request));
    }

    @PostMapping("/rooms/{roomId}/files")
    public MessageInfo upload(@PathVariable String roomId, @RequestParam("file") MultipartFile upload,
                              HttpServletRequest request) throws IOException {
        String username = username(request);
        chat.requireMember(roomId, username);
        if (upload.isEmpty() || upload.getSize() > MAX_UPLOAD_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File size must be between 1 byte and 10 MB.");
        }
        String originalName = safeFileName(upload.getOriginalFilename());
        String contentType = upload.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : upload.getContentType();
        String fileId = UUID.randomUUID().toString();
        Path uploadDirectory = properties.uploadDir().toAbsolutePath().normalize();
        Files.createDirectories(uploadDirectory);
        Path destination = uploadDirectory.resolve(fileId + ".bin").normalize();
        if (!destination.startsWith(uploadDirectory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file path.");
        }
        try {
            upload.transferTo(destination);
            validateImage(destination, contentType);
            MessageInfo saved = chat.saveAttachment(roomId, username, fileId, originalName, contentType);
            webSocket.broadcastMessage(roomId, saved);
            return saved;
        } catch (RuntimeException | IOException e) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable String fileId, HttpServletRequest request) {
        ChatService.FileRecord file = chat.file(fileId, username(request));
        Path uploadDirectory = properties.uploadDir().toAbsolutePath().normalize();
        Path storedPath = uploadDirectory.resolve(file.storageName() + ".bin").normalize();
        if (!storedPath.startsWith(uploadDirectory) || !Files.isRegularFile(storedPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored file is unavailable.");
        }
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(file.contentType());
        } catch (IllegalArgumentException e) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(file.originalName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(storedPath));
    }

    private String username(HttpServletRequest request) {
        return (String) request.getAttribute(com.chat.platform.auth.TokenAuthInterceptor.USERNAME_ATTRIBUTE);
    }

    private String safeFileName(String name) {
        String normalized = name == null ? "attachment" : name.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file name.");
        }
        return normalized;
    }

    private void validateImage(Path file, String contentType) throws IOException {
        if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            return;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image file.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or invalid image file.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > 20_000_000L) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Images must not exceed 20 megapixels.");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    public record HealthResponse(String status) {
    }
}
