package com.abdatytch.order_service.service.impl;

import com.abdatytch.order_service.dto.request.DecisionDeRetourRequest;
import com.abdatytch.order_service.dto.request.DemandeDeRetourRequest;
import com.abdatytch.order_service.entities.Commande;
import com.abdatytch.order_service.entities.Retour;
import com.abdatytch.order_service.enums.MotifRetour;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.enums.StatutRetour;
import com.abdatytch.order_service.exception.OrderException;
import com.abdatytch.order_service.exception.RefusExplique;
import com.abdatytch.order_service.metrics.OrderMetrics;
import com.abdatytch.order_service.repository.CommandeRepository;
import com.abdatytch.order_service.repository.RetourRepository;
import com.abdatytch.order_service.security.PorteeDeLAcheteur;
import com.abdatytch.order_service.service.MachineDeCommande;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les retours arbitres par Sankadi.
 *
 * <h2>Ce que ces tests fixent</h2>
 *
 * Les quatre etapes non sautables, et surtout que le REMBOURSEMENT exige la
 * RECEPTION. Confondre « accepter » et « avoir recu » fait rembourser un colis
 * jamais revenu, et c'est industrialisable : on demande, on obtient l'accord,
 * on n'envoie rien.
 */
@DisplayName("RetourServiceImpl — Sankadi arbitre")
class RetourServiceImplTest {

    private static final String ACHETEUR = "acheteur-1";
    private static final PorteeDeLAcheteur PORTEE =
            new PorteeDeLAcheteur(ACHETEUR, "ML", null, false);

    private RetourRepository retours;
    private CommandeRepository commandes;
    private MachineDeCommande machine;
    private CommandeServiceImpl commandeService;
    private SimpleMeterRegistry registre;
    private RetourServiceImpl service;

    private Commande commande;

    @BeforeEach
    void preparer() {
        retours = mock(RetourRepository.class);
        commandes = mock(CommandeRepository.class);
        machine = mock(MachineDeCommande.class);
        commandeService = mock(CommandeServiceImpl.class);
        registre = new SimpleMeterRegistry();
        service = new RetourServiceImpl(retours, commandes, machine, commandeService,
                new OrderMetrics(registre));

        commande = new Commande();
        commande.setId(UUID.randomUUID());
        commande.setNumero("CMD-261114-AAA111");
        commande.setAuthUserId(ACHETEUR);
        commande.setStatut(StatutCommande.LIVREE);
        commande.setMontantArticles(10000L);
        commande.setLivreeLe(LocalDateTime.now().minusDays(1));
        commande.setRetourPossibleJusquAu(LocalDateTime.now().plusDays(6));
    }

    private Retour retour(StatutRetour statut) {
        Retour r = new Retour();
        r.setId(UUID.randomUUID());
        r.setCommandeId(commande.getId());
        r.setDemandePar(ACHETEUR);
        r.setMotif(MotifRetour.ENDOMMAGE);
        r.setStatut(statut);
        return r;
    }

    /** Le verrou de la base : 1 si l'etat attendu correspond, 0 sinon. */
    private void leVerrouAccepte(StatutRetour etatReel) {
        when(retours.changerStatut(any(UUID.class), any(StatutRetour.class),
                any(StatutRetour.class)))
                .thenAnswer(appel -> Mono.just(appel.getArgument(1) == etatReel ? 1 : 0));
    }

    @Nested
    @DisplayName("La demande")
    class Demande {

        @Test
        @DisplayName("la commande d'un AUTRE rend 404")
        void commandeDUnAutreRend404() {
            commande.setAuthUserId("quelqu-un-dautre");
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));

            StepVerifier.create(service.demander(PORTEE, commande.getId(),
                            new DemandeDeRetourRequest(MotifRetour.ENDOMMAGE, null)))
                    .expectErrorSatisfies(erreur -> assertThat(
                                    ((OrderException) erreur).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND))
                    .verify();

