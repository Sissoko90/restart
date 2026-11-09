package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.commons.notification.ConsentementDeNotification;
import com.abdatytch.notification_service.client.ClientContactsUtilisateur;
import com.abdatytch.notification_service.client.ClientPreferencesUtilisateur;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                              boolean indisponible,
                              Map<String, String> preferences) {

        /** user-service n'a pas repondu : on ne sait pas, donc on reessaiera. */
        static Coordonnees indisponibles() {
            return new Coordonnees(null, true, Map.of());
        }

        static Coordonnees de(ClientContactsUtilisateur.Contacts contacts,
                              Map<String, String> preferences) {
            return new Coordonnees(contacts, false,
                    preferences == null ? Map.of() : preferences);
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
            List.of(CanalDeNotification.IN_APP,
                    CanalDeNotification.WEBSOCKET,
                    // PUSH a REJOINT cette liste, et ce n'est pas un relachement.
                    //
                    // Un jeton d'appareil n'est pas une coordonnee de profil :
                    // il appartient a un telephone et non a une personne, le
                    // systeme le renouvelle sans que le compte change, et une
                    // meme personne en a autant qu'elle possede d'appareils.
                    // Ces jetons vivent donc dans la table jeton_d_appareil de
                    // CE service, et CanalPush les lit lui-meme.
                    //
                    // Avant, ce resolveur lui passait le NUMERO DE TELEPHONE
                    // faute de mieux — le commentaire de `pour` l'admettait —
                    // donc chaque push payait un appel a user-service pour une
                    // valeur que le canal ne pouvait pas utiliser, et refusait.
                    //
                    // Consequence : un push n'echoue plus parce que
                    // user-service est indisponible.
                    CanalDeNotification.PUSH);

    private final ClientContactsUtilisateur contacts;
    private final ClientPreferencesUtilisateur preferences;
    private final Map<CanalDeNotification, Boolean> consentementParDefaut;

    public ResolveurDeCoordonnees(
            ClientContactsUtilisateur contacts,
            ClientPreferencesUtilisateur preferences,
            @Value("${notification.consentement.defaut.courriel:true}") boolean courrielParDefaut,
            @Value("${notification.consentement.defaut.sms:false}") boolean smsParDefaut,
            @Value("${notification.consentement.defaut.push:false}") boolean pushParDefaut) {

        this.contacts = contacts;
        this.preferences = preferences;
        this.consentementParDefaut = Map.of(
                CanalDeNotification.COURRIEL, courrielParDefaut,
                CanalDeNotification.SMS, smsParDefaut,
                CanalDeNotification.PUSH, pushParDefaut);
    }

    /**
     * Cette personne accepte-t-elle ce canal.
     *
     * <h2>Le silence n'est pas un oui, et ce n'est pas un non</h2>
     *
     * Trois reponses, et la troisieme est le cas courant : la quasi-totalite des
     * comptes n'a rien exprime. C'est alors la politique de DEFAUT qui tranche,
     * et elle differe par canal pour une raison simple — ces canaux ne se
     * valent pas.
     *
     * <p>Le courriel est accorde par defaut : il est le canal par lequel on
     * confirme une inscription et on previent d'un changement de mot de passe.
     * Le refuser par defaut rendrait la plateforme inutilisable, et personne ne
     * pourrait l'accepter puisque l'invitation a le faire passerait par lui.
     *
     * <p>Le SMS et le push sont REFUSES par defaut. Un SMS se paie et arrive sur
     * le telephone de la personne ; un push s'affiche sur son ecran. Ce sont des
     * canaux qu'on demande, pas qu'on impose — et dans le doute, ne pas envoyer
     * est reparable, envoyer ne l'est pas.
     *
     * <p>Ces defauts sont dans le config-repo, donc modifiables a chaud : une
     * campagne qui justifierait d'ouvrir le SMS par defaut ne demande pas un
     * redeploiement.
     *
     * <h2>Ce qui n'est PAS soumis au consentement</h2>
     *
     * IN_APP et WEBSOCKET. Ils remettent dans la propre boite de reception de
     * la personne, sur sa propre session : lui demander la permission de lui
     * montrer ses messages dans son interface n'aurait pas de sens, et refuser
     * ce canal reviendrait a les lui cacher. Voir
     * {@code ConsentementDeNotification}.
     */
    public boolean consent(CanalDeNotification canal, Coordonnees coordonnees) {
        if (!ConsentementDeNotification.demandeUnConsentement(canal)) {
            return true;
        }

        Boolean exprime = ConsentementDeNotification.lire(
                coordonnees == null ? Map.of() : coordonnees.preferences(), canal);

        // `exprime` null = la personne n'a rien dit. Le defaut tranche, et il
        // n'est jamais null : la carte couvre les trois canaux a consentement.
        return exprime != null ? exprime : consentementParDefaut.get(canal);
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
        // DEUX questions distinctes, et les confondre est un defaut que
        // l'arrivee du push a rendu visible.
        //
        // « Faut-il une coordonnee ? » decide de l'appel a user-service.
        // « Faut-il le consentement ? » decide de l'appel a setting-service.
        //
        // Elles coincidaient tant que les canaux a consentement etaient
        // exactement les canaux a coordonnee. PUSH les separe : il demande le
        // consentement de la personne — c'est un canal qui fait sonner un
        // telephone — et n'a besoin d'aucune coordonnee de profil.
        //
        // Avec la condition unique d'avant, une notification purement PUSH
        // aurait pris le court-circuit ci-dessous et recu une carte de
        // preferences VIDE : le consentement serait retombe sur la politique
        // par defaut au lieu du choix de la personne. Un refus exprime aurait
        // ete ignore, en silence.
        boolean coordonneeNecessaire = demande.canaux().stream()
                .anyMatch(canal -> !SANS_COORDONNEE.contains(canal));
        boolean consentementNecessaire = demande.canaux().stream()
                .anyMatch(ConsentementDeNotification::demandeUnConsentement);

        if (!coordonneeNecessaire && !consentementNecessaire) {
            // Boite de reception seule : ni coordonnee ni consentement a lire.
            // Aucun appel sortant, donc aucune dependance a un autre service.
            return Mono.just(Coordonnees.de(ClientContactsUtilisateur.AUCUN, Map.of()));
        }

        String secours = demande.destinataireDeSecours();
        if (secours != null && !secours.isBlank()) {
            // Coordonnee directe : la personne n'a pas de compte, donc ni
            // profil chez user-service ni preferences chez setting-service.
            //
            // Aucun consentement a demander, et ce n'est pas une faille : ce
            // chemin sert les confirmations d'inscription et les invitations,
            // ou l'adresse vient de la personne elle-meme, a l'instant.
            return Mono.just(Coordonnees.de(null, Map.of()));
        }

        if (!coordonneeNecessaire) {
            // PUSH seul : le consentement se lit, le profil non.
            //
            // Un seul appel sortant au lieu de deux, et surtout : ce chemin ne
            // depend plus de user-service. Une panne de ce service n'empeche
            // donc plus un push d'aboutir.
            return preferences.pour(demande.destinataireId())
                    .map(lues -> Coordonnees.de(ClientContactsUtilisateur.AUCUN, lues));
        }

        // Les deux lectures en PARALLELE, et non l'une apres l'autre.
        //
        // Elles sont independantes, et les enchainer additionnerait leurs
        // delais sur le chemin de chaque notification — jusqu'a six secondes
        // pour deux services lents, alors que le pire cas doit rester trois.
        return Mono.zip(
                        contacts.pour(demande.destinataireId())
                                // Traduite en etat ici, et non propagee : une
                                // erreur ferait echouer la notification
                                // ENTIERE, y compris ses canaux IN_APP et
                                // WEBSOCKET qui n'avaient besoin de rien.
                                .map(Optional::of)
                                .onErrorReturn(Optional.empty()),
                        // Ce client ne signale jamais d'erreur : il rend une
                        // carte vide, et la politique de defaut s'applique.
                        preferences.pour(demande.destinataireId()))
                .map(deux -> deux.getT1()
                        .map(trouves -> Coordonnees.de(trouves, deux.getT2()))
                        .orElseGet(Coordonnees::indisponibles));
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

        // PUSH n'apparait plus ici : il est dans SANS_COORDONNEE, donc traite
        // par le retour anticipe en tete de methode. Il lisait le numero de
        // telephone faute de jeton d'appareil, ce qui ne pouvait aboutir.
        return switch (canal) {
            case COURRIEL -> valeurOuNull(coordonnees.contacts().email());
            case SMS -> valeurOuNull(coordonnees.contacts().phoneNumber());
            default -> null;
        };
    }

    private static String valeurOuNull(String valeur) {
        return valeur != null && !valeur.isBlank() ? valeur : null;
    }
}
