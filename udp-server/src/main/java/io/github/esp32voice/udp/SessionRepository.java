package io.github.esp32voice.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRepository {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Map<String, SessionData> byNonce = new ConcurrentHashMap<>();

    public SessionRepository(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public SessionData findByPacketNonce(byte[] nonce) {
        if (nonce.length != 16) {
            return null;
        }
        String fixedPart = HexFormat.of().withUpperCase().formatHex(nonce, 4, 8);
        SessionData cached = byNonce.get(fixedPart);
        if (cached != null) {
            return cached;
        }
        String sessionId = redis.opsForValue().get("nonce:" + fixedPart);
        if (sessionId == null) {
            return null;
        }
        String raw = redis.opsForValue().get("session:" + sessionId);
        if (raw == null) {
            return null;
        }
        try {
            SessionData session = json.readValue(raw, SessionData.class);
            byte[] expected = HexFormat.of().parseHex(session.udp().nonce());
            if (!HexFormat.of().withUpperCase().formatHex(expected, 4, 8).equals(fixedPart)) {
                return null;
            }
            byNonce.put(fixedPart, session);
            return session;
        } catch (Exception e) {
            return null;
        }
    }

    public void remove(String sessionId) {
        byNonce.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
    }
}

