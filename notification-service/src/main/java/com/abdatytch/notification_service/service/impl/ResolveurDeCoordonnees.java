package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.notification_service.client.ClientContactsUtilisateur;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Ou joindre une personne, pour un canal donne.
 *
 * <h2>Pourquoi cette etape existe</h2>
 *
 * Parce que l'emetteur ne fournit PAS de coordonnee : il donne un
 * {@code destinataireId}. Trois raisons, detaillees dans
 * {@code DemandeDeNotification} — les coordonnees changent et un message qui en
 * recopie une part a l'ancienne adresse ; une adresse dans un message Kafka est
 * une donnee personnelle que l'effacement RGPD ne peut pas atteindre ; et un
 * seul service doit savoir ou joindre quelqu'un, sinon le consentement se
 * verifie a sept endroits.
 *
 * <h2>UNE resolution par notification, et non une par canal</h2>
 *
 * La resolution est une entree-sortie ; la selection par canal est du calcul.
 * Les deux sont donc separees : {@link #contactsPour} appelle user-service une
 * fois, {@link #pour} choisit ensuite le bon champ sans rien appeler.
 *
 * <p>Melanger les deux aurait produit un appel par canal — trois pour une
 * notification critique, qui vise COURRIEL, IN_APP, PUSH et WEBSOCKET. Et sur
 * le chemin d'une alerte de securite, ou chaque canal compte, c'est trois fois
 * plus d'occasions qu'une panne de user-service fasse perdre le message.
 *
 * <h2>Trois reponses, et non deux</h2>
 *
 * C'est le point qui decide du sort de la notification :
 *
 * <ul>
 *   <li>une coordonnee : on remet ;</li>
 *   <li><b>aucun contact connu</b> — pas de profil, ou champ vide. Definitif :
 *       le canal repond {@code REFUSE}, et reessayer ne trouverait rien de
 *       plus ;</li>
 *   <li><b>contacts indisponibles</b> — user-service n'a pas repondu. Rien ne
 *       dit qu'il n'y a pas d'adresse : le canal repond {@code ECHOUE}, pour
 *       qu'une reprise rattrape la notification.</li>
 * </ul>
 *
 * <p>Confondre les deux derniers perdrait silencieusement des notifications
 * pendant chaque incident de user-service — en les marquant « pas d'adresse »,
 * donc en les rendant non reessayables.
 *
 * <h2>La coordonnee de secours court-circuite l'appel</h2>
 *
 * {@code destinataireDeSecours} existe pour les destinataires <b>sans
 * compte</b> : confirmation d'inscription, invitation. Quand il est renseigne,
 * interroger user-service serait inutile — et echouerait, puisque la personne
 * n'y a precisement pas de profil.
 *
 * <h2>Ce qui n'est PAS encore ici : le consentement</h2>
 *
 * C'est le bon endroit pour verifier qu'une personne accepte les SMS avant de
 * lui en envoyer, et le faire ici plutot que dans chaque emetteur. Ce n'est pas
 * fait : les preferences vivent dans setting-service, et choisir les defauts
 * par canal est une decision d'exploitation, pas un detail d'implementation.
 * Tant que ce n'est pas tranche, tous les canaux demandes sont essayes.
 */
@Component
public class ResolveurDeCoordonnees {

    /**
     * L'etat des contacts d'une personne.
     *
     * @param contacts     les coordonnees, ou {@code null} si indisponibles ou
     *                     si une coordonnee de secours rend l'appel inutile
     * @param indisponible {@code true} quand user-service n'a pas repondu
     */
    public record Coordonnees(ClientContactsUtilisateur.Contacts contacts,
                              boolean indisponible) {

        /** user-service n'a pas repondu : on ne sait pas, donc on reessaiera. */
        static Coordonnees indisponibles() {
            return new Coordonnees(null, true);
        }

        static Coordonnees de(ClientContactsUtilisateur.Contacts contacts) {
            return new Coordonnees(contacts, false);
        }
    }

    /**
     * Les canaux qui n'ont besoin d'aucune coordonnee.
     *
     * <p>Ils routent sur l'identifiant du porteur. C'est pourquoi la chaine
     * complete reste utile meme quand user-service est injoignable : une
     * notification en boite de reception aboutit toujours.
     */
    private static final List<CanalDeNotification> SANS_COORDONNEE =
            List.of(CanalDeNotification.IN_APP, CanalDeNotification.WEBSOCKET);

    private final ClientContactsUtilisateur contacts;

    public ResolveurDeCoordonnees(ClientContactsUtilisateur contacts) {
        this.contacts = contacts;
    }

    /**
     * Ce canal a-t-il besoin d'une coordonnee pour aboutir.
     *
     * <p>Expose parce que l'orchestrateur en a besoin : quand user-service est
     * injoignable, il doit marquer ECHOUE les canaux a coordonnee <b>et laisser
     * passer les autres</b>. Sans cette distinction, une panne de user-service
     * ferait echouer jusqu'a la boite de reception, qui n'avait besoin de rien.
     */
    public boolean aBesoinDUneCoordonnee(CanalDeNotification canal) {
        return !SANS_COORDONNEE.contains(canal);
    }

    /**
     * Resout les contacts une fois pour toute la notification.
     *
     * <p>N'appelle user-service que si un canal demande en a besoin : une
     * notification purement en boite de reception ne doit pas dependre d'un
     * autre service, ni lui couter une requete.
     */
    public Mono<Coordonnees> contactsPour(DemandeDeNotification demande) {
        String secours = demande.destinataireDeSecours();
        if (secours != null && !secours.isBlank()) {
            // La coordonnee est fournie : aucun appel. `pour` la rendra pour
            // n'importe quel canal a coordonnee, comme avant.
            return Mono.just(Coordonnees.de(null));
        }

        boolean coordonneeNecessaire = demande.canaux().stream()
                .anyMatch(canal -> !SANS_COORDONNEE.contains(canal));
        if (!coordonneeNecessaire) {
            return Mono.just(Coordonnees.de(ClientContactsUtilisateur.AUCUN));
        }

        return contacts.pour(demande.destinataireId())
                .map(Coordonnees::de)
                // L'erreur est traduite en etat, et non propagee : propagee,
                // elle ferait echouer la notification ENTIERE, y compris ses
                // canaux IN_APP et WEBSOCKET qui n'avaient besoin de rien.
                .onErrorReturn(Coordonnees.indisponibles());
    }

    /**
     * La coordonnee a utiliser, ou {@code null} si elle est inconnue.
     *
     * <p>{@code null} et non une chaine vide : le canal distingue ainsi
     * « inconnue » de « vide », et son message de refus est exact.
     *
     * <p>Fonction PURE : aucun appel, aucune attente. Elle choisit un champ.
     */
    public String pour(DemandeDeNotification demande, CanalDeNotification canal,
                       Coordonnees coordonnees) {
        if (SANS_COORDONNEE.contains(canal)) {
            return null;
        }

        // La coordonnee de secours gagne sur tout le reste : elle designe une
        // personne qui n'a pas de profil, donc rien a aller chercher.
        String secours = demande.destinataireDeSecours();
        if (secours != null && !secours.isBlank()) {
            return secours;
        }

        if (coordonnees == null || coordonnees.contacts() == null) {
            return null;
        }

        return switch (canal) {
            case COURRIEL -> valeurOuNull(coordonnees.contacts().email());
            // PUSH utilise le numero faute de jeton d'appareil : ce module n'en
            // detient aucun. Le canal refusera, et c'est exact — mieux vaut un
            // refus visible qu'une adresse inventee.
            case SMS, PUSH -> valeurOuNull(coordonnees.contacts().phoneNumber());
            default -> null;
        };
    }

    private static String valeurOuNull(String valeur) {
        return valeur != null && !valeur.isBlank() ? valeur : null;
    }
}
