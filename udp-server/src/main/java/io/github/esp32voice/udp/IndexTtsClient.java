package io.github.esp32voice.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class IndexTtsClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final URI endpoint;
    private final String model;
    private final String voice;

    public IndexTtsClient(ObjectMapper json,
                          @Value("${index-tts.speech-url}") URI endpoint,
                          @Value("${index-tts.model}") String model,
                          @Value("${index-tts.voice}") String voice) {
        this.json = json;
        this.endpoint = endpoint;
        this.model = model;
        this.voice = voice;
    }

    public byte[] synthesize(String input) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("voice", voice);
        body.put("input", input);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Index-TTS-vLLM returned HTTP " + response.statusCode());
        }
        return response.body();
    }
}

