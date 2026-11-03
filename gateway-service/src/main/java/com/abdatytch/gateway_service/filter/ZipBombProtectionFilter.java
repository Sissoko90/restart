package com.abdatytch.gateway_service.filter;

import com.abdatytch.gateway_service.security.ZipBombProtection;
import com.abdatytch.gateway_service.security.ZipBombProtection.ZipBombCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**

 * Filtre WebFlux pour détecter les attaques Zip Bomb dans gateway-service
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZipBombProtectionFilter implements WebFilter, Ordered {

    private final ZipBombProtection zipBombProtection;

    /**
     * Taille maximale acceptee a la reception, avant tout controle.
     *
     * <p>Distincte du plafond de decompression : celui-ci borne ce qu'on accepte
     * de lire, celui-la ce qu'on accepte de produire en decompressant.
     */
    private static final int MAX_RECEIVED_BYTES = 20 * 1024 * 1024;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        HttpMethod method = request.getMethod();
        if (method != HttpMethod.POST && method != HttpMethod.PUT) {
            return chain.filter(exchange);
        }

        MediaType contentType = request.getHeaders().getContentType();
        if (contentType == null) {
            return chain.filter(exchange);
        }

        String contentTypeStr = contentType.toString().toLowerCase();

        if (!zipBombProtection.isCompressedFile(contentTypeStr)) {
            return chain.filter(exchange);
        }

        log.debug(" Checking for Zip Bomb: {} {}", method, request.getURI().getPath());

        return checkZipBombAndContinue(exchange, chain, request);
    }

    private Mono<Void> checkZipBombAndContinue(ServerWebExchange exchange, WebFilterChain chain,
                                                ServerHttpRequest request) {
        // join BORNE, et non join sans limite.
        //
        // Un filtre anti-bombe de decompression qui bufferise sans limite est
        // auto-contradictoire : il suffit d'envoyer un corps enorme pour
        // epuiser la memoire du Gateway AVANT que le controle de bombe ne
        // s'execute. L'attaque visee est le gonflement a la decompression ;
        // celle-ci etait le gonflement a la reception, et elle fonctionnait.
        //
        // spring.codec.max-in-memory-size ne s'applique pas ici : il borne les
        // codecs, pas un join explicite.
        return DataBufferUtils.join(request.getBody(), MAX_RECEIVED_BYTES)
            .flatMap(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);

                String filename = request.getHeaders().getContentDisposition().getFilename();
                if (filename == null) {
                    filename = "unknown-" + System.currentTimeMillis();
                }

                ZipBombCheckResult result = zipBombProtection.checkForZipBomb(bytes, filename);

                if (!result.isSafe()) {
                    log.warn(" ZIP BOMB BLOCKED at Gateway: {} - Path: {} - Reason: {}",
                        filename, request.getURI().getPath(), result.reason());
                    return rejectRequest(exchange, result.reason());
                }

                log.debug(" ZIP file passed security check: {}", filename);

                ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(request) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
                        return Flux.just(buffer);
                    }
                };

                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            })
            .switchIfEmpty(chain.filter(exchange));
    }

    private Mono<Void> rejectRequest(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String errorJson = String.format(
            "{\"error\":\"ZIP_BOMB_DETECTED\",\"message\":\"File rejected at gateway: %s\",\"status\":400,\"timestamp\":\"%s\"}",
            reason.replace("\"", "'"),
            java.time.Instant.now().toString()
        );

        DataBuffer buffer = exchange.getResponse().bufferFactory()
            .wrap(errorJson.getBytes(StandardCharsets.UTF_8));

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
