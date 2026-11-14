package com.abdatytch.order_service.service.impl;

import com.abdatytch.order_service.client.ClientMarketCatalogue;
import com.abdatytch.order_service.client.ClientMarketStock;
import com.abdatytch.order_service.dto.request.AjoutAuPanierRequest;
import com.abdatytch.order_service.entities.Panier;
import com.abdatytch.order_service.entities.PanierLigne;
import com.abdatytch.order_service.enums.StatutPanier;
import com.abdatytch.order_service.exception.OrderException;
import com.abdatytch.order_service.exception.RefusExplique;
import com.abdatytch.order_service.repository.PanierLigneRepository;
import com.abdatytch.order_service.repository.PanierRepository;
import com.abdatytch.order_service.security.PorteeDeLAcheteur;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le panier, mis a l'epreuve.
 *
 * <h2>Ce que ces tests fixent</h2>
 *
 * Le controle d'APPARTENANCE d'une ligne — sans lui, n'importe qui modifie le
 * panier d'autrui en citant un identifiant — et le fait qu'une ligne
 * indisponible soit RENDUE A PART plutot que supprimee.
 */
@DisplayName("PanierServiceImpl")
class PanierServiceImplTest {

    private static final String ACHETEUR = "acheteur-1";
    private static final PorteeDeLAcheteur PORTEE =
            new PorteeDeLAcheteur(ACHETEUR, "ML", null, false);

    private PanierRepository paniers;
    private PanierLigneRepository lignes;
    private ClientMarketCatalogue catalogue;
    private PanierServiceImpl service;

    private Panier panier;

    @BeforeEach
    void preparer() {
        paniers = mock(PanierRepository.class);
        lignes = mock(PanierLigneRepository.class);
        catalogue = mock(ClientMarketCatalogue.class);
        service = new PanierServiceImpl(paniers, lignes, catalogue, 50, 20);

        panier = new Panier();
        panier.setId(UUID.randomUUID());
        panier.setAuthUserId(ACHETEUR);
        panier.setStatut(StatutPanier.OUVERT);
    }

    private PanierLigne ligne(UUID panierId, UUID varianteId, int quantite) {
        PanierLigne l = new PanierLigne();
        l.setId(UUID.randomUUID());
        l.setPanierId(panierId);
        l.setProduitId(UUID.randomUUID());
        l.setVarianteId(varianteId);
        l.setQuantite(quantite);
        return l;
    }

    private ClientMarketCatalogue.LigneDecrite decrite(PanierLigne l, boolean disponible) {
        return new ClientMarketCatalogue.LigneDecrite(
                l.getProduitId(), l.getVarianteId(), UUID.randomUUID(), "ML",
                "Bazin", "rouge", "M", "SKU-1",
                disponible ? 5000L : null, disponible ? "XOF" : null,
                l.getQuantite(), disponible, disponible ? null : "Rupture");
    }

    @Nested
    @DisplayName("L'affichage")
    class Affichage {

        /**
         * Demander son panier quand on n'en a pas n'est pas une faute. Un 404
         * obligerait chaque client a traiter ce cas a part, et le premier qui
         * l'oublie affiche une erreur a un acheteur qui n'a rien fait de mal.
         */
        @Test
        @DisplayName("sans panier, rend un panier VIDE et non une erreur")
        void sansPanierRendVide() {
            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.empty());

            StepVerifier.create(service.afficher(PORTEE))
                    .assertNext(reponse -> {
                        assertThat(reponse.panierId()).isNull();
                        assertThat(reponse.lignes()).isEmpty();
                        assertThat(reponse.totalDisponible()).isZero();
                    })
                    .verifyComplete();
        }

        /**
         * Un panier vide ne declenche AUCUN appel sortant. Interroger
         * market-service pour une liste vide gaspille un aller-retour et
         * compte dans les metriques du disjoncteur — donc rapproche son
         * ouverture sans raison.
         */
        @Test
        @DisplayName("un panier sans ligne n'interroge pas market-service")
        void panierVideNAppellePas() {
            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignes.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.empty());

            service.afficher(PORTEE).block();

