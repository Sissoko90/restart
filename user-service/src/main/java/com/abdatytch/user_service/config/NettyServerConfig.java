package com.abdatytch.user_service.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**

 * Configuration optimale de Netty pour performances maximales
 * Toutes les valeurs sont externalisées dans application.yml
 *
 * Note: Spring Boot 4.x configure automatiquement Netty via application.yml
 * Cette classe log simplement les configurations au démarrage
 */
@Slf4j
@Configuration
public class NettyServerConfig {

    @Value("${netty.server.worker-threads:0}")
    private int workerThreads;

    @Value("${netty.server.connection-timeout:10000}")
    private int connectionTimeout;

    @Value("${netty.server.idle-timeout:60}")
    private int idleTimeout;

    @Value("${netty.server.backlog:1024}")
    private int backlog;

    @Value("${netty.server.receive-buffer:262144}")
    private int receiveBuffer;

    @Value("${netty.server.send-buffer:262144}")
    private int sendBuffer;

    @Value("${netty.server.tcp-nodelay:true}")
    private boolean tcpNodelay;

    @Value("${netty.server.keep-alive:true}")
    private boolean keepAlive;

    @Value("${netty.server.compression:true}")
    private boolean compression;

    @PostConstruct
    public void logNettyConfiguration() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int effectiveWorkerThreads = workerThreads > 0 ? workerThreads : availableProcessors * 2;

        log.info("Configuration Netty:");
        log.info("- CPU disponibles: {}", availableProcessors);
        log.info("- Worker threads: {}", effectiveWorkerThreads);
        log.info("- Backlog: {}", backlog);
        log.info("- Receive buffer: {} KB", receiveBuffer / 1024);
        log.info("- Send buffer: {} KB", sendBuffer / 1024);
        log.info("- TCP NoDelay: {}", tcpNodelay);
        log.info("- Keep-Alive: {}", keepAlive);
        log.info("- Compression: {}", compression);
    }
}
