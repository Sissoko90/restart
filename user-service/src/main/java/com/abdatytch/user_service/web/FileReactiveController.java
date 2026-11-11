package com.abdatytch.user_service.web;

import com.abdatytch.user_service.constants.WebConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**

 * Controller réactif pour servir les fichiers uploadés
 * PRÉSERVE TOUTE LA LOGIQUE MÉTIER de FileController
 */
@RestController
@RequestMapping("/files")
@Slf4j
public class FileReactiveController {

    @Value("${file.upload.dir:uploads/profiles}")
    private String uploadDir;

    /**

     * Sert un fichier du répertoire d'un utilisateur.
     *
     * <p><b>Accès restreint au propriétaire.</b> Le segment {@code userId} du
     * chemin est l'{@code authUserId} sous lequel le fichier a été déposé
     * ({@code UserReactiveController#uploadProfilePicture}). Il vient du client :
     * sans le contrôle ci-dessous, il suffisait de le remplacer par celui d'un
     * autre pour lire ses fichiers — et l'endpoint étant alors en {@code permitAll},
     * sans même être authentifié.
     *
     * <p>Si le produit veut des avatars visibles par les autres utilisateurs,
     * c'est une décision explicite à prendre : elle passe par une URL publique
     * dédiée portant un identifiant non devinable, pas par la levée de ce
     * contrôle.
     */
    @GetMapping("/{userId}/{filename:.+}")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Flux<DataBuffer>>> serveFile(
            @PathVariable String userId,
            @PathVariable String filename) {

        return currentAuthentication()
                .filter(authentication -> isOwnerOrAdmin(authentication, userId))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Accès refusé au fichier d'autrui : demandé={}, fichier={}",
                            userId, filename);
                    // 404 et non 403 : un 403 confirmerait au demandeur que ce
                    // répertoire existe, et permettrait d'énumérer les comptes.
                    return Mono.empty();
                }))
                .flatMap(authentication -> serveOwnedFile(userId, filename))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> serveOwnedFile(String userId, String filename) {
        return Mono.fromCallable(() -> resolveReadableFile(userId, filename))
                // Les tests d'existence et de lecture touchent le système de fichiers :
                // appels bloquants, à isoler de l'EventLoop.
                .subscribeOn(Schedulers.boundedElastic())
                .map(filePath -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        // nosniff : le type est IMPOSE a image/jpeg, mais sans
                        // cet en-tete un navigateur peut encore renifler le
                        // contenu reel. Un fichier SVG ou HTML depose puis
                        // servi « inline » depuis l'origine de la plateforme
                        // deviendrait alors du script execute dans ce contexte.
                        //
                        // Le chemin d'envoi n'accepte plus que des extensions
                        // d'images, mais deux garde-fous valent mieux qu'un
                        // quand le premier porte sur ce que le client declare.
                        .header("X-Content-Type-Options", "nosniff")
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + sanitizeHeaderValue(filename) + "\"")
                        .body(DataBufferUtils.read(filePath, new DefaultDataBufferFactory(), 4096)))
                // Fichier absent -> Mono vide -> 404. L'ancienne version renvoyait
                // `null` depuis fromCallable, ce qui produit un Mono VIDE : le flatMap
                // suivant n'était jamais exécuté et sa branche 404 était du code mort.
                // Le client recevait un 200 sans corps.
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    log.error(WebConstants.LOG_ERROR_SERVING_FILE, userId, filename, e);
                    return Mono.just(ResponseEntity.internalServerError().<Flux<DataBuffer>>build());
                });
    }

    private Mono<Authentication> currentAuthentication() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated);
    }

    /**

     * Le répertoire est nommé d'après l'{@code authUserId}, qui est aussi le
     * sujet du jeton — donc le nom du principal authentifié.
     */
    private boolean isOwnerOrAdmin(Authentication authentication, String requestedUserId) {
        if (requestedUserId.equals(authentication.getName())) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }

    /**

     * Résout le fichier demandé, ou renvoie null s'il est introuvable ou illisible.
     *
     * <p><b>Traversée de chemin.</b> {@code filename} vient du client et le motif
     * {@code {filename:.+}} accepte les séparateurs : sans contrôle, un
     * {@code ../../etc/passwd} sortirait du répertoire d'upload. On normalise donc le
     * chemin résolu et on vérifie qu'il reste sous la racine autorisée.
     */
    private Path resolveReadableFile(String userId, String filename) {
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path filePath = root.resolve(userId).resolve(filename).normalize();

        if (!filePath.startsWith(root)) {
            log.warn("Tentative de traversée de chemin refusée : userId={}, filename={}",
                    userId, filename);
            return null;
        }

        if (!Files.isReadable(filePath) || Files.isDirectory(filePath)) {
            log.warn(WebConstants.LOG_WARN_FILE_NOT_FOUND, filePath);
            return null;
        }

        return filePath;
    }

    /**

     * Neutralise les caractères qui permettraient d'injecter un en-tête HTTP
     * supplémentaire via le nom de fichier (CRLF injection).
     */
    private String sanitizeHeaderValue(String value) {
        return value.replaceAll("[\\r\\n\"]", "_");
    }
}