            verify(retours, never()).save(any());
        }

        /**
         * L'echeance lue est celle STOCKEE sur la commande, et non un recalcul
         * depuis le delai configure : l'acheteur a droit a la fenetre qu'on lui
         * a annoncee, meme si le reglage a change depuis.
         */
        @Test
        @DisplayName("au-dela de la fenetre, 409 et aucun dossier ouvert")
        void fenetrePasseeRefuse() {
            commande.setRetourPossibleJusquAu(LocalDateTime.now().minusDays(1));
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));

            StepVerifier.create(service.demander(PORTEE, commande.getId(),
                            new DemandeDeRetourRequest(MotifRetour.ENDOMMAGE, null)))
                    .expectErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(RefusExplique.class);
                        assertThat(erreur.getMessage()).contains("periode de retour");
                    })
                    .verify();

            verify(retours, never()).save(any());
        }

        /**
         * Un colis qui n'arrive pas se signale des l'EXPEDITION, et sans
         * fenetre : il n'y a pas de date de livraison pour la faire courir, et
         * exiger une livraison avant de signaler une non-livraison serait
         * absurde.
         */
        @Test
        @DisplayName("JAMAIS_RECU est accepte depuis EXPEDIEE, sans fenetre")
        void jamaisRecuDepuisExpediee() {
            commande.setStatut(StatutCommande.EXPEDIEE);
            commande.setLivreeLe(null);
            commande.setRetourPossibleJusquAu(null);

            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));
            when(retours.ouvertDe(commande.getId())).thenReturn(Mono.empty());
            when(retours.save(any(Retour.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
            when(machine.faireAvancer(any(), eq(StatutCommande.RETOUR_EN_COURS),
                    any(), any(), any())).thenReturn(Mono.just(commande));

            StepVerifier.create(service.demander(PORTEE, commande.getId(),
                            new DemandeDeRetourRequest(MotifRetour.JAMAIS_RECU, "rien recu")))
                    .assertNext(rendu -> assertThat(rendu.motif())
                            .isEqualTo(MotifRetour.JAMAIS_RECU))
                    .verifyComplete();
        }

        /**
         * Un autre motif depuis EXPEDIEE est refuse : les autres motifs
         * supposent qu'on a VU l'article.
         */
        @Test
        @DisplayName("un autre motif depuis EXPEDIEE est refuse")
        void autreMotifDepuisExpedieeRefuse() {
            commande.setStatut(StatutCommande.EXPEDIEE);
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));

            StepVerifier.create(service.demander(PORTEE, commande.getId(),
                            new DemandeDeRetourRequest(MotifRetour.ENDOMMAGE, null)))
                    .expectError(RefusExplique.class)
                    .verify();
        }

        /**
         * Un retour deja ouvert : 409 et non un second dossier. L'index unique
         * partiel le refuserait aussi, mais une erreur de contrainte ne dit
         * rien a l'acheteur.
         */
        @Test
        @DisplayName("un retour deja ouvert refuse le second avec un motif lisible")
        void retourDejaOuvertRefuse() {
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));
            when(retours.ouvertDe(commande.getId()))
                    .thenReturn(Mono.just(retour(StatutRetour.DEMANDE)));

            StepVerifier.create(service.demander(PORTEE, commande.getId(),
                            new DemandeDeRetourRequest(MotifRetour.AUTRE, null)))
                    .expectErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(RefusExplique.class);
                        assertThat(erreur.getMessage()).contains("deja en cours");
                    })
                    .verify();

            verify(retours, never()).save(any());
        }

        /**
         * La commande passe a RETOUR_EN_COURS : cela l'empeche d'etre cloturee
         * par le balayeur, donc empeche la boutique d'etre payee pendant
         * l'arbitrage. Sans ce couplage, un retour serait accepte sur une
         * commande deja soldee.
         */
        @Test
        @DisplayName("une demande fait passer la commande en RETOUR_EN_COURS")
        void demandeBloqueLaCloture() {
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));
            when(retours.ouvertDe(commande.getId())).thenReturn(Mono.empty());
            when(retours.save(any(Retour.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
            when(machine.faireAvancer(any(), any(), any(), any(), any()))
                    .thenReturn(Mono.just(commande));

            service.demander(PORTEE, commande.getId(),
                    new DemandeDeRetourRequest(MotifRetour.ENDOMMAGE, "casse")).block();

            verify(machine).faireAvancer(any(), eq(StatutCommande.RETOUR_EN_COURS),
                    any(), any(), any());
        }
    }

    @Nested
    @DisplayName("L'arbitrage")
    class Arbitrage {

        /**
         * Refuser sans dire pourquoi fait revenir l'acheteur, et Sankadi traite
         * deux fois le meme dossier. La contrainte de base le refuse aussi,
         * mais une erreur de contrainte ne dit rien a l'agent qui remplit le
         * formulaire.
         */
        @Test
        @DisplayName("un refus sans motif est rejete AVANT toute ecriture")
        void refusSansMotifRejete() {
            StepVerifier.create(service.arbitrer(UUID.randomUUID(),
                            new DecisionDeRetourRequest(false, "  "), "agent"))
                    .expectErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(RefusExplique.class);
                        assertThat(((RefusExplique) erreur).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST);
                    })
                    .verify();

            verify(retours, never()).changerStatut(any(), any(), any());
        }

        /**
         * Un refus renvoie la commande a LIVREE : elle reprend son cours, le
         * balayeur pourra la cloturer, et la boutique sera payee. Sans ce
         * retour, un refus la laisserait bloquee pour toujours.
         */
        @Test
        @DisplayName("un refus ramene la commande a LIVREE")
        void refusRamneLaCommandeALivree() {
            Retour demande = retour(StatutRetour.DEMANDE);
            when(retours.findById(any(UUID.class))).thenReturn(Mono.just(demande));
            leVerrouAccepte(StatutRetour.DEMANDE);
            when(retours.save(any(Retour.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));
            when(machine.faireAvancer(any(), any(), any(), any(), any()))
                    .thenReturn(Mono.just(commande));

            service.arbitrer(demande.getId(),
                    new DecisionDeRetourRequest(false, "trace d'usage"), "agent").block();

            verify(machine).faireAvancer(any(), eq(StatutCommande.LIVREE),
                    any(), any(), any());
        }

        /**
         * Une acceptation laisse la commande en RETOUR_EN_COURS : la boutique
         * n'est pas payee tant que l'article n'est pas revenu.
         */
        @Test
        @DisplayName("une acceptation ne change PAS l'etat de la commande")
        void acceptationNeDebloquePas() {
            Retour demande = retour(StatutRetour.DEMANDE);
            when(retours.findById(any(UUID.class))).thenReturn(Mono.just(demande));
            leVerrouAccepte(StatutRetour.DEMANDE);
            when(retours.save(any(Retour.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));

            service.arbitrer(demande.getId(),
                    new DecisionDeRetourRequest(true, null), "agent").block();

            verify(machine, never()).faireAvancer(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Les quatre etapes ne se sautent pas")
    class EtapesNonSautables {

        /**
         * <h3>LE test de ce fichier</h3>
         *
         * Rembourser depuis ACCEPTE — sans etre passe par RECU — doit echouer.
         * Accepter un retour et avoir recu l'article sont deux faits
         * differents, et les confondre fait rembourser un colis jamais revenu.
         */
        @Test
        @DisplayName("on ne rembourse PAS un retour seulement accepte")
        void pasDeRemboursementSansReception() {
            Retour accepte = retour(StatutRetour.ACCEPTE);
            when(retours.findById(any(UUID.class))).thenReturn(Mono.just(accepte));
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));

            StepVerifier.create(service.rembourser(accepte.getId(), "agent"))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .withFailMessage("""
                                    Un retour seulement ACCEPTE a pu etre rembourse.

                                    L'article n'est jamais revenu. C'est l'erreur la \
                                    plus couteuse d'une place de marche, et elle est \
                                    industrialisable : on demande, on obtient \
                                    l'accord, on n'envoie rien.""")
                            .isInstanceOf(OrderException.class))
                    .verify();

            verify(retours, never()).save(any());
        }

        /**
         * Le stock est rendu A LA RECEPTION, et non a l'acceptation. A
         * l'acceptation, l'article n'etait pas encore reparti : recrediter la
         * boutique l'aurait fait vendre deux fois le meme exemplaire.
         */
        @Test
        @DisplayName("la reception REND le stock")
        void receptionRendLeStock() {
            Retour accepte = retour(StatutRetour.ACCEPTE);
            when(retours.findById(any(UUID.class))).thenReturn(Mono.just(accepte));
            leVerrouAccepte(StatutRetour.ACCEPTE);
            when(retours.save(any(Retour.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));
            when(commandeService.rendreLeStockDe(any(Commande.class)))
                    .thenReturn(Mono.just(1));

            service.marquerRecu(accepte.getId(), "agent").block();

            verify(commandeService).rendreLeStockDe(any(Commande.class));
        }

        /**
         * L'acceptation, elle, ne rend RIEN : l'article n'est pas reparti.
         */
        @Test
        @DisplayName("l'acceptation ne rend AUCUN stock")
        void acceptationNeRendPasLeStock() {
            Retour demande = retour(StatutRetour.DEMANDE);
            when(retours.findById(any(UUID.class))).thenReturn(Mono.just(demande));
            leVerrouAccepte(StatutRetour.DEMANDE);
            when(retours.save(any(Retour.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));

            service.arbitrer(demande.getId(),
                    new DecisionDeRetourRequest(true, null), "agent").block();

            verify(commandeService, never()).rendreLeStockDe(any());
        }

        /**
         * Le montant rembourse vient de la COMMANDE, et non d'une valeur
         * fournie par l'agent : un montant saisi a la main est un montant qui
         * finit par differer de ce qui a ete encaisse.
         */
        @Test
        @DisplayName("le montant rembourse est celui de la commande, pas une saisie")
        void montantVientDeLaCommande() {
            Retour recu = retour(StatutRetour.RECU);
            recu.setRecuLe(LocalDateTime.now());
            when(retours.findById(any(UUID.class))).thenReturn(Mono.just(recu));
            leVerrouAccepte(StatutRetour.RECU);
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));
            when(machine.faireAvancer(any(), any(), any(), any(), any()))
                    .thenReturn(Mono.just(commande));

            java.util.concurrent.atomic.AtomicReference<Retour> ecrit =
                    new java.util.concurrent.atomic.AtomicReference<>();
            when(retours.save(any(Retour.class))).thenAnswer(appel -> {
                ecrit.set(appel.getArgument(0));
                return Mono.just(appel.getArgument(0));
            });

            service.rembourser(recu.getId(), "agent").block();

            assertThat(ecrit.get().getMontantRembourse())
                    .withFailMessage("Le montant rembourse ne vient pas de la commande.")
                    .isEqualTo(10000L);
            assertThat(ecrit.get().getRembourseLe()).isNotNull();
            verify(machine).faireAvancer(any(), eq(StatutCommande.REMBOURSEE),
                    any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Ce que l'acheteur voit")
    class VueAcheteur {

        /**
         * L'acheteur ne voit pas QUI chez Sankadi a tranche : nommer l'agent
         * l'expose a la pression d'un vendeur mecontent. Il voit en revanche le
         * MOTIF, dont il a besoin.
         */
        @Test
        @DisplayName("l'acheteur voit le motif mais pas l'identite de l'agent")
        void agentMasquePourLAcheteur() {
            Retour refuse = retour(StatutRetour.REFUSE);
            refuse.setDecidePar("agent-secret");
            refuse.setDecideLe(LocalDateTime.now());
            refuse.setMotifDecision("trace d'usage");

            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));
            when(retours.findByCommandeIdOrderByCreatedAtDesc(commande.getId()))
                    .thenReturn(reactor.core.publisher.Flux.just(refuse));

            StepVerifier.create(service.mesRetours(PORTEE, commande.getId()))
                    .assertNext(rendu -> {
                        assertThat(rendu.decidePar())
                                .withFailMessage("""
                                        L'identite de l'agent est rendue a l'acheteur.

                                        Nommer l'agent l'expose a la pression d'un \
                                        vendeur mecontent.""")
                                .isNull();
                        assertThat(rendu.motifDecision())
                                .withFailMessage("Le motif est masque : l'acheteur ne "
                                        + "sait pas pourquoi on a refuse.")
                                .isEqualTo("trace d'usage");
                    })
                    .verifyComplete();
        }
    }
}
