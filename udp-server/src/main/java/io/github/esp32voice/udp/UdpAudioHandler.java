package io.github.esp32voice.udp;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Component
@ChannelHandler.Sharable
public class UdpAudioHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final Logger log = LoggerFactory.getLogger(UdpAudioHandler.class);
    private final SessionRepository sessions;
    private final AudioPacketCodec packets;
    private final OpusService opus;
    private final SileroVad vad;
    private final VoicePipeline pipeline;
    private final long frameDurationMs;
    private final int prefillFrames;
    private final Map<String, InetSocketAddress> clients = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();
    private final AtomicLong lastUnknownPacketLog = new AtomicLong();
    private final AtomicLong lastInvalidPacketLog = new AtomicLong();
    private volatile Channel channel;

    public UdpAudioHandler(SessionRepository sessions,
                           AudioPacketCodec packets,
                           OpusService opus,
                           SileroVad vad,
                           VoicePipeline pipeline,
                           @Value("${audio.frame-duration-ms}") long frameDurationMs,
                           @Value("${audio.prefill-frames}") int prefillFrames) {
        this.sessions = sessions;
        this.packets = packets;
        this.opus = opus;
        this.vad = vad;
        this.pipeline = pipeline;
        this.frameDurationMs = frameDurationMs;
        this.prefillFrames = prefillFrames;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, DatagramPacket datagram) {
        int size = datagram.content().readableBytes();
        if (size <= 16 || size > 4096) {
            warnAtMostEveryFiveSeconds(lastInvalidPacketLog,
                    "Dropped UDP packet with invalid size " + size);
            return;
        }
        byte[] raw = new byte[size];
        datagram.content().readBytes(raw);
        byte[] nonce = Arrays.copyOf(raw, 16);
        SessionData session = sessions.findByPacketNonce(nonce);
        if (session == null) {
            warnAtMostEveryFiveSeconds(lastUnknownPacketLog,
                    "Received UDP audio but no matching MQTT session was found");
            return;
        }
        String sessionId = session.sessionId();
        try {
            AudioPacketCodec.DecodedPacket decoded = packets.decrypt(raw, session.udp().key());
            byte[] pcm16k = opus.decodeTo16k(sessionId, decoded.opus());
            InetSocketAddress previous = clients.put(sessionId, datagram.sender());
            if (!datagram.sender().equals(previous)) {
                log.info("Receiving UDP audio for session {} from {}", sessionId, datagram.sender());
            }
            vad.accept(sessionId, pcm16k).ifPresent(utterance -> {
                log.info("VAD completed utterance for session {}, duration {} ms",
                        sessionId, utterance.length * 1000L / (16000 * 2));
                    pipeline.submit(sessionId, utterance,
                            (generation, pcm24k) -> sendReply(session, generation, pcm24k));
            });
        } catch (Exception e) {
            warnAtMostEveryFiveSeconds(lastInvalidPacketLog,
                    "Dropped invalid UDP audio for session " + sessionId + ": " + e.getMessage());
        }
    }

    public void abort(String sessionId) {
        log.info("Aborting voice pipeline for session {}", sessionId);
        pipeline.abort(sessionId);
        vad.reset(sessionId);
    }

    public void remove(String sessionId) {
        log.info("Removing UDP session {}", sessionId);
        pipeline.remove(sessionId);
        vad.reset(sessionId);
        opus.remove(sessionId);
        sessions.remove(sessionId);
        clients.remove(sessionId);
        sequences.remove(sessionId);
    }

    private void sendReply(SessionData session, long generation, byte[] pcm24k) {
        InetSocketAddress recipient = clients.get(session.sessionId());
        Channel currentChannel = channel;
        if (recipient == null || currentChannel == null) {
            return;
        }
        try {
            AtomicInteger sequence = sequences.computeIfAbsent(session.sessionId(), ignored -> new AtomicInteger());
            List<byte[]> frames = opus.encode24k(session.sessionId(), pcm24k);
            log.info("Sending {} UDP audio frames to session {}", frames.size(), session.sessionId());
            long pacingStart = System.nanoTime();
            int pacedFrames = 0;
            for (int index = 0; index < frames.size(); index++) {
                if (!pipeline.isCurrent(session.sessionId(), generation)) {
                    log.info("Stopped UDP reply for aborted session {}", session.sessionId());
                    return;
                }
                byte[] frame = frames.get(index);
                byte[] encrypted = packets.encrypt(frame, session.udp().key(),
                        session.udp().nonce(), sequence.getAndIncrement());
                currentChannel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(encrypted), recipient));
                if (index >= prefillFrames) {
                    pacedFrames++;
                    long remaining = pacingStart
                            + TimeUnit.MILLISECONDS.toNanos((long) pacedFrames * frameDurationMs)
                            - System.nanoTime();
                    if (remaining > 0) {
                        LockSupport.parkNanos(remaining);
                    }
                }
            }
            log.info("Finished UDP reply for session {}", session.sessionId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Failed to send audio for session {}: {}", session.sessionId(), e.getMessage());
        }
    }

    private void warnAtMostEveryFiveSeconds(AtomicLong lastLog, String message) {
        long now = System.currentTimeMillis();
        long previous = lastLog.get();
        if (now - previous >= 5000 && lastLog.compareAndSet(previous, now)) {
            log.warn(message);
        }
    }
}
