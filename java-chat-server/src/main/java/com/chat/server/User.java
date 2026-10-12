package com.chat.server;

import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String passwordHash;
    private volatile boolean online;
    private volatile long lastSeen;

    public User(String username, String password) {
        this.username = username;
        this.passwordHash = hashPassword(password);
        this.lastSeen = System.currentTimeMillis();
    }

    public String getUsername() {
        return username;
    }

    public boolean matchesPassword(String rawPassword) {
        return passwordHash.equals(hashPassword(rawPassword));
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
        this.lastSeen = System.currentTimeMillis();
    }

    public long getLastSeen() {
        return lastSeen;
    }

    private static String hashPassword(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
