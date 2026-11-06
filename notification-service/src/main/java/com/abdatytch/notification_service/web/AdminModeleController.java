package com.abdatytch.notification_service.web;

import com.abdatytch.notification_service.dto.request.SurchargeDeModeleRequest;
import com.abdatytch.notification_service.dto.response.ModeleResponse;
import com.abdatytch.notification_service.repositories.SurchargeDeModeleRepository;
import com.abdatytch.notification_service.service.impl.CleDeModele;
import com.abdatytch.notification_service.service.impl.MoteurDeModeles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.TreeSet;

/**
 * Administration des textes de notification.
 *
 * <h2>Ce que cette surface permet, et ce qu'elle ne permet pas</h2>
 *
 * Elle pose, remplace et retire des <b>surcharges</b>. Elle ne touche jamais au
 * fichier {@code modeles-notification.properties}, qui est livre avec le code :
 * il n'existe donc aucun moyen de supprimer definitivement un texte par cette
 * API. Retirer une surcharge rend le texte du fichier.
 *
 * <p>C'est la propriete qui rend cette surface sure a exposer : la pire chose
 * qu'un appel puisse faire est de masquer un texte, et un second appel le
 * retablit.
 *
 * <h2>Pourquoi une cle mal formee est REFUSEE et non enregistree</h2>
 *
 * Le moteur n'interroge que {@code <modele>[.<canal>].sujet} et
 * {@code ...corps}. Une surcharge posee sous toute autre forme serait acceptee
 * par la table, relue au rechargement, listee par {@code GET} — et jamais
 * consultee. L'exploitant croirait avoir corrige un texte.
 *
 * <p>{@code CleDeModele} tranche a l'ecriture, et rend le MOTIF du refus : « cle
 * invalide » obligerait a deviner lequel des controles a echoue.
 *
 * <h2>La reponse est relue depuis le MOTEUR, pas construite depuis l'ecriture</h2>
 *
 * Apres un {@code PUT} ou un {@code DELETE}, l'instantane du moteur est recharge
 * puis interroge, et c'est son etat qui part dans la reponse. Repondre « voici
 * ce que j'ai ecrit » affirmerait un effet sans l'avoir constate — alors que
 * c'est precisement ce que l'exploitant veut verifier. Ici, un 200 signifie que
 * le rendu utilise bien ce texte.
 *
 * <p>Sur plusieurs instances, seule celle qui a recu l'appel est a jour
 * immediatement ; les autres convergent au rechargement periodique. Voir
 * {@code MoteurDeModeles} : c'est le meme et unique mecanisme, declenche tot
 * ici.
 */
@RestController
@RequestMapping("/admin/modeles")
@Tag(name = "Administration des modèles",
        description = "Correction à chaud des textes de notification")
public class AdminModeleController {

    private static final Logger log = LoggerFactory.getLogger(AdminModeleController.class);

    private final MoteurDeModeles moteur;
    private final SurchargeDeModeleRepository surcharges;

    public AdminModeleController(MoteurDeModeles moteur,
                                 SurchargeDeModeleRepository surcharges) {
        this.moteur = moteur;
        this.surcharges = surcharges;
    }

