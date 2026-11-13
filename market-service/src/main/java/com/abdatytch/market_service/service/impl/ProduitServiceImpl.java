package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.client.ClientMedia;
import com.abdatytch.market_service.dto.request.CaracteristiqueRequest;
import com.abdatytch.market_service.dto.request.CreationProduitRequest;
import com.abdatytch.market_service.dto.request.PhotoRequest;
import com.abdatytch.market_service.dto.request.VarianteRequest;
import com.abdatytch.market_service.dto.response.CaracteristiqueResponse;
import com.abdatytch.market_service.dto.response.PageResponse;
import com.abdatytch.market_service.dto.response.ProduitResponse;
import com.abdatytch.market_service.dto.response.VarianteResponse;
import com.abdatytch.market_service.entities.Boutique;
import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.entities.ProduitCaracteristique;
import com.abdatytch.market_service.entities.ProduitPhoto;
import com.abdatytch.market_service.entities.ProduitVariante;
import com.abdatytch.market_service.enums.StatutBoutique;
import com.abdatytch.market_service.enums.StatutModeration;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.exception.RefusExplique;
import com.abdatytch.market_service.mapper.MarketMapper;
import com.abdatytch.market_service.repository.BoutiqueRepository;
import com.abdatytch.market_service.repository.ProduitCaracteristiqueRepository;
import com.abdatytch.market_service.repository.ProduitPhotoRepository;
import com.abdatytch.market_service.repository.ProduitRepository;
import com.abdatytch.market_service.repository.ProduitVarianteRepository;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import com.abdatytch.market_service.service.IProduitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le catalogue et la gestion des articles.
 *
 * <h2>LE N+1 EST LA QUESTION CENTRALE DE CETTE CLASSE</h2>
 *
 * Une page de catalogue affiche vingt articles, chacun avec ses photos. Ecrit
 * naivement, cela fait vingt lectures de photos et vingt appels a
 * media-service. {@link #enrichir} ramene donc les photos de toute la page en
 * UNE requete, et demande leurs liens signes en UN appel.
 *
 * <p>Les variantes et les caracteristiques ne sont PAS chargees pour une liste :
 * un ecran de catalogue n'en montre aucune. Elles n'apparaissent que sur la
 * fiche, ou le cout d'une lecture supplementaire est justifie.
 *
 * <h2>Toute ecriture repart EN_ATTENTE</h2>
 *
 * Creer, modifier, changer une photo ou un prix. Sans cette regle, il suffirait
 * de faire approuver un article anodin puis de le remplacer — et la moderation
 * ne servirait a rien.
 */
@Service
public class ProduitServiceImpl implements IProduitService {

    private static final Logger log = LoggerFactory.getLogger(ProduitServiceImpl.class);

    /** Sans devise explicite, celle du marche. Jamais nulle : la base la refuse. */
    private static final String DEVISE_PAR_DEFAUT = "XOF";

    private final ProduitRepository produits;
    private final ProduitPhotoRepository photos;
    private final ProduitVarianteRepository variantes;
    private final ProduitCaracteristiqueRepository caracteristiques;
    private final BoutiqueRepository boutiques;
    private final ClientMedia media;

    public ProduitServiceImpl(ProduitRepository produits, ProduitPhotoRepository photos,
                              ProduitVarianteRepository variantes,
                              ProduitCaracteristiqueRepository caracteristiques,
                              BoutiqueRepository boutiques, ClientMedia media) {
        this.produits = produits;
        this.photos = photos;
        this.variantes = variantes;
        this.caracteristiques = caracteristiques;
        this.boutiques = boutiques;
        this.media = media;
    }

    // ================================================================
    // LECTURES
    // ================================================================

    @Override
    public Mono<PageResponse<ProduitResponse>> catalogue(PorteeDuCatalogue portee,
                                                         UUID categorieId, String recherche,
                                                         int page, int taille) {
        int decalage = page * taille;
        String motif = motifDeRecherche(recherche);

        Flux<Produit> trouves;
        Mono<Long> total;

        // Le COUNT porte EXACTEMENT les memes conditions que la liste, clause
        // de visibilite ET terme de recherche compris.
        //
        // Sans la clause, il compterait des articles que l'appelant n'a pas le
        // droit de voir. Sans le terme — le defaut d'origine — une recherche
        // ramenant deux articles annoncait le total du catalogue entier, et
        // l'ecran affichait quarante-neuf pages vides.
        if (motif == null) {
            trouves = produits.catalogue(portee.administrateur(), portee.boutiqueId(),
                    portee.professionnel(), portee.pays(), categorieId, taille, decalage);
            total = produits.compterLeCatalogue(portee.administrateur(),
                    portee.boutiqueId(), portee.professionnel(), portee.pays(), categorieId);
        } else {
            trouves = produits.rechercher(portee.administrateur(), portee.boutiqueId(),
                    portee.professionnel(), portee.pays(), motif, taille, decalage);
            total = produits.compterLaRecherche(portee.administrateur(),
                    portee.boutiqueId(), portee.professionnel(), portee.pays(), motif);
        }

        return trouves.collectList()
                .flatMap(liste -> enrichir(liste, false))
                .zipWith(total)
                .map(paire -> PageResponse.de(paire.getT1(), paire.getT2(), page, taille));
    }

    @Override
    public Mono<ProduitResponse> fiche(PorteeDuCatalogue portee, UUID produitId) {
        return produits.parIdentifiantVisible(produitId, portee.administrateur(),
                        portee.boutiqueId(), portee.professionnel(), portee.pays())
                // Hors portee ou inexistant : la MEME reponse. Un 403
                // confirmerait que la reference existe.
                .switchIfEmpty(Mono.error(MarketException.introuvable()))
                .flatMap(produit -> enrichir(List.of(produit), true))
                .map(liste -> liste.get(0));
    }

    @Override
    public Mono<PageResponse<ProduitResponse>> mesProduits(String authUserId, int page,
                                                           int taille) {
        return maBoutique(authUserId)
                .flatMap(boutique -> produits
                        .parBoutique(boutique.getId(), taille, page * taille)
                        .collectList()
                        .flatMap(liste -> enrichir(liste, false))
                        .map(liste -> PageResponse.de(liste, liste.size(), page, taille)));
    }

    // ================================================================
    // ECRITURES
    // ================================================================

    @Override
    public Mono<ProduitResponse> creer(String authUserId, CreationProduitRequest demande) {
        return maBoutiqueApprouvee(authUserId)
                .flatMap(boutique -> {
                    Produit produit = new Produit();
                    produit.setBoutiqueId(boutique.getId());
                    // Le pays vient de la BOUTIQUE, denormalise pour que
                    // l'index du catalogue le couvre.
                    produit.setPays(boutique.getPays());
                    appliquer(produit, demande);
                    produit.setStatutModeration(StatutModeration.EN_ATTENTE);
                    produit.setActif(Boolean.TRUE);
                    return produits.save(produit);
                })
                .doOnNext(cree -> log.info("Produit {} cree, en attente de moderation",
                        cree.getId()))
                .flatMap(produit -> enrichir(List.of(produit), true))
                .map(liste -> liste.get(0));
    }

    @Override
    public Mono<ProduitResponse> modifier(String authUserId, UUID produitId,
                                          CreationProduitRequest demande) {
        return mien(authUserId, produitId)
                .flatMap(produit -> {
                    appliquer(produit, demande);
                    return renvoyerEnModeration(produit);
                })
                .flatMap(produit -> enrichir(List.of(produit), true))
                .map(liste -> liste.get(0));
    }

    @Override
    public Mono<Void> remplacerLesPhotos(String authUserId, UUID produitId,
                                         List<PhotoRequest> demandees) {
        if (demandees != null && demandees.stream().filter(PhotoRequest::principale).count() > 1) {
            return Mono.error(RefusExplique.demandeInvalide(
                    "Une seule photo peut etre principale."));
        }
        return mien(authUserId, produitId)
                // Remplacement COMPLET et non ajout : l'ecran d'edition rend
                // l'etat voulu de la galerie, ordre compris. Une API d'ajout
                // obligerait le client a calculer un diff, et deux clients le
                // calculeraient differemment.
                .flatMap(produit -> photos.deleteByProduitId(produitId)
                        .thenMany(Flux.fromIterable(demandees == null ? List.<PhotoRequest>of()
                                : demandees))
                        .index()
                        .map(paire -> {
                            ProduitPhoto photo = new ProduitPhoto();
                            photo.setProduitId(produitId);
                            photo.setDocumentId(paire.getT2().documentId());
                            photo.setPosition(paire.getT2().position() != null
                                    ? paire.getT2().position() : paire.getT1().intValue());
                            photo.setPrincipale(paire.getT2().principale());
                            return photo;
                        })
                        .collectList()
                        .flatMapMany(photos::saveAll)
                        .then(renvoyerEnModeration(produit)))
                .then();
    }

    @Override
    public Mono<Void> remplacerLesVariantes(String authUserId, UUID produitId,
                                            List<VarianteRequest> demandees) {
        return mien(authUserId, produitId)
                .flatMap(produit -> variantes.deleteByProduitId(produitId)
                        .thenMany(Flux.fromIterable(demandees == null
                                ? List.<VarianteRequest>of() : demandees))
                        .map(demande -> {
                            ProduitVariante variante = new ProduitVariante();
                            variante.setProduitId(produitId);
                            variante.setSku(demande.sku());
                            variante.setCouleur(demande.couleur());
                            variante.setTaille(demande.taille());
                            variante.setPrix(demande.prix());
                            variante.setStock(demande.stock());
                            variante.setActif(Boolean.TRUE);
                            return variante;
                        })
                        .collectList()
                        .flatMapMany(variantes::saveAll)
                        .then(renvoyerEnModeration(produit)))
                .then();
    }

    @Override
    public Mono<Void> remplacerLesCaracteristiques(String authUserId, UUID produitId,
                                                   List<CaracteristiqueRequest> demandees) {
        return mien(authUserId, produitId)
                .flatMap(produit -> caracteristiques.deleteByProduitId(produitId)
                        .thenMany(Flux.fromIterable(demandees == null
                                ? List.<CaracteristiqueRequest>of() : demandees))
                        .index()
                        .map(paire -> {
                            ProduitCaracteristique c = new ProduitCaracteristique();
                            c.setProduitId(produitId);
                            c.setCle(paire.getT2().cle());
                            c.setValeur(paire.getT2().valeur());
                            c.setPosition(paire.getT1().intValue());
                            return c;
                        })
                        .collectList()
                        .flatMapMany(caracteristiques::saveAll)
                        .then(renvoyerEnModeration(produit)))
                .then();
    }

    @Override
    public Mono<Void> changerLActivation(String authUserId, UUID produitId, boolean actif) {
        return mien(authUserId, produitId)
                .flatMap(produit -> {
                    // Retirer de la vitrine ne RENVOIE PAS en moderation : le
                    // contenu n'a pas change. C'est la seule ecriture dans ce
                    // cas, et c'est pour cela qu'elle a son propre endpoint.
                    produit.setActif(actif);
                    return produits.save(produit);
                })
                .then();
    }

    @Override
    public Mono<Void> supprimer(String authUserId, UUID produitId) {
        // Photos, variantes et caracteristiques partent par ON DELETE CASCADE,
        // declare en base. Les supprimer ici en plus ferait deux endroits ou la
        // regle vit, et l'un des deux finirait par oublier une table.
        return mien(authUserId, produitId).flatMap(produits::delete);
    }

    // ================================================================
    // AIDES
    // ================================================================

    /**
     * Le motif {@code LIKE} d'une recherche, jokers ECHAPPES.
     *
     * <h3>Le defaut que cette methode ferme</h3>
     *
     * Le motif etait {@code "%" + terme + "%"}, sans echappement. Un terme
     * valant {@code %} donnait donc {@code %%%} — trois jokers — qui
     * correspond a TOUT. Et un terme valant {@code _} correspondait a
     * n'importe quel caractere.
     *
     * <p>Ce n'est pas une faille : la clause de visibilite s'applique quand
     * meme, et la taille de page est plafonnee. C'est un defaut de
     * COMPORTEMENT, et il se voit des qu'un article s'appelle « Solde -50% » ou
     * « T_shirt » : le chercher par son nom exact ne le trouve pas, tandis
     * qu'une frappe de travers ramene le catalogue.
     *
     * <h3>L'ANTISLASH D'ABORD</h3>
     *
     * Son remplacement doit preceder les deux autres, faute de quoi les
     * antislashs qu'ils introduisent seraient echappes a leur tour : le terme
     * {@code %} deviendrait {@code \\%}, c'est-a-dire un antislash litteral
     * suivi d'un joker — l'inverse du but.
     *
     * @return le motif, ou {@code null} si la recherche est vide
     */
    static String motifDeRecherche(String recherche) {
        if (recherche == null || recherche.isBlank()) {
            return null;
        }
        String terme = recherche.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + terme + "%";
    }

    private Mono<Produit> renvoyerEnModeration(Produit produit) {
        produit.setStatutModeration(StatutModeration.EN_ATTENTE);
        produit.setMotifDecision(null);
        produit.setDecidePar(null);
        produit.setDecideLe(null);
        return produits.save(produit);
    }

    private void appliquer(Produit produit, CreationProduitRequest demande) {
        produit.setNom(demande.nom());
        produit.setDescription(demande.description());
        produit.setPrix(demande.prix());
        produit.setDevise(demande.devise() == null || demande.devise().isBlank()
                ? DEVISE_PAR_DEFAUT : demande.devise().toUpperCase());
        produit.setCategorieId(demande.categorieId());
        produit.setVisibilite(demande.visibilite());
    }

    /** La boutique du compte, ou introuvable. */
    private Mono<Boutique> maBoutique(String authUserId) {
        return boutiques.findByAuthUserId(authUserId)
                .switchIfEmpty(Mono.error(MarketException.introuvable()));
    }

    /**
     * La boutique du compte, APPROUVEE.
     *
     * <p>Une boutique en attente ne peut pas creer d'article : elle produirait
     * un stock d'articles a moderer avant meme que son dossier ne soit examine.
     * Le refus est explique, parce que l'auteur peut agir dessus.
     */
    private Mono<Boutique> maBoutiqueApprouvee(String authUserId) {
        return maBoutique(authUserId)
                .flatMap(boutique -> boutique.getStatut() == StatutBoutique.APPROUVEE
                        ? Mono.just(boutique)
                        : Mono.error(RefusExplique.etatIncompatible(
                                "Votre boutique n'est pas encore approuvee. Vous pourrez "
                                        + "publier des articles des que la moderation aura "
                                        + "valide votre dossier.")));
    }

    /** Un article de MA boutique, ou introuvable. */
    private Mono<Produit> mien(String authUserId, UUID produitId) {
        return maBoutique(authUserId)
                .flatMap(boutique -> produits.findById(produitId)
                        .filter(produit -> boutique.getId().equals(produit.getBoutiqueId()))
                        // L'article d'autrui est INTROUVABLE, pas interdit :
                        // distinguer les deux dirait quels identifiants
                        // existent.
                        .switchIfEmpty(Mono.error(MarketException.introuvable())));
    }

    /**
     * Ajoute photos — et, pour une fiche, variantes et caracteristiques.
     *
     * <h3>UNE requete de photos et UN appel a media-service pour TOUTE la page</h3>
     *
     * C'est la raison d'etre de cette methode. Ecrit par produit, ce serait
     * vingt lectures et vingt appels sortants pour un ecran de catalogue.
     *
     * <h3>Une photo non signable est OMISE, jamais rendue brute</h3>
     *
     * media-service ne signe que les types autorises. Rendre l'identifiant a
     * defaut donnerait au client un lien qu'il ne peut pas suivre.
     */
    private Mono<List<ProduitResponse>> enrichir(List<Produit> liste, boolean fiche) {
        if (liste.isEmpty()) {
            return Mono.just(List.of());
        }
        List<UUID> identifiants = liste.stream().map(Produit::getId).toList();

        Mono<Map<UUID, List<ProduitPhoto>>> parProduit = Flux.fromIterable(identifiants)
                .flatMap(photos::findByProduitIdOrderByPositionAsc)
                .collectMultimap(ProduitPhoto::getProduitId)
                .map(carte -> carte.entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                e -> List.copyOf(e.getValue()))));

        return parProduit.flatMap(photosParProduit -> {
            List<String> documents = photosParProduit.values().stream()
                    .flatMap(List::stream)
                    .sorted(Comparator.comparing(ProduitPhoto::getPosition,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(ProduitPhoto::getDocumentId)
                    .distinct()
                    .toList();

            return media.liensSignes(documents).flatMap(liens ->
                    Flux.fromIterable(liste)
                            .concatMap(produit -> composer(produit,
                                    photosParProduit.getOrDefault(produit.getId(), List.of()),
                                    liens, fiche))
                            .collectList());
        });
    }

    private Mono<ProduitResponse> composer(Produit produit, List<ProduitPhoto> sesPhotos,
                                           Map<String, String> liens, boolean fiche) {
        List<String> urls = new ArrayList<>();
        sesPhotos.stream()
                .sorted(Comparator.comparing(ProduitPhoto::getPrincipale,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ProduitPhoto::getPosition,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(photo -> {
                    String url = liens.get(photo.getDocumentId());
                    if (url != null) {
                        urls.add(url);
                    }
                });

        Mono<String> nomBoutique = boutiques.findById(produit.getBoutiqueId())
                .map(Boutique::getNom).defaultIfEmpty("");

        if (!fiche) {
            return nomBoutique.map(nom -> MarketMapper.versReponse(produit, nom, urls,
                    List.of(), List.of()));
        }

        Mono<List<VarianteResponse>> sesVariantes = variantes.findByProduitId(produit.getId())
                .map(MarketMapper::versReponse).collectList();
        Mono<List<CaracteristiqueResponse>> sesCaracteristiques =
                caracteristiques.findByProduitIdOrderByPositionAsc(produit.getId())
                        .map(MarketMapper::versReponse).collectList();

        return Mono.zip(nomBoutique, sesVariantes, sesCaracteristiques)
                .map(t -> MarketMapper.versReponse(produit, t.getT1(), urls,
                        t.getT2(), t.getT3()));
    }
}
