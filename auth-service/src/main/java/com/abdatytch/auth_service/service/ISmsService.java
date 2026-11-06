package com.abdatytch.auth_service.service;

import reactor.core.publisher.Mono;

/**
 * L'envoi de SMS, vu par ce service.
 *
 * <h2>Ce qui a change derriere cette interface, et ce qui n'a pas change</h2>
 *
 * Le sous-systeme SMS — les deux fournisseurs, le registre des envois, les
 * accuses de reception, la surveillance du solde — vivait dans ce module et a
 * rejoint notification-service. Cette interface reste ici : c'est le
 * <b>besoin</b> d'auth-service, pas l'implementation.
 *
 * <p>La signature est volontairement identique a celle d'avant le deplacement.
 * Ses quatre appelants n'ont pas ete touches, et la semantique est la meme :
 * termine = remis au fournisseur ; termine vide = rien n'est parti.
 *
 * <h2>Pourquoi ce chemin reste synchrone</h2>
 *
 * Les notifications de la plateforme passent par un evenement Kafka. Un code de
 * verification non : l'utilisateur attend devant son ecran, et le message ne
 * doit pas etre persiste — ce serait ecrire un code a usage unique encore
 * valide dans une ligne de base de donnees. Voir {@code InternalSmsController}
 * pour le raisonnement complet.
 */
public interface ISmsService {

    /**
     * Remet un SMS au fournisseur.
     *
     * @param phoneNumber le numero destinataire
     * @param message     le texte <b>fini</b>. Ce module compose ses propres
     *                    messages depuis son magasin de modeles, modifiable a
     *                    chaud. Tant que les deux moteurs de modeles de la
     *                    plateforme ne sont pas reunis, le detenteur du texte
     *                    reste celui qui le possede deja
     * @return termine quand le fournisseur a accepte. Termine <b>vide</b> quand
     *         rien n'est parti — envoi desactive, ou service de notification
     *         injoignable. Un appelant qui annonce « code envoye » sur une
     *         terminaison vide ferait attendre un message qui ne viendra pas
     */
    Mono<Void> sendSms(String phoneNumber, String message);

    /**
     * L'envoi de SMS est-il actif sur la plateforme.
     *
     * <p>Ne sert <b>qu'a formuler la reponse</b> : « code envoye par SMS » ou
     * « code genere sans envoi ». C'est la seule raison de son existence —
     * annoncer un SMS que la configuration empeche de partir faisait attendre un
     * message qui n'arrivait jamais.
     *
     * <p>Ne commande aucun envoi : l'autorite qui applique la regle est
     * notification-service, seul detenteur des fournisseurs.
     */
    boolean envoiActif();
}
