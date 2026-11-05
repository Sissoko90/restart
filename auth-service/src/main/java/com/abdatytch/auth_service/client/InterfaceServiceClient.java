package com.abdatytch.auth_service.client;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import com.abdatytch.auth_service.service.ServiceTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**

 * Client pour communiquer avec interface-service
 * Utilisé pour déléguer la génération de JWT à interface-service
 *
 * Architecture Auth-as-a-Service:
 * - auth-service authentifie les utilisateurs finaux
 * - interface-service signe les JWT avec ses clés RSA
 * - Les développeurs valident les JWT via JWKS public
 */
@Component
@Slf4j
public class InterfaceServiceClient {

    @Value("${interface-service.url:http://localhost:8088}")
    private String interfaceServiceUrl;

    // Aucun défaut : secret d'appel de /internal/jwt/sign.
    @Value("${security.internal-secret}")
    private String internalSecret;

    private final WebClient webClient;
    private final ServiceTokenProvider serviceTokenProvider;

    /**
     * Delai maximal d'un appel a interface-service.
     *
     * <h3>Le defaut que cela ferme</h3>
     *
     * Les trois appels de cette classe n'en avaient <b>aucun</b>. Or
     * interface-service est l'<b>autorite JWT</b> de la plateforme : il signe
     * les jetons que les huit autres services verifient, et il est sur le chemin
     * de chaque connexion.
     *
     * <p>Sans delai maximal, une instance figee — pas tombee, figee : le cas le
     * plus courant et le plus traitre — retient indefiniment la chaine
     * reactive appelante. Les connexions s'accumulent sans jamais aboutir ni
     * echouer, et rien ne distingue cet etat d'une lenteur passagere.
     *
     * <p>Les trois autres clients sortants du service ({@code AfrikSmsProvider},
     * {@code AfrikSmsAdministration}, {@code GatewayGeoLocationClient}) portent
     * bien le leur. Celui du chemin critique etait le seul sans.
     *
     * <p>Cinq secondes : une signature RSA et un aller-retour local se comptent
     * en millisecondes. Au-dela, ce n'est plus de la lenteur, c'est une panne —
     * et il vaut mieux la declarer que l'attendre.
     */
    private final Duration delaiMaximal;

    /**
     * Nom d'instance du disjoncteur, propre a cette dependance.
     *
     * <p>Une instance par dependance : deux dependances qui partagent un nom
     * tombent ensemble, et la panne de l'une fermerait le chemin de l'autre.
     */
    static final String DISJONCTEUR = "interface-service";

    private final IDisjoncteurSortant disjoncteur;

