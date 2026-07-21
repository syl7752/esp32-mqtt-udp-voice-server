package io.github.esp32voice.udp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class VoiceControlListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(VoiceControlListener.class);
    private final ObjectMapper json;
    private final UdpAudioHandler handler;

    public VoiceControlListener(ObjectMapper json, UdpAudioHandler handler) {
        this.json = json;
        this.handler = handler;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode event = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            String sessionId = event.path("sessionId").asText();
            switch (event.path("type").asText()) {
                case "abort" -> {
                    log.info("Received abort control for session {}", sessionId);
                    handler.abort(sessionId);
                }
                case "disconnect" -> {
                    log.info("Received disconnect control for session {}", sessionId);
                    handler.remove(sessionId);
                }
                default -> { }
            }
        } catch (Exception e) {
            log.warn("Ignored invalid voice control event: {}", e.getMessage());
        }
    }
}
