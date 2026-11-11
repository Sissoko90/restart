package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.cache.CacheDePortee;
import com.abdatytch.market_service.dto.request.DecisionRequest;
import com.abdatytch.market_service.entities.Avis;
import com.abdatytch.market_service.entities.Boutique;
import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.enums.StatutBoutique;
import com.abdatytch.market_service.enums.StatutModeration;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.exception.RefusExplique;
import com.abdatytch.market_service.metrics.MarketMetrics;
import com.abdatytch.market_service.repository.AvisRepository;
import com.abdatytch.market_service.repository.BoutiqueRepository;
import com.abdatytch.market_service.repository.ProduitRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les trois files de moderation.
 *
 * <p>Ce qui est pince ici : le motif obligatoire au refus, les transitions
 * impossibles, l'eviction du cache apres l'ecriture, et le recalcul des
 * agregats quand un avis change d'etat.
 */
@DisplayName("ModerationServiceImpl")
class ModerationServiceImplTest {

    private static final String MODERATEUR = "admin-1";

    private BoutiqueRepository boutiques;
    private ProduitRepository produits;
    private AvisRepository avis;
    private CacheDePortee cache;
    private ModerationServiceImpl service;

    @BeforeEach
    void preparer() {
        boutiques = mock(BoutiqueRepository.class);
        produits = mock(ProduitRepository.class);
        avis = mock(AvisRepository.class);
        cache = mock(CacheDePortee.class);
        when(cache.evincer(anyString())).thenReturn(Mono.empty());
        when(boutiques.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        when(produits.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        when(avis.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        when(produits.reconcilierLesAgregats(any())).thenReturn(Mono.empty());
        service = new ModerationServiceImpl(boutiques, produits, avis, cache,
                new MarketMetrics(new SimpleMeterRegistry()));
    }

    private static Boutique boutique(StatutBoutique statut) {
        Boutique boutique = new Boutique();
        boutique.setId(UUID.randomUUID());
        boutique.setAuthUserId("vendeur-1");
        boutique.setStatut(statut);
        return boutique;
    }

    private static Produit produit() {
        Produit produit = new Produit();
        produit.setId(UUID.randomUUID());
        produit.setStatutModeration(StatutModeration.EN_ATTENTE);
        return produit;
    }

    private static Avis unAvis() {
        Avis avis = new Avis();
        avis.setId(UUID.randomUUID());
        avis.setProduitId(UUID.randomUUID());
        avis.setAuthUserId("client-1");
        avis.setNote((short) 4);
        avis.setStatutModeration(StatutModeration.EN_ATTENTE);
        return avis;
    }

    // ==================================================================
    @Nested
    @DisplayName("Le motif")
    class Motif {

        /**
         * Verifie dans le SERVICE et non par une annotation.
         *
         * <p>La regle depend du SENS de la decision : {@code @NotBlank} sur le
         * champ refuserait aussi les approbations, qui n'ont rien a expliquer.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = DecisionRequest.Sens.class, names = {"REJETER", "SUSPENDRE"})
        @DisplayName("est obligatoire au refus et a la suspension")
        void obligatoireAuRefus(DecisionRequest.Sens sens) {
            assertThatThrownBy(() -> service.deciderBoutique(MODERATEUR, UUID.randomUUID(),
                    new DecisionRequest(sens, "  ")))
                    .isInstanceOf(RefusExplique.class)
                    .hasMessageContaining("doit porter un motif");
        }

        @Test
        @DisplayName("est facultatif a l'approbation")
        void facultatifALApprobation() {
            Boutique b = boutique(StatutBoutique.EN_ATTENTE);
            when(boutiques.findById(b.getId())).thenReturn(Mono.just(b));

            StepVerifier.create(service.deciderBoutique(MODERATEUR, b.getId(),
                            new DecisionRequest(DecisionRequest.Sens.APPROUVER, null)))
                    .assertNext(r -> assertThat(r.statut())
                            .isEqualTo(StatutBoutique.APPROUVEE))
                    .verifyComplete();
        }

        @Test
        @DisplayName("est RENDU au vendeur")
        void motifRendu() {
            Boutique b = boutique(StatutBoutique.EN_ATTENTE);
            when(boutiques.findById(b.getId())).thenReturn(Mono.just(b));

            StepVerifier.create(service.deciderBoutique(MODERATEUR, b.getId(),
                            new DecisionRequest(DecisionRequest.Sens.REJETER,
                                    "RCCM illisible")))
                    .assertNext(r -> assertThat(r.motifDecision())
                            .withFailMessage("""
                                    Le motif n'est pas rendu au vendeur.

                                    Un refus dont on ignore la cause se represente a \
                                    l'identique, et fait deux fois le travail de \
                                    l'equipe de moderation.""")
                            .isEqualTo("RCCM illisible"))
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les boutiques")
    class Boutiques {

        /**
         * L'eviction suit l'ECRITURE, jamais l'inverse.
         *
         * <p>Evincer d'abord laisserait une fenetre ou une requete concurrente
         * recalculerait l'ancienne portee et la remettrait en cache.
         */
        @Test
        @DisplayName("approuver evince la portee du vendeur")
        void approbationEvince() {
            Boutique b = boutique(StatutBoutique.EN_ATTENTE);
            when(boutiques.findById(b.getId())).thenReturn(Mono.just(b));

            service.deciderBoutique(MODERATEUR, b.getId(),
                    new DecisionRequest(DecisionRequest.Sens.APPROUVER, null)).block();

            verify(boutiques).save(any());
            verify(cache).evincer("vendeur-1");
        }

        @Test
        @DisplayName("inscrit QUI a decide : une decision anonyme ne se discute pas")
        void tracabilite() {
            Boutique b = boutique(StatutBoutique.EN_ATTENTE);
            when(boutiques.findById(b.getId())).thenReturn(Mono.just(b));

            service.deciderBoutique(MODERATEUR, b.getId(),
                    new DecisionRequest(DecisionRequest.Sens.APPROUVER, null)).block();

            assertThat(b.getDecidePar()).isEqualTo(MODERATEUR);
            assertThat(b.getDecideLe()).isNotNull();
        }

        /**
         * Suspendre suppose une boutique DEJA approuvee.
         *
         * <p>Suspendre un dossier en attente n'a pas de sens : il n'a jamais
         * rien publie, et l'etat resultant serait indistinguable d'un refus.
         */
        @Test
        @DisplayName("ne suspend QUE ce qui est approuve")
        void suspensionBornee() {
            Boutique b = boutique(StatutBoutique.EN_ATTENTE);
            when(boutiques.findById(b.getId())).thenReturn(Mono.just(b));

            StepVerifier.create(service.deciderBoutique(MODERATEUR, b.getId(),
                            new DecisionRequest(DecisionRequest.Sens.SUSPENDRE, "fraude")))
                    .expectErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(RefusExplique.class)
                            .hasMessageContaining("Seule une boutique approuvee"))
                    .verify();
        }

        @Test
        @DisplayName("une boutique inconnue est introuvable")
        void inconnue() {
            UUID id = UUID.randomUUID();
            when(boutiques.findById(id)).thenReturn(Mono.empty());

            StepVerifier.create(service.deciderBoutique(MODERATEUR, id,
                            new DecisionRequest(DecisionRequest.Sens.APPROUVER, null)))
                    .expectError(MarketException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les articles et les avis")
    class ArticlesEtAvis {

        @Test
        @DisplayName("un article ne se SUSPEND pas : il se rejette")
        void articleNonSuspendable() {
            assertThatThrownBy(() -> service.deciderProduit(MODERATEUR, UUID.randomUUID(),
                    new DecisionRequest(DecisionRequest.Sens.SUSPENDRE, "motif")))
                    .isInstanceOf(RefusExplique.class)
                    .hasMessageContaining("ne se suspend pas");
        }

        @Test
        @DisplayName("un avis non plus")
        void avisNonSuspendable() {
            assertThatThrownBy(() -> service.deciderAvis(MODERATEUR, UUID.randomUUID(),
                    new DecisionRequest(DecisionRequest.Sens.SUSPENDRE, "motif")))
                    .isInstanceOf(RefusExplique.class);
        }

        @Test
        @DisplayName("approuver un article le rend publiable")
        void approbationDArticle() {
            Produit p = produit();
            when(produits.findById(p.getId())).thenReturn(Mono.just(p));

            StepVerifier.create(service.deciderProduit(MODERATEUR, p.getId(),
                            new DecisionRequest(DecisionRequest.Sens.APPROUVER, null)))
                    .assertNext(r -> assertThat(r.statutModeration())
                            .isEqualTo(StatutModeration.APPROUVE))
                    .verifyComplete();
        }

        /**
         * La note moyenne change avec l'etat d'un avis.
         *
         * <p>Recalculee et non incrementee : un increment suppose connaitre
         * l'etat precedent, et se desynchronise des qu'une decision est reprise.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = DecisionRequest.Sens.class, names = {"APPROUVER", "REJETER"})
        @DisplayName("toute decision sur un avis recalcule les agregats")
        void agregatsRecalcules(DecisionRequest.Sens sens) {
            Avis a = unAvis();
            when(avis.findById(a.getId())).thenReturn(Mono.just(a));

            service.deciderAvis(MODERATEUR, a.getId(),
                    new DecisionRequest(sens, "motif")).block();

            verify(produits).reconcilierLesAgregats(a.getProduitId());
        }

        /**
         * Le libelle d'auteur ne doit pas etre l'identifiant.
         *
         * <p>Il relierait chaque avis au compte qui l'a ecrit, sur une page
         * publique.
         */
        @Test
        @DisplayName("un avis rendu ne porte pas l'identifiant de son auteur")
        void auteurAnonymise() {
            Avis a = unAvis();
            when(avis.findById(a.getId())).thenReturn(Mono.just(a));

            StepVerifier.create(service.deciderAvis(MODERATEUR, a.getId(),
                            new DecisionRequest(DecisionRequest.Sens.APPROUVER, null)))
                    .assertNext(r -> assertThat(r.auteur())
                            .isNotEqualTo("client-1")
                            .contains("…"))
                    .verifyComplete();
        }
    }
}
