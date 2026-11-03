package com.abdatytch.gateway_service.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Configuration Netty pour Gateway Service - Architecture Réactive Non-Bloquante
 * Configuration centralisée via .env partagée avec auth-service
 */
@Configuration
public class NettyConfig {

    @Value("${netty.worker-threads:0}")
    private int workerThreads;

    @Value("${netty.connection-pool.max-connections:500}")
    private int maxConnections;

    @Value("${netty.connection-pool.max-idle-time:20}")
    private int maxIdleTime;

    @Value("${netty.connection-pool.max-life-time:60}")
    private int maxLifeTime;

    @Value("${netty.timeouts.connect:5000}")
    private int connectTimeout;

    @Value("${netty.timeouts.read:60}")
    private int readTimeout;

    @Value("${netty.timeouts.write:60}")
    private int writeTimeout;

    @Value("${netty.timeouts.response:10}")
    private int responseTimeout;

    @Bean
    public LoopResources loopResources() {
        int threads = workerThreads > 0 ? workerThreads : Runtime.getRuntime().availableProcessors();
        return LoopResources.create(
            "gateway-event-loop",
            threads,
            true
        );
    }

    @Bean
    public ConnectionProvider connectionProvider() {
        return ConnectionProvider.builder("gateway-pool")
            .maxConnections(maxConnections)
            .maxIdleTime(Duration.ofSeconds(maxIdleTime))
            .maxLifeTime(Duration.ofSeconds(maxLifeTime))
            .pendingAcquireTimeout(Duration.ofSeconds(45))
            .evictInBackground(Duration.ofSeconds(120))
            .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder(LoopResources loopResources, 
                                               ConnectionProvider connectionProvider) {
        
        HttpClient httpClient = HttpClient.create(connectionProvider)
            .runOn(loopResources)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.TCP_NODELAY, true)
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(readTimeout, TimeUnit.SECONDS))
                .addHandlerLast(new WriteTimeoutHandler(writeTimeout, TimeUnit.SECONDS))
            )
            .compress(true)
            .responseTimeout(Duration.ofSeconds(responseTimeout));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(50 * 1024 * 1024) // 50 MB pour supporter les uploads de fichiers
            );
    }
    
    @Bean
    public WebClient webClient(WebClient.Builder webClientBuilder) {
        return webClientBuilder.build();
    }
}
