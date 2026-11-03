package com.abdatytch.interface_service.filter;

import com.abdatytch.commons.security.SecretComparisonUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**

 * Filtre pour sécuriser les endpoints admin avec une clé secrète
 * Vérifie le header X-Admin-Secret pour les requêtes vers /admin/** */
@Component
@Order(OrdreDesFiltres.SECRET_ADMIN)
public class AdminSecretFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminSecretFilter.class);
    private static final String ADMIN_SECRET_HEADER = "X-Admin-Secret";

    /**
     * Chemins atteints par un clic dans un courriel, donc sans en-tête possible.
     *
     * <p>Voir {@code estAtteintDepuisUnCourriel} pour le raisonnement. La liste
     * est volontairement comparée par <b>égalité</b> et non par préfixe : un
     * {@code startsWith} ouvrirait {@code /admin/confirm/quelque-chose}, et une
     * exemption doit désigner exactement ce qu'elle exempte.
     */
    private static final java.util.Set<String> CHEMINS_DEPUIS_COURRIEL =
            java.util.Set.of("/admin/confirm");

    @Value("${admin.secret.key:}")
    private String adminSecretKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Vérifier si c'est une requête admin
        //
        // `startsWith("/admin/")` laissait `/admin` exactement non garde. Aucun
        // endpoint n'y repond aujourd'hui, mais la garde doit couvrir le
        // prefixe, pas seulement ses descendants : un @GetMapping("") ajoute
        // demain a AdminController serait publie sans controle.
        if (estCheminAdmin(path) && !estAtteintDepuisUnCourriel(path)) {
            String providedSecret = exchange.getRequest().getHeaders().getFirst(ADMIN_SECRET_HEADER);

            // Vérifier si la clé est configurée
            if (adminSecretKey == null || adminSecretKey.isEmpty()) {
                log.warn("Admin secret key non configuré - endpoints admin bloqués");
                return sendUnauthorized(exchange);
            }

            // Vérifier si le header est présent
            if (providedSecret == null || providedSecret.isEmpty()) {
                log.warn(" Tentative d'accès admin sans header X-Admin-Secret - IP: {}",
                        exchange.getRequest().getRemoteAddress());
                return sendUnauthorized(exchange);
            }

            // Vérifier si la clé correspond
            if (!SecretComparisonUtil.matches(adminSecretKey, providedSecret)) {
                log.warn(" Tentative d'accès admin avec clé invalide - IP: {}",
                        exchange.getRequest().getRemoteAddress());
                return sendUnauthorized(exchange);
            }

            log.debug(" Admin secret validé pour: {}", path);
        }

        return chain.filter(exchange);
    }

    /** Le chemin relève-t-il de l'administration ? */
    private boolean estCheminAdmin(String path) {
        return "/admin".equals(path) || path.startsWith("/admin/");
    }

    /**
     * Chemins atteints par un <b>clic dans un courriel</b>, donc sans en-tête.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * {@code /admin/confirm} est l'endpoint « C'est moi / Ce n'est pas moi » :
     * l'administrateur reçoit un lien par courriel et le clique dans son
     * navigateur. Un navigateur suivant un lien <b>ne peut pas</b> ajouter
     * {@code X-Admin-Secret}.
     *
     * <p>Ce filtre exigeait donc un en-tête que l'appelant légitime est
     * incapable d'envoyer : le lien de confirmation répondait 401 et le compte
     * administrateur restait bloqué, sans recours.
     *
     * <p>{@code SecurityConfig} exemptait bien ce chemin
     * ({@code .pathMatchers("/admin/confirm").permitAll()}, avec le commentaire
     * « jeton à usage unique reçu par courriel ») — l'intention était donc
     * explicite. Mais ce filtre est un bean {@code WebFilter} : il s'exécute
     * dans la chaîne HTTP <b>globale</b>, en amont de Spring Security.
     * L'exemption était posée une couche trop bas.
     *
     * <p>C'est pourquoi l'exemption est reprise ici, à l'endroit où elle agit.
     * Ce chemin n'est pas pour autant ouvert : il porte sa propre vérification,
     * un jeton à usage unique qui expire et qui est invalidé à l'usage — ce que
     * {@code AdminConfirmationTest} vérifie.
     */
    private boolean estAtteintDepuisUnCourriel(String path) {
        return CHEMINS_DEPUIS_COURRIEL.contains(path);
    }

    private Mono<Void> sendUnauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        String body = "{\"error\":\"Unauthorized\",\"message\":\"Accès non autorisé\"}";
        DataBuffer buffer = DefaultDataBufferFactory.sharedInstance.wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
