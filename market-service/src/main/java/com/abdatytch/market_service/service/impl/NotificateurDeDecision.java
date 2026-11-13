package com.abdatytch.market_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.commons.notification.IMessagerieSortante;
import com.abdatytch.market_service.client.ClientProfilUtilisateur;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Previent le vendeur d'une decision de moderation.
 *
 * <h2>Le defaut que ce composant ferme</h2>
 *
 * Un vendeur dont la boutique etait approuvee ou refusee ne recevait RIEN : il
 * devait revenir voir. Pour un refus, cela veut dire qu'il attend une reponse
 * qui est deja arrivee, et qu'il finit par redeposer le meme dossier.
 *
 * <h2>Une notification ne fait JAMAIS echouer une decision</h2>
 *
 * La decision est deja ecrite en base quand ce composant est appele. Propager
 * son erreur annulerait une moderation parce qu'un courriel n'est pas parti —
 * et le moderateur, voyant l'echec, la rendrait deux fois.
 *
 * <p>La non-remise est en revanche JOURNALISEE. C'est la difference entre
 * « cela peut echouer » et « cela echoue en silence », et ce depot a deja paye
 * quatre {@code .subscribe()} sans gestionnaire.
 *
 * <h2>L'adresse vient de user-service</h2>
 *
 * Jamais d'un champ saisi. Prevenir a une adresse que le vendeur aurait
 * lui-meme declaree permettrait de faire partir un courriel au nom de la
 * plateforme vers n'importe qui.
 *
 * <h2>Urgence NORMALE</h2>
 *
 * Une decision de moderation importe, mais elle ne conditionne l'acces de
 * personne a son compte. {@code CRITIQUE} emprunte le compte d'expedition
 * dedie, dont la reputation est reservee a ce qui bloque quelqu'un dehors.
 */
@Component
public class NotificateurDeDecision {

    private static final Logger log = LoggerFactory.getLogger(NotificateurDeDecision.class);

    static final String MODELE_BOUTIQUE = "market.boutique-decision";
    static final String MODELE_PRODUIT = "market.produit-decision";

    /**
     * Ce qu'on affiche quand le moderateur n'a pas laisse de motif.
     *
     * <p>Une ligne VIDE dans un courriel se lit comme une erreur d'envoi. Un
     * tiret dit « il n'y en a pas », ce qui est l'information exacte — une
     * approbation n'a rien a expliquer.
     */
    private static final String SANS_MOTIF = "—";

    private final IMessagerieSortante messagerie;
    private final ClientProfilUtilisateur profils;

    public NotificateurDeDecision(IMessagerieSortante messagerie,
                                  ClientProfilUtilisateur profils) {
        this.messagerie = messagerie;
        this.profils = profils;
    }

    /** Une decision sur une boutique. */
    public Mono<Void> boutique(String authUserId, String nomBoutique, Sens sens,
                               String motif) {
        return envoyer(authUserId, MODELE_BOUTIQUE,
                Map.of("nomBoutique", texte(nomBoutique),
                        "decision", sens.libelle(),
                        "motif", texte(motif, SANS_MOTIF),
                        "suite", sens.suitePourUneBoutique()),
                "decision de boutique");
    }

    /** Une decision sur un article. */
    public Mono<Void> produit(String authUserId, String nomProduit, Sens sens, String motif) {
        return envoyer(authUserId, MODELE_PRODUIT,
                Map.of("nomProduit", texte(nomProduit),
                        "decision", sens.libelle(),
                        "motif", texte(motif, SANS_MOTIF),
                        "suite", sens.suitePourUnArticle()),
                "decision d'article");
    }

    private Mono<Void> envoyer(String authUserId, String modele,
                               Map<String, String> variables, String libelle) {
        if (authUserId == null || authUserId.isBlank()) {
            return Mono.empty();
        }
        return profils.pour(authUserId)
                .flatMap(profil -> Mono.justOrEmpty(profil.adresse()))
                .flatMap(adresse -> messagerie.envoyer(new IMessagerieSortante.Demande(
                                CanalDeNotification.COURRIEL, adresse, modele, variables,
                                DemandeDeNotification.Urgence.NORMALE))
                        .doOnNext(remis -> {
                            if (!remis) {
                                // NI l'adresse NI l'identifiant : ce journal
                                // part vers Loki, et les deux sont des donnees
                                // personnelles.
                                log.warn("Courriel NON remis : {}", libelle);
                            }
                        }))
                .doOnError(erreur -> log.warn("Notification impossible ({}) : {}",
                        erreur.getClass().getSimpleName(), libelle))
                // Une decision deja ecrite ne doit pas etre annulee parce qu'un
                // courriel n'est pas parti.
                .onErrorResume(erreur -> Mono.empty())
                .then();
    }

    private static String texte(String valeur) {
        return texte(valeur, "");
    }

    private static String texte(String valeur, String defaut) {
        return valeur == null || valeur.isBlank() ? defaut : valeur.trim();
    }

    /**
     * Le sens d'une decision, DEJA TRADUIT.
     *
     * <h3>Pourquoi ici et non dans le modele</h3>
     *
     * Mettre {@code APPROUVEE} dans un courriel ferait lire une valeur
     * d'enumeration a quelqu'un qui vend du tissu. Et le modele ne peut pas
     * traduire : la substitution est litterale, par choix — un moteur capable
     * de conditionnelles serait un moteur capable d'evaluer.
     */
    public enum Sens {

        APPROUVE("approuvee",
                "Elle est desormais visible dans le catalogue.",
                "Il est desormais visible dans le catalogue."),

        REJETE("refusee",
                "Corrigez ce qui est indique ci-dessus, puis soumettez a nouveau.",
                "Corrigez ce qui est indique ci-dessus, puis soumettez a nouveau."),

        SUSPENDU("suspendue",
                "Vos articles ne sont plus visibles le temps de la verification.",
                "Il n'est plus visible le temps de la verification.");

        private final String libelle;
        private final String suiteBoutique;
        private final String suiteArticle;

        Sens(String libelle, String suiteBoutique, String suiteArticle) {
            this.libelle = libelle;
            this.suiteBoutique = suiteBoutique;
            this.suiteArticle = suiteArticle;
        }

        String libelle() {
            return libelle;
        }

        String suitePourUneBoutique() {
            return suiteBoutique;
        }

        String suitePourUnArticle() {
            return suiteArticle;
        }
    }
}
