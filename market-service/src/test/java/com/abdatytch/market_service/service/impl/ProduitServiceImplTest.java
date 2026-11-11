package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.client.ClientMedia;
import com.abdatytch.market_service.dto.request.CreationProduitRequest;
import com.abdatytch.market_service.dto.request.PhotoRequest;
import com.abdatytch.market_service.entities.Boutique;
import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.entities.ProduitPhoto;
import com.abdatytch.market_service.enums.StatutBoutique;
import com.abdatytch.market_service.enums.StatutModeration;
import com.abdatytch.market_service.enums.Visibilite;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.exception.RefusExplique;
import com.abdatytch.market_service.repository.BoutiqueRepository;
import com.abdatytch.market_service.repository.ProduitCaracteristiqueRepository;
import com.abdatytch.market_service.repository.ProduitPhotoRepository;
import com.abdatytch.market_service.repository.ProduitRepository;
import com.abdatytch.market_service.repository.ProduitVarianteRepository;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le catalogue et la gestion des articles.
 *
 * <p>La regle centrale pincee ici : <b>toute ecriture repart EN_ATTENTE</b>.
 * Sans elle, il suffirait de faire approuver un article anodin puis de le
 * remplacer, et la moderation ne servirait a rien.
 */
@DisplayName("ProduitServiceImpl")
class ProduitServiceImplTest {

    private static final String MOI = "u-1";
    private static final UUID MA_BOUTIQUE = UUID.randomUUID();

    private ProduitRepository produits;
    private ProduitPhotoRepository photos;
    private ProduitVarianteRepository variantes;
    private ProduitCaracteristiqueRepository caracteristiques;
    private BoutiqueRepository boutiques;
    private ClientMedia media;
    private ProduitServiceImpl service;

    /** Les identifiants observes a l'entree de save(), avant toute assignation. */
    private final List<UUID> identifiantsALEcriture = new java.util.ArrayList<>();

    @BeforeEach
    void preparer() {
        produits = mock(ProduitRepository.class);
        photos = mock(ProduitPhotoRepository.class);
        variantes = mock(ProduitVarianteRepository.class);
        caracteristiques = mock(ProduitCaracteristiqueRepository.class);
        boutiques = mock(BoutiqueRepository.class);
        media = mock(ClientMedia.class);

        // Le simulacre imite la BASE : elle rend la ligne AVEC son
        // identifiant. Sans cela, l'enrichissement recevrait un identifiant nul
        // et echouerait sur « the iterator returned a null value » — un echec
        // du test, pas du code.
        //
        // L'identifiant au moment de l'ECRITURE est releve avant d'etre pose :
        // c'est lui qui dit si le service a respecte la regle « ne jamais
        // appeler setId() avant une ecriture ».
        identifiantsALEcriture.clear();
        when(produits.save(any())).thenAnswer(a -> {
            Produit produit = a.getArgument(0);
            identifiantsALEcriture.add(produit.getId());
            if (produit.getId() == null) {
                produit.setId(UUID.randomUUID());
            }
            return Mono.just(produit);
        });
        when(photos.findByProduitIdOrderByPositionAsc(any())).thenReturn(Flux.empty());
        when(photos.deleteByProduitId(any())).thenReturn(Mono.empty());
        when(photos.saveAll(anyList())).thenReturn(Flux.empty());
        when(variantes.findByProduitId(any())).thenReturn(Flux.empty());
        when(caracteristiques.findByProduitIdOrderByPositionAsc(any())).thenReturn(Flux.empty());
        when(media.liensSignes(anyList())).thenReturn(Mono.just(Map.of()));
        // any(UUID.class) et non any() : ReactiveCrudRepository declare aussi
        // findById(Publisher), donc la surcharge serait ambigue.
        when(boutiques.findById(any(UUID.class)))
                .thenReturn(Mono.just(boutique(StatutBoutique.APPROUVEE)));

        service = new ProduitServiceImpl(produits, photos, variantes, caracteristiques,
                boutiques, media);
    }

    private static Boutique boutique(StatutBoutique statut) {
        Boutique boutique = new Boutique();
        boutique.setId(MA_BOUTIQUE);
        boutique.setAuthUserId(MOI);
        boutique.setNom("Sankadi");
        boutique.setStatut(statut);
        boutique.setPays("ML");
        return boutique;
    }

    private static CreationProduitRequest demande() {
        return new CreationProduitRequest("Bazin riche", "Qualite superieure",
                25_000L, null, null, Visibilite.PUBLIQUE);
    }

