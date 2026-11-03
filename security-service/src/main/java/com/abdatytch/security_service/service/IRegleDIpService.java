package com.abdatytch.security_service.service;

import com.abdatytch.security_service.dto.request.PoseDeRegleIpRequest;
import com.abdatytch.security_service.dto.response.RegleDIpResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Regles de blocage d'adresses : consulter, poser, lever.
 *
 * <h2>Le defaut que ce service corrige</h2>
 *
 * La table {@code regle_ip} existait, son entite existait, son depot existait,
 * et {@code PublicationDeLaPolitiqueImpl} savait deja publier les regles actives
 * vers Redis. Il manquait le seul morceau par lequel une regle pouvait naitre :
 * <b>aucun service, aucun controleur</b>. Le depot n'etait consomme que par la
 * publication, qui republiait donc indefiniment une table vide.
 *
 * <p>Les constantes etaient ecrites elles aussi — {@code REGLES_IP_ENDPOINT},
 * {@code LOG_IP_BLOQUEE}, {@code PERMANENT_INTERDIT_POUR_DETECTION} — et
 * n'etaient referencees par aucun code. C'est la forme la plus avancee du motif
 * de ce depot : non pas une protection jamais atteinte, mais une protection dont
 * tout a ete construit <b>sauf</b> la porte d'entree.
 *
 * <h2>Ce que ce service ne fait pas</h2>
 *
 * Il ne refuse aucune requete. Le blocage est applique par
 * {@code IpBlacklistWebFilter}, dans le gateway, qui interroge Redis. La base
 * porte la decision, son motif et son auteur ; Redis ne porte que la reponse a
 * « cette adresse est-elle bloquee ».
 *
 * <p>Une seule regle tient cette separation : <b>rien n'ecrit dans Redis sans
 * passer par la base</b>. Voir {@code ClesRedisSecurite}.
 *
 * <h2>Pourquoi la republication est declenchee par le controleur</h2>
 *
 * Et non par ce service. Sans elle, une regle posee n'atteindrait Redis qu'a la
 * publication planifiee suivante — cinq minutes plus tard. Un administrateur qui
 * bloque une adresse en cours d'attaque verrait un succes et l'attaque
 * continuerait pendant cinq minutes.
 *
 * <p>Le declenchement est au controleur parce que c'est lui qui connait la
 * frontiere de l'appel : il publie <b>apres</b> que l'ecriture a abouti, et
 * l'echec de la publication n'annule pas la decision. Voir
 * {@code PaysBloqueController}, qui applique le meme decoupage.
 */
public interface IRegleDIpService {

    /**
     * Regles non levees, historique exclu, les actives d'abord.
     *
     * <p>Les regles <b>expirees mais non levees</b> y figurent, marquees
     * {@code active: false} : une echeance passee n'est pas une levee, et savoir
     * qu'une adresse a ete bloquee la semaine derniere est ce qu'un
     * administrateur vient chercher ici.
     */
    Flux<RegleDIpResponse> regles();

    /**
     * Pose une regle.
     *
     * <p>L'adresse est normalisee avant ecriture : {@code 041.223.010.005} et
     * {@code 41.223.10.5} designent la meme machine, et en faire deux lignes
     * produirait deux regles dont une seule serait consultee.
     *
     * @throws com.abdatytch.security_service.exception.SecurityPolicyException si
     *         l'adresse n'est pas une adresse litterale, si c'est un prefixe
     *         CIDR — que l'applicateur ne sait pas evaluer —, si l'echeance est
     *         deja passee, ou si une regle active couvre deja cette adresse
     */
    Mono<RegleDIpResponse> poser(PoseDeRegleIpRequest requete, String auteur);

    /**
     * Leve une regle avant son terme.
     *
     * <p>La ligne n'est pas supprimee : savoir qu'une adresse a ete bloquee puis
     * debloquee, par qui et quand, est exactement ce qu'un journal d'audit doit
     * montrer.
     *
     * @throws com.abdatytch.security_service.exception.RessourceIntrouvableException
     *         si l'identifiant est inconnu — <b>404</b>, parce qu'un 403 sur un
     *         identifiant existant permettrait d'enumerer les regles en lisant
     *         le seul code de statut
     */
    Mono<RegleDIpResponse> lever(String id, String auteur);
}
