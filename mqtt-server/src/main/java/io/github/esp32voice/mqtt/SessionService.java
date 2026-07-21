package io.github.esp32voice.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class SessionService {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;
    private final String udpHost;
    private final int udpPort;

    public SessionService(StringRedisTemplate redis,
                          ObjectMapper json,
                          @Value("${voice.session-ttl-seconds}") long ttlSeconds,
                          @Value("${voice.udp-public-host}") String udpHost,
                          @Value("${voice.udp-port}") int udpPort) {
        this.redis = redis;
        this.json = json;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.udpHost = udpHost;
        this.udpPort = udpPort;
    }

    public SessionData create(String clientId, String deviceTopic) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        byte[] key = randomBytes(16);
        byte[] nonce = new byte[16];
        nonce[0] = 1;
        System.arraycopy(randomBytes(4), 0, nonce, 4, 4);

        SessionData session = new SessionData(
                "hello",
                "udp",
                sessionId,
                new SessionData.AudioParams("opus", 24000, 1, 60),
                new SessionData.UdpInfo(
                        udpHost,
                        udpPort,
                        HexFormat.of().withUpperCase().formatHex(key),
                        HexFormat.of().withUpperCase().formatHex(nonce)));

        try {
            redis.opsForValue().set("session:" + sessionId, json.writeValueAsString(session), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize session", e);
        }
        redis.opsForValue().set("nonce:" + HexFormat.of().withUpperCase().formatHex(nonce, 4, 8), sessionId, ttl);
        redis.opsForValue().set("session:" + sessionId + ":topic", deviceTopic, ttl);
        redis.opsForValue().set("client:" + clientId + ":session", sessionId, ttl);
        return session;
    }

    public boolean refresh(String sessionId) {
        if (sessionId == null || !Boolean.TRUE.equals(redis.hasKey("session:" + sessionId))) {
            return false;
        }
        redis.expire("session:" + sessionId, ttl);
        redis.expire("session:" + sessionId + ":topic", ttl);
        return true;
    }

    public void remove(String sessionId, String clientId) {
        if (sessionId == null) {
            return;
        }
        String raw = redis.opsForValue().get("session:" + sessionId);
        if (raw != null) {
            try {
                SessionData session = json.readValue(raw, SessionData.class);
                byte[] nonce = HexFormat.of().parseHex(session.udp().nonce());
                redis.delete("nonce:" + HexFormat.of().withUpperCase().formatHex(nonce, 4, 8));
            } catch (Exception ignored) {
                // Expired or malformed sessions are removed below anyway.
            }
        }
        redis.delete("session:" + sessionId);
        redis.delete("session:" + sessionId + ":topic");
        if (clientId != null) {
            redis.delete("client:" + clientId + ":session");
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}

