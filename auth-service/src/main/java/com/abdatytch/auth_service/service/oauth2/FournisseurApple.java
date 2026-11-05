package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.OAuth2UserInfo;
import com.abdatytch.auth_service.enums.OAuthProvider;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static com.abdatytch.auth_service.service.oauth2.FournisseurGoogle.encoder;

/**
 * Sign in with Apple, derriere {@link IFournisseurOAuth2}.
 *
 * <h2>Trois differences de fond avec Google et Facebook</h2>
 *
 * <ol>
 *   <li><b>Aucun point de profil.</b> Google et Facebook exposent un
 *       {@code userinfo} que l'on interroge avec le jeton d'acces. Apple n'en a
 *       pas : l'identite est <b>dans</b> le {@code id_token} rendu par l'echange,
 *       et c'est pour cela que {@link ClesPubliquesApple} est indispensable et
 *       non un supplement de prudence. Sans verification de ce jeton, l'identite
 *       serait prise dans une chaine non verifiee.</li>
 *   <li><b>Le secret client est calcule.</b> Voir {@link SecretClientApple} :
 *       un JWT ES256 a produire et renouveler, la ou les deux autres fournissent
 *       une chaine fixe.</li>
 *   <li><b>Le nom n'arrive jamais ici.</b> Apple ne le transmet pas dans
 *       l'echange de code mais dans le champ {@code user} du formulaire de
 *       rappel, et <b>uniquement a la premiere autorisation</b>. Cette classe
 *       rend donc un nom nul, que le rappel complete. La subtilite est connue
 *       pour produire des comptes anonymes : la deuxieme connexion n'apporte
 *       plus rien, donc un nom non enregistre la premiere fois est perdu
 *       definitivement — sauf a faire revoquer l'acces dans les reglages Apple.</li>
 * </ol>
 *
 * <h2>Non configure n'est pas en panne</h2>
 *
 * Sans identifiants Apple, cette classe reste un bean et {@link #estConfigure()}
 * rend faux. Rien n'echoue au demarrage : une variable Apple vide ne doit pas
 * empecher les connexions par telephone, par courriel et par mot de passe.
 *
 * <p>{@link SecretClientApple} n'est donc construit que si la configuration est
 * complete — sa construction valide la cle privee et leve si elle est illisible.
 */
@Slf4j
public class FournisseurApple implements IFournisseurOAuth2 {

    /** Instance de disjoncteur, distincte de celle du JWKS. */
    static final String DISJONCTEUR = "apple-token";

    /**
     * Disjoncteur de la revocation, distinct de celui de l'echange.
     *
     * <p>Les deux appels n'ont pas la meme consequence : un echange de code qui
     * echoue empeche une connexion, une revocation qui echoue n'empeche rien
     * puisque l'erreur est avalee. Les compter ensemble ferait ouvrir le
     * disjoncteur des connexions a cause de revocations en echec — une panne
     * fabriquee par une operation qui, elle, tolere l'echec.
     */
    static final String DISJONCTEUR_REVOCATION = "apple-revoke";

    private final WebClient webClient;
    private final ClesPubliquesApple clesPubliques;
    private final IDisjoncteurSortant disjoncteur;
    private final SecretClientApple secretClient;
    private final String servicesId;
    private final String redirectUri;
    private final Duration delaiMaximal;

