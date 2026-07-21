package io.github.esp32voice.udp;

import com.fasterxml.jackson.databind.JsonNode;
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
public class OpenAiResponsesClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final String instructions;

    public OpenAiResponsesClient(ObjectMapper json,
                                 @Value("${openai.responses-url}") URI endpoint,
                                 @Value("${openai.api-key}") String apiKey,
                                 @Value("${openai.model}") String model,
                                 @Value("${openai.instructions}") String instructions) {
        this.json = json;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.instructions = instructions;
    }

    public String respond(String input) throws Exception {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured");
        }
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("instructions", instructions);
        body.put("input", input);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("OpenAI returned HTTP " + response.statusCode());
        }
        JsonNode result = json.readTree(response.body());
        for (JsonNode output : result.path("output")) {
            if (!"message".equals(output.path("type").asText())) {
                continue;
            }
            for (JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    return content.path("text").asText().trim();
                }
            }
        }
        throw new IllegalStateException("OpenAI response contained no output text");
    }
}

