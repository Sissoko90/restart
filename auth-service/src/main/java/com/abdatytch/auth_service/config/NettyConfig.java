package com.abdatytch.auth_service.config;

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
 * Configuration Netty pour Auth Service - Architecture Réactive Non-Bloquante
 * 
 * Architecture Netty:
 * 1. EventLoopGroup (Selector Pattern):
 *    - Gère les événements I/O de manière asynchrone
 *    - 1 thread par CPU core pour parallélisme optimal
 *    - Évite le context switching excessif
 * 
 * 2. Channels:
 *    - Abstraction des connexions réseau (sockets)
 *    - Chaque channel est assigné à un EventLoop
 *    - Pipeline de handlers pour traiter les données
 * 
 * 3. Buffers (ByteBuf):
 *    - Gestion mémoire efficace avec pooling
 *    - Zero-copy quand possible
 *    - Direct buffers pour I/O natives
 * 
 * 4. Worker Threads:
 *    - Threads dédiés au traitement des événements I/O
 *    - Non-bloquants, event-driven
 *    - Réutilisation via event loop
 * 
 * 5. Selectors (NIO):
 *    - Multiplexage I/O - 1 thread gère N connexions
 *    - Notifications événementielles (read/write ready)
 *    - Évite les threads bloqués en attente
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

    /**
     * Configure EventLoopGroup avec worker threads optimisés
     * 
     * EventLoop = Selector (NIO) + Queue d'événements
     * - Chaque EventLoop tourne dans son propre thread
     * - Gère plusieurs channels (connexions) de manière non-bloquante
     * - Utilise epoll (Linux) ou kqueue (macOS) pour performance maximale
     * 
     * Nombre de workers = CPU cores pour exploiter tout le hardware
     */
    @Bean
    public LoopResources loopResources() {
        int threads = workerThreads > 0 ? workerThreads : Runtime.getRuntime().availableProcessors();
        return LoopResources.create(
            "auth-event-loop",  // Préfixe nom des threads
            threads,            // Nombre de worker threads (1 par core si 0)
            true                // Daemon threads (arrêt propre JVM)
        );
    }

    /**
     * Connection Provider - Pool de connexions HTTP
     * 
     * Avantages:
     * - Réutilisation des connexions TCP (évite handshake répété)
     * - Limite le nombre de connexions simultanées
     * - Éviction automatique des connexions idle/expirées
     * - Backpressure via pending acquire timeout
     */
    @Bean
    public ConnectionProvider connectionProvider() {
        return ConnectionProvider.builder("auth-pool")
            .maxConnections(maxConnections)                         // Max connexions totales
            .maxIdleTime(Duration.ofSeconds(maxIdleTime))          // Durée max idle avant éviction
            .maxLifeTime(Duration.ofSeconds(maxLifeTime))          // Durée de vie max d'une connexion
            .pendingAcquireTimeout(Duration.ofSeconds(45))         // Timeout acquisition connexion
            .evictInBackground(Duration.ofSeconds(120))            // Nettoyage périodique
            .build();
    }

    /**
     * WebClient réactif avec Netty optimisé
     * 
     * Utilisé pour appels HTTP sortants (user-service, etc.)
     * - Non-bloquant: libère le thread pendant l'attente réseau
     * - Backpressure: contrôle du flux de données
     * - Retry/Circuit breaker intégrables
     * 
     * Options Netty importantes:
     * - TCP_NODELAY: désactive l'algorithme de Nagle (latence réduite)
     * - SO_KEEPALIVE: détection connexions mortes
     * - CONNECT_TIMEOUT: timeout établissement connexion
     */
    @Bean
    public WebClient webClient(LoopResources loopResources, 
                                ConnectionProvider connectionProvider) {
        
        HttpClient httpClient = HttpClient.create(connectionProvider)
            .runOn(loopResources)  // Utilise nos EventLoops configurés
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
            .option(ChannelOption.SO_KEEPALIVE, true)      // Keep-alive TCP
            .option(ChannelOption.TCP_NODELAY, true)       // Désactive Nagle (low latency)
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(readTimeout, TimeUnit.SECONDS))
                .addHandlerLast(new WriteTimeoutHandler(writeTimeout, TimeUnit.SECONDS))
            )
            .compress(true)  // Compression HTTP (gzip/deflate)
            .responseTimeout(Duration.ofSeconds(responseTimeout));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
