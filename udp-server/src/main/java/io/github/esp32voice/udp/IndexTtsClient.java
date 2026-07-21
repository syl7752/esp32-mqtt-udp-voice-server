package io.github.esp32voice.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Component
public class IndexTtsClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String apiMode;
    private final URI endpoint;
    private final String model;
    private final String voice;
    private final List<String> audioPaths;

    public IndexTtsClient(ObjectMapper json,
                          @Value("${index-tts.api-mode}") String apiMode,
                          @Value("${index-tts.speech-url}") URI endpoint,
                          @Value("${index-tts.model}") String model,
                          @Value("${index-tts.voice}") String voice,
                          @Value("${index-tts.audio-paths}") String audioPaths) {
        this.json = json;
        this.apiMode = apiMode;
        this.endpoint = endpoint;
        this.model = model;
        this.voice = voice;
        this.audioPaths = Arrays.stream(audioPaths.split(","))
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .toList();
    }

    public byte[] synthesize(String input) throws Exception {
        ObjectNode body = json.createObjectNode();
        if ("reference-audio".equals(apiMode)) {
            if (audioPaths.isEmpty()) {
                throw new IllegalStateException("INDEX_TTS_AUDIO_PATHS is required in reference-audio mode");
            }
            body.put("text", input);
            ArrayNode paths = body.putArray("audio_paths");
            audioPaths.forEach(paths::add);
        } else {
            body.put("model", model);
            body.put("voice", voice);
            body.put("input", input);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            String detail = new String(response.body(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ");
            if (detail.length() > 500) {
                detail = detail.substring(0, 500) + "...";
            }
            throw new IllegalStateException("Index-TTS-vLLM returned HTTP "
                    + response.statusCode() + ": " + detail);
        }
        return response.body();
    }
}
