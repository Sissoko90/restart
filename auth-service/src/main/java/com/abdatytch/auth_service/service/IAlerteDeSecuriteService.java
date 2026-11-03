package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.ContexteDeSecurite;
import com.abdatytch.auth_service.entities.User;
import reactor.core.publisher.Mono;

/**
 * Prévient la personne de ce qui vient d'arriver à son compte.
 *
 * <h2>Ce que ce service fait, et ce qu'il ne fait pas</h2>
 *
 * Il ne bloque rien, n'autorise rien, ne refuse rien. Il rend <b>visible</b> ce
 * qui, sans lui, serait passé inaperçu — et c'est précisément la différence
 * entre un détournement de compte réussi et un détournement de compte repéré.
 *
 * <p>Un attaquant qui obtient un mot de passe change l'adresse de courriel, puis
 * le mot de passe. Chaque étape est légitime vue du système : la bonne session,
 * les bons justificatifs. La seule chose qui trahit l'opération est que le
 * propriétaire en soit informé <b>pendant</b>, pas le jour où il n'arrive plus à
 * se connecter.
 *
 * <h2>La règle qui gouverne tout ce service : l'ANCIENNE coordonnée</h2>
 *
 * Quand une adresse change, c'est l'<b>ancienne</b> qui est prévenue. Prévenir
 * la nouvelle ne prévient que l'attaquant : il vient justement de la choisir.
 * C'est une inversion facile à écrire sans y penser — l'entité en mémoire porte
 * déjà la nouvelle valeur au moment où on voudrait l'envoyer — et c'est la
 * raison pour laquelle l'ancienne valeur est un <b>paramètre explicite</b> de
 * {@link #adresseModifiee}, et non quelque chose que ce service irait relire.
 *
 * <h2>Une alerte ne fait JAMAIS échouer l'opération</h2>
 *
 * Le changement de mot de passe est déjà enregistré quand l'alerte part. Une
 * panne de SMTP ne doit pas transformer une opération réussie en erreur : la
 * personne croirait son mot de passe inchangé alors qu'il a changé, et
 * réessaierait avec l'ancien.
 *
 * <p>Les erreurs sont donc avalées — mais <b>comptées</b>. Un envoi avalé et
 * seulement journalisé est une protection qui s'éteint sans bruit : c'est le
 * défaut que ce dépôt passe son temps à retirer. Le compteur
 * {@code auth_security_alerts_total} porte une étiquette de résultat, et une
 * alerte qui ne part plus se voit sur la courbe.
 *
 * <h2>Jamais vers une coordonnée non vérifiée</h2>
 *
 * Une alerte dit « voici ce qui se passe sur ce compte » : l'envoyer à une
 * adresse que personne n'a prouvée, c'est le dire à quelqu'un qui n'est
 * peut-être pas le titulaire. Et une adresse non vérifiée peut avoir été saisie
 * par l'attaquant lui-même.
 */
public interface IAlerteDeSecuriteService {

    /**
     * Le mot de passe a changé.
     *
     * <p>Les coordonnées <b>actuelles</b> sont prévenues, et c'est correct ici :
     * un changement de mot de passe ne touche pas aux coordonnées, donc
     * l'actuelle est l'ancienne.
     *
     * <p>Envoyée <b>sans condition</b>, et non derrière une préférence. Un
     * changement de mot de passe est rare et toujours notable : le rendre
     * optionnel le désactiverait pour tous ceux qui n'ouvrent jamais leurs
     * réglages, c'est-à-dire la plupart. Voir {@link #nouvelleConnexion} pour le
     * compromis inverse, et pourquoi il diffère.
     */
    Mono<Void> motDePasseModifie(User user, ContexteDeSecurite contexte);

    /**
     * L'adresse de courriel a changé : l'<b>ancienne</b> est prévenue.
     *
     * @param ancienneAdresse l'adresse telle qu'elle était <b>avant</b> la
     *        modification. Passée explicitement parce que {@code user} porte
     *        déjà la nouvelle : la relire depuis l'entité enverrait l'alerte à
     *        l'attaquant.
     * @param ancienneEtaitVerifiee si l'ancienne adresse avait été confirmée.
     *        Sinon rien n'est envoyé : une adresse non prouvée n'appartient pas
     *        forcément au titulaire, et peut avoir été posée par l'attaquant au
     *        tour précédent.
     */
    Mono<Void> adresseModifiee(User user, String ancienneAdresse,
                               boolean ancienneEtaitVerifiee,
                               ContexteDeSecurite contexte);

    /**
     * Le numéro de téléphone a changé : l'<b>ANCIEN</b> est prévenu.
     *
     * <h2>Pourquoi le SMS passe devant ici, et le courriel dans les autres cas</h2>
     *
     * L'ancien numéro est la coordonnée qui disparaît : c'est son titulaire qui
     * vient d'être dépossédé, et c'est donc lui qu'il faut atteindre. Envoyer
     * d'abord au courriel — qui, lui, n'a pas changé — laisserait sans
     * information la seule personne pour qui elle compte.
     *
     * <h2>Pourquoi ce changement est le plus sensible des trois</h2>
     *
     * Le numéro n'est pas seulement un identifiant de connexion : c'est le canal
     * de {@code /auth/forgot-password}. Qui le remplace prend le contrôle de la
     * réinitialisation du mot de passe, donc du compte entier — sans jamais
     * connaître le mot de passe actuel.
     *
     * @param ancienNumero le numéro tel qu'il était <b>avant</b>. Passé
     *        explicitement parce que {@code user} porte déjà le nouveau.
     * @param ancienEtaitVerifie si l'ancien numéro avait été confirmé. Sinon le
     *        SMS est sauté et le courriel prend le relais : un numéro jamais
     *        prouvé peut n'avoir jamais existé.
     */
    Mono<Void> numeroModifie(User user, String ancienNumero,
                             boolean ancienEtaitVerifie,
                             ContexteDeSecurite contexte);

    /**
     * Une connexion vient d'aboutir.
     *
     * <h2>Pourquoi celle-ci est une préférence, et les autres non</h2>
     *
     * Une connexion est un événement <b>fréquent</b> et ordinaire. Alerter à
     * chaque fois pour tout le monde enverrait un courriel par connexion, et le
     * résultat connu de ce volume est que les gens cessent de les lire — puis
     * les désactivent. Une alerte qu'on ignore protège moins qu'une alerte
     * qu'on a choisie.
     *
     * <p>Le critère est donc : rare et toujours notable, alors sans condition
     * ({@link #motDePasseModifie}, {@link #adresseModifiee}) ; fréquent et
     * ordinaire, alors sur demande explicite.
     *
     * <p>Sans effet si {@code alerteNouvelleConnexion} est à faux, ou si
     * l'adresse n'est pas vérifiée — il n'y a alors nulle part où envoyer.
     */
    Mono<Void> nouvelleConnexion(User user, ContexteDeSecurite contexte);

    /**
     * Active ou désactive l'alerte de nouvelle connexion.
     *
     * @throws com.abdatytch.auth_service.exception.AuthException
     *         {@code VAL_6008} à l'activation si l'adresse n'est pas vérifiée —
     *         accepter silencieusement produirait un réglage affiché comme actif
     *         qui n'enverrait jamais rien, c'est-à-dire la pire des deux
     *         réponses : la personne se croirait surveillée
     */
    Mono<Boolean> changerLalerteDeConnexion(String userId, boolean actif);
}
