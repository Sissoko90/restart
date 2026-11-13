package com.abdatytch.market_service.repository;

import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Les produits, TOUJOURS filtres par la portee du demandeur.
 *
 * <h2>Aucune methode ne rend un produit sans portee</h2>
 *
 * Y compris la lecture unitaire. Une methode « technique » qui rendrait un
 * produit par son identifiant seul finirait par etre appelee depuis un
 * controleur — c'est ainsi que naissent les failles d'acces direct a un objet,
 * et ce depot en a deja corrige sur deux services.
 *
 * <p>Les seules exceptions sont les ecritures de moderation, qui portent leur
 * propre controle de role, et la reconciliation des agregats d'avis, qui ne
 * rend aucun produit.
 *
 * <h2>La clause de visibilite n'est pas recopiee</h2>
 *
 * Elle vient de {@link PorteeDuCatalogue#CLAUSE_SQL}, concatenee a la
 * compilation. {@code RegleDeVisibiliteUniqueTest} refuse qu'une requete de ce
 * module filtre sur {@code visibilite} ou {@code statut_moderation} autrement.
 */
@Repository
public interface ProduitRepository extends ReactiveCrudRepository<Produit, UUID> {

    /**
     * Le catalogue, page par page.
     *
     * <p>{@code LIMIT}/{@code OFFSET} et non un curseur : l'ecran attend un
     * numero de page. Le cout de l'OFFSET profond est connu et accepte tant que
     * la recherche reste filtree par pays et categorie.
     */
    @Query("SELECT p.* FROM produit p JOIN boutique b ON b.id = p.boutique_id "
            + "WHERE " + PorteeDuCatalogue.CLAUSE_SQL
            + " AND (:categorieId IS NULL OR p.categorie_id = :categorieId) "
            + "ORDER BY p.created_at DESC LIMIT :limite OFFSET :decalage")
    Flux<Produit> catalogue(boolean administrateur, UUID boutiqueId, boolean professionnel,
                            String pays, UUID categorieId, int limite, int decalage);

    /** Le nombre total, pour que l'ecran sache combien de pages il reste. */
    @Query("SELECT COUNT(*) FROM produit p JOIN boutique b ON b.id = p.boutique_id "
            + "WHERE " + PorteeDuCatalogue.CLAUSE_SQL
            + " AND (:categorieId IS NULL OR p.categorie_id = :categorieId)")
    Mono<Long> compterLeCatalogue(boolean administrateur, UUID boutiqueId,
                                  boolean professionnel, String pays, UUID categorieId);

    /**
     * Recherche plein texte simple sur le nom et la description.
     *
     * <p>{@code ILIKE} et non un index textuel : le volume ne le justifie pas
     * encore, et un index GIN sur deux colonnes se paie a chaque ecriture. Le
     * jour ou cela ne suffira plus, c'est ici qu'il faudra regarder — pas dans
     * la clause de visibilite, qui ne change pas.
     *
     * <h3>{@code ESCAPE} est EXPLICITE</h3>
     *
     * PostgreSQL prend deja l'antislash par defaut, donc cette clause ne change
     * rien aujourd'hui. Elle est ecrite parce que le caractere d'echappement
     * est une convention du moteur, et que {@code ProduitServiceImpl} echappe
     * de son cote en SUPPOSANT laquelle : enoncer l'hypothese la rend
     * verifiable, et {@code RechercheEchappeeTest} la verifie.
     */
    @Query("SELECT p.* FROM produit p JOIN boutique b ON b.id = p.boutique_id "
            + "WHERE " + PorteeDuCatalogue.CLAUSE_SQL
            + " AND (p.nom ILIKE :motif ESCAPE '\\' "
            + "     OR p.description ILIKE :motif ESCAPE '\\') "
            + "ORDER BY p.created_at DESC LIMIT :limite OFFSET :decalage")
    Flux<Produit> rechercher(boolean administrateur, UUID boutiqueId, boolean professionnel,
                             String pays, String motif, int limite, int decalage);

    /**
     * Le nombre de resultats de la RECHERCHE.
     *
     * <h3>Le defaut que cette methode ferme</h3>
     *
     * {@code compterLeCatalogue} etait appele meme quand une recherche etait
     * active : il ignore le terme, donc il rendait le total du catalogue
     * ENTIER. Une recherche ramenant deux articles annoncait cinquante pages,
     * et l'ecran en affichait quarante-neuf vides.
     *
     * <p>Le COUNT porte donc exactement les memes conditions que la liste —
     * clause de visibilite comprise.
     */
    @Query("SELECT COUNT(*) FROM produit p JOIN boutique b ON b.id = p.boutique_id "
            + "WHERE " + PorteeDuCatalogue.CLAUSE_SQL
            + " AND (p.nom ILIKE :motif ESCAPE '\\' "
            + "     OR p.description ILIKE :motif ESCAPE '\\')")
    Mono<Long> compterLaRecherche(boolean administrateur, UUID boutiqueId,
                                  boolean professionnel, String pays, String motif);

    /**
     * Un produit par son identifiant, SI la portee le permet.
     *
     * <p>Rend vide quand elle ne le permet pas, ce que la couche web traduit en
     * 404 — jamais 403.
     */
    @Query("SELECT p.* FROM produit p JOIN boutique b ON b.id = p.boutique_id "
            + "WHERE p.id = :id AND " + PorteeDuCatalogue.CLAUSE_SQL)
    Mono<Produit> parIdentifiantVisible(UUID id, boolean administrateur, UUID boutiqueId,
                                        boolean professionnel, String pays);

    /** La vitrine d'une boutique, telle que son proprietaire la voit. */
    @Query("SELECT * FROM produit WHERE boutique_id = :boutiqueId "
            + "ORDER BY created_at DESC LIMIT :limite OFFSET :decalage")
    Flux<Produit> parBoutique(UUID boutiqueId, int limite, int decalage);

    /** La file de moderation, les plus anciens d'abord. */
    @Query("SELECT * FROM produit WHERE statut_moderation = 'EN_ATTENTE' "
            + "ORDER BY created_at LIMIT :limite OFFSET :decalage")
    Flux<Produit> aModerer(int limite, int decalage);

    /**
     * Recalcule les agregats d'avis d'un produit.
     *
     * <p>Appelee apres chaque ecriture d'avis approuve. Elle recalcule au lieu
     * d'incrementer : un increment suppose connaitre l'etat precedent, et se
     * desynchronise des qu'un avis est modere apres coup.
     */
    @Query("""
            UPDATE produit SET
                note_moyenne = COALESCE((SELECT ROUND(AVG(note), 2) FROM avis
                    WHERE produit_id = :produitId AND statut_moderation = 'APPROUVE'), 0),
                nombre_avis = (SELECT COUNT(*) FROM avis
                    WHERE produit_id = :produitId AND statut_moderation = 'APPROUVE'),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :produitId
            """)
    Mono<Void> reconcilierLesAgregats(UUID produitId);

    /**
     * Propage le pays de la boutique a ses produits.
     *
     * <p>Le pays est denormalise sur le produit pour que l'index du catalogue
     * le couvre. Une boutique qui change de pays doit donc propager — c'est une
     * operation de moderation, pas une mise a jour courante.
     */
    @Query("UPDATE produit SET pays = :pays, updated_at = CURRENT_TIMESTAMP "
            + "WHERE boutique_id = :boutiqueId")
    Mono<Void> propagerLePays(UUID boutiqueId, String pays);

    /** Le nombre d'articles en attente, pour la metrique de file. */
    @Query("SELECT COUNT(*) FROM produit WHERE statut_moderation = 'EN_ATTENTE'")
    Mono<Long> compterAModerer();
}
