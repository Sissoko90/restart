package com.abdatytch.notification_service.service;

import reactor.core.publisher.Mono;

/**
 * Envoi de SMS.
 *
 * <p>Le SDK Twilio est bloquant : l'implémentation isole l'appel réseau sur
 * {@code Schedulers.boundedElastic()} pour ne pas retenir l'EventLoop.
 */
public interface ISmsService {

    /**
     * Remet un SMS au fournisseur.
     *
     * <h2>Pourquoi {@code Mono<Boolean>} et non {@code Mono<Void>}</h2>
     *
     * Un {@code Mono<Void>} ne peut PAS dire si l'envoi a eu lieu : une
     * completion reussie est vide, et une completion « rien n'est parti » l'est
     * aussi. Les deux sont alors indistinguables — c'est ce que notait deja la
     * javadoc precedente — et {@code CanalSms} ne pouvait donc pas rendre un
     * etat exact : il aurait annonce REMIS pour un message qu'un plafond venait
     * de refuser.
     *
     * <p>Quatre raisons de ne rien envoyer, toutes rendues {@code false} :
     * {@code sms.enabled} faux, numero hors de la liste autorisee, plafond
     * journalier atteint, fournisseur incompletement configure.
     *
     * @param phoneNumber le numero destinataire
     * @param message     le texte deja rendu
     * @return {@code true} si le fournisseur a accepte le message,
     *         {@code false} si rien n'est parti. Ne se termine <b>jamais</b>
     *         vide : l'appelant obtient toujours une reponse
     */
    Mono<Boolean> sendSms(String phoneNumber, String message);

    /**
     * L'envoi de SMS est-il actif ?
     *
     * <p>Sert à formuler une réponse sans attendre l'envoi — par exemple
     * « Code OTP envoyé par SMS » plutôt que « code généré sans envoi » — là où
     * l'appelant répond avant que {@link #sendSms} n'aboutisse.
     *
     * <p>Ne pas en faire une condition de l'envoi : {@code sendSms} reste le
     * seul à décider s'il envoie, et il a trois autres raisons de refuser que
     * cette méthode ne connaît pas.
     */
    boolean envoiActif();
}
