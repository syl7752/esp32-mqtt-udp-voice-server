package io.github.esp32voice.mqtt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/device")
public class OtaController {
    private final String endpoint;
    private final String username;
    private final String password;
    private final String upTopic;
    private final String downTopic;
    private final String clientIdPrefix;
    private final int keepalive;
    private final String firmwareVersion;
    private final String firmwareUrl;

    public OtaController(
            @Value("${mqtt.public-endpoint}") String endpoint,
            @Value("${mqtt.username}") String username,
            @Value("${mqtt.password}") String password,
            @Value("${mqtt.device-up-topic}") String upTopic,
            @Value("${mqtt.down-topic}") String downTopic,
            @Value("${ota.client-id-prefix}") String clientIdPrefix,
            @Value("${ota.keepalive}") int keepalive,
            @Value("${ota.firmware-version}") String firmwareVersion,
            @Value("${ota.firmware-url}") String firmwareUrl) {
        this.endpoint = endpoint;
        this.username = username;
        this.password = password;
        this.upTopic = upTopic;
        this.downTopic = downTopic;
        this.clientIdPrefix = clientIdPrefix;
        this.keepalive = keepalive;
        this.firmwareVersion = firmwareVersion;
        this.firmwareUrl = firmwareUrl;
    }

    @PostMapping("/ota")
    public ResponseEntity<OtaResponse> ota(
            @RequestHeader(value = "Device-Id", required = false) String deviceId,
            @RequestBody(required = false) JsonNode body) {
        String identity = firstNonBlank(deviceId, text(body, "mac_address"), text(body, "mac"));
        if (identity == null) {
            return ResponseEntity.badRequest().build();
        }
        String clientId = clientIdPrefix + normalize(identity);
        return ResponseEntity.ok(new OtaResponse(
                new Mqtt(endpoint, clientId, username, password, keepalive,
                        upTopic.formatted(clientId), downTopic.formatted(clientId)),
                new ServerTime(System.currentTimeMillis(), "UTC", 0),
                new Firmware(firmwareVersion, firmwareUrl)));
    }

    private String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    public record OtaResponse(Mqtt mqtt,
                              @JsonProperty("server_time") ServerTime serverTime,
                              Firmware firmware) {
    }

    public record Mqtt(String endpoint,
                       @JsonProperty("client_id") String clientId,
                       String username,
                       String password,
                       int keepalive,
                       @JsonProperty("publish_topic") String publishTopic,
                       @JsonProperty("subscribe_topic") String subscribeTopic) {
    }

    public record ServerTime(long timestamp, String timezone,
                             @JsonProperty("timezone_offset") int timezoneOffset) {
    }

    public record Firmware(String version, String url) {
    }
}
