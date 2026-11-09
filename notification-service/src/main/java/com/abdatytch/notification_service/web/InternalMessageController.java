package com.abdatytch.notification_service.web;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.notification_service.service.IMessagerieDirecte;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Message transactionnel demande par un autre service : code, lien, avis.
 *
 * <h2>UN endpoint pour les deux canaux, et non un par canal</h2>
 *
 * Il remplace {@code POST /internal/sms}. La logique est identique d'un canal a
 * l'autre — rendre le modele pour ce canal, remettre, dire ce qui s'est passe —
 * donc un second endpoint {@code /internal/courriel} aurait recopie un chemin
 * pour changer une seule valeur. Le canal est une donnee de la demande, pas une
 * route.
 *
 * <h2>L'appelant fournit un MODELE, plus jamais du texte fini</h2>
 *
 * C'est le changement de cette passe, et la raison d'etre de la rupture de
 * contrat. L'ancien endpoint prenait {@code {numero, message}} : auth-service
 * composait donc le texte depuis SON propre magasin de modeles, modifiable a
 * chaud par son propre controleur. Deux moteurs de modeles pour une plateforme,
 * deux versions de « votre code de verification est… », qui divergent des la
 * premiere correction.
 *
 * <p>Le javadoc de l'ancien endpoint nommait lui-meme la condition :
 * « tant que les deux moteurs de modeles de la plateforme ne sont pas reunis,
 * le detenteur du texte reste celui qui le possede deja ». Ils le sont.
 *
 * <p>Consequence concrete : une faute dans un code de verification se corrige
 * desormais par {@code PUT /api/v1/admin/modeles}, sans livraison, et la
 * correction vaut pour le SMS comme pour le courriel.
 *
 * <h2>Ce chemin ne persiste RIEN</h2>
 *
 * Ni notification, ni remise par canal. Ecrire le corps en base y laisserait un
 * code a usage unique encore valide, dans une ligne que son proprietaire peut
 * relire. Il remet au fournisseur, et c'est tout.
 *
 * <h2>Ce qui protege ce chemin, et pourquoi pas ici</h2>
 *
 * Deux controles, et il faut les deux — <b>aucun des deux n'est dans cette
 * classe</b> :
 *
 * <ol>
 *   <li>dans {@code GatewayAccessWebFilter}, le <b>pair TCP</b> doit etre une
 *       adresse privee. Pas un en-tete, que l'appelant choisirait ;</li>
 *   <li>dans la chaine de securite, {@code AutorisationInterne} compare
 *       {@code X-Internal-Secret} a temps constant. C'est le controle qui porte
 *       : en conteneurs, « reseau prive » englobe tous les voisins.</li>
 * </ol>
 *
 * <p>Pose dans la chaine et non dans le controleur, le second couvre la surface
 * entiere {@code /internal/**}, y compris ce que personne n'a encore ecrit — un
 * endpoint ajoute demain ne peut pas oublier le controle.
 *
 * <p>La passerelle n'expose aucune route vers {@code /internal/**} : ce chemin
 * reste injoignable depuis l'exterieur.
 */
@RestController
@RequestMapping(InternalMessageController.CHEMIN)
public class InternalMessageController {

    static final String CHEMIN = "/internal/message";

    private final IMessagerieDirecte messagerie;

    public InternalMessageController(IMessagerieDirecte messagerie) {
        this.messagerie = messagerie;
    }

    /**
     * Le message a remettre, decrit par son MODELE.
     *
     * @param canal        {@code SMS} ou {@code COURRIEL}. Les autres canaux
     *                     sont refuses avec un motif : ils demandent un
     *                     identifiant de porteur et une notification
     *                     enregistree, que ce chemin ne produit pas
     * @param destinataire numero ou adresse
     * @param modele       nom du gabarit. Un modele introuvable n'envoie RIEN et
     *                     le dit : un message vide ferait croire a l'emetteur
     *                     qu'il a prevenu
     * @param variables    valeurs du gabarit. Peut etre absente pour un modele
     *                     sans variable
     * @param urgence      {@code CRITIQUE} fait partir le courriel par le compte
     *                     SMTP dedie, dont la reputation d'expedition n'est pas
     *                     melee a celle du trafic ordinaire. Absente vaut
     *                     « ordinaire » : on ne promeut pas un message faute de
     *                     savoir
     */
    public record DemandeDeMessage(
            @NotNull CanalDeNotification canal,
            @NotBlank String destinataire,
            @NotBlank String modele,
            Map<String, String> variables,
            DemandeDeNotification.Urgence urgence) {
    }

    /**
     * Ce que l'appel apprend a l'appelant.
     *
     * @param remis {@code true} si le fournisseur a accepte le message
     * @param motif pourquoi, quand il a refuse. SANS donnee personnelle — il
     *              finit dans les journaux d'un autre service
     */
    public record Remise(boolean remis, String motif) {
    }

    @PostMapping
    public Mono<Remise> envoyer(@Valid @RequestBody DemandeDeMessage demande) {
        // Aucun journal de succes ici.
        //
        // Le fournisseur est seul a savoir qu'un envoi a ete accepte. Un
        // « message envoye » ecrit a cet etage affirmerait le contraire de ce
        // qui s'est passe chaque fois qu'un garde-fou refuse — et precisement
        // pendant un incident, quand les journaux sont le plus lus.
        //
        // Un refus n'est PAS une erreur HTTP : le service a fonctionne, et une
        // limite de depense voulue ne doit pas ressembler a une panne.
        // L'appelant lit `remis` et `motif`.
        return messagerie.remettre(new IMessagerieDirecte.Demande(
                        demande.canal(),
                        demande.destinataire(),
                        demande.modele(),
                        demande.variables() == null ? Map.of() : demande.variables(),
                        demande.urgence()))
                .map(resultat -> new Remise(resultat.remis(), resultat.motif()));
    }
}
