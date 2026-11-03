package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.dto.response.OAuth2UserInfo;
import com.abdatytch.auth_service.enums.OAuthProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

import static com.abdatytch.auth_service.service.oauth2.FournisseurGoogle.encoder;
import static com.abdatytch.auth_service.service.oauth2.FournisseurGoogle.estRenseigne;

/**
 * Facebook, derriere {@link IFournisseurOAuth2}.
 *
 * <h2>Le perimetre demande n'inclut pas l'adresse, et c'est voulu</h2>
 *
 * Facebook n'accorde {@code email} qu'apres revision de l'application. Demander
 * un perimetre non accorde fait echouer l'autorisation entiere, donc le
 * perimetre reste {@code public_profile}. La consequence porte plus loin que
 * cette classe : une personne qui arrive par Facebook n'a <b>pas</b> d'adresse,
 * et {@code emailVerified} est faux — c'est pourquoi le parcours de completion
 * de profil existe.
 *
 * <h2>Pas de jeton de rafraichissement</h2>
 *
 * Facebook n'en fournit pas par defaut. Le champ est explicitement {@code null}
 * plutot qu'absent, pour que la lecture du code ne laisse pas croire a un oubli.
 */
@Slf4j
@Component
public class FournisseurFacebook implements IFournisseurOAuth2 {

    private final RestTemplate restTemplate;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public FournisseurFacebook(RestTemplate restTemplate,
                               @Value("${oauth2.facebook.client-id:}") String clientId,
                               @Value("${oauth2.facebook.client-secret:}") String clientSecret,
                               @Value("${oauth2.facebook.redirect-uri:}") String redirectUri) {
        this.restTemplate = restTemplate;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public OAuthProvider fournisseur() {
        return OAuthProvider.FACEBOOK;
    }

    @Override
    public boolean estConfigure() {
        return estRenseigne(clientId) && estRenseigne(clientSecret) && estRenseigne(redirectUri);
    }

    @Override
    public String urlDAutorisation(String etat) {
        return AppConstants.OAUTH2_FACEBOOK_AUTH_URL
                + "?client_id=" + encoder(clientId)
                + "&redirect_uri=" + encoder(redirectUri)
                + "&response_type=code"
                + "&scope=public_profile"
                + "&state=" + encoder(etat);
    }

    @Override
    public String uriDeRappel() {
        return redirectUri;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rien a faire : Facebook n'impose aucune revocation de notre part,
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
        return Mono.fromCallable(() -> echangerMaintenant(code))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @SuppressWarnings("unchecked")
    private OAuth2UserInfo echangerMaintenant(String code) {
        // Le code et le secret partent en parametres d'URL : c'est la forme
        // qu'impose Facebook pour cet echange. Ils sont donc encodes, sans quoi
        // un code contenant « & » ou « = » tronquerait la requete.
        String tokenUrl = AppConstants.FACEBOOK_TOKEN_URL
                + "?client_id=" + encoder(clientId)
                + "&client_secret=" + encoder(clientSecret)
                + "&code=" + encoder(code)
                + "&redirect_uri=" + encoder(redirectUri);

        ResponseEntity<Map<String, Object>> tokenResponse = restTemplate.getForEntity(
                tokenUrl, (Class<Map<String, Object>>) (Class<?>) Map.class);
        Map<String, Object> tokenBody = tokenResponse.getBody();
        String accessToken = (String) tokenBody.get("access_token");
        Integer expiresIn = (Integer) tokenBody.get("expires_in");

        log.info(ServiceConstants.LOG_INFO_OAUTH2_USERINFO_FETCH,
                AppConstants.OAUTH2_PROVIDER_FACEBOOK);

        String userInfoUrl = AppConstants.FACEBOOK_USERINFO_URL
                + "&access_token=" + encoder(accessToken);
        ResponseEntity<Map<String, Object>> userInfoResponse = restTemplate.getForEntity(
                userInfoUrl, (Class<Map<String, Object>>) (Class<?>) Map.class);

        Map<String, Object> userInfoData = userInfoResponse.getBody();
        Map<String, Object> picture = (Map<String, Object>) userInfoData.get("picture");
        Map<String, Object> pictureData = picture != null
                ? (Map<String, Object>) picture.get("data")
                : null;

        String nomComplet = (String) userInfoData.get("name");
        // nomComplet peut etre absent : le decouper sans verifier levait une
        // NullPointerException, c'est-a-dire un 500, la ou un profil incomplet
        // est un cas normal.
        String[] nameParts = nomComplet != null ? nomComplet.split(" ", 2) : new String[0];
        String email = (String) userInfoData.get("email");

        return OAuth2UserInfo.builder()
                .providerId((String) userInfoData.get("id"))
                .email(email)
                .name(nomComplet)
                .firstName(nameParts.length > 0 ? nameParts[0] : null)
                .lastName(nameParts.length > 1 ? nameParts[1] : null)
                .pictureUrl(pictureData != null ? (String) pictureData.get("url") : null)
                // Verifie seulement si une adresse existe : voir la javadoc de
                // la classe, le perimetre public_profile n'en fournit pas.
                .emailVerified(email != null && !email.isEmpty())
                .accessToken(accessToken)
                .refreshToken(null)
                .expiresIn(expiresIn != null ? expiresIn.longValue() : null)
                .build();
    }
}
