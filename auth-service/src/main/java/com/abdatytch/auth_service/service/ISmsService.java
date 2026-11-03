package com.abdatytch.auth_service.service;

import reactor.core.publisher.Mono;

/**
 * Envoi de SMS.
 *
 * <p>Le SDK Twilio est bloquant : l'implémentation isole l'appel réseau sur
 * {@code Schedulers.boundedElastic()} pour ne pas retenir l'EventLoop.
 */
public interface ISmsService {

    Mono<Void> sendSms(String phoneNumber, String message);

    /**
     * L'envoi de SMS est-il actif ?
     *
     * <p>Sert à ne pas annoncer un envoi qui n'a pas eu lieu.
     * {@link #sendSms} se termine par un {@code Mono} <b>vide</b> quand
     * {@code sms.enabled} est faux : un succès, indistinguable d'un envoi
     * réussi. L'appelant répondait donc « Code OTP envoyé par SMS » alors que
     * rien ne partait, et l'utilisateur attendait un message qui n'arriverait
     * jamais.
     *
     * <p>Ne pas en faire une condition de l'envoi : {@code sendSms} reste le
     * seul à décider s'il envoie. Cette méthode ne sert qu'à <b>formuler</b> la
     * réponse.
     */
    boolean envoiActif();
}