    private static Produit approuve() {
        Produit produit = new Produit();
        produit.setId(UUID.randomUUID());
        produit.setBoutiqueId(MA_BOUTIQUE);
        produit.setNom("Bazin");
        produit.setPrix(1000L);
        produit.setPays("ML");
        produit.setVisibilite(Visibilite.PUBLIQUE);
        produit.setStatutModeration(StatutModeration.APPROUVE);
        produit.setMotifDecision("ancien motif");
        produit.setActif(Boolean.TRUE);
        return produit;
    }

    // ==================================================================
    @Nested
    @DisplayName("La creation")
    class Creation {

        @Test
        @DisplayName("exige une boutique APPROUVEE, et le dit")
        void boutiqueApprouveeExigee() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(boutique(StatutBoutique.EN_ATTENTE)));

            StepVerifier.create(service.creer(MOI, demande()))
                    .expectErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(RefusExplique.class)
                            .hasMessageContaining("pas encore approuvee"))
                    .verify();
            verify(produits, never()).save(any());
        }

        @Test
        @DisplayName("l'article NAIT en attente, actif, au pays de la boutique")
        void etatInitial() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(boutique(StatutBoutique.APPROUVEE)));

            service.creer(MOI, demande()).block();

            var capture = ArgumentCaptor.forClass(Produit.class);
            verify(produits).save(capture.capture());
            Produit cree = capture.getValue();
            assertThat(cree.getStatutModeration()).isEqualTo(StatutModeration.EN_ATTENTE);
            assertThat(cree.getActif()).isTrue();
            assertThat(cree.getPays())
                    .withFailMessage("""
                            Le pays ne vient pas de la boutique.

                            Il decide de la visibilite : le laisser venir d'ailleurs \
                            permettrait d'elargir sa portee.""")
                    .isEqualTo("ML");
            assertThat(identifiantsALEcriture)
                    .withFailMessage("""
                            L'identifiant a ete pose AVANT l'ecriture.

                            R2DBC decide qu'une ligne est neuve a l'identifiant nul. \
                            Un identifiant deja pose fait emettre un UPDATE pour une \
                            ligne jamais inseree, et le save() est sans effet.""")
                    .containsExactly((UUID) null);
        }

        @Test
        @DisplayName("sans devise, applique celle du marche plutot que nul")
        void devisePardefaut() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(boutique(StatutBoutique.APPROUVEE)));

            service.creer(MOI, demande()).block();

            var capture = ArgumentCaptor.forClass(Produit.class);
            verify(produits).save(capture.capture());
            assertThat(capture.getValue().getDevise())
                    .withFailMessage("La devise est nulle : la base la refuse.")
                    .isEqualTo("XOF");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Toute ecriture repart EN ATTENTE")
    class RetourEnModeration {

        @Test
        @DisplayName("modifier un article approuve efface aussi son ancien motif")
        void modificationRenvoie() {
            Produit p = approuve();
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(boutique(StatutBoutique.APPROUVEE)));
            when(produits.findById(any(UUID.class))).thenReturn(Mono.just(p));

            service.modifier(MOI, p.getId(), demande()).block();

            assertThat(p.getStatutModeration()).isEqualTo(StatutModeration.EN_ATTENTE);
            assertThat(p.getMotifDecision())
                    .withFailMessage("""
                            L'ancien motif de decision survit a la modification.

                            Le vendeur lirait le motif d'un refus qui ne porte plus \
                            sur ce qu'il vient d'ecrire.""")
                    .isNull();
            assertThat(p.getDecidePar()).isNull();
        }

        @Test
        @DisplayName("remplacer les photos renvoie aussi en moderation")
        void photosRenvoient() {
            Produit p = approuve();
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(boutique(StatutBoutique.APPROUVEE)));
            when(produits.findById(any(UUID.class))).thenReturn(Mono.just(p));

            service.remplacerLesPhotos(MOI, p.getId(),
                    List.of(new PhotoRequest("doc-1", 0, true))).block();

            assertThat(p.getStatutModeration()).isEqualTo(StatutModeration.EN_ATTENTE);
        }

        /**
         * LA SEULE ECRITURE QUI NE RENVOIE PAS.
         *
         * <p>Retirer un article de la vitrine ne change pas son contenu. C'est
         * pour cela que l'operation a son propre endpoint.
         */
        @Test
        @DisplayName("retirer de la vitrine ne renvoie PAS en moderation")
        void activationNeRenvoiePas() {
            Produit p = approuve();
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(boutique(StatutBoutique.APPROUVEE)));
            when(produits.findById(any(UUID.class))).thenReturn(Mono.just(p));

            service.changerLActivation(MOI, p.getId(), false).block();

            assertThat(p.getActif()).isFalse();
            assertThat(p.getStatutModeration())
                    .withFailMessage("""
                            Retirer un article de la vitrine le renvoie en moderation.

                            Son contenu n'a pas change : la file se remplirait \
                            d'articles deja valides.""")
                    .isEqualTo(StatutModeration.APPROUVE);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La propriete")
    class Propriete {

        @Test
        @DisplayName("l'article d'autrui est INTROUVABLE, pas interdit")
        void articleDAutrui() {
            Produit sien = approuve();
            sien.setBoutiqueId(UUID.randomUUID());
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(boutique(StatutBoutique.APPROUVEE)));
            when(produits.findById(any(UUID.class))).thenReturn(Mono.just(sien));

            StepVerifier.create(service.modifier(MOI, sien.getId(), demande()))
                    .expectErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(MarketException.class)
                            .hasMessageContaining("404"))
                    .verify();
        }

        @Test
        @DisplayName("sans boutique du tout : introuvable")
        void sansBoutique() {
            when(boutiques.findByAuthUserId(MOI)).thenReturn(Mono.empty());

            StepVerifier.create(service.modifier(MOI, UUID.randomUUID(), demande()))
                    .expectError(MarketException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les photos")
    class Photos {

        /**
         * La base porte un index unique PARTIEL sur la photo principale.
         *
         * <p>Le refus est donne AVANT l'ecriture pour que le message parle de
         * l'intention et non d'une contrainte.
         */
        @Test
        @DisplayName("refuse DEUX photos principales, avant toute ecriture")
        void deuxPrincipales() {
            StepVerifier.create(service.remplacerLesPhotos(MOI, UUID.randomUUID(),
                            List.of(new PhotoRequest("a", 0, true),
                                    new PhotoRequest("b", 1, true))))
                    .expectErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(RefusExplique.class)
                            .hasMessageContaining("Une seule photo"))
                    .verify();
            verify(photos, never()).deleteByProduitId(any());
        }

        @Test
        @DisplayName("ne garde que l'identifiant du document")
        void seulementLIdentifiant() {
            Produit p = approuve();
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(boutique(StatutBoutique.APPROUVEE)));
            when(produits.findById(any(UUID.class))).thenReturn(Mono.just(p));

            service.remplacerLesPhotos(MOI, p.getId(),
                    List.of(new PhotoRequest("doc-7", null, false))).block();

            var capture = ArgumentCaptor.forClass(List.class);
            verify(photos).saveAll(capture.capture());
            List<ProduitPhoto> posees = capture.getValue();
            assertThat(posees).hasSize(1);
            assertThat(posees.get(0).getDocumentId()).isEqualTo("doc-7");
            assertThat(posees.get(0).getPosition())
                    .withFailMessage("Sans position fournie, l'ordre d'envoi fait foi.")
                    .isZero();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le catalogue")
    class Catalogue {

        /**
         * Les quatre champs de la portee arrivent jusqu'a la requete.
         *
         * <p>Si l'un d'eux etait perdu en route, la clause SQL s'evaluerait sur
         * une valeur par defaut — et montrerait trop.
         */
        @Test
        @DisplayName("transmet la portee entiere au depot")
        void porteeTransmise() {
            var portee = new PorteeDuCatalogue("u-1", "ML", MA_BOUTIQUE, true, false);
            when(produits.catalogue(anyBoolean(), any(), anyBoolean(), anyString(),
                    any(), anyInt(), anyInt())).thenReturn(Flux.empty());
            when(produits.compterLeCatalogue(anyBoolean(), any(), anyBoolean(),
                    anyString(), any())).thenReturn(Mono.just(0L));

            service.catalogue(portee, null, null, 2, 10).block();

            verify(produits).catalogue(eq(false), eq(MA_BOUTIQUE), eq(true), eq("ML"),
                    eq(null), eq(10), eq(20));
            verify(produits).compterLeCatalogue(eq(false), eq(MA_BOUTIQUE), eq(true),
                    eq("ML"), eq(null));
        }

        @Test
        @DisplayName("une fiche hors portee est introuvable")
        void ficheHorsPortee() {
            var portee = new PorteeDuCatalogue("u-1", "SN", null, false, false);
            UUID id = UUID.randomUUID();
            when(produits.parIdentifiantVisible(eq(id), anyBoolean(), any(),
                    anyBoolean(), anyString())).thenReturn(Mono.empty());

            StepVerifier.create(service.fiche(portee, id))
                    .expectError(MarketException.class)
                    .verify();
        }
    }
}
