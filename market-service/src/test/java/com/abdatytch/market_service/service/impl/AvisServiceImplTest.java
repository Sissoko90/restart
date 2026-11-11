package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.dto.request.AvisRequest;
import com.abdatytch.market_service.entities.Avis;
import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.enums.StatutModeration;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.exception.RefusExplique;
import com.abdatytch.market_service.repository.AvisRepository;
import com.abdatytch.market_service.repository.ProduitRepository;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Les avis clients : on ne note que ce qu'on a le droit de voir. */
@DisplayName("AvisServiceImpl")
class AvisServiceImplTest {

    private static final UUID PRODUIT = UUID.randomUUID();
    private static final UUID MA_BOUTIQUE = UUID.randomUUID();

    private AvisRepository avis;
    private ProduitRepository produits;
    private AvisServiceImpl service;

    @BeforeEach
    void preparer() {
        avis = mock(AvisRepository.class);
        produits = mock(ProduitRepository.class);
        when(avis.save(any())).thenAnswer(a -> {
            Avis sauve = a.getArgument(0);
            if (sauve.getId() == null) {
                sauve.setId(UUID.randomUUID());
            }
            return Mono.just(sauve);
        });
        when(produits.reconcilierLesAgregats(any())).thenReturn(Mono.empty());
        service = new AvisServiceImpl(avis, produits);
    }

    private static Produit produit(UUID boutique) {
        Produit produit = new Produit();
        produit.setId(PRODUIT);
        produit.setBoutiqueId(boutique);
        produit.setStatutModeration(StatutModeration.APPROUVE);
        produit.setActif(Boolean.TRUE);
        return produit;
    }

    private void visible(UUID boutiqueDuProduit) {
        when(produits.parIdentifiantVisible(eq(PRODUIT), anyBoolean(), any(),
                anyBoolean(), any())).thenReturn(Mono.just(produit(boutiqueDuProduit)));
    }

    private static PorteeDuCatalogue portee(UUID maBoutique) {
        return new PorteeDuCatalogue("client-1", "ML", maBoutique, maBoutique != null, false);
    }

    @Test
    @DisplayName("on ne note pas un article HORS de sa portee")
    void horsPortee() {
        when(produits.parIdentifiantVisible(eq(PRODUIT), anyBoolean(), any(),
                anyBoolean(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.deposer(portee(null), PRODUIT,
                        new AvisRequest((short) 5, "parfait")))
                .expectError(MarketException.class)
                .verify();
        verify(avis, never()).save(any());
    }

    /**
     * Un vendeur ne note pas sa propre boutique.
     *
     * <p>Le refus est EXPLIQUE : c'est une regle comprehensible, pas une
     * suspicion.
     */
    @Test
    @DisplayName("un vendeur ne note pas sa propre boutique")
    void pasSaPropreBoutique() {
        visible(MA_BOUTIQUE);

        StepVerifier.create(service.deposer(portee(MA_BOUTIQUE), PRODUIT,
                        new AvisRequest((short) 5, "excellent")))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(RefusExplique.class)
                        .hasMessageContaining("votre propre boutique"))
                .verify();
    }

    @Test
    @DisplayName("un avis nait EN ATTENTE")
    void naitEnAttente() {
        visible(UUID.randomUUID());
        when(avis.findByProduitIdAndAuthUserId(PRODUIT, "client-1")).thenReturn(Mono.empty());

        StepVerifier.create(service.deposer(portee(null), PRODUIT,
                        new AvisRequest((short) 4, "bien")))
                .assertNext(r -> assertThat(r.statutModeration())
                        .isEqualTo(StatutModeration.EN_ATTENTE))
                .verifyComplete();
    }

    /**
     * Un second envoi MODIFIE le premier.
     *
     * <p>La base n'en accepte qu'un par personne et par produit. Echouer sur
     * la contrainte donnerait un message qui ne dirait rien a personne.
     */
    @Test
    @DisplayName("un second envoi remplace le premier et repart en moderation")
    void secondEnvoiRemplace() {
        visible(UUID.randomUUID());
        Avis existant = new Avis();
        existant.setId(UUID.randomUUID());
        existant.setProduitId(PRODUIT);
        existant.setAuthUserId("client-1");
        existant.setNote((short) 5);
        existant.setStatutModeration(StatutModeration.APPROUVE);
        existant.setMotifDecision("ancien");
        when(avis.findByProduitIdAndAuthUserId(PRODUIT, "client-1"))
                .thenReturn(Mono.just(existant));

        service.deposer(portee(null), PRODUIT, new AvisRequest((short) 1, "finalement non"))
                .block();

        var capture = ArgumentCaptor.forClass(Avis.class);
        verify(avis).save(capture.capture());
        Avis sauve = capture.getValue();
        assertThat(sauve.getId())
                .withFailMessage("Un second avis est CREE au lieu de remplacer le "
                        + "premier : la contrainte d'unicite le refusera.")
                .isEqualTo(existant.getId());
        assertThat(sauve.getNote()).isEqualTo((short) 1);
        assertThat(sauve.getStatutModeration())
                .withFailMessage("""
                        Un avis approuve puis remplace reste approuve.

                        Il suffirait d'ecrire un commentaire anodin, de le faire \\
                        approuver, puis de le remplacer.""")
                .isEqualTo(StatutModeration.EN_ATTENTE);
        assertThat(sauve.getMotifDecision()).isNull();
    }

    /**
     * Les agregats sont recalcules MEME quand l'avis repasse en attente.
     *
     * <p>Un avis approuve qui redevient en attente ne doit plus compter dans la
     * moyenne : sans ce recalcul, la note resterait celle d'un avis qui n'est
     * plus publie.
     */
    @Test
    @DisplayName("le depot recalcule la note moyenne du produit")
    void agregatsRecalcules() {
        visible(UUID.randomUUID());
        when(avis.findByProduitIdAndAuthUserId(PRODUIT, "client-1")).thenReturn(Mono.empty());

        service.deposer(portee(null), PRODUIT, new AvisRequest((short) 3, null)).block();

        verify(produits).reconcilierLesAgregats(PRODUIT);
    }

    @Test
    @DisplayName("son propre avis est lisible meme en attente")
    void monAvisEnAttente() {
        Avis mien = new Avis();
        mien.setId(UUID.randomUUID());
        mien.setProduitId(PRODUIT);
        mien.setAuthUserId("client-1");
        mien.setNote((short) 2);
        mien.setStatutModeration(StatutModeration.EN_ATTENTE);
        when(avis.findByProduitIdAndAuthUserId(PRODUIT, "client-1")).thenReturn(Mono.just(mien));

        StepVerifier.create(service.mien("client-1", PRODUIT))
                .assertNext(r -> assertThat(r.statutModeration())
                        .isEqualTo(StatutModeration.EN_ATTENTE))
                .verifyComplete();
    }

    @Test
    @DisplayName("sans avis de sa part : introuvable")
    void sansAvis() {
        when(avis.findByProduitIdAndAuthUserId(PRODUIT, "client-1")).thenReturn(Mono.empty());
        StepVerifier.create(service.mien("client-1", PRODUIT))
                .expectError(MarketException.class)
                .verify();
    }
}
