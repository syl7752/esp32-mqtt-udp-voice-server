package io.github.esp32voice.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class DeviceEventForwarder implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(DeviceEventForwarder.class);
    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final MessageChannel outbound;

    public DeviceEventForwarder(ObjectMapper json,
                                StringRedisTemplate redis,
                                @Qualifier("mqttOutboundChannel") MessageChannel mqttOutboundChannel) {
        this.json = json;
        this.redis = redis;
        this.outbound = mqttOutboundChannel;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode event = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            String sessionId = event.path("sessionId").asText();
            String topic = redis.opsForValue().get("session:" + sessionId + ":topic");
            if (topic == null) {
                return;
            }
            ObjectNode payload = event.deepCopy();
            payload.remove("sessionId");
            payload.remove("timestamp");
            outbound.send(MessageBuilder.withPayload(json.writeValueAsString(payload))
                    .setHeader(MqttHeaders.TOPIC, topic)
                    .setHeader(MqttHeaders.QOS, 1)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to forward device event: {}", e.getMessage());
        }
    }
}
