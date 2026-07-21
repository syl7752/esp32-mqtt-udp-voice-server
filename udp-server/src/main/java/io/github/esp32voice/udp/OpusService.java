package io.github.esp32voice.udp;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OpusService {
    private final int sampleRate;
    private final int frameSamples;
    private final int bitrate;
    private final Map<String, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final Map<String, OpusEncoder> encoders = new ConcurrentHashMap<>();

    public OpusService(@Value("${audio.sample-rate}") int sampleRate,
                       @Value("${audio.frame-duration-ms}") int frameDurationMs,
                       @Value("${audio.opus-bitrate}") int bitrate) {
        this.sampleRate = sampleRate;
        this.frameSamples = sampleRate * frameDurationMs / 1000;
        this.bitrate = bitrate;
    }

    public byte[] decodeTo16k(String sessionId, byte[] opus) throws Exception {
        OpusDecoder decoder = decoders.computeIfAbsent(sessionId, ignored -> {
            try {
                return new OpusDecoder(sampleRate, 1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        short[] decoded = new short[sampleRate * 120 / 1000];
        int samples = decoder.decode(opus, 0, opus.length, decoded, 0, decoded.length, false);
        short[] source = Arrays.copyOf(decoded, samples);
        short[] target = resample(source, sampleRate, 16000);
        ByteBuffer bytes = ByteBuffer.allocate(target.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : target) {
            bytes.putShort(sample);
        }
        return bytes.array();
    }

    public List<byte[]> encode24k(String sessionId, byte[] pcm) throws Exception {
        OpusEncoder encoder = encoders.computeIfAbsent(sessionId, ignored -> {
            try {
                OpusEncoder value = new OpusEncoder(sampleRate, 1, OpusApplication.OPUS_APPLICATION_AUDIO);
                value.setBitrate(bitrate);
                value.setComplexity(8);
                value.setPacketLossPercent(10);
                value.setForceChannels(1);
                value.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
                return value;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        short[] samples = new short[(pcm.length + 1) / 2];
        ByteBuffer input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples.length && input.remaining() >= 2; i++) {
            samples[i] = input.getShort();
        }

        List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < samples.length; offset += frameSamples) {
            short[] frame = new short[frameSamples];
            System.arraycopy(samples, offset, frame, 0, Math.min(frameSamples, samples.length - offset));
            byte[] encoded = new byte[4000];
            int length = encoder.encode(frame, 0, frameSamples, encoded, 0, encoded.length);
            frames.add(Arrays.copyOf(encoded, length));
        }
        return frames;
    }

    public void remove(String sessionId) {
        decoders.remove(sessionId);
        encoders.remove(sessionId);
    }

    private short[] resample(short[] input, int sourceRate, int targetRate) {
        if (sourceRate == targetRate || input.length == 0) {
            return input;
        }
        int length = Math.max(1, (int) Math.round(input.length * (double) targetRate / sourceRate));
        short[] output = new short[length];
        double scale = (double) sourceRate / targetRate;
        for (int i = 0; i < length; i++) {
            double position = i * scale;
            int left = Math.min((int) position, input.length - 1);
            int right = Math.min(left + 1, input.length - 1);
            double fraction = position - left;
            output[i] = (short) Math.round(input[left] + (input[right] - input[left]) * fraction);
        }
        return output;
    }
}
