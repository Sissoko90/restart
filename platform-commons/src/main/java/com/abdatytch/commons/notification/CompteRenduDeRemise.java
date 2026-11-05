package com.abdatytch.commons.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ce qui est reellement arrive a une demande de notification.
 *
 * <h2>Pourquoi un compte rendu</h2>
 *
 * Parce que « notification envoyee » est une supposition tant que personne ne
 * l'a verifie. Ce depot a deja trouve trois fois la meme forme de ce defaut :
 * un {@code log.debug("evenement envoye")} ecrit avant que l'envoi n'aboutisse,
 * un compteur d'echecs jamais incremente, un blocage inscrit dans une cle que
 * personne ne lisait.
 *
 * <p>Un emetteur qui demande une notification CRITIQUE — code de verification,
 * alerte de securite — doit pouvoir constater qu'aucun canal n'a abouti. Sans
 * ce compte rendu, l'information n'existe que dans les journaux de
 * notification-service, donc nulle part pour celui qui en a besoin.
 *
 * @param demandeId   reprend {@code DemandeDeNotification.demandeId}
 * @param remises     une entree par canal essaye, dans l'ordre d'essai
 * @param rendu       vrai si AU MOINS un canal a abouti
 * @param termineeLe  horodatage de fin de traitement
 */
public record CompteRenduDeRemise(
        UUID demandeId,
        List<RemiseParCanal> remises,
        boolean rendu,
        Instant termineeLe
) {

    /**
     * Le sort d'un canal.
     *
     * @param canal  le canal essaye
     * @param etat   ce qui lui est arrive
     * @param motif  pour un echec ou un refus, en clair et sans donnee
     *               personnelle : ni adresse, ni numero, ni jeton. Un motif
     *               voyage dans un message durable et dans les journaux
     */
    public record RemiseParCanal(CanalDeNotification canal, Etat etat, String motif) {
    }

    /**
     * Les quatre sorts possibles, et la distinction compte.
     *
     * <p>Confondre {@link #REFUSE} et {@link #ECHOUE} est l'erreur a eviter :
     * le premier ne se reparera pas en reessayant, le second peut-etre. Les
     * melanger produit soit des reessais inutiles a l'infini, soit un abandon
     * sur une panne passagere.
     */
    public enum Etat {
        /** Remis au canal, qui l'a accepte. */
        REMIS,

        /**
         * Impossible pour une raison qui ne changera pas : canal desactive,
         * aucune coordonnee connue, consentement absent. Reessayer est inutile.
         */
        REFUSE,

        /**
         * Panne : serveur SMTP injoignable, operateur en erreur. Un reessai a
         * un sens.
         */
        ECHOUE,

        /**
         * Canal volatil sans destinataire present : WEBSOCKET alors que
         * personne n'est connecte. Ce n'est NI une panne NI un refus — c'est le
         * fonctionnement normal, et le compter comme un echec rendrait les
         * alertes de ce service inexploitables.
         */
        SANS_DESTINATAIRE
    }
}
