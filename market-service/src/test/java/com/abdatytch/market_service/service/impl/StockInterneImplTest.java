package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.dto.request.ReservationStockRequest;
import com.abdatytch.market_service.dto.request.RestitutionStockRequest;
import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.entities.ProduitVariante;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.repository.ProduitRepository;
import com.abdatytch.market_service.repository.ProduitVarianteRepository;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import com.abdatytch.market_service.security.ResolveurDePortee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La reservation de stock, mise a l'epreuve.
 *
 * <h2>Ce que ces tests fixent</h2>
 *
 * Le TOUT ou RIEN, l'ordre de verrouillage, le controle que la variante
 * appartient bien au produit annonce, et surtout que le signal vide d'un
 * {@code UPDATE} est traite comme un refus et non comme un succes.
 *
 * <h2>Le mock IMITE la base</h2>
 *
 * {@code reserver} rend 1 ou 0 selon le stock, comme le ferait un
 * {@code WHERE stock >= :quantite}. Un mock qui rendrait toujours 1 ferait
 * passer une reservation sans condition.
 */
@DisplayName("StockInterneImpl — la reservation")
class StockInterneImplTest {

    private static final String ACHETEUR = "acheteur-1";

    private ProduitRepository produits;
    private ProduitVarianteRepository variantes;
    private ResolveurDePortee portees;
    private StockInterneImpl service;

    private Produit produit;
    private ProduitVariante variante;

    @BeforeEach
    void preparer() {
        produits = mock(ProduitRepository.class);
        variantes = mock(ProduitVarianteRepository.class);
        portees = mock(ResolveurDePortee.class);
        service = new StockInterneImpl(produits, variantes, portees);

        produit = new Produit();
        produit.setId(UUID.randomUUID());
        produit.setBoutiqueId(UUID.randomUUID());
        produit.setNom("Bazin");
        produit.setPays("ML");
        produit.setDevise("XOF");

        variante = new ProduitVariante();
        variante.setId(UUID.randomUUID());
        variante.setProduitId(produit.getId());
        variante.setCouleur("rouge");
        variante.setTaille("M");
        variante.setSku("SKU-1");
        variante.setPrix(5000L);
        variante.setStock(3);
        variante.setActif(Boolean.TRUE);

        when(portees.pourLeCompte(ACHETEUR)).thenReturn(Mono.just(
                new PorteeDuCatalogue(ACHETEUR, "ML", null, false, false)));
    }

    private ReservationStockRequest demande(UUID produitId, UUID varianteId, int quantite) {
        return new ReservationStockRequest(ACHETEUR,
                List.of(new ReservationStockRequest.Ligne(produitId, varianteId, quantite)));
    }

    private void leProduitEstVisible() {
        when(produits.parIdentifiantVisible(eq(produit.getId()), anyBoolean(), any(),
                anyBoolean(), any())).thenReturn(Mono.just(produit));
    }

    @Nested
    @DisplayName("La portee est resolue ICI")
    class PorteeResolue {

        /**
         * L'appelant ne transmet qu'un identifiant : la portee est resolue par
         * ce service. S'il pouvait l'annoncer, il suffirait d'un appel forge
         * pour se declarer professionnel et acheter un article reserve.
         */
        @Test
        @DisplayName("la portee vient du resolveur, pas de la demande")
        void porteeResolueEtNonTransmise() {
            leProduitEstVisible();
            when(variantes.findById(any(UUID.class))).thenReturn(Mono.just(variante));
            when(variantes.reserver(any(UUID.class), anyInt())).thenReturn(Mono.just(1));

            service.reserver(demande(produit.getId(), variante.getId(), 1)).block();

            verify(portees).pourLeCompte(ACHETEUR);
        }
    }

    @Nested
    @DisplayName("Le refus n'est jamais un succes")
    class RefusJamaisSucces {

        /**
         * <h3>LE test de ce fichier</h3>
         *
         * Un {@code UPDATE} qui ne touche aucune ligne rend 0 — et sans
         * {@code @Modifying}, il rendait un {@code Mono} VIDE. Les deux cas
         * doivent produire un 409, jamais une liste vide avec un 200.
         *
         * <p>Mesure du 2026-11-14 : reserver cinq unites d'un article qui n'en
         * avait qu'une rendait « 200 [] » sans rien journaliser.
         */
        @Test
        @DisplayName("un stock insuffisant rend 409, jamais une liste vide")
        void stockInsuffisantRend409() {
            leProduitEstVisible();
            when(variantes.findById(any(UUID.class))).thenReturn(Mono.just(variante));
            when(variantes.reserver(any(UUID.class), anyInt())).thenReturn(Mono.just(0));

            StepVerifier.create(service.reserver(demande(produit.getId(), variante.getId(), 5)))
                    .expectErrorSatisfies(erreur -> assertThat(
                                    ((MarketException) erreur).getStatusCode())
                            .withFailMessage("""
                                    Un stock insuffisant ne rend pas 409.

                                    S'il rend 200 avec une liste vide, l'appelant croit \
                                    avoir reserve et cree une commande a zero ligne.""")
                            .isEqualTo(HttpStatus.CONFLICT))
                    .verify();
        }