    /**
     * @param secretClient {@code null} si la configuration Apple est incomplete
     *        — l'absence est un etat normal, pas une erreur de deploiement
     */
    public FournisseurApple(WebClient.Builder constructeurWebClient,
                            ClesPubliquesApple clesPubliques,
                            IDisjoncteurSortant disjoncteur,
                            SecretClientApple secretClient,
                            String servicesId,
                            String redirectUri,
                            long delaiEnSecondes) {
        this.webClient = constructeurWebClient.build();
        this.clesPubliques = clesPubliques;
        this.disjoncteur = disjoncteur;
        this.secretClient = secretClient;
        this.servicesId = servicesId;
        this.redirectUri = redirectUri;
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);
        disjoncteur.declarer(DISJONCTEUR);
        disjoncteur.declarer(DISJONCTEUR_REVOCATION);
    }

    @Override
    public OAuthProvider fournisseur() {
        return OAuthProvider.APPLE;
    }

    @Override
    public boolean estConfigure() {
        return secretClient != null
                && FournisseurGoogle.estRenseigne(servicesId)
                && FournisseurGoogle.estRenseigne(redirectUri);
    }

    /**
     * {@inheritDoc}
     *
     * <h2>Deux parametres qu'Apple impose</h2>
     *
     * {@code response_type=code id_token}, puisque l'identite vient du
     * {@code id_token} ; et {@code response_mode=form_post}, qu'Apple <b>exige</b>
     * des que le perimetre demande {@code name} ou {@code email}. Le second a
     * une consequence directe sur le controleur : le rappel arrive en
     * {@code POST}, avec un corps de formulaire, alors que Google et Facebook
     * reviennent en {@code GET}.
     */
    @Override
    public String urlDAutorisation(String etat) {
        if (!estConfigure()) {
            throw new AuthException(ErrorCode.OAUTH2_PROVIDER_NOT_CONFIGURED);
        }
        return AppConstants.APPLE_AUTH_URL
                + "?client_id=" + encoder(servicesId)
                + "&redirect_uri=" + encoder(redirectUri)
                + "&response_type=" + encoder("code id_token")
                + "&response_mode=form_post"
                + "&scope=" + encoder("name email")
                + "&state=" + encoder(etat);
    }

    @Override
    public String uriDeRappel() {
        return redirectUri;
    }

    /**
     * {@inheritDoc}
     *
     * <h2>Ce qu'Apple exige</h2>
     *
     * Depuis 2022, une application distribuee sur l'App Store qui propose Sign
     * in with Apple <b>et</b> la suppression de compte doit appeler ce point de
     * revocation. Ne pas le faire est un motif de refus en revue, et la
     * verification est faite : Apple teste le parcours de suppression.
     *
     * <h2>Pourquoi l'echec n'est jamais propage</h2>
     *
     * Cet appel intervient pendant l'effacement d'un compte. La personne a
     * demande que ses donnees disparaissent : une indisponibilite d'Apple ne
     * doit pas l'en empecher, sinon une panne chez un tiers bloque l'exercice
     * d'un droit. L'echec est donc journalise et avale.
     *
     * <p>La trace reste exploitable : {@code AccountErasureServiceImpl} tient un
     * registre de preuve, et l'echec y figure — « effacer n'est pas supprimer la
     * ligne » vaut aussi pour la preuve de ce qui n'a pas abouti.
     */
    @Override
    public Mono<Void> revoquerLAcces(String jeton) {
        if (!estConfigure() || jeton == null || jeton.isBlank()) {
            // Un compte lie avant qu'Apple soit configure, ou sans jeton
            // conserve, n'a rien a revoquer. Ce n'est pas une erreur.
            return Mono.empty();
        }

        MultiValueMap<String, String> formulaire = new LinkedMultiValueMap<>();
        formulaire.add("client_id", servicesId);
        formulaire.add("client_secret", secretClient.valeur());
        formulaire.add("token", jeton);
        formulaire.add("token_type_hint", "refresh_token");

        Mono<Void> appel = webClient.post()
                .uri(AppConstants.APPLE_REVOKE_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formulaire))
                .retrieve()
                .toBodilessEntity()
                .timeout(delaiMaximal)
                .then();

        return disjoncteur.proteger(DISJONCTEUR_REVOCATION, appel)
                .doOnSuccess(rien -> log.info("Acces Apple revoque"))
                .onErrorResume(erreur -> {
                    // Ni le jeton ni le message : le premier est un
                    // justificatif, le second peut le contenir.
                    log.error("Revocation Apple en echec ({}) : l'effacement continue",
                            erreur.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<OAuth2UserInfo> echangerLeCode(String code) {
        if (!estConfigure()) {
            return Mono.error(new AuthException(ErrorCode.OAUTH2_PROVIDER_NOT_CONFIGURED));
        }

        MultiValueMap<String, String> formulaire = new LinkedMultiValueMap<>();
        formulaire.add("grant_type", "authorization_code");
        formulaire.add("code", code);
        formulaire.add("client_id", servicesId);
        // Le secret est produit a la demande et mis en cache : voir
        // SecretClientApple. L'appel reste hors de l'EventLoop par la nature de
        // la chaine reactive qui l'entoure.
        formulaire.add("client_secret", secretClient.valeur());
        formulaire.add("redirect_uri", redirectUri);

        Mono<Map<String, Object>> reponse = webClient.post()
                .uri(AppConstants.APPLE_TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formulaire))
                .retrieve()
                .bodyToMono(TYPE_REPONSE)
                // Un corps vide rendrait un Mono VIDE, et la chaine
                // d'authentification se terminerait sans jeton ni refus : le
                // client attendrait son propre delai d'expiration.
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.EXTERNAL_SERVICE_ERROR)))
                .timeout(delaiMaximal)
                .onErrorMap(TimeoutException.class, e -> {
                    log.error("Echange de code Apple sans reponse en {}", delaiMaximal);
                    return new AuthException(ErrorCode.SERVICE_UNAVAILABLE);
                });

        return disjoncteur.proteger(DISJONCTEUR, reponse)
                .flatMap(this::lireIdentite);
    }

    @SuppressWarnings("unchecked")
    private static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>>
            TYPE_REPONSE = new org.springframework.core.ParameterizedTypeReference<>() {
    };

    /**
     * Tire l'identite du {@code id_token}, apres verification.
     *
     * <p>Le {@code access_token} d'Apple n'est volontairement pas utilise pour
     * aller chercher un profil : il n'y a rien a aller chercher.
     */
    private Mono<OAuth2UserInfo> lireIdentite(Map<String, Object> corps) {
        Object jetonDIdentite = corps.get("id_token");
        if (!(jetonDIdentite instanceof String jeton) || jeton.isBlank()) {
            // Sans id_token, il n'y a aucune identite : l'echange a echoue meme
            // si la reponse etait un 200. Rendre une erreur plutot que de
            // continuer avec des champs nuls, qui creeraient un compte vide.
            log.error("Reponse d'echange Apple sans id_token");
            return Mono.error(new AuthException(ErrorCode.EXTERNAL_SERVICE_ERROR));
        }

        return clesPubliques.verifier(jeton).map(revendications -> {
            String adresse = revendications.get("email", String.class);
            return OAuth2UserInfo.builder()
                    // Le « sub » d'Apple est stable pour cette application et
                    // ne change pas si la personne change d'adresse. C'est la
                    // seule cle de liaison fiable.
                    .providerId(revendications.getSubject())
                    .email(adresse)
                    // Le nom n'est PAS ici : Apple le transmet dans le
                    // formulaire de rappel, a la premiere autorisation
                    // seulement. Voir la javadoc de la classe.
                    .name(null)
                    .firstName(null)
                    .lastName(null)
                    .pictureUrl(null)
                    .emailVerified(estVerifiee(revendications))
                    .accessToken((String) corps.get("access_token"))
                    .refreshToken((String) corps.get("refresh_token"))
                    .expiresIn(dureeDeValidite(corps))
                    .build();
        });
    }

    /**
     * Lit {@code email_verified}, qu'Apple envoie tantot en booleen tantot en
     * chaine.
     *
     * <p>Le lire uniquement comme un booleen rendrait faux pour la chaine
     * {@code "true"} : une adresse verifiee par Apple serait alors traitee comme
     * non verifiee, et la personne se verrait demander une verification de plus.
     */
    private static boolean estVerifiee(Claims revendications) {
        Object valeur = revendications.get("email_verified");
        if (valeur instanceof Boolean booleen) {
            return booleen;
        }
        return "true".equalsIgnoreCase(String.valueOf(valeur));
    }

    private static Long dureeDeValidite(Map<String, Object> corps) {
        Object valeur = corps.get("expires_in");
        return valeur instanceof Number nombre ? nombre.longValue() : null;
    }
}
