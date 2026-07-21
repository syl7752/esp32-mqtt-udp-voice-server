package io.github.esp32voice.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

@Component
public class VoicePipeline {
    private static final Logger log = LoggerFactory.getLogger(VoicePipeline.class);
    private final FunAsrClient asr;
    private final OpenAiResponsesClient llm;
    private final IndexTtsClient tts;
    private final AudioTools audio;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();

    public VoicePipeline(FunAsrClient asr,
                         OpenAiResponsesClient llm,
                         IndexTtsClient tts,
                         AudioTools audio,
                         StringRedisTemplate redis,
                         ObjectMapper json) {
        this.asr = asr;
        this.llm = llm;
        this.tts = tts;
        this.audio = audio;
        this.redis = redis;
        this.json = json;
    }

    public void submit(String sessionId, byte[] pcm16k, BiConsumer<Long, byte[]> reply) {
        long generation = generations.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
        executor.execute(() -> process(sessionId, generation, pcm16k, reply));
    }

    public void abort(String sessionId) {
        generations.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
    }

    public boolean isCurrent(String sessionId, long generation) {
        return generations.getOrDefault(sessionId, new AtomicLong()).get() == generation;
    }

    public void remove(String sessionId) {
        abort(sessionId);
        generations.remove(sessionId);
    }

    private void process(String sessionId, long generation, byte[] pcm16k,
                         BiConsumer<Long, byte[]> reply) {
        boolean failed = false;
        try {
            log.info("Starting ASR for session {}", sessionId);
            String transcript = asr.transcribe(audio.pcm16ToWav(pcm16k, 16000));
            if (transcript.isBlank() || !isCurrent(sessionId, generation)) {
                log.info("ASR returned no usable result for session {}", sessionId);
                return;
            }
            log.info("ASR completed for session {}, {} characters", sessionId, transcript.length());
            publish(Map.of("type", "stt", "sessionId", sessionId, "text", transcript));
            log.info("Starting OpenAI response for session {}", sessionId);
            String response = llm.respond(transcript);
            if (response.isBlank() || !isCurrent(sessionId, generation)) {
                log.info("OpenAI returned no usable result for session {}", sessionId);
                return;
            }
            log.info("OpenAI response completed for session {}, {} characters", sessionId, response.length());
            publish(Map.of("type", "tts", "sessionId", sessionId, "state", "start"));
            log.info("Starting TTS for session {}", sessionId);
            byte[] wav = tts.synthesize(response);
            if (!isCurrent(sessionId, generation)) {
                return;
            }
            byte[] pcm24k = audio.wavToMonoPcm(wav, 24000);
            log.info("TTS completed for session {}, audio duration {} ms",
                    sessionId, pcm24k.length * 1000L / (24000 * 2));
            reply.accept(generation, pcm24k);
        } catch (Exception e) {
            failed = true;
            log.warn("Voice pipeline failed for session {}: {}", sessionId, e.getMessage());
        } finally {
            if (isCurrent(sessionId, generation)) {
                publish(Map.of("type", "tts", "sessionId", sessionId, "state", "stop"));
                if (failed) {
                    publish(Map.of("type", "goodbye", "sessionId", sessionId,
                            "session_id", sessionId));
                }
            }
        }
    }

    private void publish(Map<String, String> event) {
        try {
            redis.convertAndSend("device:events", json.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Cannot publish device event: {}", e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
