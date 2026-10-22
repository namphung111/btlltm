package com.chat.platform.api;

import com.chat.platform.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ProfileController {
    private static final long MAX_AVATAR_SIZE = 5L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final AppProperties properties;

    public ProfileController(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @PostMapping("/profile/avatar")
    public AvatarInfo uploadAvatar(@RequestParam("file") MultipartFile upload,
                                   HttpServletRequest request) throws IOException {
        String username = username(request);
        if (upload.isEmpty() || upload.getSize() > MAX_AVATAR_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Avatar size must be between 1 byte and 5 MB.");
        }
        String contentType = upload.getContentType();
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar must be an image.");
        }

        String avatarFile = UUID.randomUUID().toString();
        Path uploadDirectory = properties.uploadDir().toAbsolutePath().normalize();
        Files.createDirectories(uploadDirectory);
        Path destination = uploadDirectory.resolve(avatarFile + ".bin").normalize();
        if (!destination.startsWith(uploadDirectory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid avatar path.");
        }

        try {
            upload.transferTo(destination);
            String validatedContentType = validateImage(destination);
            jdbc.update("UPDATE users SET avatar_file = ?, avatar_content_type = ? WHERE username = ?",
                    avatarFile, validatedContentType, username);
            return new AvatarInfo(username, validatedContentType);
        } catch (RuntimeException | IOException e) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    @GetMapping("/users/{username}/avatar")
    public ResponseEntity<Resource> avatar(@PathVariable String username) {
        AvatarRecord avatar = jdbc.query(
                        "SELECT avatar_file, avatar_content_type FROM users WHERE username = ?",
                        (row, index) -> new AvatarRecord(row.getString("avatar_file"),
                                row.getString("avatar_content_type")), username)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));
        if (avatar.fileId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User has no avatar.");
        }
        Path uploadDirectory = properties.uploadDir().toAbsolutePath().normalize();
        Path storedPath = uploadDirectory.resolve(avatar.fileId() + ".bin").normalize();
        if (!storedPath.startsWith(uploadDirectory) || !Files.isRegularFile(storedPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Avatar file is unavailable.");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(new FileSystemResource(storedPath));
    }

    private String username(HttpServletRequest request) {
        return (String) request.getAttribute(com.chat.platform.auth.TokenAuthInterceptor.USERNAME_ATTRIBUTE);
    }

    private String validateImage(Path file) throws IOException {
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
                            "Avatars must not exceed 20 megapixels.");
                }
                return switch (reader.getFormatName().toLowerCase(java.util.Locale.ROOT)) {
                    case "jpeg", "jpg" -> MediaType.IMAGE_JPEG_VALUE;
                    case "png" -> MediaType.IMAGE_PNG_VALUE;
                    case "gif" -> MediaType.IMAGE_GIF_VALUE;
                    case "bmp" -> "image/bmp";
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unsupported avatar image format.");
                };
            } finally {
                reader.dispose();
            }
        }
    }

    public record AvatarInfo(String username, String contentType) {
    }

    private record AvatarRecord(String fileId, String contentType) {
    }
}
