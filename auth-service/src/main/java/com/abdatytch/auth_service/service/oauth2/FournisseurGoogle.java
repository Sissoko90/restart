package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.dto.response.OAuth2UserInfo;
import com.abdatytch.auth_service.enums.OAuthProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Google, derriere {@link IFournisseurOAuth2}.
 *
 * <h2>Ce qui a change, et ce qui n'a pas change</h2>
 *
 * Les deux appels HTTP sont ceux de {@code OAuth2ServiceImpl}, deplaces sans
 * retouche : memes points d'entree, memes parametres, memes champs lus dans la
 * reponse. Le but du deplacement etait de retirer le branchement par
 * {@code if/else}, pas de reecrire un echange qui fonctionne et n'a aucun test.
 *
 * <p>Deux corrections s'y ajoutent, parce qu'elles n'auraient pas de meilleur
 * endroit :
 *
 * <ul>
 *   <li>le {@link RestTemplate} est <b>injecte</b> et non construit ici. Celui
 *       du service etait un {@code new RestTemplate()} sans aucun delai : un
 *       Google qui accepte la connexion puis ne repond pas retenait un fil de
 *       {@code boundedElastic} indefiniment, et le pool a une taille bornee.
 *       Assez de connexions dans cet etat et plus aucune I/O bloquante du
 *       service ne passe — y compris la verification des mots de passe ;</li>
 *   <li>l'URL d'autorisation vient de {@link AppConstants#OAUTH2_GOOGLE_AUTH_URL},
 *       qui <b>existait deja</b> et n'etait pas utilisee : la methode reecrivait
 *       le lien en litteral. Deux sources pour une meme valeur, dont une seule
 *       etait modifiee en cas de changement.</li>
 * </ul>
 */
@Slf4j
@Component
public class FournisseurGoogle implements IFournisseurOAuth2 {

    private final RestTemplate restTemplate;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public FournisseurGoogle(RestTemplate restTemplate,
                             @Value("${oauth2.google.client-id:}") String clientId,
                             @Value("${oauth2.google.client-secret:}") String clientSecret,
                             @Value("${oauth2.google.redirect-uri:}") String redirectUri) {
        this.restTemplate = restTemplate;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public OAuthProvider fournisseur() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public boolean estConfigure() {
        return estRenseigne(clientId) && estRenseigne(clientSecret) && estRenseigne(redirectUri);
    }

    static boolean estRenseigne(String valeur) {
        return valeur != null && !valeur.isBlank();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Le {@code redirect_uri} transmis par l'appelant est volontairement
     * ignore : seul celui de la configuration est utilise. C'etait deja le cas
     * et c'est deliberе — accepter une redirection fournie par l'appelant
     * permettrait de faire renvoyer le code d'autorisation vers un domaine
     * choisi par lui.
     */
    @Override
    public String urlDAutorisation(String etat) {
        return AppConstants.OAUTH2_GOOGLE_AUTH_URL
                + "?client_id=" + encoder(clientId)
                + "&redirect_uri=" + encoder(redirectUri)
                + "&response_type=code"
                + "&scope=" + encoder("openid email profile")
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + encoder(etat);
    }

    static String encoder(String valeur) {
        return URLEncoder.encode(valeur == null ? "" : valeur, StandardCharsets.UTF_8);
    }

    @Override
    public String uriDeRappel() {
        return redirectUri;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rien a faire : Google n'impose aucune revocation de notre part,
     * et nous ne conservons son jeton que le temps de l'echange. Le point de
     * revocation existe chez lui, mais l'appeler n'est pas exige et ajouterait
     * un appel sortant sur le chemin de l'effacement — donc une dependance
     * supplementaire a une operation qui doit aboutir.
     *
     * <p>Methode explicitement vide, et non heritee d'une {@code default} :
     * voir la javadoc de {@link IFournisseurOAuth2#revoquerLAcces}.
     */
    @Override
    public Mono<Void> revoquerLAcces(String jeton) {
        return Mono.empty();
    }

    @Override
    public Mono<OAuth2UserInfo> echangerLeCode(String code) {
        // L'echange reste bloquant (RestTemplate) et reste donc isole sur le
        // pool d'I/O bloquante, comme il l'etait dans le service. Le placer sur
        // une EventLoop immobiliserait un fil d'un pool de la taille du nombre
        // de coeurs, pour la duree d'un aller-retour vers Google.
        return Mono.fromCallable(() -> echangerMaintenant(code))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @SuppressWarnings("unchecked")
    private OAuth2UserInfo echangerMaintenant(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("code", code);
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("redirect_uri", redirectUri);
        params.add("grant_type", "authorization_code");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        ResponseEntity<Map<String, Object>> tokenResponse = restTemplate.exchange(
                AppConstants.GOOGLE_TOKEN_URL,
                HttpMethod.POST,
                request,
                (Class<Map<String, Object>>) (Class<?>) Map.class
        );

        Map<String, Object> tokenBody = tokenResponse.getBody();
        String accessToken = (String) tokenBody.get("access_token");
        String refreshToken = (String) tokenBody.get("refresh_token");
        Integer expiresIn = (Integer) tokenBody.get("expires_in");

        log.info(ServiceConstants.LOG_INFO_OAUTH2_USERINFO_FETCH,
                AppConstants.OAUTH2_PROVIDER_GOOGLE);

        HttpHeaders userInfoHeaders = new HttpHeaders();
        userInfoHeaders.setBearerAuth(accessToken);

        ResponseEntity<Map<String, Object>> userInfoResponse = restTemplate.exchange(
                AppConstants.GOOGLE_USERINFO_URL,
                HttpMethod.GET,
                new HttpEntity<>(userInfoHeaders),
                (Class<Map<String, Object>>) (Class<?>) Map.class
        );

        Map<String, Object> userInfoData = userInfoResponse.getBody();

        return OAuth2UserInfo.builder()
                .providerId((String) userInfoData.get("id"))
                .email((String) userInfoData.get("email"))
                .name((String) userInfoData.get("name"))
                .firstName((String) userInfoData.get("given_name"))
                .lastName((String) userInfoData.get("family_name"))
                .pictureUrl((String) userInfoData.get("picture"))
                .emailVerified((Boolean) userInfoData.get("verified_email"))
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(expiresIn != null ? expiresIn.longValue() : null)
                .build();
    }
}
