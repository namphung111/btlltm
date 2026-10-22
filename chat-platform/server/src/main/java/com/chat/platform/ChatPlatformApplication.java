package com.chat.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatPlatformApplication.class, args);
    }
}