        /**
         * Le cas du {@code Mono} vide, qui etait le defaut reel. Le
         * {@code defaultIfEmpty(0)} du service doit le transformer en refus.
         */
        @Test
        @DisplayName("un UPDATE qui n'emet RIEN est traite comme un refus")
        void monoVideEstUnRefus() {
            leProduitEstVisible();
            when(variantes.findById(any(UUID.class))).thenReturn(Mono.just(variante));
            // Le piege : la requete n'emet rien du tout.
            when(variantes.reserver(any(UUID.class), anyInt())).thenReturn(Mono.empty());

            StepVerifier.create(service.reserver(demande(produit.getId(), variante.getId(), 1)))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .withFailMessage("""
                                    Un Mono vide a ete avale comme un succes.

                                    C'est le defaut exact du 2026-11-14 : sans \
                                    defaultIfEmpty, le flatMap n'est jamais appele, la \
                                    ligne disparait du resultat, et l'appel rend 200.""")
                            .isInstanceOf(MarketException.class))
                    .verify();
        }
    }

    @Nested
    @DisplayName("La visibilite et l'appartenance")
    class VisibiliteEtAppartenance {

        /**
         * Invisible pour cet acheteur : la meme reponse qu'un produit
         * inexistant. Distinguer les deux dirait a un particulier qu'un article
         * existe mais lui est interdit — une fuite sur le catalogue
         * professionnel.
         */
        @Test
        @DisplayName("un produit invisible rend 404 et ne touche pas au stock")
        void produitInvisibleRend404() {
            when(produits.parIdentifiantVisible(any(), anyBoolean(), any(), anyBoolean(), any()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.reserver(demande(produit.getId(), variante.getId(), 1)))
                    .expectErrorSatisfies(erreur -> assertThat(
                                    ((MarketException) erreur).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND))
                    .verify();

            verify(variantes, never()).reserver(any(), anyInt());
        }

        /**
         * La variante doit appartenir au produit annonce. Sans ce controle, un
         * appelant reserverait le stock d'un article bon marche en citant un
         * produit visible, et la ligne de commande porterait le nom du mauvais.
         */
        @Test
        @DisplayName("une variante d'un AUTRE produit rend 404")
        void varianteDUnAutreProduitRend404() {
            leProduitEstVisible();
            ProduitVariante etrangere = new ProduitVariante();
            etrangere.setId(variante.getId());
            etrangere.setProduitId(UUID.randomUUID());   // un autre produit
            etrangere.setPrix(100L);
            etrangere.setStock(99);
            etrangere.setActif(Boolean.TRUE);
            when(variantes.findById(any(UUID.class))).thenReturn(Mono.just(etrangere));

            StepVerifier.create(service.reserver(demande(produit.getId(), variante.getId(), 1)))
                    .expectErrorSatisfies(erreur -> assertThat(
                                    ((MarketException) erreur).getStatusCode())
                            .withFailMessage("""
                                    Une variante d'un autre produit a ete acceptee.

                                    Un appelant reserverait le stock d'un article bon \
                                    marche en citant un produit visible.""")
                            .isEqualTo(HttpStatus.NOT_FOUND))
                    .verify();

            verify(variantes, never()).reserver(any(), anyInt());
        }

        @Test
        @DisplayName("une variante inexistante rend 404")
        void varianteInexistanteRend404() {
            leProduitEstVisible();
            when(variantes.findById(any(UUID.class))).thenReturn(Mono.empty());

            StepVerifier.create(service.reserver(demande(produit.getId(), variante.getId(), 1)))
                    .expectError(MarketException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("L'instantane rendu")
    class Instantane {

        /**
         * La reponse porte le prix et le nom : order-service fige sa ligne de
         * commande avec ces valeurs sans relire. Le prix vient de la VARIANTE,
         * qui porte le prix effectif d'une declinaison — deux tailles peuvent
         * ne pas couter le meme prix.
         */
        @Test
        @DisplayName("l'instantane porte le prix de la VARIANTE et la boutique du produit")
        void instantaneComplet() {
            leProduitEstVisible();
            when(variantes.findById(any(UUID.class))).thenReturn(Mono.just(variante));
            when(variantes.reserver(any(UUID.class), anyInt())).thenReturn(Mono.just(1));

            StepVerifier.create(service.reserver(demande(produit.getId(), variante.getId(), 2)))
                    .assertNext(lignes -> {
                        assertThat(lignes).hasSize(1);
                        var l = lignes.get(0);
                        assertThat(l.prixUnitaire())
                                .withFailMessage("Le prix ne vient pas de la variante.")
                                .isEqualTo(5000L);
                        assertThat(l.boutiqueId()).isEqualTo(produit.getBoutiqueId());
                        assertThat(l.devise()).isEqualTo("XOF");
                        assertThat(l.nomProduit()).isEqualTo("Bazin");
                        assertThat(l.couleur()).isEqualTo("rouge");
                        assertThat(l.quantite()).isEqualTo(2);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("La description, sans reservation")
    class Description {

        /**
         * Decrire ne reserve RIEN : reserver pour afficher retiendrait le stock
         * a chaque consultation d'un panier.
         */
        @Test
        @DisplayName("decrire ne touche jamais au stock")
        void decrireNeReservePas() {
            leProduitEstVisible();
            when(variantes.findById(any(UUID.class))).thenReturn(Mono.just(variante));

            service.decrire(demande(produit.getId(), variante.getId(), 1)).block();

            verify(variantes, never()).reserver(any(), anyInt());
        }

        /**
         * Une ligne indisponible est RENDUE avec un motif, et non omise :
         * omettre ferait disparaitre une ligne du panier sans explication.
         */
        @Test
        @DisplayName("un article invisible est rendu INDISPONIBLE, pas omis")
        void articleInvisibleEstRenduIndisponible() {
            when(produits.parIdentifiantVisible(any(), anyBoolean(), any(), anyBoolean(), any()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.decrire(demande(produit.getId(), variante.getId(), 1)))
                    .assertNext(lignes -> {
                        assertThat(lignes)
                                .withFailMessage("""
                                        La ligne a ete omise au lieu d'etre rendue \
                                        indisponible. L'acheteur chercherait ce qu'il \
                                        avait mis dans son panier.""")
                                .hasSize(1);
                        assertThat(lignes.get(0).disponible()).isFalse();
                        assertThat(lignes.get(0).motif()).isNotNull();
                    })
                    .verifyComplete();
        }

        /**
         * Le stock EXACT n'est jamais rendu : il renseignerait un concurrent
         * sur le volume de ventes d'une boutique.
         */
        @Test
        @DisplayName("un stock insuffisant est signale sans reveler le stock exact")
        void stockExactJamaisRevele() {
            leProduitEstVisible();
            when(variantes.findById(any(UUID.class))).thenReturn(Mono.just(variante));

            StepVerifier.create(service.decrire(demande(produit.getId(), variante.getId(), 99)))
                    .assertNext(lignes -> {
                        assertThat(lignes.get(0).disponible()).isFalse();
                        assertThat(lignes.get(0).motif())
                                .withFailMessage("Le stock exact est revele : un "
                                        + "concurrent lirait le volume de ventes.")
                                .doesNotContain("3");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("une declinaison desactivee est indisponible, avec son propre motif")
        void declinaisonDesactivee() {
            leProduitEstVisible();
            variante.setActif(Boolean.FALSE);
            when(variantes.findById(any(UUID.class))).thenReturn(Mono.just(variante));

            StepVerifier.create(service.decrire(demande(produit.getId(), variante.getId(), 1)))
                    .assertNext(lignes -> {
                        assertThat(lignes.get(0).disponible()).isFalse();
                        assertThat(lignes.get(0).motif()).contains("desactivee");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("La restitution")
    class Restitution {

        /**
         * Rendre ne peut pas echouer sur un plafond : refuser perdrait
         * definitivement le stock pour la boutique. Et le signal vide compte
         * pour zero, sans quoi le total serait sous-estime.
         */
        @Test
        @DisplayName("un signal vide compte pour zero et n'interrompt pas le total")
        void signalVideCompteZero() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(variantes.rendre(eq(a), anyInt())).thenReturn(Mono.empty());
            when(variantes.rendre(eq(b), anyInt())).thenReturn(Mono.just(1));

            StepVerifier.create(service.rendre(new RestitutionStockRequest(List.of(
                            new RestitutionStockRequest.Ligne(a, 1),
                            new RestitutionStockRequest.Ligne(b, 2)))))
                    .assertNext(total -> assertThat(total)
                            .withFailMessage("""
                                    Le total est faux ou la chaine s'est interrompue \
                                    sur le signal vide. Un journal qui affirme avoir \
                                    rendu moins qu'il n'a rendu est pire que pas de \
                                    journal.""")
                            .isEqualTo(1))
                    .verifyComplete();

            verify(variantes).rendre(eq(a), anyInt());
            verify(variantes).rendre(eq(b), anyInt());
        }
    }
}