    public InterfaceServiceClient(
            WebClient.Builder webClientBuilder,
            ServiceTokenProvider serviceTokenProvider,
            IDisjoncteurSortant disjoncteur,
            @Value("${interface-service.timeout-seconds:5}") long delaiEnSecondes) {
        this.webClient = webClientBuilder.build();
        this.serviceTokenProvider = serviceTokenProvider;
        this.disjoncteur = disjoncteur;
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);
        // Declare au demarrage : sans cela, l'instance n'existerait qu'apres la
        // premiere connexion, donc invisible de la supervision jusque-la.
        disjoncteur.declarer(DISJONCTEUR);
    }

    /**
     * Borne la duree totale d'un appel, et nomme l'echec.
     *
     * <p>Le delai est pose sur la chaine <b>entiere</b>, generation du jeton de
     * service comprise : le borner sur le seul appel HTTP laisserait une
     * signature lente passer au travers.
     *
     * <p>Un {@code TimeoutException} nu remonterait en 500, ce qui designe un
     * defaut de ce service. {@code SERVICE_UNAVAILABLE} dit la verite :
     * la dependance ne repond pas.
     */
    private <T> Mono<T> borner(Mono<T> appel, String operation) {
        // L'ORDRE compte : delai a l'interieur, disjoncteur a l'exterieur.
        //
        // Le disjoncteur ne compte que ce qu'il voit echouer. Pose a
        // l'interieur du delai, il ne verrait jamais une dependance FIGEE —
        // l'appel ne se terminerait pas, donc rien a compter — et c'est
        // precisement le cas le plus courant. A l'exterieur, le delai lui
        // fournit un echec franc a chaque tentative, et il peut alors decider
        // d'arreter d'essayer.
        return disjoncteur.proteger(DISJONCTEUR, appel
                .timeout(delaiMaximal)
                .onErrorMap(TimeoutException.class, e -> {
                    log.error("interface-service n'a pas repondu en {} pour {}",
                            delaiMaximal, operation);
                    return new AuthException(ErrorCode.SERVICE_UNAVAILABLE,
                            "Service de signature des jetons indisponible");
                }));
    }

    /**

     * Génère un service token pour l'authentification inter-services
     */
    private Mono<String> generateServiceToken() {
        // Porte sur boundedElastic : une signature RSA est du calcul synchrone.
        //
        // `Mono.fromCallable` seul s'execute sur le thread SOUSCRIPTEUR, donc
        // sur un EventLoop Netty en contexte de requete. C'est moins lourd que
        // BCrypt — quelques millisecondes — mais c'est le meme defaut, et il se
        // paie a chaque connexion. Voir IMotDePasseService pour la mesure qui a
        // motive cette regle.
        return Mono.fromCallable(serviceTokenProvider::generateServiceToken)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Echec de generation du jeton de service : {}",
                        e.getClass().getSimpleName()));
    }

    /**

     * Demande à interface-service de signer un JWT pour un utilisateur final
     *
     * COMMUNICATION INTER-SERVICES:
     * - X-Service-Token: JWT de service pour authentification
     * - X-Internal-Secret: Secret partagé pour autorisation interne
     *
     * @param userId ID de l'utilisateur
     * @param projectId ID du projet du développeur
     * @param email Email de l'utilisateur
     * @param username Nom d'utilisateur
     * @param roles Rôles de l'utilisateur
     * @param customClaims Claims personnalisés
     * @return Réponse contenant accessToken, refreshToken, expiresIn
     */
    public Mono<AuthTokenResponse> signUserToken(String userId, String projectId,
                                                  String email, String username,
                                                  List<String> roles,
                                                  Map<String, Object> customClaims) {

        JwtSignRequest request = new JwtSignRequest();
        request.setUserId(userId);
        request.setProjectId(projectId);
        request.setEmail(email);
        request.setUsername(username);
        request.setRoles(roles);
        request.setCustomClaims(customClaims);

        // Générer un service token pour l'authentification inter-services
        return borner(generateServiceToken()
                .flatMap(serviceToken ->
                    webClient.post()
                            .uri(interfaceServiceUrl + "/internal/jwt/sign")
                            .header("X-Service-Token", serviceToken)      // Standard inter-services
                            .header("X-Internal-Secret", internalSecret)  // Secret partagé
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(AuthTokenResponse.class)
                ), "signature de jeton")
                .doOnSuccess(resp -> log.info("Jeton signe via interface-service pour {}", userId))
                .doOnError(e -> log.error("Echec de la signature du jeton via interface-service : {}",
                        e.getClass().getSimpleName()));
    }

    /**

     * Rafraîchit un token via interface-service
     */
    public Mono<AuthTokenResponse> refreshToken(String refreshToken) {
        return borner(generateServiceToken()
                .flatMap(serviceToken ->
                    webClient.post()
                            .uri(interfaceServiceUrl + "/internal/jwt/refresh")
                            .header("X-Service-Token", serviceToken)
                            .header("X-Internal-Secret", internalSecret)
                            .bodyValue(Map.of("refreshToken", refreshToken))
                            .retrieve()
                            .bodyToMono(AuthTokenResponse.class)
                ), "rafraichissement de jeton")
                .doOnError(e -> log.error("Echec du rafraichissement via interface-service : {}",
                        e.getClass().getSimpleName()));
    }

    /**

     * Vérifie un token via interface-service
     */
    public Mono<TokenVerifyResponse> verifyToken(String token) {
        return borner(generateServiceToken()
                .flatMap(serviceToken ->
                    webClient.post()
                            .uri(interfaceServiceUrl + "/internal/jwt/verify")
                            .header("X-Service-Token", serviceToken)
                            .header("X-Internal-Secret", internalSecret)
                            .bodyValue(Map.of("token", token))
                            .retrieve()
                            .bodyToMono(TokenVerifyResponse.class)
                ), "verification de jeton");
    }

    // Request/Response DTOs
    public static class JwtSignRequest {
        private String userId;
        private String projectId;
        private String email;
        private String username;
        private List<String> roles;
        private Map<String, Object> customClaims;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
        public Map<String, Object> getCustomClaims() { return customClaims; }
        public void setCustomClaims(Map<String, Object> customClaims) { this.customClaims = customClaims; }
    }

    public static class AuthTokenResponse {
        private String accessToken;
        private String refreshToken;
        private String tokenType;
        private long expiresIn;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public long getExpiresIn() { return expiresIn; }
        public void setExpiresIn(long expiresIn) { this.expiresIn = expiresIn; }
    }

    public static class TokenVerifyResponse {
        private boolean valid;
        private String userId;
        private String projectId;

        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
    }
}
