package io.github.esp32voice.udp;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;

@Component
public class AudioPacketCodec {
    private static final int NONCE_BYTES = 16;

    public DecodedPacket decrypt(byte[] packet, String hexKey) throws Exception {
        if (packet.length <= NONCE_BYTES) {
            throw new IllegalArgumentException("UDP packet is too short");
        }
        byte[] nonce = Arrays.copyOfRange(packet, 0, NONCE_BYTES);
        int declaredSize = Short.toUnsignedInt(ByteBuffer.wrap(nonce, 2, 2)
                .order(ByteOrder.BIG_ENDIAN).getShort());
        byte[] encrypted = Arrays.copyOfRange(packet, NONCE_BYTES, packet.length);
        if (declaredSize != 0 && declaredSize != encrypted.length) {
            throw new IllegalArgumentException("UDP payload length does not match header");
        }
        long sequence = Integer.toUnsignedLong(ByteBuffer.wrap(nonce, 12, 4)
                .order(ByteOrder.BIG_ENDIAN).getInt());
        return new DecodedPacket(nonce, sequence, crypt(Cipher.DECRYPT_MODE, encrypted, hexKey, nonce));
    }

    public byte[] encrypt(byte[] opus, String hexKey, String baseNonceHex, int sequence) throws Exception {
        byte[] nonce = HexFormat.of().parseHex(baseNonceHex);
        if (nonce.length != NONCE_BYTES || opus.length > 65535) {
            throw new IllegalArgumentException("Invalid nonce or Opus frame");
        }
        ByteBuffer.wrap(nonce, 2, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) opus.length);
        ByteBuffer.wrap(nonce, 8, 4).order(ByteOrder.BIG_ENDIAN)
                .putInt((int) System.currentTimeMillis());
        ByteBuffer.wrap(nonce, 12, 4).order(ByteOrder.BIG_ENDIAN).putInt(sequence);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, opus, hexKey, nonce);
        return ByteBuffer.allocate(NONCE_BYTES + encrypted.length).put(nonce).put(encrypted).array();
    }

    private byte[] crypt(int mode, byte[] input, String hexKey, byte[] nonce) throws Exception {
        byte[] key = HexFormat.of().parseHex(hexKey);
        if (key.length != 16) {
            throw new IllegalArgumentException("Only AES-128 keys are supported");
        }
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(nonce));
        return cipher.doFinal(input);
    }

    public record DecodedPacket(byte[] nonce, long sequence, byte[] opus) {
    }
}

