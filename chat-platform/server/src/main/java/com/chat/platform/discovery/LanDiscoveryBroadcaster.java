package com.chat.platform.discovery;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

@Component
public final class LanDiscoveryBroadcaster {
    private static final int DISCOVERY_PORT = 39555;
    private static final String DISCOVERY_REQUEST = "CHAT_PLATFORM_DISCOVER";
    private static final String DISCOVERY_RESPONSE_PREFIX = "CHAT_PLATFORM_SERVER|";

    @Value("${server.port:8080}")
    private int serverPort;

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread worker;

    @PostConstruct
    void start() {
        running = true;
        worker = new Thread(this::listen, "chat-lan-discovery");
        worker.setDaemon(true);
        worker.start();
    }

    private void listen() {
        try (DatagramSocket discoverySocket = new DatagramSocket(DISCOVERY_PORT, InetAddress.getByName("0.0.0.0"))) {
            socket = discoverySocket;
            byte[] responseBytes = (DISCOVERY_RESPONSE_PREFIX + serverPort).getBytes(StandardCharsets.UTF_8);
            byte[] requestBytes = new byte[128];
            while (running) {
                DatagramPacket request = new DatagramPacket(requestBytes, requestBytes.length);
                discoverySocket.receive(request);
                String payload = new String(request.getData(), request.getOffset(), request.getLength(),
                        StandardCharsets.UTF_8);
                if (DISCOVERY_REQUEST.equals(payload)) {
                    discoverySocket.send(new DatagramPacket(responseBytes, responseBytes.length,
                            request.getAddress(), request.getPort()));
                }
            }
        } catch (IOException ignored) {
            // Discovery is optional; the REST/WebSocket server remains available.
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }
}
