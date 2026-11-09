package com.abdatytch.interface_service.service;

import reactor.core.publisher.Mono;

/**
 * Service d'envoi d'emails.
 *
 * <h2>Deux methodes ont ete RETIREES, et elles n'avaient aucun appelant</h2>
 *
 * {@code sendVerificationEmail} et {@code sendPasswordResetEmail} etaient
 * declarees, implementees, et appelees par <b>aucune</b> ligne du depot. Mesure
 * faite sur l'ensemble des sources.
 *
 * <p>Leur retrait emporte trois defauts d'un coup, et c'est la raison de le
 * faire maintenant plutot que de les laisser dormir :
 *
 * <ul>
 *   <li>elles construisaient une URL portant le JETON en parametre de requete
 *       — {@code baseUrl + "?token=" + token}. Un jeton dans une URL se
 *       retrouve dans l'historique du navigateur, dans l'en-tete
 *       {@code Referer} et dans les journaux de tout intermediaire. C'est la
 *       regle que ce depot a deja appliquee ailleurs en remplacant les jetons
 *       d'URL par un code de remise a usage unique ;</li>
 *   <li>elles lisaient deux defauts {@code @Value} portant des liens BRUTS
 *       ({@code http://localhost:3000/...}) ;</li>
 *   <li>elles composaient leur texte en {@code String.format}, dans ce module,
 *       alors que la plateforme reunit ses textes dans notification-service.</li>
 * </ul>
 *
 * <h2>Ce qui reste ici, et pourquoi</h2>
 *
 * Les deux envois d'OTP sont du HTML avec un logo en piece jointe en ligne. Le
 * moteur de modeles de notification-service est en TEXTE seul, par choix
 * explicite : un moteur HTML demande un echappement par defaut, et ce texte est
 * desormais modifiable a chaud depuis une base. Les y migrer en texte brut
 * degraderait un courriel de marque en message nu, sans que personne l'ait
 * decide.
 *
 * <p>Les deux alertes d'exploitation, elles, visent un DESTINATAIRE — un
 * operateur — et non une urgence. {@code DemandeDeNotification} ne sait pas
 * exprimer cela, et une alerte d'exploitation n'a pas sa place dans la boite de
 * reception d'un utilisateur.
 */
public interface EmailService {

    /**
     * Envoie un code OTP par email
     */
    Mono<Void> sendEmailOtp(String email, String otpCode, String firstName);

    /**
     * Envoie un code OTP de réinitialisation de mot de passe par email
     */
    Mono<Void> sendPasswordResetOtp(String email, String otpCode, String firstName);

    /**
     * Envoie une alerte admin par email en cas de tentative incorrecte
     */
    Mono<Void> sendAdminAlertEmail(String adminEmail, String subject, String message);

    /**
     * Envoie une alerte critique par email (dernière couche de sécurité)
     */
    Mono<Void> sendCriticalAlertEmail(String criticalEmail, String subject, String message);
}
