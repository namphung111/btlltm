package com.chat.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Path uploadDir, boolean demoData) {
}
