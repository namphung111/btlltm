package com.chat.platform.api;

import com.chat.platform.chat.PresenceRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api")
public class UserController {
    private final JdbcTemplate jdbc;
    private final PresenceRegistry presence;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserController(JdbcTemplate jdbc, PresenceRegistry presence) {
        this.jdbc = jdbc;
        this.presence = presence;
    }

    @GetMapping("/users")
    public List<UserInfo> search(@RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        String currentUser = username(request);
        String query = q.trim();
        if (query.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search text is too long.");
        }
        List<UserInfo> users = jdbc.query("""
                SELECT username, COALESCE(display_name, username) AS display_name
                FROM users
                WHERE username <> ? AND (LOWER(username) LIKE LOWER(?) OR LOWER(COALESCE(display_name, username)) LIKE LOWER(?))
                ORDER BY LOWER(COALESCE(display_name, username)), username LIMIT 50
                """, (row, index) -> new UserInfo(row.getString("username"),
                row.getString("display_name"), false), currentUser, "%" + query + "%", "%" + query + "%");
        return users.stream().map(user -> new UserInfo(user.username(), user.displayName(),
                presence.isOnline(user.username()))).toList();
    }

    @PatchMapping("/profile")
    public UserInfo updateProfile(@Valid @RequestBody ProfileUpdate body, HttpServletRequest request) {
        String username = username(request);
        jdbc.update("UPDATE users SET display_name = ? WHERE username = ?", body.displayName().trim(), username);
        return new UserInfo(username, body.displayName().trim(), presence.isOnline(username));
    }

    @PatchMapping("/profile/password")
    public void changePassword(@Valid @RequestBody PasswordChange body, HttpServletRequest request) {
        String username = username(request);
        String currentHash = jdbc.query("SELECT password_hash FROM users WHERE username = ?",
                (row, index) -> row.getString("password_hash"), username).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account not found."));
        if (!passwordEncoder.matches(body.currentPassword(), currentHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        }
        jdbc.update("UPDATE users SET password_hash = ? WHERE username = ?",
                passwordEncoder.encode(body.newPassword()), username);
    }

    private String username(HttpServletRequest request) {
        return (String) request.getAttribute(com.chat.platform.auth.TokenAuthInterceptor.USERNAME_ATTRIBUTE);
    }

    public record UserInfo(String username, String displayName, boolean online) { }

    public record ProfileUpdate(@NotBlank @Size(max = 100) String displayName) { }

    public record PasswordChange(@NotBlank String currentPassword,
                                 @NotBlank @Size(min = 8, max = 128) String newPassword) { }
}
