package io.github.esp32voice.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UdpServer {
    private static final Logger log = LoggerFactory.getLogger(UdpServer.class);
    private final UdpAudioHandler handler;
    private final int port;
    private final int workerThreads;
    private EventLoopGroup group;
    private Channel channel;

    public UdpServer(UdpAudioHandler handler,
                     @Value("${udp.port}") int port,
                     @Value("${udp.worker-threads}") int workerThreads) {
        this.handler = handler;
        this.port = port;
        this.workerThreads = workerThreads;
    }

    @PostConstruct
    void start() throws InterruptedException {
        group = new NioEventLoopGroup(workerThreads);
        channel = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
                .option(ChannelOption.SO_SNDBUF, 1024 * 1024)
                .handler(handler)
                .bind(port)
                .sync()
                .channel();
        handler.setChannel(channel);
        log.info("UDP audio server listening on port {}", port);
    }

    @PreDestroy
    void stop() {
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }
}

