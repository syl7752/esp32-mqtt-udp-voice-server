package io.github.esp32voice.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DeviceMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(DeviceMessageHandler.class);
    private final ObjectMapper json;
    private final SessionService sessions;
    private final StringRedisTemplate redis;
    private final MessageChannel outbound;
    private final String downTopic;

    public DeviceMessageHandler(
            ObjectMapper json,
            SessionService sessions,
            StringRedisTemplate redis,
            @Qualifier("mqttOutboundChannel") MessageChannel mqttOutboundChannel,
            @Value("${mqtt.down-topic}") String downTopic) {
        this.json = json;
        this.sessions = sessions;
        this.redis = redis;
        this.outbound = mqttOutboundChannel;
        this.downTopic = downTopic;
    }

    @ServiceActivator(inputChannel = "mqttInboundChannel")
    public void handle(Message<String> message) {
        String topic = String.valueOf(message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC));
        try {
            JsonNode root = json.readTree(message.getPayload());
            JsonNode payload = unwrap(root);
            String clientId = text(root, "clientid");
            if (clientId == null) {
                clientId = clientIdFromTopic(topic);
            }
            if (clientId == null || payload == null || !payload.hasNonNull("type")) {
                log.warn("Ignored invalid MQTT message on {}", topic);
                return;
            }

            String type = payload.path("type").asText();
            switch (type) {
                case "hello" -> hello(clientId);
                case "listen" -> listen(payload);
                case "abort" -> abort(clientId, payload);
                case "goodbye" -> goodbye(clientId, payload);
                default -> log.warn("Ignored unsupported message type {}", type);
            }
        } catch (Exception e) {
            log.warn("Failed to process MQTT message on {}: {}", topic, e.getMessage());
        }
    }

    private void hello(String clientId) throws Exception {
        String topic = downTopic.formatted(clientId);
        SessionData session = sessions.create(clientId, topic);
        send(topic, json.writeValueAsString(session));
        log.info("Created UDP session {} for device {}", session.sessionId(), clientId);
    }

    private void listen(JsonNode payload) {
        String sessionId = sessionId(payload);
        if (!sessions.refresh(sessionId)) {
            log.warn("Ignored listen event for unknown session");
        }
    }

    private void abort(String clientId, JsonNode payload) throws Exception {
        String sessionId = sessionId(payload);
        if (sessionId == null) {
            return;
        }
        redis.convertAndSend("voice:control", json.writeValueAsString(Map.of(
                "type", "abort",
                "sessionId", sessionId)));
        ObjectNode response = json.createObjectNode();
        response.put("type", "tts");
        response.put("state", "stop");
        response.put("session_id", sessionId);
        send(downTopic.formatted(clientId), json.writeValueAsString(response));
    }

    private void goodbye(String clientId, JsonNode payload) throws Exception {
        String sessionId = sessionId(payload);
        if (sessionId == null) {
            return;
        }
        redis.convertAndSend("voice:control", json.writeValueAsString(Map.of(
                "type", "disconnect",
                "sessionId", sessionId)));
        sessions.remove(sessionId, clientId);
    }

    private JsonNode unwrap(JsonNode root) throws Exception {
        JsonNode payload = root.get("payload");
        if (payload == null) {
            return root;
        }
        return payload.isTextual() ? json.readTree(payload.asText()) : payload;
    }

    private String sessionId(JsonNode node) {
        String value = text(node, "session_id");
        return value != null ? value : text(node, "sessionId");
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private String clientIdFromTopic(String topic) {
        String[] parts = topic.split("/");
        return parts.length >= 3 ? parts[parts.length - 2] : null;
    }

    private void send(String topic, String payload) {
        outbound.send(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.TOPIC, topic)
                .setHeader(MqttHeaders.QOS, 1)
                .build());
    }
}
