package com.abdatytch.notification_service.web;

import com.abdatytch.notification_service.service.ISmsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Envoi de SMS demande par un autre service.
 *
 * <h2>Pourquoi un appel synchrone, et non un evenement Kafka</h2>
 *
 * Les notifications de la plateforme passent par {@code notification.demandee},
 * et c'est le bon mecanisme pour une boite de reception : durable, reessayable,
 * sans appelant qui attend. Un code de verification ne rentre pas dans ce
 * cadre, pour deux raisons mesurees dans le schema et dans les appelants.
 *
 * <p><b>Il n'a pas de destinataire au sens de la boite de reception.</b>
 * {@code sendCode(phoneNumber, email, code, type)} dans auth-service ne dispose
 * d'aucun identifiant d'utilisateur — un code peut partir avant qu'un compte
 * existe. Or {@code notification.destinataire_id} est {@code NOT NULL}, et y
 * mettre le numero de telephone placerait une coordonnee dans la colonne qui
 * sert au filtrage de propriete.
 *
 * <p><b>Et il ne doit pas etre persiste.</b> Ecrire le corps du message dans la
 * table {@code notification} y ecrirait un code a usage unique encore valide,
 * dans une ligne que son proprietaire peut relire. Ce chemin ne persiste donc
 * rien : il remet au fournisseur, et c'est tout.
 *
 * <p>L'appelant conserve ainsi exactement la semantique qu'il avait avant le
 * deplacement du sous-systeme SMS — {@code Mono<Void>}, termine = remis au
 * fournisseur — sans qu'aucun de ses trois appelants change.
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
 * <p>Ce controleur avait d'abord verifie le secret lui-meme. C'est ce que font
 * auth-service et interface-service, dans <b>chaque</b> controleur interne — et
 * c'est la faiblesse de cette approche : un endpoint ajoute plus tard sous
 * {@code /internal/} peut oublier le controle, sans que rien ne le signale.
 * Pose dans la chaine de securite, il couvre la surface entiere, y compris ce
 * que personne n'a encore ecrit.
 *
 * <p>La passerelle n'expose aucune route vers {@code /internal/**}, donc ce
 * chemin reste injoignable depuis l'exterieur.
 */
@RestController
@RequestMapping(InternalSmsController.CHEMIN)
public class InternalSmsController {

    static final String CHEMIN = "/internal/sms";

    private final ISmsService smsService;

    public InternalSmsController(ISmsService smsService) {
        this.smsService = smsService;
    }

    /**
     * Le message a remettre.
     *
     * <p>Le texte est fourni <b>fini</b> par l'appelant, et ce n'est pas un
     * oubli : auth-service compose ses messages depuis son propre magasin de
     * modeles, modifiable a chaud par {@code MessageTemplateController}. Tant
     * que les deux moteurs de modeles de la plateforme ne sont pas reunis, le
     * detenteur du texte reste celui qui le possede deja. Dupliquer les modeles
     * ici donnerait deux versions d'un meme message, qui divergent des la
     * premiere correction.
     */
    public record DemandeSms(@NotBlank String numero, @NotBlank String message) {
    }

    /**
     * Ce que l'appel apprend a l'appelant.
     *
     * <p>Un corps vide suffisait a renvoyer {@code 200}, et c'est ce que faisait
     * la premiere version : l'appelant ne pouvait pas distinguer « remis au
     * fournisseur » de « refuse par un garde-fou de depense ».
     *
     * <p>auth-service ne lit pas encore ce champ — il formule sa reponse depuis
     * {@code envoiActif()}, qui ne connait ni le plafond journalier ni la liste
     * de numeros autorises. Le rendre ici est ce qui permettra de corriger cela
     * sans retoucher au contrat.
     *
     * @param remis {@code true} si le fournisseur a accepte le message
     */
    public record Remise(boolean remis) {
    }

    @PostMapping
    public Mono<Remise> envoyer(@Valid @RequestBody DemandeSms demande) {
        // Aucun journal de succes ici.
        //
        // Le fournisseur est seul a savoir qu'un envoi a ete accepte. Un « SMS
        // envoye » ecrit ici affirmerait le contraire de ce qui s'est passe
        // chaque fois qu'un garde-fou refuse — et precisement pendant un
        // incident, quand les journaux sont le plus lus.
        //
        // Un refus de garde-fou n'est PAS une erreur HTTP : le service a
        // fonctionne, et une limite de depense voulue ne doit pas ressembler a
        // une panne. L'appelant lit `remis` pour savoir ce qui s'est passe.
        return smsService.sendSms(demande.numero(), demande.message())
                .map(Remise::new);
    }
}
