package com.abdatytch.commons.notification;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Les messages que ce service fait partir, vus par ce service.
 *
 * <h2>Ce qu'elle remplace, et pourquoi UNE interface au lieu de deux</h2>
 *
 * Elle remplace {@code ISmsService} et {@code IEmailService}. Les deux
 * decrivaient la meme chose — « fais partir ce message » — et differaient par le
 * CANAL, qui est une donnee et non une couche. Deux interfaces imposaient deux
 * implementations, donc deux clients HTTP, deux disjoncteurs et deux delais a
 * tenir d'accord pour un seul appel vers un seul service.
 *
 * <h2>L'appelant nomme un MODELE, plus jamais un texte</h2>
 *
 * C'est le changement de cette passe. Avant, ce module tenait son propre moteur
 * de modeles — table {@code message_templates}, {@code MessageTemplateController}
 * pour les modifier a chaud, {@code DataSeeder} pour les amorcer — et composait
 * ses textes lui-meme avant de les transmettre tout faits.
 *
 * <p>Il y avait donc DEUX moteurs de modeles sur la plateforme, pour deux
 * exemplaires des memes messages. « Votre code de verification est… » existait
 * ici et dans notification-service, avec deux textes qui n'etaient deja pas
 * identiques : celui de notification-service disait « Ne le communiquez a
 * personne », celui d'ici non.
 *
 * <p>Le detenteur unique est desormais notification-service, et la correction
 * d'un texte se fait par {@code PUT /api/v1/admin/modeles} — sans livraison, et
 * valable pour le SMS comme pour le courriel.
 *
 * <h2>Pourquoi un appel synchrone, et non un evenement Kafka</h2>
 *
 * Les notifications de la plateforme passent par {@code notification.demandee},
 * et c'est le bon mecanisme pour une boite de reception. Ce que ce module envoie
 * n'en est pas :
 *
 * <ul>
 *   <li>un code de verification peut partir AVANT qu'un compte existe, donc sans
 *       identifiant de destinataire — or la boite de reception en exige un ;</li>
 *   <li>il ne doit pas etre persiste : ce serait ecrire un code a usage unique
 *       encore valide dans une ligne que son proprietaire peut relire ;</li>
 *   <li>l'utilisateur attend devant son ecran, et ce module doit savoir s'il
 *       peut annoncer « code envoye ».</li>
 * </ul>
 */
public interface IMessagerieSortante {

    /**
     * Fait partir le message.
     *
     * @return {@code true} si le fournisseur l'a accepte. <b>Jamais une
     *         erreur</b> : une panne de notification-service ne doit pas faire
     *         echouer l'operation qui a declenche le message — un compte cree,
     *         un mot de passe change — et l'appelant peut proposer un autre
     *         canal
     */
    Mono<Boolean> envoyer(Demande demande);

    /**
     * L'envoi de SMS est-il actif sur la plateforme.
     *
     * <h3>Ne sert qu'a FORMULER une reponse, et c'est une limite connue</h3>
     *
     * « Code envoye par SMS » ou « code genere sans envoi » : annoncer un SMS
     * que la configuration empeche de partir faisait attendre un message qui
     * n'arrivait jamais.
     *
     * <p>Mais cette valeur ne connait qu'UNE des raisons de ne rien envoyer —
     * l'interrupteur global. Elle ignore le numero hors liste autorisee, le
     * plafond journalier atteint et le fournisseur mal configure.
     * {@link #envoyer} rend desormais la verite, et c'est elle qu'il faudrait
     * lire.
     *
     * <p>Les deux appelants ne le font pas encore, pour une raison de structure
     * et non d'oubli : ils formulent leur message dans une methode SYNCHRONE
     * appelee apres l'envoi, et recevoir le resultat reel demanderait de le
     * faire remonter a travers {@code generateAndSendCode} — huit sites d'appel
     * en production. Cela merite sa propre passe.
     *
     * <p>Ne commande aucun envoi : l'autorite qui applique la regle est
     * notification-service, seul detenteur des fournisseurs.
     */
    boolean envoiSmsActif();

    /**
     * Un message a faire partir.
     *
     * @param canal        {@code SMS} ou {@code COURRIEL}
     * @param destinataire numero ou adresse
     * @param modele       nom du gabarit chez notification-service, par exemple
     *                     {@code securite.code-de-verification}
     * @param variables    valeurs du gabarit
     * @param urgence      {@code CRITIQUE} fait partir le courriel par le compte
     *                     SMTP dedie, dont la reputation d'expedition n'est pas
     *                     melee a celle du trafic ordinaire
     */
    record Demande(
            CanalDeNotification canal,
            String destinataire,
            String modele,
            Map<String, String> variables,
            DemandeDeNotification.Urgence urgence
    ) {

        /**
         * Un message de securite : code, alerte, confirmation.
         *
         * <h4>Pourquoi une fabrique nommee plutot que le constructeur</h4>
         *
         * {@code destinataire} et {@code modele} sont deux {@code String}
         * voisines : les inverser compile, et produit un envoi vers une adresse
         * nommee « securite.code-de-verification ». Une fabrique dont le nom
         * dit l'intention rend l'ordre lisible au site d'appel.
         *
         * <p>Et {@code CRITIQUE} y est pose une fois, au lieu d'etre repete a
         * chaque appel — ou, plus probablement, oublie une fois sur cinq, ce qui
         * ferait partir une alerte de securite par le compte du trafic
         * ordinaire.
         */
        public static Demande critique(CanalDeNotification canal, String destinataire,
                                       String modele, Map<String, String> variables) {
            return new Demande(canal, destinataire, modele, variables,
                    DemandeDeNotification.Urgence.CRITIQUE);
        }
    }
}
