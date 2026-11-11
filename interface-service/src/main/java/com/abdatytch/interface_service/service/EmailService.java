package com.abdatytch.interface_service.service;

import reactor.core.publisher.Mono;

/**
 * Les courriels declenches par ce service.
 *
 * <h2>Ce service n'envoie plus rien lui-meme</h2>
 *
 * Les quatre methodes delegent a notification-service, seul detenteur des
 * comptes SMTP et des textes. {@code sendVerificationEmail} et
 * {@code sendPasswordResetEmail}, qui n'avaient aucun appelant, ont ete
 * retirees avec les defauts qu'elles portaient — un jeton en parametre d'URL,
 * deux liens bruts en valeur par defaut, un texte compose ici.
 *
 * <h2>LES DEUX METHODES GENERIQUES ONT ETE RETIREES</h2>
 *
 * {@code sendAdminAlertEmail(destinataire, sujet, message)} et
 * {@code sendCriticalAlertEmail(...)} prenaient un corps DEJA COMPOSE. Elles
 * ont produit exactement le defaut qu'on pouvait en attendre.
 *
 * <p>Leurs trois appelants composaient du HTML en concatenation de chaines
 * Java. Ce HTML partait vers un modele de transit — {@code exploitation.alerte},
 * dont le sujet et le corps etaient les variables — declare SANS variante
 * {@code corps-html}. Le courriel partait donc en {@code text/plain} avec son
 * balisage dedans, et le destinataire lisait la source. Mesure faite sur un
 * courriel recu le 2026-11-10 a 20:02 UTC.
 *
 * <p>Une signature qui accepte un corps deja compose ne peut pas empecher cela
 * : elle ne sait pas si ce qu'on lui donne est du texte ou du balisage, ni
 * quelle variante le modele d'en face possede. Les trois methodes ci-dessous
 * prennent donc des FAITS, et le modele decide de la forme — une variante
 * texte et une variante HTML pour les deux premieres, texte seul pour le
 * rapport d'attaque.
 *
 * <p>C'est aussi ce qui rend l'echappement possible. Le {@code User-Agent} de
 * l'attaquant entrait tel quel dans le balisage de l'alerte qui le denonce ;
 * passe en variable d'un {@code corps-html}, il est echappe par le rendu.
 *
 * <h2>Aucune methode ne leve, et aucune n'echoue</h2>
 *
 * Le {@code Mono<Void>} se termine normalement, remise ou pas : une inscription
 * ou une alerte de securite ne doit pas echouer parce qu'un courriel n'est pas
 * parti. La non-remise est JOURNALISEE, ce qui n'etait pas le cas avant — elle
 * se perdait dans des {@code .subscribe()} sans gestionnaire.
 */
public interface EmailService {

    /** Le code de verification d'une adresse. */
    Mono<Void> sendEmailOtp(String email, String otpCode, String firstName);

    /** Le code de reinitialisation de mot de passe. */
    Mono<Void> sendPasswordResetOtp(String email, String otpCode, String firstName);

    /**
     * Le secret de recuperation regenere, chiffre avec la cle publique RSA de
     * l'administrateur.
     *
     * @param destinataire   l'adresse de l'administrateur
     * @param prenom         son prenom, pour l'en-tete du message
     * @param secretChiffre  le chiffre en base64 — jamais le secret en clair
     * @param heures         le delai avant la prochaine regeneration, annonce
     *                       dans le message et lu chez l'appelant pour que le
     *                       texte ne puisse pas contredire la planification
     */
    Mono<Void> envoyerLeSecretDeRecuperation(String destinataire, String prenom,
                                             String secretChiffre, long heures);

    /**
     * L'alerte de tentative de connexion administrateur, avec ses deux liens
     * d'action.
     *
     * <p>Urgence NORMALE : elle part par le compte d'expedition courant.
     *
     * @param destinataire       l'adresse d'exploitation
     * @param faits              ce qu'on sait de la tentative
     * @param urlDeConfirmation  le lien « c'est moi »
     * @param urlDeBlocage       le lien « ce n'est pas moi »
     * @param minutes            la validite des deux liens, lue chez l'appelant
     */
    Mono<Void> alerterSurUneConnexionAdmin(String destinataire, FaitsDeTentativeAdmin faits,
                                           String urlDeConfirmation, String urlDeBlocage,
                                           long minutes);

    /**
     * Le rapport d'attaque, quand la confirmation a ete franchie et que les
     * tentatives continuent.
     *
     * <p>Urgence CRITIQUE : elle emprunte le compte d'expedition dedie, et
     * {@code CanalCourriel} la fait repartir par le compte ordinaire si
     * celui-la echoue. C'est ce qui separe une alerte qui arrive d'une alerte
     * perdue.
     *
     * @param destinataire       l'adresse d'alerte critique
     * @param faits              ce qu'on sait de la tentative
     * @param urlDeMaintenance   le lien qui retablit l'acces, si cet acces est
     *                           legitime
     * @param heures             la validite de ce lien, lue chez l'appelant
     */
    Mono<Void> alerterSurUneAttaqueAdmin(String destinataire, FaitsDeTentativeAdmin faits,
                                         String urlDeMaintenance, long heures);
}
