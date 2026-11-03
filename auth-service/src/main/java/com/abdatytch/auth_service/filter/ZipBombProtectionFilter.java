package com.abdatytch.auth_service.filter;

import com.abdatytch.auth_service.security.ZipBombProtection;
import com.abdatytch.auth_service.security.ZipBombProtection.ZipBombCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
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

 * Filtre WebFlux pour détecter les attaques Zip Bomb dans auth-service
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZipBombProtectionFilter implements WebFilter, Ordered {

    private final ZipBombProtection zipBombProtection;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String method = request.getMethod().name();
        if (!("POST".equals(method) || "PUT".equals(method))) {
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

        return checkZipBombAndContinue(exchange, chain, request);
    }

    private Mono<Void> checkZipBombAndContinue(ServerWebExchange exchange, WebFilterChain chain,
                                                ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody())
            .flatMap(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);

                String filename = request.getHeaders().getContentDisposition().getFilename();
                if (filename == null) {
                    filename = "unknown";
                }

                ZipBombCheckResult result = zipBombProtection.checkForZipBomb(bytes, filename);

                if (!result.isSafe()) {
                    log.warn(" ZIP BOMB BLOCKED in auth-service: {} - Reason: {}", filename, result.reason());
                    return rejectRequest(exchange, result.reason());
                }

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
            "{\"error\":\"ZIP_BOMB_DETECTED\",\"message\":\"File rejected: %s\",\"status\":400}",
            reason.replace("\"", "'")
        );

        DataBuffer buffer = exchange.getResponse().bufferFactory()
            .wrap(errorJson.getBytes(StandardCharsets.UTF_8));

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
