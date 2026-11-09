package com.abdatytch.notification_service.service;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * Remise d'un message TRANSACTIONNEL, hors boite de reception.
 *
 * <h2>Pourquoi ce chemin existe a cote des notifications</h2>
 *
 * Un code de verification n'est pas une notification. Trois differences, et
 * chacune suffirait :
 *
 * <ul>
 *   <li><b>il n'a pas de destinataire au sens de la boite de reception.</b>
 *       {@code sendCode(phoneNumber, email, code, type)} dans auth-service ne
 *       dispose d'aucun identifiant d'utilisateur — un code peut partir avant
 *       qu'un compte existe. Or {@code notification.destinataire_id} est
 *       {@code NOT NULL}, et y mettre le numero placerait une coordonnee dans
 *       la colonne qui sert au filtrage de propriete ;</li>
 *   <li><b>il ne doit pas etre persiste.</b> Ecrire son corps dans la table
 *       {@code notification} y laisserait un code a usage unique encore valide,
 *       dans une ligne que son proprietaire peut relire ;</li>
 *   <li><b>il ne doit pas etre soumis au consentement de canal.</b> Une
 *       preference « pas de SMS » est un reglage de confort ; l'appliquer a un
 *       code de connexion enfermerait la personne hors de son compte avec un
 *       refus parfaitement journalise.</li>
 * </ul>
 *
 * <p>Et l'appelant a besoin d'une reponse SYNCHRONE : il doit savoir s'il peut
 * dire « code envoye » ou proposer un autre canal. Un evenement Kafka, durable
 * et reessayable, est le bon mecanisme pour une boite de reception ; il ne
 * repond pas a cette question.
 *
 * <h2>Ce que ce chemin partage avec les notifications, et c'est l'essentiel</h2>
 *
 * Le MOTEUR DE MODELES et les CANAUX. L'appelant fournit un nom de modele et
 * des variables, jamais du texte fini : le rendu reste donc l'affaire de ce
 * service, en un seul endroit, avec ses variantes par canal et ses surcharges
 * modifiables a chaud.
 *
 * <p>Les canaux sont les memes objets que ceux de l'orchestrateur. Les
 * garde-fous de depense du SMS — plafond journalier, liste de numeros autorises,
 * zero reessai — s'appliquent donc ici sans etre recopies, et un SMS de code se
 * compte dans le meme plafond qu'un SMS de notification. C'est voulu : ils
 * coutent au meme endroit.
 */
public interface IMessagerieDirecte {

    /**
     * Les canaux que ce chemin accepte, et la liste est VOLONTAIREMENT courte.
     *
     * <h3>Pourquoi une liste blanche et non « tous les canaux »</h3>
     *
     * {@code IN_APP} et {@code WEBSOCKET} routent sur l'identifiant du porteur
     * et sur une notification ENREGISTREE : appeles par ce chemin, ils
     * recevraient {@code destinataireId} et {@code notificationId} nuls, et
     * tenteraient d'ecrire une ligne sans identifiant. {@code PUSH} demande un
     * jeton d'appareil, que l'appelant d'un code de verification n'a pas.
     *
     * <p>Une liste blanche refuse donc ces canaux avec un motif lisible, au lieu
     * de les laisser echouer plus loin sur une contrainte de base. Et surtout :
     * un canal ajoute demain n'entre pas ici par defaut. Ce depot a deja paye
     * l'inverse — un {@code switch} oublie n'ignore pas bruyamment, il ignore.
     */
    Set<CanalDeNotification> CANAUX_ACCEPTES =
            Set.of(CanalDeNotification.SMS, CanalDeNotification.COURRIEL);

    /**
     * Remet le message, et dit ce qui s'est passe.
     *
     * @return toujours un resultat, jamais une erreur : une panne de fournisseur
     *         ne doit pas remonter en 500 chez l'appelant, qui a deja enregistre
     *         son code et peut proposer un autre canal
     */
    Mono<Resultat> remettre(Demande demande);

    /**
     * Ce que l'appelant demande.
     *
     * @param canal        {@code SMS} ou {@code COURRIEL}
     * @param destinataire numero ou adresse. Seul cas ou une coordonnee circule
     *                     dans ce service sans venir de user-service : le
     *                     destinataire n'a pas forcement de compte
     * @param modele       nom du gabarit, par exemple
     *                     {@code securite.code-de-verification}
     * @param variables    valeurs du gabarit
     * @param urgence      decide du compte SMTP employe par le canal courriel.
     *                     {@code CRITIQUE} pour un code ou une alerte
     */
    record Demande(
            CanalDeNotification canal,
            String destinataire,
            String modele,
            Map<String, String> variables,
            DemandeDeNotification.Urgence urgence
    ) {
    }

    /**
     * Ce que l'appel apprend a l'appelant.
     *
     * <h3>Un motif en plus du booleen</h3>
     *
     * Parce que {@code false} seul confond des situations qui n'appellent pas la
     * meme conduite : « modele introuvable » est un defaut a corriger tout de
     * suite, « plafond de depense atteint » est une limite voulue, « canal
     * inactif » est une configuration. L'appelant les journalise differemment, ou
     * propose un autre canal.
     *
     * <p>Le motif est SANS donnee personnelle — ni adresse, ni numero, ni code —
     * parce qu'il vient de {@code CanalDeRemise.Resultat.motif()}, dont le
     * contrat l'exige deja, et qu'il finira dans les journaux d'un autre service.
     *
     * @param remis {@code true} seulement si le fournisseur a accepte le message
     * @param motif en clair quand {@code remis} est faux, {@code null} sinon
     */
    record Resultat(boolean remis, String motif) {

        /**
         * Nomme {@code accepte} et non {@code remis}.
         *
         * <p>Une fabrique statique {@code remis()} entrerait en collision avec
         * l'accesseur du composant {@code remis} — le compilateur la refuse. Le
         * nom retenu dit d'ailleurs mieux ce qui est vrai : le fournisseur a
         * ACCEPTE le message, ce qui n'est pas la meme chose que de l'avoir
         * remis au destinataire.
         */
        public static Resultat accepte() {
            return new Resultat(true, null);
        }

        public static Resultat refuse(String motif) {
            return new Resultat(false, motif);
        }
    }
}
