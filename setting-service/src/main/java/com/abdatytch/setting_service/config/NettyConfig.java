package com.abdatytch.setting_service.config;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.server.HttpServer;

/**
 * Configuration Netty optimisée pour setting-service
 * Utilise les variables d'environnement centralisées dans .env
 */
@Slf4j
@Configuration
public class NettyConfig {

    @Value("${NETTY_WORKER_THREADS:0}")
    private int workerThreads;

    @Value("${NETTY_BACKLOG:1024}")
    private int backlog;

    @Value("${NETTY_SO_RCVBUF:262144}")
    private int receiveBufferSize;

    @Value("${NETTY_SO_SNDBUF:262144}")
    private int sendBufferSize;

    @Value("${NETTY_TCP_NODELAY:true}")
    private boolean tcpNoDelay;

    @Value("${NETTY_SO_KEEPALIVE:true}")
    private boolean keepAlive;

    @Bean
    public NettyReactiveWebServerFactory nettyReactiveWebServerFactory() {
        NettyReactiveWebServerFactory factory = new NettyReactiveWebServerFactory();
        
        factory.addServerCustomizers(new NettyServerCustomizer() {
            @Override
            public HttpServer apply(HttpServer httpServer) {
                return httpServer
                    .runOn(reactor.netty.resources.LoopResources.create(
                        "setting-service", 
                        1,
                        workerThreads == 0 ? Runtime.getRuntime().availableProcessors() * 2 : workerThreads,
                        true
                    ))
                    .option(ChannelOption.SO_BACKLOG, backlog)
                    .option(ChannelOption.SO_RCVBUF, receiveBufferSize)
                    .option(ChannelOption.SO_SNDBUF, sendBufferSize)
                    .option(ChannelOption.TCP_NODELAY, tcpNoDelay)
                    .option(ChannelOption.SO_KEEPALIVE, keepAlive)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.ALLOCATOR, io.netty.buffer.PooledByteBufAllocator.DEFAULT)
                    .compress(true);
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
