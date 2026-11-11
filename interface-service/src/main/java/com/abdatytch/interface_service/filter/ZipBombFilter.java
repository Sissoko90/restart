package com.abdatytch.interface_service.filter;

import com.abdatytch.commons.security.GatewayClientIp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import reactor.core.publisher.Flux;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**

 * Filtre de protection contre les attaques Zip Bomb
 * Analyse les fichiers ZIP uploadés pour détecter les ratios de compression suspects
 */
@Component
@Order(OrdreDesFiltres.BOMBE_ZIP)
public class ZipBombFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ZipBombFilter.class);

    @Value("${ZIPBOMB_ENABLED:true}")
    private boolean enabled;

    @Value("${ZIPBOMB_MAX_COMPRESSION_RATIO:100}")
    private int maxCompressionRatio;

    @Value("${ZIPBOMB_MAX_UNCOMPRESSED_SIZE_MB:500}")
    private int maxUncompressedSizeMB;

    @Value("${ZIPBOMB_MAX_ENTRIES:1000}")
    private int maxEntries;

    @Value("${ZIPBOMB_MAX_NESTED_DEPTH:3}")
    private int maxNestedDepth;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        // Vérifier seulement les uploads de fichiers ZIP
        String contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");
        if (contentType == null || !isZipContent(contentType)) {
            return chain.filter(exchange);
        }

        log.debug("Analyse ZipBomb pour: {}", exchange.getRequest().getPath());

        // `join` BORNE, et non `collectList()`.
        //
        // PREMIER DEFAUT, et il est auto-contradictoire : ce filtre existe pour
        // refuser une charge disproportionnee, et il chargeait la TOTALITE du
        // corps en memoire avant d'y regarder. L'attaque visee est le gonflement
        // a la DECOMPRESSION ; celle-ci etait le gonflement a la RECEPTION, et
        // elle fonctionnait.
        //
        // Exactement le defaut corrige au Gateway sur ZipBombProtectionFilter.
        return DataBufferUtils.join(exchange.getRequest().getBody(), tailleCompresseeMax())
            .flatMap(buffer -> {
                byte[] zipData = new byte[buffer.readableByteCount()];
                buffer.read(zipData);
                DataBufferUtils.release(buffer);

                try {
                    if (isZipBomb(zipData)) {
                        log.warn("ZipBomb détecté depuis IP: {} - Path: {}",
                                getClientIp(exchange),
                                exchange.getRequest().getPath());
                        return blockRequest(exchange, "ZipBomb détecté");
                    }
                } catch (Exception e) {
                    // Repli OUVERT, assume : une archive illisible n'est pas une
                    // bombe, et refuser tout ce qu'on ne sait pas analyser
                    // casserait des envois legitimes. Le controleur en aval
                    // refusera ce qu'il ne sait pas lire.
                    log.error("Erreur analyse ZipBomb: {}", e.getMessage());
                }

                // Le corps est RESTITUE a la suite de la chaine.
                //
                // SECOND DEFAUT. L'ancien code lisait le corps puis appelait
                // `chain.filter(exchange)` avec l'echange d'origine, dont le
                // corps etait deja consomme : un envoi ZIP legitime arrivait
                // VIDE au controleur. XssProtectionFilter, lui, decore bien la
                // requete pour re-emettre le corps.
                return chain.filter(exchange.mutate()
                        .request(new ServerHttpRequestDecorator(exchange.getRequest()) {
                            @Override
                            public Flux<DataBuffer> getBody() {
                                return Flux.just(exchange.getResponse()
                                        .bufferFactory().wrap(zipData));
                            }
                        })
                        .build());
            })
            // Au-dela de la borne, `join` leve : un envoi trop volumineux est
            // refuse en 413, et non propage en 500. La taille d'une requete est
            // le fait de l'appelant.
            .onErrorResume(DataBufferLimitException.class, e -> {
                log.warn("Archive trop volumineuse refusée depuis IP: {}",
                        getClientIp(exchange));
                exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                return exchange.getResponse().setComplete();
            })
            // Un corps VIDE rend un Mono vide : la requete ne serait ni
            // transmise ni repondue. Un Content-Type zip sans corps est une
            // requete malformee, pas une bombe — on la laisse au controleur.
            .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
    }

    /**
     * Borne du corps compressé accepté, en octets.
     *
     * <p>Dérivée de {@code maxUncompressedSizeMB} divisé par le ratio de
     * compression maximal : une archive dont la forme compressée dépasse déjà
     * cette taille produirait, au pire ratio toléré, plus que la limite
     * décompressée. La borner ici évite de la charger pour le constater.
     */
    private int tailleCompresseeMax() {
        long borne = (long) maxUncompressedSizeMB * 1024 * 1024 / Math.max(1, maxCompressionRatio);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(64 * 1024, borne));
    }

    /**

     * Vérifier si le contenu est un fichier ZIP
     */
    private boolean isZipContent(String contentType) {
        return contentType.contains("application/zip") ||
               contentType.contains("application/x-zip") ||
               contentType.contains("multipart/form-data"); // Peut contenir des ZIP
    }

    /**

     * Analyser le fichier ZIP pour détecter une Zip Bomb
     */
    private boolean isZipBomb(byte[] zipData) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {

            long totalUncompressedSize = 0;
            int entryCount = 0;
            int nestedDepth = 0;

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;

                // Vérifier le nombre d'entrées
                if (entryCount > maxEntries) {
                    log.warn("ZipBomb: Trop d'entrées ({} > {})", entryCount, maxEntries);
                    return true;
                }

                // Vérifier la profondeur d'imbrication
                String entryName = entry.getName();
                int depth = countPathDepth(entryName);
                if (depth > maxNestedDepth) {
                    nestedDepth = Math.max(nestedDepth, depth);
                    log.warn("ZipBomb: Profondeur excessive ({} > {})", depth, maxNestedDepth);
                    return true;
                }

                // Vérifier la taille décompressée
                long compressedSize = entry.getCompressedSize();
                long uncompressedSize = entry.getSize();

                if (uncompressedSize > 0) {
                    totalUncompressedSize += uncompressedSize;

                    // Vérifier la taille totale décompressée
                    long maxSizeBytes = (long) maxUncompressedSizeMB * 1024 * 1024;
                    if (totalUncompressedSize > maxSizeBytes) {
                        log.warn("ZipBomb: Taille décompressée excessive ({} MB > {} MB)",
                                totalUncompressedSize / (1024 * 1024), maxUncompressedSizeMB);
                        return true;
                    }

                    // Vérifier le ratio de compression
                    if (compressedSize > 0) {
                        double ratio = (double) uncompressedSize / compressedSize;
                        if (ratio > maxCompressionRatio) {
                            log.warn("ZipBomb: Ratio de compression suspect ({} > {})",
                                    (int) ratio, maxCompressionRatio);
                            return true;
                        }
                    }
                }

                zis.closeEntry();
            }

            return false;
        }
    }

    /**

     * Compter la profondeur d'un chemin
     */
    private int countPathDepth(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        return path.split("[/\\\\]").length - 1;
    }

    /**

     * Combiner les DataBuffer en tableau de bytes
     */
    private byte[] combineDataBuffers(java.util.List<DataBuffer> dataBuffers) {
        int totalSize = dataBuffers.stream().mapToInt(DataBuffer::readableByteCount).sum();
        byte[] combined = new byte[totalSize];

        int offset = 0;
        for (DataBuffer buffer : dataBuffers) {
            int size = buffer.readableByteCount();
            buffer.read(combined, offset, size);
            offset += size;
        }

        return combined;
    }

    /**

     * Obtenir l'IP du client
     */
    /**
     * Adresse du client, posee par le Gateway.
     *
     * <p>Cette methode lisait {@code X-Forwarded-For} puis {@code X-Real-IP} :
     * l'appelant choisissait donc l'adresse sur laquelle reposait la decision.
     * Le plafond de debit se contournait en faisant varier l'en-tete, et la
     * liste noire d'IP ne bloquait rien.
     *
     * <p>Voir {@code GatewayClientIp} pour le detail — le meme schema a ete
     * trouve huit fois dans ce depot.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return GatewayClientIp.of(exchange);
    }

    /**

     * Bloquer la requête avec une réponse d'erreur
     */
    private Mono<Void> blockRequest(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String jsonResponse = String.format(
            "{\"error\":\"Request blocked\",\"reason\":\"%s\",\"timestamp\":\"%s\"}",
            reason,
            java.time.Instant.now()
        );

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(jsonResponse.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
