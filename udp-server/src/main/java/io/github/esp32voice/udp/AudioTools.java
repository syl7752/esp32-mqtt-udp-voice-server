package io.github.esp32voice.udp;

import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Component
public class AudioTools {
    public byte[] pcm16ToWav(byte[] pcm, int sampleRate) {
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        wav.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1);
        wav.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return wav.array();
    }

    public byte[] wavToMonoPcm(byte[] wav, int targetRate) throws Exception {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    targetRate, 16, 1, 2, targetRate, false);
            try (AudioInputStream converted = AudioSystem.getAudioInputStream(target, source);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                converted.transferTo(out);
                return out.toByteArray();
            }
        }
    }
}

