package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.response.AppareilDeConfianceResponse;
import com.abdatytch.auth_service.dto.response.ConfianceAccordeeResponse;
import com.abdatytch.auth_service.entities.User;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Appareils de confiance : accorder, reconnaitre, revoquer.
 *
 * <h2>La regle qui tient tout</h2>
 *
 * La confiance ne s'accorde qu'<b>apres</b> un second facteur reussi. Un
 * appareil de confiance dispense du second facteur : l'accorder sans l'avoir
 * exige une fois donnerait a qui detient le mot de passe un moyen de s'en
 * dispenser definitivement — la protection s'annulerait elle-meme.
 *
 * <p>C'est pourquoi {@link #accorder} prend un {@code User} dont le second
 * facteur vient d'etre verifie, et non un identifiant : l'appelant doit avoir
 * franchi le controle pour tenir cet objet.
 *
 * <h2>Pourquoi la reconnaissance ne leve jamais</h2>
 *
 * {@link #estDeConfiance} rend {@code false} pour un jeton absent, inconnu,
 * expire, revoque, ou appartenant a un autre compte. Jamais une erreur : un
 * jeton invalide signifie seulement « demandez le second facteur », ce qui est
 * le comportement par defaut et non un incident.
 *
 * <p>Rendre une erreur ferait echouer la connexion d'une personne qui presente
 * un jeton perime — alors qu'elle doit simplement saisir son code.
 *
 * <h2>Ou vit cette donnee, et pourquoi pas dans security-service</h2>
 *
 * Elle est lue a chaque connexion. Dans security-service, cela imposerait un
 * appel reseau sur le chemin de connexion, et une panne de ce service
 * empecherait toute connexion. Le cache Redis qui resout ce probleme pour la
 * politique ne s'applique pas : quinze valeurs partagees d'un cote, des
 * millions de lignes par compte de l'autre.
 */
public interface IAppareilDeConfianceService {

    /**
     * Accorde la confiance a l'appareil courant, et rend le jeton <b>une seule
     * fois</b>.
     *
     * <p>Le jeton n'est stocke que hache : il ne pourra plus etre relu. Le
     * client doit le conserver a cet instant, et la reponse le dit.
     *
     * @param user compte dont le second facteur vient d'etre verifie
     * @param nomDemande nom choisi par la personne, ou {@code null} pour un nom
     *        deduit de l'User-Agent
     * @param userAgent pour figer navigateur, systeme et type d'appareil
     * @param ipAddress pour figer les circonstances de la confiance
     */
    Mono<ConfianceAccordeeResponse> accorder(User user, String nomDemande,
                                             String userAgent, String ipAddress);

    /**
     * Cet appareil est-il de confiance pour ce compte ?
     *
     * <p>Met a jour la date de dernier usage quand la reponse est oui : c'est ce
     * qui permet a la personne de distinguer un appareil qu'elle utilise d'un
     * appareil oublie.
     *
     * @param jeton jeton presente par le client, possiblement nul
     * @return {@code false} dans tous les cas douteux, jamais une erreur
     */
    Mono<Boolean> estDeConfiance(String userId, String jeton);

    /** Appareils de confiance du compte, le plus recemment utilise d'abord. */
    Flux<AppareilDeConfianceResponse> duCompte(String userId);

    /**
     * Revoque un appareil.
     *
     * <p>L'appartenance est verifiee, et le refus rend <b>404</b> : un 403
     * confirmerait que l'appareil existe, ce qui se parcourt. Regle appliquee
     * partout dans ce depot.
     */
    Mono<Void> revoquer(String userId, String appareilId);

    /**
     * Revoque tous les appareils du compte.
     *
     * @return le nombre d'appareils revoques
     */
    Mono<Long> revoquerTous(String userId);
}
