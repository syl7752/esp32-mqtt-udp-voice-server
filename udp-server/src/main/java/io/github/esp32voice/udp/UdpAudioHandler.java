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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Map<String, InetSocketAddress> clients = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();
    private volatile Channel channel;

    public UdpAudioHandler(SessionRepository sessions,
                           AudioPacketCodec packets,
                           OpusService opus,
                           SileroVad vad,
                           VoicePipeline pipeline,
                           @Value("${audio.frame-duration-ms}") long frameDurationMs) {
        this.sessions = sessions;
        this.packets = packets;
        this.opus = opus;
        this.vad = vad;
        this.pipeline = pipeline;
        this.frameDurationMs = frameDurationMs;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, DatagramPacket datagram) {
        int size = datagram.content().readableBytes();
        if (size <= 16 || size > 4096) {
            return;
        }
        byte[] raw = new byte[size];
        datagram.content().readBytes(raw);
        byte[] nonce = Arrays.copyOf(raw, 16);
        SessionData session = sessions.findByPacketNonce(nonce);
        if (session == null) {
            return;
        }
        String sessionId = session.sessionId();
        clients.put(sessionId, datagram.sender());
        try {
            AudioPacketCodec.DecodedPacket decoded = packets.decrypt(raw, session.udp().key());
            byte[] pcm16k = opus.decodeTo16k(sessionId, decoded.opus());
            vad.accept(sessionId, pcm16k).ifPresent(utterance ->
                    pipeline.submit(sessionId, utterance,
                            (generation, pcm24k) -> sendReply(session, generation, pcm24k)));
        } catch (Exception e) {
            log.debug("Dropped invalid audio packet for session {}: {}", sessionId, e.getMessage());
        }
    }

    public void abort(String sessionId) {
        pipeline.abort(sessionId);
        vad.reset(sessionId);
    }

    public void remove(String sessionId) {
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
            for (byte[] frame : opus.encode24k(session.sessionId(), pcm24k)) {
                if (!pipeline.isCurrent(session.sessionId(), generation)) {
                    return;
                }
                byte[] encrypted = packets.encrypt(frame, session.udp().key(),
                        session.udp().nonce(), sequence.getAndIncrement());
                currentChannel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(encrypted), recipient));
                Thread.sleep(frameDurationMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Failed to send audio for session {}: {}", session.sessionId(), e.getMessage());
        }
    }
}

