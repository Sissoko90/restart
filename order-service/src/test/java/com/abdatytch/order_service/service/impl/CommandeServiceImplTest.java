package com.abdatytch.order_service.service.impl;

import com.abdatytch.order_service.client.ClientMarketStock;
import com.abdatytch.order_service.entities.Commande;
import com.abdatytch.order_service.entities.CommandeLigne;
import com.abdatytch.order_service.entities.Panier;
import com.abdatytch.order_service.entities.PanierLigne;
import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.enums.StatutPanier;
import com.abdatytch.order_service.exception.OrderException;
import com.abdatytch.order_service.exception.RefusExplique;
import com.abdatytch.order_service.metrics.OrderMetrics;
import com.abdatytch.order_service.repository.CommandeLigneRepository;
import com.abdatytch.order_service.repository.CommandeRepository;
import com.abdatytch.order_service.repository.PanierLigneRepository;
import com.abdatytch.order_service.repository.PanierRepository;
import com.abdatytch.order_service.security.PorteeDeLAcheteur;
import com.abdatytch.order_service.service.MachineDeCommande;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le passage en caisse et la vie d'une commande.
 *
 * <h2>Ce que ces tests fixent</h2>
 *
 * L'ordre des operations — refuser AVANT de reserver quand le pays est
 * inconnu —, la decoupe par boutique, et surtout la COMPENSATION : le stock
 * est rendu quand l'ecriture echoue apres une reservation reussie, et l'echec
 * de la compensation est MESURE.
 */
@DisplayName("CommandeServiceImpl")
class CommandeServiceImplTest {

    private static final String ACHETEUR = "acheteur-1";
    private static final PorteeDeLAcheteur PORTEE =
            new PorteeDeLAcheteur(ACHETEUR, "ML", null, false);
    private static final PorteeDeLAcheteur SANS_PAYS =
            new PorteeDeLAcheteur(ACHETEUR, null, null, false);

    private CommandeRepository commandes;
    private CommandeLigneRepository lignesDeCommande;
    private PanierRepository paniers;
    private PanierLigneRepository lignesDePanier;
    private ClientMarketStock stock;
    private MachineDeCommande machine;
    private OrderMetrics metriques;
    private SimpleMeterRegistry registre;
    private CommandeServiceImpl service;

    private Panier panier;

    @BeforeEach
    void preparer() {
        commandes = mock(CommandeRepository.class);
        lignesDeCommande = mock(CommandeLigneRepository.class);
        paniers = mock(PanierRepository.class);
        lignesDePanier = mock(PanierLigneRepository.class);
        stock = mock(ClientMarketStock.class);
        machine = mock(MachineDeCommande.class);
        registre = new SimpleMeterRegistry();
        metriques = new OrderMetrics(registre);

        service = new CommandeServiceImpl(commandes, lignesDeCommande, paniers,
                lignesDePanier, stock, machine, metriques,
                new BigDecimal("5.00"), 30L, 7L);

        panier = new Panier();
        panier.setId(UUID.randomUUID());
        panier.setAuthUserId(ACHETEUR);
        panier.setStatut(StatutPanier.OUVERT);
    }

    private PanierLigne lignePanier() {
        PanierLigne l = new PanierLigne();
        l.setId(UUID.randomUUID());
        l.setPanierId(panier.getId());
        l.setProduitId(UUID.randomUUID());
        l.setVarianteId(UUID.randomUUID());
        l.setQuantite(2);
        return l;
    }

    private ClientMarketStock.LigneReservee reservee(UUID boutiqueId, long prix, int quantite) {
        return new ClientMarketStock.LigneReservee(
                UUID.randomUUID(), UUID.randomUUID(), boutiqueId, "ML",
                "Bazin", "rouge", "M", "SKU", prix, "XOF", quantite);
    }

    /** Le save() imite la base : il assigne un identifiant. */
    private void laBaseAccepteLesEcritures() {
        when(commandes.save(any(Commande.class))).thenAnswer(appel -> {
            Commande c = appel.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return Mono.just(c);
        });
        when(lignesDeCommande.save(any(CommandeLigne.class)))
                .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
        when(machine.inscrireLaCreation(any(UUID.class), any())).thenReturn(Mono.empty());
        when(paniers.save(any(Panier.class)))
                .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
    }

