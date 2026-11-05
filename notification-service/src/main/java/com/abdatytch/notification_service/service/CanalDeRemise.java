package com.abdatytch.notification_service.service;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.CompteRenduDeRemise;
import com.abdatytch.commons.notification.DemandeDeNotification;
import reactor.core.publisher.Mono;

/**
 * Un moyen de remettre une notification.
 *
 * <h2>Pourquoi une interface, et non cinq methodes dans un service</h2>
 *
 * Parce que l'orchestration et la remise n'ont pas les memes raisons de
 * changer. L'orchestration decide QUELS canaux essayer et dans quel ordre ; un
 * canal sait seulement remettre. Ajouter un operateur SMS ne doit pas obliger a
 * relire la logique de repli, et changer la politique de repli ne doit pas
 * toucher au code SMTP.
 *
 * <p>Spring injecte toutes les implementations en {@code List<CanalDeRemise>} :
 * ajouter un canal, c'est ajouter une classe. Aucun {@code switch} a mettre a
 * jour — et c'est le point, parce qu'un {@code switch} oublie ne se signale
 * jamais, il ignore simplement le canal.
 *
 * <h2>Un canal ne leve pas d'exception</h2>
 *
 * Il rend toujours un {@link Resultat}. Une exception qui remonterait ferait
 * echouer la remise des AUTRES canaux de la meme notification : un serveur SMTP
 * injoignable empecherait l'enregistrement en boite de reception. Chaque canal
 * est donc responsable de rattraper ses propres pannes et de les declarer.
 */
public interface CanalDeRemise {

    /** Le canal couvert par cette implementation. */
    CanalDeNotification canal();

    /**
     * Ce canal peut-il remettre ?
     *
     * <p>Lu AVANT chaque tentative, et non une fois au demarrage : un canal peut
     * devenir indisponible — configuration retiree, operateur resilie.
     *
     * <p>Un canal inactif rend {@code REFUSE} et non {@code ECHOUE} : ce n'est
     * pas une panne, et le reessayer ne changerait rien.
     */
    boolean estActif();

    /**
     * Remet le message.
     *
     * @param message ce qu'il faut remettre, deja rendu pour CE canal
     * @return toujours un resultat, jamais une erreur
     */
    Mono<Resultat> remettre(MessageARemettre message);

    /**
     * Ce qu'un canal recoit : du contenu deja rendu, et de quoi joindre la
     * personne.
     *
     * <p>Le contenu est <b>rendu par canal</b> : un SMS de 160 caracteres et un
     * courriel HTML ne portent pas le meme texte. Rendre une seule fois pour
     * tous les canaux obligerait a choisir le plus contraint, donc a appauvrir
     * le courriel.
     *
     * @param destinataireId le {@code sub} du porteur, pour l'audit et pour le
     *                       routage WebSocket
     * @param coordonnee     adresse de courriel, numero, jeton d'appareil —
     *                       selon le canal. {@code null} pour les canaux qui
     *                       n'en ont pas besoin (IN_APP, WEBSOCKET)
     * @param sujet          ligne de sujet. Ignore par les canaux qui n'en ont
     *                       pas
     * @param corps          le contenu rendu pour ce canal
     * @param urgence        reprise de la demande ; un canal peut s'en servir
     *                       pour decider d'un son ou d'une priorite
     * @param notificationId identifiant de la notification enregistree, pour
     *                       que le client puisse la marquer lue
     */
    record MessageARemettre(
            String destinataireId,
            String coordonnee,
            String sujet,
            String corps,
            DemandeDeNotification.Urgence urgence,
            java.util.UUID notificationId
    ) {
    }

    /**
     * Le sort d'une tentative.
     *
     * @param etat  ce qui s'est passe
     * @param motif en clair, SANS donnee personnelle : ni adresse, ni numero,
     *              ni jeton. Ce motif part en base et dans les journaux
     */
    record Resultat(CompteRenduDeRemise.Etat etat, String motif) {

        public static Resultat remis() {
            return new Resultat(CompteRenduDeRemise.Etat.REMIS, null);
        }

        /** Impossible et definitif : canal inactif, coordonnee absente. */
        public static Resultat refuse(String motif) {
            return new Resultat(CompteRenduDeRemise.Etat.REFUSE, motif);
        }

        /** Panne : un reessai a un sens. */
        public static Resultat echoue(String motif) {
            return new Resultat(CompteRenduDeRemise.Etat.ECHOUE, motif);
        }

        /**
         * Canal volatil sans destinataire present.
         *
         * <p>Ni panne ni refus : c'est le fonctionnement normal d'un WebSocket
         * quand personne n'est connecte. Le compter comme un echec rendrait les
         * alertes de ce service inexploitables, puisque la plupart des
         * utilisateurs sont hors ligne la plupart du temps.
         */
        public static Resultat sansDestinataire() {
            return new Resultat(CompteRenduDeRemise.Etat.SANS_DESTINATAIRE, null);
        }

        public boolean aAbouti() {
            return etat == CompteRenduDeRemise.Etat.REMIS;
        }
    }
}
