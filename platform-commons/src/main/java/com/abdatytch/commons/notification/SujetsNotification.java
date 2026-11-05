package com.abdatytch.commons.notification;

/**
 * Nom du sujet Kafka par lequel les services demandent une notification.
 *
 * <h2>Pourquoi une constante partagee</h2>
 *
 * Meme raison que {@code ErasureTopics} et que {@code ClesRedisSecurite} : un
 * emetteur et un consommateur qui ecrivent chacun le nom du sujet finiront par
 * ne plus parler du meme. Et la divergence serait <b>silencieuse</b> — Kafka
 * cree le sujet manquant a la premiere publication, donc l'emetteur reussit, et
 * notification-service ecoute un sujet ou rien n'arrive jamais.
 *
 * <p>Ce depot a paye exactement ce defaut sur la liste noire d'adresses : trois
 * composants, trois prefixes de cle, et un blocage ecrit que personne ne lisait.
 */
public final class SujetsNotification {

    private SujetsNotification() {
        throw new UnsupportedOperationException("Cette classe ne peut pas etre instanciee");
    }

    /**
     * Demande de notification, emise par n'importe quel service.
     *
     * <p>Un seul sujet pour tous les canaux, et non un sujet par canal : le
     * choix des canaux appartient a l'emetteur et voyage DANS le message. Un
     * sujet par canal obligerait l'emetteur a publier plusieurs fois le meme
     * contenu, et ferait perdre le lien entre les remises d'une meme
     * notification.
     */
    public static final String DEMANDE = "notification.demandee";

    /**
     * Compte rendu de remise, emis par notification-service.
     *
     * <p>Il existe pour la meme raison que l'accuse de reception d'effacement :
     * un emetteur qui demande une notification critique — une alerte de
     * securite, un code de verification — doit pouvoir savoir si elle est
     * partie. Sans compte rendu, « notification envoyee » n'est qu'une
     * supposition.
     */
    public static final String COMPTE_RENDU = "notification.remise";
}