    @Nested
    @DisplayName("L'ordre des operations")
    class OrdreDesOperations {

        /**
         * Le pays est verifie AVANT toute reservation. Refuser ici ne coute
         * rien ; refuser apres avoir reserve obligerait a compenser, avec le
         * risque que la compensation echoue a son tour.
         */
        @Test
        @DisplayName("un pays inconnu refuse AVANT de reserver quoi que ce soit")
        void paysInconnuRefuseAvantDeReserver() {
            StepVerifier.create(service.passerCommande(SANS_PAYS))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(OrderException.class))
                    .verify();

            verify(stock, never()).reserver(any());
            verify(paniers, never()).ouvertDe(any());
        }

        @Test
        @DisplayName("un panier vide est refuse sans reserver")
        void panierVideRefuse() {
            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignesDePanier.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.empty());

            StepVerifier.create(service.passerCommande(PORTEE))
                    .expectError(RefusExplique.class)
                    .verify();

            verify(stock, never()).reserver(any());
        }

        @Test
        @DisplayName("sans panier ouvert, 400 et aucune reservation")
        void sansPanierRefuse() {
            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.empty());

            StepVerifier.create(service.passerCommande(PORTEE))
                    .expectError(RefusExplique.class)
                    .verify();

            verify(stock, never()).reserver(any());
        }
    }

    @Nested
    @DisplayName("Une commande par boutique")
    class UneParBoutique {

        /**
         * Un panier de deux boutiques produit DEUX commandes. C'est la seule
         * decoupe qui permette un remboursement partiel rattachable a un
         * versement.
         */
        @Test
        @DisplayName("deux boutiques dans un panier produisent deux commandes")
        void deuxBoutiquesDeuxCommandes() {
            UUID boutiqueA = UUID.randomUUID();
            UUID boutiqueB = UUID.randomUUID();

            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignesDePanier.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.just(lignePanier(), lignePanier()));
            when(stock.reserver(any())).thenReturn(Mono.just(List.of(
                    reservee(boutiqueA, 10000L, 1),
                    reservee(boutiqueB, 20000L, 1))));
            laBaseAccepteLesEcritures();

            StepVerifier.create(service.passerCommande(PORTEE))
                    .assertNext(rendues -> assertThat(rendues)
                            .withFailMessage("""
                                    Un panier de deux boutiques n'a pas produit deux \
                                    commandes. Chaque boutique expedie, est payee et \
                                    subit un retour separement.""")
                            .hasSize(2))
                    .verifyComplete();
        }

        /**
         * Le panier passe a CONVERTI et ses lignes sont CONSERVEES : les
         * supprimer effacerait ce qui a produit les commandes.
         */
        @Test
        @DisplayName("le panier est converti, ses lignes conservees")
        void panierConvertiLignesConservees() {
            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignesDePanier.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.just(lignePanier()));
            when(stock.reserver(any())).thenReturn(Mono.just(List.of(
                    reservee(UUID.randomUUID(), 10000L, 1))));
            laBaseAccepteLesEcritures();

            service.passerCommande(PORTEE).block();

            assertThat(panier.getStatut()).isEqualTo(StatutPanier.CONVERTI);
            verify(lignesDePanier, never()).deleteByPanierId(any());
        }

        /**
         * La commission est calculee et figee. 5 % de 10000 font 500, et la
         * decomposition boucle — sans quoi la contrainte
         * {@code commande_decomposition_boucle} refuserait l'insertion.
         */
        @Test
        @DisplayName("la commission est figee et la decomposition boucle")
        void commissionFigeeEtBouclee() {
            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignesDePanier.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.just(lignePanier()));
            when(stock.reserver(any())).thenReturn(Mono.just(List.of(
                    reservee(UUID.randomUUID(), 5000L, 2))));

            java.util.concurrent.atomic.AtomicReference<Commande> ecrite =
                    new java.util.concurrent.atomic.AtomicReference<>();
            when(commandes.save(any(Commande.class))).thenAnswer(appel -> {
                Commande c = appel.getArgument(0);
                if (c.getId() == null) {
                    c.setId(UUID.randomUUID());
                }
                ecrite.set(c);
                return Mono.just(c);
            });
            when(lignesDeCommande.save(any(CommandeLigne.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
            when(machine.inscrireLaCreation(any(UUID.class), any())).thenReturn(Mono.empty());
            when(paniers.save(any(Panier.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            service.passerCommande(PORTEE).block();

            Commande c = ecrite.get();
            assertThat(c.getMontantArticles()).isEqualTo(10000L);
            assertThat(c.getCommissionMontant()).isEqualTo(500L);
            assertThat(c.getMontantDuALaBoutique()).isEqualTo(9500L);
            assertThat(c.getCommissionMontant() + c.getMontantDuALaBoutique())
                    .withFailMessage("La decomposition ne boucle pas : la contrainte "
                            + "commande_decomposition_boucle refuserait l'insertion.")
                    .isEqualTo(c.getMontantArticles());
            assertThat(c.getCommissionTauxPourcent())
                    .withFailMessage("Le taux n'est pas fige sur la commande : un "
                            + "changement de taux reecrirait le passe.")
                    .isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(c.getAPayerJusquAu())
                    .withFailMessage("Aucune echeance de paiement : le stock resterait "
                            + "bloque indefiniment.")
                    .isAfter(LocalDateTime.now());
        }

        /**
         * La commission est MASQUEE pour l'acheteur. Ce que Sankadi prend sur
         * une vente est une affaire entre Sankadi et la boutique, et le
         * masquage est fait dans le service — compter sur le client pour ne pas
         * afficher un champ qu'on lui envoie n'est pas une protection.
         */
        @Test
        @DisplayName("l'acheteur ne voit PAS la commission dans ses commandes")
        void commissionMasqueePourLAcheteur() {
            Commande c = new Commande();
            c.setId(UUID.randomUUID());
            c.setNumero("CMD-1");
            c.setStatut(StatutCommande.PAYEE);
            c.setMontantArticles(10000L);
            c.setCommissionMontant(500L);
            c.setMontantDuALaBoutique(9500L);
            c.setBoutiqueId(UUID.randomUUID());

            when(commandes.findByAuthUserIdOrderByCreatedAtDesc(ACHETEUR))
                    .thenReturn(Flux.just(c));
            when(lignesDeCommande.findByCommandeIdOrderByCreatedAtAsc(c.getId()))
                    .thenReturn(Flux.empty());

            StepVerifier.create(service.mesCommandes(PORTEE))
                    .assertNext(rendue -> {
                        assertThat(rendue.montantArticles()).isEqualTo(10000L);
                        assertThat(rendue.commissionMontant())
                                .withFailMessage("""
                                        La commission est envoyee a l'acheteur.

                                        Ce que Sankadi prend sur une vente est une \
                                        affaire entre Sankadi et la boutique.""")
                                .isNull();
                        assertThat(rendue.montantDuALaBoutique()).isNull();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("La compensation")
    class Compensation {

        /**
         * <h3>LE test de ce fichier</h3>
         *
         * L'ecriture echoue APRES une reservation reussie : le stock doit etre
         * rendu, et l'erreur d'origine propagee. Sans la restitution, du stock
         * reste bloque pour une commande qui n'existe pas.
         */
        @Test
        @DisplayName("une ecriture echouee apres reservation REND le stock")
        void ecritureEchoueeRendLeStock() {
            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignesDePanier.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.just(lignePanier()));
            when(stock.reserver(any())).thenReturn(Mono.just(List.of(
                    reservee(UUID.randomUUID(), 10000L, 1))));
            // L'ecriture echoue.
            when(commandes.save(any(Commande.class)))
                    .thenReturn(Mono.error(new IllegalStateException("base indisponible")));
            when(stock.rendre(any())).thenReturn(Mono.just(1));

            StepVerifier.create(service.passerCommande(PORTEE))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .withFailMessage("""
                                    L'erreur d'origine n'est pas propagee.

                                    L'acheteur n'a pas de commande : lui rendre un \
                                    succes serait un mensonge.""")
                            .isInstanceOf(IllegalStateException.class))
                    .verify();

            verify(stock).rendre(any());
        }

        /**
         * La compensation echoue a son tour : du stock reste retenu sans
         * commande. C'est la LIMITE CONNUE du modele a deux bases, et elle doit
         * etre COMPTEE — sinon elle est invisible et introuvable sans
         * inventaire.
         */
        @Test
        @DisplayName("une compensation echouee est COMPTEE, et l'erreur d'origine propagee")
        void compensationEchoueeEstComptee() {
            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignesDePanier.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.just(lignePanier()));
            when(stock.reserver(any())).thenReturn(Mono.just(List.of(
                    reservee(UUID.randomUUID(), 10000L, 1))));
            when(commandes.save(any(Commande.class)))
                    .thenReturn(Mono.error(new IllegalStateException("base indisponible")));
            when(stock.rendre(any()))
                    .thenReturn(Mono.error(new IllegalStateException("market injoignable")));

            double avant = compteur(OrderMetrics.COMPENSATIONS_ECHOUEES);

            StepVerifier.create(service.passerCommande(PORTEE))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .withFailMessage("L'echec de compensation a masque l'erreur "
                                    + "d'origine : l'appelant ne saurait pas pourquoi "
                                    + "sa commande a echoue.")
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("base indisponible"))
                    .verify();

            assertThat(compteur(OrderMetrics.COMPENSATIONS_ECHOUEES))
                    .withFailMessage("""
                            La compensation echouee n'est PAS comptee.

                            Du stock reste retenu sans commande, invisible et \
                            introuvable sans inventaire. L'alerte \
                            CompensationDeStockEchouee ne partirait jamais.""")
                    .isEqualTo(avant + 1);
        }

        /**
         * Une reservation REFUSEE n'a rien pris : il ne faut surtout pas rendre
         * du stock jamais reserve, cela le CREERAIT.
         */
        @Test
        @DisplayName("une reservation refusee ne declenche AUCUNE restitution")
        void reservationRefuseeNeRendRien() {
            when(paniers.ouvertDe(ACHETEUR)).thenReturn(Mono.just(panier));
            when(lignesDePanier.findByPanierIdOrderByCreatedAtAsc(panier.getId()))
                    .thenReturn(Flux.just(lignePanier()));
            when(stock.reserver(any()))
                    .thenReturn(Mono.error(OrderException.stockIndisponible()));

            StepVerifier.create(service.passerCommande(PORTEE))
                    .expectError(OrderException.class)
                    .verify();

            verify(stock, never()).rendre(any());
        }
    }

    @Nested
    @DisplayName("L'annulation par l'acheteur")
    class AnnulationAcheteur {

        /**
         * L'acheteur n'annule que tant que rien n'est encaisse. Au-dela, c'est
         * a Sankadi d'arbitrer : la boutique a peut-etre deja prepare le colis,
         * et le remboursement engage de l'argent.
         */
        @Test
        @DisplayName("une commande PAYEE ne s'annule plus, avec un motif en clair")
        void commandePayeeNeSAnnulePlus() {
            Commande payee = new Commande();
            payee.setId(UUID.randomUUID());
            payee.setAuthUserId(ACHETEUR);
            payee.setStatut(StatutCommande.PAYEE);

            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(payee));

            StepVerifier.create(service.annulerParLAcheteur(PORTEE, payee.getId(), "non"))
                    .expectErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(RefusExplique.class);
                        assertThat(((RefusExplique) erreur).getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT);
                        assertThat(erreur.getMessage())
                                .withFailMessage("Le refus ne dirige pas vers la demande "
                                        + "de retour : l'acheteur ira au support.")
                                .contains("retour");
                    })
                    .verify();

            verify(machine, never()).faireAvancer(any(), any(), any(), any(), any());
        }

        /**
         * La commande d'un AUTRE rend 404. Un 403 confirmerait qu'elle existe.
         */
        @Test
        @DisplayName("annuler la commande d'un autre rend 404")
        void commandeDUnAutreRend404() {
            Commande pasLaMienne = new Commande();
            pasLaMienne.setId(UUID.randomUUID());
            pasLaMienne.setAuthUserId("quelqu-un-dautre");
            pasLaMienne.setStatut(StatutCommande.A_PAYER);

            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(pasLaMienne));

            StepVerifier.create(service.annulerParLAcheteur(PORTEE, pasLaMienne.getId(), null))
                    .expectErrorSatisfies(erreur -> assertThat(
                                    ((OrderException) erreur).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Les transitions pilotees")
    class TransitionsPilotees {

        /**
         * Une annulation administrative passe par le chemin qui REND LE STOCK.
         * Sans ce detour, le stock resterait retenu — et ce serait d'autant
         * plus invisible que l'operation aurait l'air d'avoir reussi.
         */
        @Test
        @DisplayName("une annulation administrative rend le stock")
        void annulationAdministrativeRendLeStock() {
            Commande c = new Commande();
            c.setId(UUID.randomUUID());
            c.setNumero("CMD-9");
            c.setStatut(StatutCommande.A_PAYER);
            // Les trois montants sont NOT NULL en base, et la contrainte
            // commande_decomposition_boucle exige qu'ils s'additionnent.
            c.setMontantArticles(10000L);
            c.setCommissionMontant(500L);
            c.setMontantDuALaBoutique(9500L);
            c.setDevise("XOF");

            // Le prix et le montant sont NOT NULL en base : un fixture sans
            // eux decrit une ligne qui ne peut pas exister.
            CommandeLigne l = new CommandeLigne();
            l.setVarianteId(UUID.randomUUID());
            l.setProduitId(UUID.randomUUID());
            l.setNomProduit("Bazin");
            l.setPrixUnitaire(5000L);
            l.setQuantite(2);
            l.setMontantLigne(10000L);

            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(c));
            when(machine.faireAvancer(any(), any(), any(), any(), any()))
                    .thenReturn(Mono.just(c));
            when(commandes.save(any(Commande.class))).thenReturn(Mono.just(c));
            when(lignesDeCommande.findByCommandeIdOrderByCreatedAtAsc(c.getId()))
                    .thenReturn(Flux.just(l));
            when(stock.rendre(any())).thenReturn(Mono.just(1));

            service.fairePasserA(c.getId(), StatutCommande.ANNULEE, "agent",
                    RoleDeTransition.ADMIN, "boutique suspendue").block();

            verify(stock)
                    .rendre(any());
        }

        /**
         * Un echec de restitution ne fait PAS echouer l'annulation. L'acheteur
         * a droit a son annulation ; le stock non rendu est un probleme de
         * boutique, mesure et journalise, pas un motif de refuser.
         */
        @Test
        @DisplayName("un echec de restitution n'empeche pas l'annulation")
        void echecDeRestitutionNEmpechePasLAnnulation() {
            Commande c = new Commande();
            c.setId(UUID.randomUUID());
            c.setNumero("CMD-10");
            c.setStatut(StatutCommande.A_PAYER);
            // Les trois montants sont NOT NULL en base, et la contrainte
            // commande_decomposition_boucle exige qu'ils s'additionnent.
            c.setMontantArticles(10000L);
            c.setCommissionMontant(500L);
            c.setMontantDuALaBoutique(9500L);
            c.setDevise("XOF");

            CommandeLigne l = new CommandeLigne();
            l.setVarianteId(UUID.randomUUID());
            l.setProduitId(UUID.randomUUID());
            l.setNomProduit("Bazin");
            l.setPrixUnitaire(5000L);
            l.setQuantite(1);
            l.setMontantLigne(5000L);

            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(c));
            when(machine.faireAvancer(any(), any(), any(), any(), any()))
                    .thenReturn(Mono.just(c));
            when(commandes.save(any(Commande.class))).thenReturn(Mono.just(c));
            when(lignesDeCommande.findByCommandeIdOrderByCreatedAtAsc(c.getId()))
                    .thenReturn(Flux.just(l));
            when(stock.rendre(any()))
                    .thenReturn(Mono.error(new IllegalStateException("injoignable")));

            double avant = compteur(OrderMetrics.COMPENSATIONS_ECHOUEES);

            StepVerifier.create(service.fairePasserA(c.getId(), StatutCommande.ANNULEE,
                            "agent", RoleDeTransition.ADMIN, "motif"))
                    .assertNext(rendue -> assertThat(rendue).isNotNull())
                    .verifyComplete();

            assertThat(compteur(OrderMetrics.COMPENSATIONS_ECHOUEES))
                    .withFailMessage("Le stock non rendu n'est pas compte : il serait "
                            + "perdu pour la boutique sans que rien ne le dise.")
                    .isEqualTo(avant + 1);
        }
    }

    private double compteur(String nom) {
        var c = registre.find(nom).counter();
        return c == null ? 0d : c.count();
    }
}
