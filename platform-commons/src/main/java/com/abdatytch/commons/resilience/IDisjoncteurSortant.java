package com.abdatytch.commons.resilience;

import reactor.core.publisher.Mono;

/**
 * Disjoncteur pose <b>explicitement</b> sur un appel sortant.
 *
 * <h2>Pourquoi cette interface vit dans platform-commons</h2>
 *
 * Elle vivait dans auth-service, ou cinq appelants s'en servaient : les deux
 * fournisseurs OAuth2 Apple, les cles publiques Apple, le client
 * interface-service et le fournisseur SMS. Le jour ou le sous-systeme SMS
 * rejoint notification-service, deux modules en ont besoin — et la recopier
 * serait exactement ce que ce depot paie en boucle : deux implementations d'une
 * meme regle, qui divergent en silence.
 *
 * <h2>Pourquoi explicite et non par annotation</h2>
 *
 * Les annotations {@code @CircuitBreaker} de ce depot ne font rien, pour trois
 * raisons qui se cumulent — aspects non appliques faute d'{@code aspectjweaver},
 * {@code recordExceptions} ne listant que des exceptions de
 * {@code RestTemplate} sur une pile WebClient, et noms d'instances ne
 * correspondant pas a la configuration. Voir
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
 * reessaie un appel de temps en temps pour savoir si la dependance est revenue.
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
 *
 * <p>Cette regle etant propre a chaque module — elle parle de SES types
 * d'exceptions — elle est <b>injectee</b> dans {@link DisjoncteurSortant}
 * plutot qu'ecrite dedans. Ce module partage le mecanisme, pas le vocabulaire
 * d'erreurs des autres.
 */
public interface IDisjoncteurSortant {

    /**
     * Protege un appel sortant.
     *
     * @param nom   instance de disjoncteur, une par dependance. Deux
     *              dependances qui partagent un nom tombent ensemble : la panne
     *              de l'une ferme le chemin de l'autre.
     * @param appel l'appel, deja borne par un delai maximal — sans quoi le
     *              disjoncteur n'aurait aucun echec a compter
     * @return le resultat, ou l'erreur que le module appelant a choisi de lever
     *         quand le disjoncteur est ouvert
     */
    <T> Mono<T> proteger(String nom, Mono<T> appel);

    /**
     * Declare une instance au demarrage.
     *
     * <p>Sans cela, l'instance n'existe qu'au premier appel : elle n'apparait
     * donc ni dans {@code /actuator/circuitbreakers} ni dans Prometheus avant
     * le premier trafic. Un disjoncteur invisible tant qu'il n'a pas servi est
     * un disjoncteur qu'on ne sait pas surveiller.
     */
    void declarer(String nom);
}
