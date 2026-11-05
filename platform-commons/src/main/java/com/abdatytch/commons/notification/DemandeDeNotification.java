package com.abdatytch.commons.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Demande de notification, publiee par un service et traitee par
 * {@code notification-service}.
 *
 * <h2>Pourquoi un evenement et non un appel HTTP</h2>
 *
 * Parce qu'une notification ne doit <b>jamais</b> faire echouer ce qui l'a
 * declenchee. Un appel synchrone lie la reussite d'une inscription a la
 * disponibilite d'un serveur SMTP : le compte est cree, le courriel part en
 * erreur, et l'appelant recoit un 500 pour une operation qui a reussi.
 *
 * <p>Ce depot porte deja la trace de ce raisonnement, en moins bien : les deux
 * {@code EmailServiceImpl} existants sont appeles depuis le chemin requete et
 * detaches par un {@code .subscribe()}, donc leurs echecs ne sont qu'une ligne
 * de journal. L'evenement remplace ce detachement par une file durable : si
 * notification-service est arrete, les demandes attendent au lieu de
 * disparaitre.
 *
 * <h2>L'emetteur ne fournit PAS les coordonnees</h2>
 *
 * Il donne un {@code destinataireId} — le {@code sub} du porteur — et non une
 * adresse ni un numero. Trois raisons :
 *
 * <ul>
 *   <li>les coordonnees changent, et un service qui les recopie dans un message
 *       envoie a l'ancienne adresse. C'est le defaut deja repertorie dans ce
 *       depot : prevenir la nouvelle adresse ne previent que l'attaquant ;</li>
 *   <li>une adresse dans un message Kafka est une donnee personnelle dans un
 *       journal durable, que l'effacement RGPD ne peut pas atteindre ;</li>
 *   <li>un seul service doit savoir ou joindre une personne, sinon la
 *       verification du consentement se fait a sept endroits.</li>
 * </ul>
 *
 * <p>{@code notification-service} resout donc l'identifiant en coordonnees au
 * moment de remettre. Le champ {@code destinataireDeSecours} existe pour le seul
 * cas ou cette resolution est impossible : une notification adressee a quelqu'un
 * qui n'a pas encore de compte — confirmation d'inscription, invitation.
 *
 * <h2>Le contenu est un MODELE, pas du texte fini</h2>
 *
 * {@code modele} nomme un gabarit et {@code variables} le remplit. L'emetteur
 * n'ecrit donc ni HTML ni formule de politesse, et la traduction, la mise en
 * page et la signature appartiennent a un seul endroit. Un emetteur qui
 * composerait le texte imposerait sa langue et son format a tous les canaux —
 * or un SMS de 160 caracteres et un courriel HTML ne portent pas le meme texte.
 *
 * @param demandeId          identifiant de la demande, repris dans le compte
 *                           rendu et dans les journaux
 * @param destinataireId     le {@code sub} du porteur. Exemple :
 *                           {@code 11111111-1111-1111-1111-111111111111}
 * @param canaux             canaux demandes, dans l'ordre d'importance.
 *                           Exemple : {@code [IN_APP, WEBSOCKET, PUSH]}
 * @param modele             nom du gabarit. Exemple : {@code projet.cree}
 * @param variables          valeurs du gabarit. Exemple :
 *                           {@code {"nomDuProjet": "mon-projet"}}
 * @param urgence            decide de l'insistance et du repli entre canaux
 * @param serviceEmetteur    pour l'audit et les metriques. Exemple :
 *                           {@code interface-service}
 * @param destinataireDeSecours coordonnee a utiliser si l'identifiant ne se
 *                           resout pas — une adresse de courriel ou un numero.
 *                           {@code null} dans le cas normal
 * @param demandeeLe         horodatage de la demande
 */
public record DemandeDeNotification(
        UUID demandeId,
        String destinataireId,
        List<CanalDeNotification> canaux,
        String modele,
        Map<String, String> variables,
        Urgence urgence,
        String serviceEmetteur,
        String destinataireDeSecours,
        Instant demandeeLe
) {

    /**
     * Combien la notification insiste.
     *
     * <p>Ce n'est pas une etiquette decorative : elle decide du repli entre
     * canaux. Une alerte de securite doit atteindre la personne meme hors
     * ligne ; une information de confort ne justifie pas de reveiller un
     * telephone.
     */
    public enum Urgence {
        /**
         * Information de confort. Aucun repli : si le canal demande echoue, on
         * n'en essaie pas un autre.
         */
        INFORMATION,

        /** Cas courant. Repli sur les autres canaux demandes. */
        NORMALE,

        /**
         * Securite ou acces. Tous les canaux disponibles sont essayes, et un
         * echec sur TOUS est une anomalie a signaler — pas une ligne de journal.
         */
        CRITIQUE
    }

    /**
     * Notification ordinaire : boite de reception, plus remise immediate si la
     * personne est connectee.
     *
     * <p>Les deux canaux ensemble, et c'est le point : {@code WEBSOCKET} seul
     * perdrait la notification pour qui n'est pas connecte, {@code IN_APP} seul
     * obligerait a attendre un rechargement de page.
     */
    public static DemandeDeNotification tempsReel(String destinataireId, String modele,
                                                  Map<String, String> variables,
                                                  String serviceEmetteur) {
        return new DemandeDeNotification(UUID.randomUUID(), destinataireId,
                List.of(CanalDeNotification.IN_APP, CanalDeNotification.WEBSOCKET),
                modele, variables, Urgence.NORMALE, serviceEmetteur, null, Instant.now());
    }

    /**
     * Notification de securite : tous les canaux, et elle doit arriver.
     *
     * <p>Le courriel y figure meme si la personne est connectee : une alerte
     * « votre mot de passe a change » doit laisser une trace que l'attaquant ne
     * peut pas effacer en fermant un onglet.
     */
    public static DemandeDeNotification critique(String destinataireId, String modele,
                                                 Map<String, String> variables,
                                                 String serviceEmetteur) {
        return new DemandeDeNotification(UUID.randomUUID(), destinataireId,
                List.of(CanalDeNotification.COURRIEL, CanalDeNotification.IN_APP,
                        CanalDeNotification.PUSH, CanalDeNotification.WEBSOCKET),
                modele, variables, Urgence.CRITIQUE, serviceEmetteur, null, Instant.now());
    }

    /**
     * Notification vers une coordonnee directe, pour un destinataire qui n'a pas
     * encore de compte.
     *
     * <p>Seul cas ou une adresse circule dans un message : confirmation
     * d'inscription, invitation. {@code destinataireId} reste renseigne quand il
     * existe, pour que l'audit sache de qui il s'agit.
     */
    public static DemandeDeNotification versCoordonnee(String destinataireId,
                                                       String coordonnee,
                                                       CanalDeNotification canal,
                                                       String modele,
                                                       Map<String, String> variables,
                                                       String serviceEmetteur) {
        return new DemandeDeNotification(UUID.randomUUID(), destinataireId,
                List.of(canal), modele, variables, Urgence.NORMALE, serviceEmetteur,
                coordonnee, Instant.now());
    }
}
