package com.abdatytch.auth_service.service;

import reactor.core.publisher.Mono;

/**
 * Disjoncteur pose <b>explicitement</b> sur un appel sortant.
 *
 * <h2>Pourquoi explicite et non par annotation</h2>
 *
 * Les annotations {@code @CircuitBreaker} de ce module ne faisaient rien, pour
 * trois raisons qui se cumulaient — aspects non appliques faute
 * d'{@code aspectjweaver}, {@code recordExceptions} ne listant que des
 * exceptions de {@code RestTemplate} sur une pile WebClient, et noms
 * d'instances ne correspondant pas a la configuration. Voir
 * {@code AnnotationsResilienceInertesTest} pour la mesure.
 *
 * <p>Un operateur Reactor pose au point d'appel n'a aucun de ces defauts : il
 * est visible dans le code, il ne depend d'aucun aspect, et il se teste sans
 * contexte Spring. On ne peut pas l'ecrire et croire a tort qu'il tourne.
 *
 * <h2>Ce que le disjoncteur apporte, en plus du delai maximal</h2>
 *
 * Un delai maximal borne <b>chaque</b> appel. Avec une dependance morte, chaque
 * requete attend donc le delai entier avant d'echouer : cinq secondes par
 * connexion, pour un resultat connu d'avance. Le disjoncteur retient cette
 * information — apres un nombre d'echecs, il refuse <b>immediatement</b>, et
 * reessaie un appel de temps en temps pour savoir si la dependance est
 * revenue.
 *
 * <p>Les deux sont donc complementaires, et aucun ne remplace l'autre : sans
 * delai, le disjoncteur ne verrait jamais d'echec a compter ; sans disjoncteur,
 * le delai se paie a chaque appel.
 *
 * <h2>Ce qui compte comme un echec</h2>
 *
 * <b>Uniquement</b> une indisponibilite de la dependance, decidee par
 * l'appelant qui a deja traduit ses erreurs. Un refus metier — identifiants
 * invalides, numero mal forme, credit epuise — ne doit <b>jamais</b> ouvrir un
 * disjoncteur : il signale que la dependance fonctionne et qu'elle a repondu.
 *
 * <p>C'est precisement ce que la configuration d'origine se trompait a faire,
 * dans l'autre sens : sa liste d'exceptions ne contenant aucun type que
 * WebClient leve, <b>tout</b> echec de transport etait compte comme un succes.
 */
public interface IDisjoncteurSortant {

    /**
     * Protege un appel sortant.
     *
     * @param nom instance de disjoncteur, une par dependance. Deux dependances
     *        qui partagent un nom tombent ensemble : la panne de l'une ferme le
     *        chemin de l'autre.
     * @param appel l'appel, deja borne par un delai maximal — sans quoi le
     *        disjoncteur n'aurait aucun echec a compter
     * @return le resultat, ou une erreur {@code SYS_5005} (503) si la
     *         dependance est indisponible ou si le disjoncteur est ouvert
     */
    <T> Mono<T> proteger(String nom, Mono<T> appel);

    /**
     * Cree l'instance au DEMARRAGE, sans attendre le premier appel.
     *
     * <h3>Pourquoi ce n'est pas un detail</h3>
     *
     * Resilience4j cree une instance a la premiere sollicitation. Sans
     * declaration explicite, un disjoncteur n'existe donc pas avant le premier
     * appel sortant : il n'apparait ni sur {@code /actuator/circuitbreakers},
     * ni dans Prometheus, ni sur le tableau de bord — et l'alerte
     * {@code DisjoncteurOuvert} ne peut pas se declencher.
     *
     * <p>C'est precisement cette propriete qui a rendu le diagnostic des
     * annotations inertes difficile : les disjoncteurs nommes par annotation
     * etaient absents a l'execution, et il fallait savoir que l'absence
     * pouvait signifier « jamais sollicite » <b>ou</b> « aspect jamais
     * applique ». Declarer au demarrage leve l'ambiguite : present signifie
     * cable, absent signifie non cable.
     *
     * <p>A appeler dans le constructeur du client qui possede la dependance.
     */
    void declarer(String nom);
}
