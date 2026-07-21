package io.github.esp32voice.udp;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SileroVad {
    private static final int SAMPLE_RATE = 16000;
    private static final int WINDOW = 512;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Path modelPath;
    private final float threshold;
    private final int silenceSamples;
    private final int maxSamples;
    private OrtSession model;

    public SileroVad(@Value("${vad.model-path}") Path modelPath,
                     @Value("${vad.threshold}") float threshold,
                     @Value("${vad.silence-ms}") int silenceMs,
                     @Value("${vad.max-utterance-seconds}") int maxSeconds) {
        this.modelPath = modelPath;
        this.threshold = threshold;
        this.silenceSamples = silenceMs * SAMPLE_RATE / 1000;
        this.maxSamples = maxSeconds * SAMPLE_RATE;
    }

    @PostConstruct
    void load() throws Exception {
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalStateException("Silero VAD model not found: " + modelPath.toAbsolutePath());
        }
        model = environment.createSession(modelPath.toString(), new OrtSession.SessionOptions());
    }

    public Optional<byte[]> accept(String sessionId, byte[] pcm16k) throws Exception {
        State state = states.computeIfAbsent(sessionId, ignored -> new State());
        short[] incoming = toShorts(pcm16k);
        short[] combined = new short[state.carry.length + incoming.length];
        System.arraycopy(state.carry, 0, combined, 0, state.carry.length);
        System.arraycopy(incoming, 0, combined, state.carry.length, incoming.length);

        for (int offset = 0; offset + WINDOW <= combined.length; offset += WINDOW) {
            short[] window = Arrays.copyOfRange(combined, offset, offset + WINDOW);
            Optional<byte[]> completed = processWindow(state, window);
            if (completed.isPresent()) {
                int consumed = offset + WINDOW;
                state.carry = Arrays.copyOfRange(combined, consumed, combined.length);
                return completed;
            }
        }
        int consumed = combined.length - combined.length % WINDOW;
        state.carry = Arrays.copyOfRange(combined, consumed, combined.length);
        return Optional.empty();
    }

    public void reset(String sessionId) {
        states.remove(sessionId);
    }

    private Optional<byte[]> processWindow(State state, short[] samples) throws Exception {
        float probability = infer(state, samples);
        if (!state.speaking) {
            if (probability >= threshold) {
                state.speaking = true;
                state.preRoll.forEach(block -> write(state.utterance, block));
                state.preRoll.clear();
                write(state.utterance, samples);
            } else {
                state.preRoll.addLast(samples);
                while (state.preRoll.size() > 5) {
                    state.preRoll.removeFirst();
                }
            }
            return Optional.empty();
        }

        write(state.utterance, samples);
        state.silence = probability < threshold ? state.silence + WINDOW : 0;
        int utteranceSamples = state.utterance.size() / 2;
        if (state.silence < silenceSamples && utteranceSamples < maxSamples) {
            return Optional.empty();
        }
        byte[] completed = state.utterance.toByteArray();
        state.resetUtterance();
        return Optional.of(completed);
    }

    private float infer(State state, short[] samples) throws Exception {
        float[] normalized = new float[WINDOW];
        for (int i = 0; i < WINDOW; i++) {
            normalized[i] = samples[i] / 32768f;
        }
        String inputName = model.getInputNames().stream()
                .filter(name -> name.equals("input") || name.contains("input"))
                .findFirst().orElseThrow();
        String stateName = model.getInputNames().stream()
                .filter(name -> name.toLowerCase().contains("state"))
                .findFirst().orElseThrow();
        String rateName = model.getInputNames().stream()
                .filter(name -> name.equals("sr") || name.toLowerCase().contains("rate"))
                .findFirst().orElseThrow();

        try (OnnxTensor audio = OnnxTensor.createTensor(environment,
                     FloatBuffer.wrap(normalized), new long[]{1, WINDOW});
             OnnxTensor recurrent = OnnxTensor.createTensor(environment, state.recurrent);
             OnnxTensor rate = OnnxTensor.createTensor(environment,
                     LongBuffer.wrap(new long[]{SAMPLE_RATE}), new long[]{1})) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, audio);
            inputs.put(stateName, recurrent);
            inputs.put(rateName, rate);
            try (OrtSession.Result result = model.run(inputs)) {
                float probability = 0;
                for (Map.Entry<String, OnnxValue> entry : result) {
                    Object value = entry.getValue().getValue();
                    if (value instanceof float[][] output && output.length > 0 && output[0].length > 0) {
                        probability = output[0][0];
                    } else if (value instanceof float[][][] nextState) {
                        state.recurrent = nextState;
                    }
                }
                return probability;
            }
        }
    }

    private short[] toShorts(byte[] bytes) {
        short[] samples = new short[bytes.length / 2];
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = input.getShort();
        }
        return samples;
    }

    private void write(ByteArrayOutputStream output, short[] samples) {
        ByteBuffer bytes = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            bytes.putShort(sample);
        }
        output.writeBytes(bytes.array());
    }

    @PreDestroy
    void close() throws Exception {
        if (model != null) {
            model.close();
        }
    }

    private static final class State {
        private float[][][] recurrent = new float[2][1][128];
        private short[] carry = new short[0];
        private final ArrayDeque<short[]> preRoll = new ArrayDeque<>();
        private ByteArrayOutputStream utterance = new ByteArrayOutputStream();
        private boolean speaking;
        private int silence;

        private void resetUtterance() {
            recurrent = new float[2][1][128];
            preRoll.clear();
            utterance = new ByteArrayOutputStream();
            speaking = false;
            silence = 0;
        }
    }
}

