package com.chat.platform.auth;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {
    private static final long TOKEN_LIFETIME_MILLIS = Duration.ofHours(24).toMillis();
    private final ConcurrentHashMap<String, TokenEntry> tokens = new ConcurrentHashMap<>();

    public String issue(String username) {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        String token = UUID.randomUUID().toString() + UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, new TokenEntry(username, now + TOKEN_LIFETIME_MILLIS));
        return token;
    }

    public String findUsername(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        TokenEntry entry = tokens.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt() <= System.currentTimeMillis()) {
            tokens.remove(token, entry);
            return null;
        }
        return entry.username();
    }

    public boolean revoke(String token) {
        return token != null && tokens.remove(token) != null;
    }

    private record TokenEntry(String username, long expiresAt) {
    }
}