            verify(catalogue, never()).decrire(any());
        }

        /**
         * Une ligne indisponible est rendue A PART, avec son motif, et NON
         * supprimee. Un article qui disparait du panier sans explication se lit
         * comme une perte de donnees : l'acheteur cherche ce qu'il avait mis,
         * puis abandonne.
         */
        @Test
        @DisplayName("une ligne indisponible est rendue a part, jamais supprimee")
        void ligneIndisponibleRendueAPart() {
            PanierLigne dispo = ligne(panier.getId(), UUID.randomUUID(), 2);
            PanierLigne rupture = ligne(panier.getId(), UUID.randomUUID(), 1);

            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignes.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.just(dispo, rupture));
            when(catalogue.decrire(any())).thenReturn(Mono.just(
                    List.of(decrite(dispo, true), decrite(rupture, false))));

            StepVerifier.create(service.afficher(PORTEE))
                    .assertNext(reponse -> {
                        assertThat(reponse.lignes()).hasSize(1);
                        assertThat(reponse.lignesIndisponibles())
                                .withFailMessage("""
                                        La ligne indisponible a disparu au lieu d'etre \
                                        rendue a part. L'acheteur chercherait ce qu'il \
                                        avait mis dans son panier.""")
                                .hasSize(1);
                        assertThat(reponse.lignesIndisponibles().get(0).motif())
                                .withFailMessage("La ligne est barree sans motif : "
                                        + "l'acheteur recommence jusqu'a abandonner.")
                                .isNotNull();
                        // Le total ne compte QUE le disponible : 2 x 5000.
                        assertThat(reponse.totalDisponible()).isEqualTo(10000L);
                    })
                    .verifyComplete();

            verify(lignes, never()).delete(any());
        }

        /**
         * market-service n'a rien dit d'une ligne : elle est rendue
         * indisponible, pas omise. Une absence de reponse n'est pas une absence
         * d'article.
         */
        @Test
        @DisplayName("une ligne non decrite est rendue indisponible, pas omise")
        void ligneNonDecriteEstRendue() {
            PanierLigne orpheline = ligne(panier.getId(), UUID.randomUUID(), 3);

            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignes.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.just(orpheline));
            when(catalogue.decrire(any())).thenReturn(Mono.just(List.of()));

            StepVerifier.create(service.afficher(PORTEE))
                    .assertNext(reponse -> {
                        assertThat(reponse.lignes()).isEmpty();
                        assertThat(reponse.lignesIndisponibles()).hasSize(1);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("L'appartenance d'une ligne")
    class Appartenance {

        /**
         * <h3>LE test de ce fichier</h3>
         *
         * Sans ce controle, n'importe qui modifie le panier d'autrui en citant
         * un identifiant de ligne. Et le refus rend 404 et non 403 : un 403
         * confirmerait que la ligne existe, et une reference confirmee
         * s'enumere.
         */
        @Test
        @DisplayName("modifier la ligne d'un AUTRE panier rend 404")
        void ligneDUnAutreEstRefusee() {
            UUID autrePanier = UUID.randomUUID();
            PanierLigne pasLaMienne = ligne(autrePanier, UUID.randomUUID(), 1);

            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignes.findById(any(UUID.class))).thenReturn(Mono.just(pasLaMienne));

            StepVerifier.create(service.fixerQuantite(PORTEE, pasLaMienne.getId(), 5))
                    .expectErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(OrderException.class);
                        assertThat(((OrderException) erreur).getStatusCode())
                                .withFailMessage("""
                                        Le refus ne rend pas 404.

                                        Un 403 confirmerait que la ligne existe, et une \
                                        reference confirmee s'enumere.""")
                                .isEqualTo(HttpStatus.NOT_FOUND);
                    })
                    .verify();

            verify(lignes, never()).save(any());
        }

        @Test
        @DisplayName("une quantite nulle retire la ligne")
        void quantiteNulleRetire() {
            PanierLigne mienne = ligne(panier.getId(), UUID.randomUUID(), 2);

            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignes.findById(any(UUID.class))).thenReturn(Mono.just(mienne));
            when(lignes.delete(any(PanierLigne.class))).thenReturn(Mono.empty());
            when(lignes.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.empty());

            service.fixerQuantite(PORTEE, mienne.getId(), 0).block();

            verify(lignes).delete(any(PanierLigne.class));
            verify(lignes, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Les plafonds")
    class Plafonds {

        /**
         * Le plafond de quantite est applique par le SERVICE et non par une
         * annotation : il est configurable, et une annotation porte une
         * constante de compilation.
         */
        @Test
        @DisplayName("une quantite au-dela du plafond est refusee avec un motif")
        void quantiteAuDelaDuPlafond() {
            StepVerifier.create(service.ajouter(PORTEE, new AjoutAuPanierRequest(
                            UUID.randomUUID(), UUID.randomUUID(), 21)))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(RefusExplique.class)
                            .hasMessageContaining("20"))
                    .verify();

            // Rien n'a ete lu ni ecrit : le refus est immediat.
            verify(paniers, never()).ouvertDe(any());
        }

        @Test
        @DisplayName("fixer une quantite au-dela du plafond est refuse aussi")
        void fixerAuDelaDuPlafond() {
            StepVerifier.create(service.fixerQuantite(PORTEE, UUID.randomUUID(), 21))
                    .expectError(RefusExplique.class)
                    .verify();
            verify(paniers, never()).ouvertDe(any());
        }
    }

    @Nested
    @DisplayName("La creation du panier")
    class Creation {

        /**
         * Deux onglets peuvent demander un panier en meme temps. L'index unique
         * partiel refuse le second, et le service RELIT celui qui a gagne
         * plutot que d'echouer : du point de vue de l'acheteur, son panier
         * existe, et c'est tout ce qui compte.
         */
        @Test
        @DisplayName("une creation concurrente relit le panier gagnant")
        void creationConcurrenteRelit() {
            PanierLigne ajoutee = ligne(panier.getId(), UUID.randomUUID(), 1);

            when(paniers.ouvertDe(ACHETEUR))
                    .thenReturn(Mono.empty())                      // premiere lecture
                    .thenReturn(Mono.just(panier));                // apres le conflit
            when(paniers.save(any(Panier.class)))
                    .thenReturn(Mono.error(new IllegalStateException("doublon")));
            when(lignes.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.empty());
            when(lignes.ajouterOuAugmenter(any(), any(), any(), anyInt()))
                    .thenReturn(Mono.just(ajoutee));

            StepVerifier.create(service.ajouter(PORTEE, new AjoutAuPanierRequest(
                            ajoutee.getProduitId(), ajoutee.getVarianteId(), 1)))
                    .assertNext(reponse -> assertThat(reponse.panierId())
                            .withFailMessage("""
                                    La creation concurrente a echoue au lieu de relire \
                                    le panier gagnant. Un acheteur avec deux onglets \
                                    verrait une erreur sans raison.""")
                            .isEqualTo(panier.getId()))
                    .verifyComplete();
        }
    }
}
