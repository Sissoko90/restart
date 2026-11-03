package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.enums.VerificationCodeType;
import reactor.core.publisher.Mono;

/**
 * Envoi d'emails transactionnels.
 *
 * <p>L'envoi SMTP lui-même est bloquant (JavaMailSender) : l'implémentation
 * l'isole sur {@code Schedulers.boundedElastic()} pour ne pas retenir l'EventLoop.
 */
public interface IEmailService {

    Mono<Void> sendWelcomeEmail(String toEmail, String firstName);

    Mono<Void> sendVerificationEmail(String toEmail, String code, VerificationCodeType type);

    /**
     * Envoie un avis de sécurité, sujet et corps déjà rédigés.
     *
     * <h2>Pourquoi celui-ci ne passe PAS par les gabarits en base</h2>
     *
     * Les autres envois lisent leur gabarit dans {@code message_templates}, ce
     * qui permet de retoucher un texte sans redéployer. Pour un avis de
     * sécurité, cette souplesse devient une dépendance de trop : une ligne
     * absente, renommée ou mal formatée empêcherait la personne d'apprendre que
     * son mot de passe vient de changer.
     *
     * <p>Le texte est donc dans le code, assemblé par
     * {@link IAlerteDeSecuriteService}. Un avis de sécurité doit partir même
     * quand le reste est cassé.
     *
     * <p>Ne contient jamais de code, de jeton ni de lien d'action : un courriel
     * d'alerte est lu par qui a accès à la boîte, et c'est précisément ce dont
     * on n'est pas sûr au moment où on l'envoie.
     */
    Mono<Void> envoyerAvisDeSecurite(String toEmail, String sujet, String corps);
}