    /**
     * Liste tous les textes EN VIGUEUR, des deux couches confondues.
     *
     * <h3>Pourquoi lister le fichier aussi, et pas seulement les surcharges</h3>
     *
     * Parce qu'on ne corrige pas une cle qu'on ne connait pas. Lister les seules
     * surcharges obligerait l'exploitant a ouvrir le code source pour trouver le
     * nom exact d'un modele — et une cle devinee de travers est enregistree sans
     * effet.
     *
     * <p>C'est aussi ce qui rend visible la regle de resolution : les variantes
     * de canal apparaissent comme des cles distinctes. L'exploitant voit donc
     * que corriger {@code securite.code-de-verification.corps} ne touche pas
     * {@code securite.code-de-verification.sms.corps}, au lieu de le decouvrir
     * en comparant un SMS a un courriel.
     *
     * <p>{@code GET /admin/modeles}
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lister les textes en vigueur",
            description = "Fichier et surcharges confondus, avec l'origine de chacun.")
    public Flux<ModeleResponse> lister() {
        Map<String, String> enVigueur = moteur.surchargesEnVigueur();

        // Un TreeSet pour l'union triee : les deux couches partagent des cles,
        // et une liste sans ordre rendrait la reponse illisible des la dizaine
        // de modeles.
        var toutes = new TreeSet<String>();
        toutes.addAll(moteur.clesDuSocle());
        toutes.addAll(enVigueur.keySet());

        return Flux.fromIterable(toutes).map(this::etatDe);
    }

    /**
     * Pose ou remplace la surcharge d'une cle.
     *
     * <p>{@code PUT /admin/modeles}
     */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Surcharger un texte",
            description = "Prend le pas sur le fichier. Rechargé immédiatement.")
    public Mono<ModeleResponse> surcharger(@RequestBody SurchargeDeModeleRequest demande,
                                           @AuthenticationPrincipal Jwt jwt) {
        if (demande == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Corps de requete absent : il faut une cle et une valeur."));
        }
        String cle = demande.cle();
        String motif = CleDeModele.motifDInvalidite(cle);
        if (motif != null) {
            // 400 et le motif : l'appelant est un exploitant, pas un inconnu
            // qui enumere. Le texte lui dit quoi corriger.
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, motif));
        }
        if (demande.valeur() == null || demande.valeur().isBlank()) {
            // Un texte vide n'est pas une suppression. Il produirait un message
            // envoye et vide — ce que le moteur refuse par principe, un message
            // vide faisant croire a l'emetteur qu'il a notifie. Pour revenir au
            // fichier, il faut DELETE.
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La valeur est vide. Un texte vide partirait sans contenu : "
                            + "pour revenir au texte du fichier, supprimer la "
                            + "surcharge."));
        }

        // Le `sub` du porteur, jamais son adresse : cette colonne est consultee
        // librement par l'exploitant.
        String auteur = jwt.getSubject();

        // La VALEUR n'est pas journalisee. Un modele porte des marqueurs de
        // variables et non des donnees personnelles, mais ce journal n'a aucun
        // besoin du contenu : la cle et l'auteur suffisent a l'audit, et la
        // table garde le reste.
        log.info("Admin - surcharge du modele {} par {}", cle, auteur);

        return surcharges.enregistrer(cle, demande.valeur(), auteur)
                .then(moteur.rechargerMaintenant())
                .thenReturn(cle)
                .map(this::etatDe);
    }

    /**
     * Retire une surcharge : le texte du fichier reprend effet.
     *
     * <h3>Un 404 quand il n'y avait rien a retirer</h3>
     *
     * {@code deleteById} rend {@code Mono<Void>} et ne dit pas si une ligne
     * existait : repondre 204 sans lire affirmerait avoir retire une surcharge
     * qui n'etait peut-etre pas la. Sur une cle mal orthographiee, l'exploitant
     * croirait avoir annule une correction toujours en vigueur.
     *
     * <p>D'ou la lecture prealable. Deux requetes au lieu d'une, sur une surface
     * d'administration appelee quelques fois par an.
     *
     * <p>Ce 404 ne contrevient pas a la regle « 404 et non 403 » du depot : elle
     * vise les identifiants enumerables appartenant a autrui. Ici la cle est
     * celle que l'exploitant vient de taper, et le 404 l'informe.
     *
     * <p>{@code DELETE /admin/modeles?cle=...}
     */
    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Retirer une surcharge",
            description = "Le texte du fichier reprend effet. 404 s'il n'y avait aucune surcharge.")
    public Mono<ModeleResponse> retirer(@RequestParam String cle) {
        log.info("Admin - retrait de la surcharge du modele {}", cle);

        return surcharges.findById(cle)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Aucune surcharge sur cette cle. Le texte du fichier "
                                + "s'applique deja, et une API ne peut pas le "
                                + "retirer.")))
                .flatMap(existante -> surcharges.deleteById(cle))
                .then(moteur.rechargerMaintenant())
                .thenReturn(cle)
                .map(this::etatDe);
    }

    /**
     * L'etat d'une cle, lu depuis le moteur.
     *
     * <p>Une seule fonction pour les trois endpoints : la liste, le resultat
     * d'une surcharge et celui d'un retrait decrivent la meme chose, et deux
     * constructions auraient fini par ne plus dire pareil.
     */
    private ModeleResponse etatDe(String cle) {
        String surchargee = moteur.surchargesEnVigueur().get(cle);
        boolean dansLeFichier = moteur.clesDuSocle().contains(cle);

        if (surchargee != null) {
            return new ModeleResponse(cle, surchargee,
                    ModeleResponse.Origine.SURCHARGE, dansLeFichier);
        }
        if (dansLeFichier) {
            // Lu par le moteur et non par un second chargement du fichier :
            // deux lecteurs du meme fichier finissent par differer.
            return new ModeleResponse(cle, moteur.valeurDuSocle(cle),
                    ModeleResponse.Origine.FICHIER, true);
        }
        return new ModeleResponse(cle, null, ModeleResponse.Origine.AUCUNE, false);
    }
}
