package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.response.EtatDesCodesResponse;
import com.abdatytch.auth_service.dto.response.LotDeCodesResponse;
import com.abdatytch.auth_service.entities.User;
import reactor.core.publisher.Mono;

/**
 * Codes de secours : la sortie quand l'appareil du second facteur est perdu.
 *
 * <h2>Le trou que ce service comble</h2>
 *
 * L'enrolement du second facteur est devenu accessible en libre-service. Sa
 * seule sortie restait {@code ISecondFacteurService.retirer}, reserve a un
 * {@code SUPER_ADMIN} — concu quand le second facteur ne concernait qu'une
 * poignee d'administrateurs.
 *
 * <p>Pour un client qui perd son telephone, cette sortie est pire que lente :
 * elle oblige le support a juger de l'identite de quelqu'un qui ne peut plus la
 * prouver. C'est le chemin exact des attaques par ingenierie sociale — « j'ai
 * perdu mon telephone, pouvez-vous desactiver ma double authentification ».
 *
 * <p>Les codes de secours deplacent la preuve <b>avant</b> l'incident : ils sont
 * remis a l'enrolement, quand la personne peut encore prouver qui elle est.
 *
 * <h2>Pourquoi un code consomme n'est pas supprime</h2>
 *
 * La personne doit pouvoir voir <b>lesquels</b> de ses codes ont servi, quand et
 * depuis quelle adresse. Un code consomme qu'elle ne reconnait pas est le signal
 * le plus clair qu'elle puisse recevoir d'une intrusion — et une suppression
 * effacerait ce signal.
 */
public interface ICodeDeSecoursService {

    /**
     * Produit un lot de codes et les rend <b>une seule fois</b>.
     *
     * <p>Invalide le lot precedent, s'il existe. Deux lots valides a la fois
     * doubleraient la surface sans que la personne le sache : elle croirait
     * avoir remplace ses codes alors que les anciens fonctionneraient encore.
     *
     * <p>Le lot precedent est marque <b>invalide</b> et non consomme : la
     * distinction compte, parce qu'un code « consomme » signale un usage et
     * ferait soupçonner une intrusion inexistante.
     *
     * @param user compte dont le second facteur est actif
     * @throws com.abdatytch.auth_service.exception.AuthException
     *         {@code 2FA_9001} si le second facteur n'est pas actif — des codes
     *         de secours ne secourent de rien quand il n'y a rien a contourner
     */
    Mono<LotDeCodesResponse> regenerer(User user);

    /**
     * Consomme un code, s'il correspond a l'un des codes utilisables.
     *
     * <h2>Ce que cette methode ne doit pas reveler</h2>
     *
     * Rend {@code false} pour un code absent, inconnu, deja consomme ou
     * invalide. Jamais d'erreur distincte : dire « ce code a deja servi » plutot
     * que « code invalide » confirmerait qu'il a existe, donc que la liste
     * devinee est la bonne.
     *
     * <h2>L'echec n'est PAS compte ici</h2>
     *
     * Le frein appartient a {@link ISecondFacteurService}, seul endroit ou « la
     * tentative a echoue » se decide — un code peut avoir ete presente comme
     * code TOTP d'abord, et une tentative ne doit compter qu'une fois.
     *
     * <p>Le compter ici a ete essaye, et c'etait un defaut : cette methode
     * avale ses erreurs de lecture et rend {@code false} sans rien compter, de
     * sorte qu'une table illisible faisait disparaitre le frein du second
     * facteur tout entier. La force brute sur le TOTP devenait gratuite, sans
     * qu'aucune erreur ne le signale.
     *
     * @param codePresente code saisi, possiblement nul
     * @param ipAddress adresse d'ou le code est presente, conservee pour que la
     *        personne reconnaisse — ou non — l'usage
     */
    Mono<Boolean> consommer(User user, String codePresente, String ipAddress);

    /**
     * Etat des codes du compte : combien restent, lesquels ont servi.
     *
     * <p>Ne rend <b>jamais</b> les codes ni leurs empreintes. Seuls les quatre
     * premiers caracteres, pour que la personne reconnaisse ses codes sur son
     * papier.
     *
     * <p>Le nombre restant voyage dans la meme reponse que la liste : un second
     * endpoint qui ne rendrait que le compte serait la meme information deduite
     * deux fois, par deux chemins a maintenir d'accord.
     */
    Mono<EtatDesCodesResponse> etat(String userId);

    /**
     * Invalide tout le lot utilisable d'un compte.
     *
     * <h2>Pourquoi un lot est lie au secret qu'il secourt</h2>
     *
     * Appele chaque fois que le secret du second facteur change ou disparait :
     * desactivation, retrait administratif, reinitialisation. Sans cela, les
     * codes imprimes du temps de l'appareil perdu resteraient utilisables apres
     * un re-enrolement — qui exige un code du <b>nouvel</b> appareil et ne les
     * touche donc pas. Un lot oublie dans un tiroir serait une clef valide des
     * annees plus tard.
     *
     * <p>Les codes sont marques <b>invalides</b>, non consommes, et les lignes
     * sont conservees : « effacer n'est pas supprimer la ligne ».
     *
     * <p>Sans effet s'il n'y a aucun lot : l'invariant est plus simple a tenir
     * que ses exceptions.
     *
     * @return le nombre de codes invalides
     */
    Mono<Long> invaliderLeLot(String userId);
}
