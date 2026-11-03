package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.ProfileCompletionRequest;
import com.abdatytch.auth_service.dto.request.OAuth2CallbackRequest;
import com.abdatytch.auth_service.dto.response.OAuth2AuthResponse;
import reactor.core.publisher.Mono;

/**
 * Interface du service OAuth2
 */
public interface IOAuth2Service {
    
    /**
     * URL d'autorisation du fournisseur, vers laquelle rediriger la personne.
     *
     * <p>Rend un {@code Mono} et non une chaine : la valeur anti-falsification
     * du parcours doit etre retenue avant que l'URL soit remise, et cette
     * ecriture va dans Redis. Une signature synchrone imposerait d'attendre
     * Redis sur une EventLoop.
     */
    Mono<String> getAuthorizationUrl(String provider, String redirectUri);
    
    Mono<OAuth2AuthResponse> handleOAuth2Callback(OAuth2CallbackRequest request, String ipAddress, String userAgent);
    
    Mono<OAuth2AuthResponse> completeProfile(ProfileCompletionRequest request, String ipAddress, String userAgent);

    /**
     * URI de rappel declaree chez le fournisseur.
     *
     * <p>Etait une methode privee de {@code AuthController}, qui relisait pour
     * cela {@code oauth2.google.redirect-uri} et
     * {@code oauth2.facebook.redirect-uri} dans deux {@code @Value} — alors que
     * cette implementation les lit <b>deja</b>. Deux lectures de la meme
     * propriete, donc deux endroits a corriger lors d'un renommage, et aucune
     * erreur si l'un des deux est oublie : l'echange de jeton echouerait chez
     * le fournisseur avec un {@code redirect_uri_mismatch}, loin de la cause.
     *
     * @throws IllegalArgumentException si le fournisseur est inconnu
     */
    String redirectUriDuFournisseur(String provider);

    /**
     * URL de redirection vers le front apres un rappel OAuth2 abouti.
     *
     * <p>La construction etait dans le controleur, avec deux {@code String.format}
     * et une interpolation de {@code frontendUrl}. Le service la rend, et le
     * controleur se contente d'emettre le 302 : le contrat HTTP ne change pas,
     * mais la regle « profil complet ou non, et quels parametres » a un seul
     * detenteur.
     *
     * <h2>Les jetons ne sont plus dans l'URL</h2>
     *
     * Cette methode construisait
     * {@code ?success=true&accessToken=...&refreshToken=...}. Les deux jetons
     * voyageaient donc dans la chaine de requete d'une redirection de
     * navigateur : historique, journaux d'acces de tout proxy et du frontal,
     * en-tete {@code Referer} des requetes de la page chargee. Le jeton de
     * rafraichissement est le plus grave — il suffit a renouveler une session
     * pendant des jours, sans mot de passe ni second facteur.
     *
     * <p>La dette etait signalee ici comme « relevant d'une decision ». Elle est
     * prise : la redirection ne porte plus qu'un <b>code a usage unique</b>, que
     * le frontal echange par POST contre les jetons. Voir
     * {@code CodeDeRemiseOAuth2Redis} et {@link #echanger(String)}.
     *
     * <p>Le profil incomplet suit le meme chemin : son {@code tempToken} est
     * plus limite, mais il permet de completer un profil, donc de prendre la
     * main sur un compte en cours de creation.
     *
     * <h2>Pourquoi le type de retour devient reactif</h2>
     *
     * Le code doit etre retenu dans Redis avant que la redirection soit emise.
     * Rendre une {@code String} obligerait a bloquer pour y parvenir, dans un
     * service qui a pour regle de ne jamais bloquer sur le chemin d'une requete.
     *
     * <h2>Le contrat du frontend change</h2>
     *
     * Il recevait {@code /auth/callback?accessToken=...&refreshToken=...} ou
     * {@code /auth/complete-profile?tempToken=...}. Il recoit desormais
     * {@code /auth/callback?code=...} dans les deux cas, et appelle
     * {@code POST /auth/oauth2/exchange} avec ce code. La reponse porte
     * {@code profileComplete}, qui dit lequel des deux parcours suivre.
     */
    Mono<String> urlDeRedirectionApresRappel(OAuth2AuthResponse reponse);

    /**
     * Echange le code de remise contre les jetons.
     *
     * <p>Appele par le frontal, en POST, juste apres la redirection. Le code est
     * <b>consomme</b> : un second appel echoue, parce qu'un code rejouable
     * laisserait quiconque a vu l'URL — dans un journal, dans un
     * {@code Referer} — obtenir les jetons a son tour, et reintroduirait donc
     * exactement le defaut que ce code corrige.
     *
     * @param code le code recu dans la redirection
     * @throws com.abdatytch.auth_service.exception.AuthException si le code est
     *         absent, inconnu, expire ou deja utilise. Les quatre cas rendent la
     *         <b>meme</b> erreur : distinguer « inconnu » de « deja utilise »
     *         dirait a un attaquant s'il est arrive trop tard, donc si l'URL
     *         qu'il a trouvee valait la peine d'etre cherchee ailleurs.
     */
    Mono<OAuth2AuthResponse> echanger(String code);

    /** URL de redirection vers le front pour un rappel en erreur. */
    String urlDErreur(String erreur, String description);
}
