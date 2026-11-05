package com.abdatytch.commons.notification;

/**
 * Les canaux par lesquels une notification peut etre remise.
 *
 * <h2>Pourquoi cet enum vit dans platform-commons</h2>
 *
 * Parce que l'emetteur choisit les canaux et que le destinataire du message —
 * {@code notification-service} — les applique. C'est donc un contrat entre
 * services, et ce depot a appris ce que coute un contrat recopie de part et
 * d'autre : deux listes qui divergent en silence, et un canal demande qui
 * n'existe pas chez celui qui remet.
 *
 * <p>Un enum plutot qu'une chaine : une valeur inconnue devient une erreur
 * explicite au lieu d'un canal ignore sans bruit.
 *
 * <h2>Ce qui distingue IN_APP de WEBSOCKET</h2>
 *
 * Les deux visent l'application, et ce sont pourtant deux garanties
 * differentes :
 *
 * <ul>
 *   <li>{@link #IN_APP} est <b>persistant</b>. La notification est enregistree
 *       et l'utilisateur la retrouve a sa prochaine connexion. C'est une boite
 *       de reception.</li>
 *   <li>{@link #WEBSOCKET} est <b>immediat et volatil</b>. Si personne n'est
 *       connecte, la remise echoue et rien n'est conserve. C'est une
 *       notification vivante.</li>
 * </ul>
 *
 * <p>Les confondre est l'erreur classique : demander WEBSOCKET seul pour une
 * information importante la perd des que l'utilisateur est hors ligne. Pour
 * qu'une notification survive a la deconnexion, demander les deux — ce que fait
 * {@code DemandeDeNotification.tempsReel}.
 */
public enum CanalDeNotification {

    /** Courriel, par SMTP. */
    COURRIEL,

    /** SMS, par un operateur tiers. */
    SMS,

    /**
     * Boite de reception applicative : enregistre en base, relu par l'API.
     * Survit a la deconnexion.
     */
    IN_APP,

    /**
     * Notification push de l'appareil, par Firebase Cloud Messaging.
     * Atteint l'utilisateur application fermee.
     */
    PUSH,

    /**
     * Remise immediate a une session ouverte. Volatile : rien n'est conserve si
     * l'utilisateur n'est pas connecte.
     */
    WEBSOCKET
}
