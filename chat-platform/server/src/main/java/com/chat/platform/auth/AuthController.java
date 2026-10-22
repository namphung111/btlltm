package com.chat.platform.auth;

import com.chat.platform.api.ApiModels.AuthResponse;
import com.chat.platform.api.ApiModels.Credentials;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final TokenService tokens;

    public AuthController(JdbcTemplate jdbc, TokenService tokens) {
        this.jdbc = jdbc;
        this.tokens = tokens;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody Credentials credentials) {
        String username = credentials.username().trim();
        if (!username.matches("[A-Za-z0-9_.-]{3,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Username can contain letters, digits, dot, underscore and hyphen.");
        }
        if (userExists(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already registered.");
        }
        try {
            jdbc.update("INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                    username, passwordEncoder.encode(credentials.password()), System.currentTimeMillis());
        } catch (DuplicateKeyException alreadyRegistered) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already registered.");
        }
        return new AuthResponse(username, tokens.issue(username));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody Credentials credentials) {
        String username = credentials.username().trim();
        var users = jdbc.query("SELECT password_hash FROM users WHERE username = ?",
                (row, index) -> row.getString("password_hash"), username);
        if (users.isEmpty() || !passwordEncoder.matches(credentials.password(), users.get(0))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
        }
        return new AuthResponse(username, tokens.issue(username));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        String token = authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()) : null;
        if (!tokens.revoke(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token.");
        }
    }

    private boolean userExists(String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?",
                Integer.class, username);
        return count != null && count > 0;
    }
}
