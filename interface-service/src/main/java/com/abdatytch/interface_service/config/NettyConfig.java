package com.abdatytch.interface_service.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.server.HttpServer;

import java.util.concurrent.TimeUnit;

/**
 * Configuration Netty optimisée pour performance maximale
 * Utilise les variables d'environnement centralisées dans .env
 */
@Configuration
public class NettyConfig {

    private static final Logger log = LoggerFactory.getLogger(NettyConfig.class);

    @Value("${netty.worker-threads:0}")
    private int workerThreads;

    @Value("${netty.socket.backlog:1024}")
    private int backlog;

    @Value("${netty.socket.receive-buffer-size:262144}")
    private int receiveBufferSize;

    @Value("${netty.socket.send-buffer-size:262144}")
    private int sendBufferSize;

    @Value("${netty.socket.tcp-nodelay:true}")
    private boolean tcpNoDelay;

    @Value("${netty.socket.keep-alive:true}")
    private boolean keepAlive;

    @Value("${netty.timeouts.read:60}")
    private int readTimeout;

    @Value("${netty.timeouts.write:60}")
    private int writeTimeout;

    @Value("${netty.channel.write-buffer-high-water-mark:65536}")
    private int writeBufferHighWaterMark;

    @Value("${netty.channel.write-buffer-low-water-mark:32768}")
    private int writeBufferLowWaterMark;

    @Value("${netty.compression.enabled:true}")
    private boolean compressionEnabled;

    /**
     * Configuration personnalisée du serveur Netty
     */
    @Bean
    public NettyReactiveWebServerFactory nettyReactiveWebServerFactory() {
        NettyReactiveWebServerFactory factory = new NettyReactiveWebServerFactory();
        
        factory.addServerCustomizers(new NettyServerCustomizer() {
            @Override
            public HttpServer apply(HttpServer httpServer) {
                return httpServer
                    // Configuration des threads
                    .runOn(reactor.netty.resources.LoopResources.create(
                        "interface-service", 
                        1, // boss threads
                        workerThreads == 0 ? Runtime.getRuntime().availableProcessors() * 2 : workerThreads,
                        true
                    ))
                    // Configuration des options de socket
                    .option(ChannelOption.SO_BACKLOG, backlog)
                    .option(ChannelOption.SO_RCVBUF, receiveBufferSize)
                    .option(ChannelOption.SO_SNDBUF, sendBufferSize)
                    .option(ChannelOption.TCP_NODELAY, tcpNoDelay)
                    .option(ChannelOption.SO_KEEPALIVE, keepAlive)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_LINGER, 0)
                    // Configuration des buffers d'écriture (options dépréciées supprimées)
                    .option(ChannelOption.ALLOCATOR, io.netty.buffer.PooledByteBufAllocator.DEFAULT)
                    // Configuration des timeouts
                    .doOnConnection(connection -> {
                        connection.addHandlerLast(new ReadTimeoutHandler(readTimeout, TimeUnit.SECONDS));
                        connection.addHandlerLast(new WriteTimeoutHandler(writeTimeout, TimeUnit.SECONDS));
                    })
                    // Configuration de la compression
                    .compress(compressionEnabled);
            }
        });

        log.info("Configuration Netty appliquée - Workers: {}, Backlog: {}, Buffers: {}KB/{}KB", 
                workerThreads == 0 ? "auto" : workerThreads, 
                backlog, 
                receiveBufferSize/1024, 
                sendBufferSize/1024);

        return factory;
    }
}
