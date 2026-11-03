package com.abdatytch.security_service.service;

import reactor.core.publisher.Mono;

/**
 * Rend la decision applicable : le seul chemin entre la base et Redis.
 *
 * <h2>Pourquoi cette interface existe</h2>
 *
 * Le gateway et auth-service doivent repondre a « cette adresse est-elle
 * bloquee », « ce pays est-il refuse », « quelle est la duree de session » a
 * <b>chaque</b> requete. Les faire appeler ce service mettrait un saut reseau
 * sur le chemin critique, et une indisponibilite de ce service arreterait la
 * plateforme — un service de politique ne doit pas devenir un point de panne
 * unique pour le trafic.
 *
 * <p>La politique est donc <b>publiee</b> dans Redis, et les applicateurs
 * lisent le cache. C'est le motif deja en place pour
 * {@code TokenRevocationChecker}.
 *
 * <h2>La regle qui tient tout l'edifice</h2>
 *
 * <b>Rien n'ecrit dans ces cles sans passer par la base.</b> La base porte la
 * decision, son motif et son auteur ; Redis n'en porte que la reponse
 * immediate. Ecrire directement dans Redis recreerait un second magasin sans
 * trace — et ce depot sait ou cela mene : le verrouillage de compte vivait dans
 * Redis d'un cote et en base de l'autre, les deux ne se rencontraient jamais, et
 * un administrateur verrouillait un compte qui continuait de se connecter.
 *
 * <h2>Pourquoi une republication complete, et non des ecritures ciblees</h2>
 *
 * Chaque changement republie l'etat entier de la famille concernee. C'est plus
 * de travail qu'une ecriture ciblee, et c'est deliberе : une ecriture ciblee qui
 * echoue laisse Redis et la base en desaccord <b>definitivement</b>, sans que
 * rien le signale. Une republication complete, rejouee periodiquement, ramene
 * toujours le cache sur la base.
 */
public interface IPublicationDeLaPolitique {

    /**
     * Republie la politique typee.
     *
     * @return le nombre de parametres publies — la politique complete, defauts
     *         compris, et non les seules surcharges : un applicateur ne doit pas
     *         avoir a connaitre les defauts pour fonctionner, sinon ils vivent a
     *         deux endroits
     */
    Mono<Integer> publierLaPolitique();

    /**
     * Republie les adresses bloquees.
     *
     * <p>Les regles expirees ne sont pas publiees, et les cles correspondantes
     * sont retirees. Sans ce retrait, une regle expiree continuerait de bloquer
     * : l'echeance ne vaut que si quelqu'un l'applique.
     */
    Mono<Integer> publierLesReglesDIp();

    /** Republie l'ensemble des pays refuses. */
    Mono<Integer> publierLesPaysBloques();

    /**
     * Republie les fenetres de maintenance actives.
     *
     * <p>Les fenetres a venir ne sont pas publiees : annoncer une maintenance ne
     * doit pas la declencher.
     */
    Mono<Integer> publierLaMaintenance();

    /**
     * Republie tout.
     *
     * <p>Appelee au demarrage et periodiquement. Le demarrage compte : une
     * instance qui demarre apres une purge de Redis trouverait un cache vide et
     * laisserait tout passer, en silence.
     */
    Mono<Void> publierTout();
}
