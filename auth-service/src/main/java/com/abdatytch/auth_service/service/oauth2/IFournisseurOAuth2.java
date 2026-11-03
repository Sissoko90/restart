package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.dto.response.OAuth2UserInfo;
import com.abdatytch.auth_service.enums.OAuthProvider;
import reactor.core.publisher.Mono;

/**
 * Fournisseur d'identite OAuth2 : la seule chose que le reste du service
 * connaisse d'un Google, d'un Facebook ou d'un Apple.
 *
 * <h2>Pourquoi cette interface, alors que deux fournisseurs existaient deja</h2>
 *
 * {@code OAuth2ServiceImpl} branche sur le fournisseur par {@code if/else} en
 * <b>sept</b> endroits — construction de l'URL d'autorisation, echange du code,
 * lecture du profil, choix de l'URI de rappel, type d'evenement journalise.
 * Ajouter Apple comme huitieme branche aurait double la dette, et Apple est
 * precisement celui qui ressemble le moins aux deux autres :
 *
 * <ul>
 *   <li>son <b>secret client n'est pas une chaine</b> mais un JWT signe en
 *       ES256 qu'il faut produire et renouveler ;</li>
 *   <li>il n'a <b>aucun endpoint de profil</b> : l'identite vient du
 *       {@code id_token}, qu'il faut verifier contre son JWKS ;</li>
 *   <li>il ne transmet le nom et l'adresse qu'a la <b>premiere</b>
 *       autorisation.</li>
 * </ul>
 *
 * <p>C'est la meme forme que {@link com.abdatytch.auth_service.service.sms.ISmsProvider},
 * qui fait cohabiter AfrikSMS et Twilio sans un seul {@code if} dans le service
 * appelant.
 *
 * <h2>Un fournisseur non configure reste un bean</h2>
 *
 * {@link #estConfigure()} dit si ses identifiants sont presents, et le service
 * appelant refuse un parcours vers un fournisseur qui ne l'est pas — avec une
 * erreur <b>nommee</b>, au moment de l'appel.
 *
 * <p>Le demarrage, lui, <b>n'echoue pas</b> : c'est volontaire et c'est la
 * difference avec {@code app.frontend-url}, dont l'absence est une erreur de
 * deploiement. Un fournisseur social absent est un etat normal — on n'active
 * pas Apple le premier jour — et faire tomber tout le service parce qu'une
 * variable Apple est vide empecherait aussi les connexions par telephone, par
 * courriel et par mot de passe. La panne serait hors de proportion avec la
 * cause.
 */
public interface IFournisseurOAuth2 {

    /**
     * Fournisseur assure par cette implementation.
     *
     * <p>Une valeur de l'enumeration et non une chaine : la correspondance est
     * alors verifiee a la compilation, et le fournisseur est le meme objet que
     * celui ecrit dans {@code oauth_accounts.provider}.
     */
    OAuthProvider fournisseur();

    /**
     * Les identifiants necessaires sont-ils tous presents ?
     *
     * <p>Interroge a l'appel, pas seulement au demarrage : une configuration
     * peut arriver par rechargement du config-server sans redemarrage.
     */
    boolean estConfigure();

    /**
     * URL vers laquelle rediriger la personne pour qu'elle s'authentifie.
     *
     * @param etat valeur anti-falsification de requete, a renvoyer telle quelle
     *        par le fournisseur
     */
    String urlDAutorisation(String etat);

    /**
     * URI de rappel declaree chez ce fournisseur.
     *
     * <p>Le fournisseur la detient parce qu'il l'emploie deja pour construire
     * l'URL d'autorisation : elle doit etre <b>identique</b> dans l'URL
     * d'autorisation et dans l'echange de code, sans quoi le fournisseur refuse
     * l'echange par un {@code redirect_uri_mismatch}. Deux lectures separees de
     * la meme propriete rendaient cet ecart possible, et le message d'erreur ne
     * designait pas la cause.
     */
    String uriDeRappel();

    /**
     * Echange le code d'autorisation contre l'identite de la personne.
     *
     * <p>Rend une erreur plutot qu'un {@code Mono} vide quand l'echange echoue :
     * un {@code Mono} vide ferait se terminer la chaine d'authentification sans
     * jeton ni refus, et le client attendrait son propre delai d'expiration.
     * Ce depot a corrige cette confusion une dizaine de fois.
     *
     * @param code code d'autorisation a usage unique, remis par le fournisseur
     */
    Mono<OAuth2UserInfo> echangerLeCode(String code);

    /**
     * Previent le fournisseur que nous n'utiliserons plus ce jeton.
     *
     * <h2>Pourquoi cette methode existe, et pourquoi elle n'est pas
     * {@code default}</h2>
     *
     * Apple l'<b>exige</b> : une application distribuee sur l'App Store qui
     * propose Sign in with Apple et la suppression de compte doit appeler son
     * point de revocation. Ne pas le faire est un motif de refus en revue.
     *
     * <p>Elle n'est pas {@code default} alors qu'elle serait vide pour deux
     * fournisseurs sur trois, et c'est deliberе : ce depot s'est fait prendre
     * par une methode {@code default} de depot que Mockito simulait, rendant sa
     * logique inerte dans 33 tests sans qu'aucun echoue. Une implementation
     * explicite par fournisseur, meme vide, ne peut pas disparaitre
     * silencieusement.
     *
     * <p>Rend un {@code Mono} <b>complete</b> et jamais une erreur quand il n'y
     * a rien a faire. Un echec de revocation ne doit pas faire echouer
     * l'effacement du compte : la personne a demande que ses donnees
     * disparaissent, et une indisponibilite d'Apple ne doit pas l'en empecher.
     *
     * @param jeton jeton de rafraichissement remis par le fournisseur, ou le
     *        jeton d'acces a defaut. Peut etre nul : un compte lie avant que la
     *        colonne existe n'en a pas
     */
    Mono<Void> revoquerLAcces(String jeton);
}
