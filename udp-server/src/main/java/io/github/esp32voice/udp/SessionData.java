package io.github.esp32voice.udp;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SessionData(
        String type,
        String transport,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("audio_params") AudioParams audioParams,
        UdpInfo udp) {

    public record AudioParams(
            String format,
            @JsonProperty("sample_rate") int sampleRate,
            int channels,
            @JsonProperty("frame_duration") int frameDuration) {
    }

    public record UdpInfo(String server, int port, String key, String nonce) {
    }
}

