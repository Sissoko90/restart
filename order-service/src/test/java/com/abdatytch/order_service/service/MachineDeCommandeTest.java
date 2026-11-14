package com.abdatytch.order_service.service;

import com.abdatytch.order_service.entities.Commande;
import com.abdatytch.order_service.entities.EvenementCommande;
import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.exception.OrderException;
import com.abdatytch.order_service.metrics.OrderMetrics;
import com.abdatytch.order_service.repository.CommandeRepository;
import com.abdatytch.order_service.repository.EvenementCommandeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L'applicateur de transitions, mis a l'epreuve.
 *
 * <h2>Ce que ces tests fixent</h2>
 *
 * Les trois gestes qui ne doivent jamais se separer : verifier la legalite,
 * ecrire SOUS VERROU, inscrire au registre. Dispersees, ces operations
 * finissent par se desaccorder — un chemin oublie le registre, un autre oublie
 * la verification.
 *
 * <h2>Le mock IMITE la base</h2>
 *
 * {@code changerStatut} rend 1 ou 0 selon que l'etat attendu correspond, comme
 * le ferait un {@code WHERE statut = :attendu}. Un mock qui rendrait toujours 1
 * ferait passer un verrou inexistant : c'est l'erreur que ce depot a deja
 * payee sur des mocks qui n'assignaient pas d'identifiant.
 */
@DisplayName("MachineDeCommande — le verrou et le registre")
class MachineDeCommandeTest {

    private CommandeRepository commandes;
    private EvenementCommandeRepository evenements;
    private OrderMetrics metriques;
    private SimpleMeterRegistry registre;
    private MachineDeCommande machine;

    private Commande commande;

    @BeforeEach
    void preparer() {
        commandes = mock(CommandeRepository.class);
        evenements = mock(EvenementCommandeRepository.class);
        registre = new SimpleMeterRegistry();
        metriques = new OrderMetrics(registre);
        machine = new MachineDeCommande(commandes, evenements, metriques);

        commande = new Commande();
        commande.setId(UUID.randomUUID());
        commande.setNumero("CMD-261114-TEST01");
        commande.setStatut(StatutCommande.A_PAYER);

        // Le registre accepte et rend ce qu'on lui donne, comme le ferait un
        // save() reel.
        when(evenements.save(any(EvenementCommande.class)))
                .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
    }

    /** Le verrou de la base : 1 si l'etat attendu correspond, 0 sinon. */
    private void laBaseEstA(StatutCommande etatReel) {
        when(commandes.changerStatut(any(UUID.class), any(StatutCommande.class),
                any(StatutCommande.class)))
                .thenAnswer(appel -> Mono.just(
                        appel.getArgument(1) == etatReel ? 1 : 0));
    }

    @Nested
    @DisplayName("La legalite de la transition")
    class Legalite {

        /**
         * La legalite est demandee a l'ENUMERATION, qui detient la machine. La
         * reecrire ici en ferait une seconde copie, et c'est la copie la moins
         * lue qui finirait par decider.
         */
        @Test
        @DisplayName("une transition illegale est refusee SANS toucher la base")
        void transitionIllegaleNEcritRien() {
            StepVerifier.create(machine.faireAvancer(commande, StatutCommande.LIVREE,
                            "agent", RoleDeTransition.ADMIN, null))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(OrderException.class))
                    .verify();

            // LE POINT DU TEST : aucune ecriture, et aucun evenement. Une
            // transition refusee qui aurait tout de meme inscrit au registre
            // laisserait un historique qui raconte ce qui ne s'est pas passe.
            verify(commandes, never()).changerStatut(any(), any(), any());
            verify(evenements, never()).save(any());
        }

        @Test
        @DisplayName("une transition legale est ecrite et inscrite")
        void transitionLegalePasse() {
            laBaseEstA(StatutCommande.A_PAYER);
            Commande apres = new Commande();
            apres.setId(commande.getId());
            apres.setStatut(StatutCommande.PAYEE);
            when(commandes.findById(eq(commande.getId()))).thenReturn(Mono.just(apres));

            StepVerifier.create(machine.faireAvancer(commande, StatutCommande.PAYEE,
                            "agent", RoleDeTransition.ADMIN, "encaissement constate"))
                    .assertNext(rendue -> assertThat(rendue.getStatut())
                            .isEqualTo(StatutCommande.PAYEE))
                    .verifyComplete();

            verify(evenements).save(any(EvenementCommande.class));
        }
    }

    @Nested
    @DisplayName("Le verrou optimiste")
    class Verrou {

        /**
         * <h3>Le cas qui compte</h3>
         *
         * La commande a ete lue en {@code A_PAYER}, mais la base est deja en
         * {@code PAYEE} : un autre appel a gagne entre-temps. Le
         * {@code WHERE statut = :attendu} ne trouve plus rien, zero ligne est
         * modifiee, et la transition DOIT echouer.
         *
         * <p>Sans ce verrou, les deux appels ecriraient, et la commande
         * passerait deux fois a PAYEE — donc deux evenements au registre, donc
         * un historique qui montre deux encaissements pour un seul paiement.
         */
        @Test
        @DisplayName("une transition perdue au profit d'un autre appel echoue")
        void transitionPerdueEchoue() {
            // L'objet lu dit A_PAYER, la base dit deja PAYEE.
            laBaseEstA(StatutCommande.PAYEE);

            StepVerifier.create(machine.faireAvancer(commande, StatutCommande.PAYEE,
                            "agent", RoleDeTransition.ADMIN, null))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .withFailMessage("""
                                    La transition a reussi alors que la base avait \
                                    deja change d'etat.

                                    Deux appels concurrents ecriraient tous deux, et \
                                    le registre montrerait deux encaissements pour un \
                                    seul paiement.""")
                            .isInstanceOf(OrderException.class))
                    .verify();
        }

        /**
         * Et surtout : RIEN n'est inscrit au registre quand la transition est
         * perdue. Un evenement ecrit pour une transition qui n'a pas eu lieu
         * est un historique qui ment, et c'est exactement ce dont Sankadi se
         * sert pour arbitrer.
         */
        @Test
        @DisplayName("une transition perdue n'inscrit rien au registre")
        void transitionPerdueNInscritRien() {
            laBaseEstA(StatutCommande.PAYEE);

            machine.faireAvancer(commande, StatutCommande.PAYEE, "agent",
                            RoleDeTransition.ADMIN, null)
                    .onErrorResume(e -> Mono.empty())
                    .block();

            verify(evenements, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Le registre")
    class Registre {

        /**
         * Le registre porte l'etat d'ARRIVEE et celui de DEPART. Sans l'etat de
         * depart, l'historique dit ou la commande est allee mais pas d'ou elle
         * venait — et une transition inattendue devient indetectable.
         */
        @Test
        @DisplayName("l'evenement porte les deux etats, l'auteur et son role")
        void evenementComplet() {
            laBaseEstA(StatutCommande.A_PAYER);
            Commande apres = new Commande();
            apres.setId(commande.getId());
            apres.setStatut(StatutCommande.PAYEE);
            when(commandes.findById(eq(commande.getId()))).thenReturn(Mono.just(apres));

            machine.faireAvancer(commande, StatutCommande.PAYEE, "agent-42",
                    RoleDeTransition.ADMIN, "vu sur le relve").block();

            ArgumentCaptor<EvenementCommande> capture =
                    ArgumentCaptor.forClass(EvenementCommande.class);
            verify(evenements).save(capture.capture());
            EvenementCommande inscrit = capture.getValue();

            assertThat(inscrit.getDeStatut()).isEqualTo(StatutCommande.A_PAYER);
            assertThat(inscrit.getVersStatut()).isEqualTo(StatutCommande.PAYEE);
            assertThat(inscrit.getParQui()).isEqualTo("agent-42");
            assertThat(inscrit.getRole())
                    .withFailMessage("""
                            Le ROLE n'est pas inscrit.

                            La meme personne peut etre acheteuse sur une commande et \
                            arbitre sur une autre, et c'est le role exerce qui \
                            legitime la transition. Sans lui, l'historique ne dit \
                            pas a quel titre quelqu'un a agi.""")
                    .isEqualTo(RoleDeTransition.ADMIN);
            assertThat(inscrit.getCommandeId()).isEqualTo(commande.getId());
        }

        /**
         * L'identifiant doit rester NUL : il est produit par la base. Le poser
         * ferait emettre un UPDATE pour une ligne jamais inseree, et le
         * {@code save()} serait silencieusement sans effet — donc un evenement
         * perdu, donc un trou dans l'historique d'arbitrage.
         */
        @Test
        @DisplayName("l'evenement est ecrit sans identifiant assigne")
        void aucunIdentifiantAssigne() {
            laBaseEstA(StatutCommande.A_PAYER);
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(commande));

            machine.faireAvancer(commande, StatutCommande.ANNULEE, "acheteur",
                    RoleDeTransition.ACHETEUR, "change d'avis").block();

            ArgumentCaptor<EvenementCommande> capture =
                    ArgumentCaptor.forClass(EvenementCommande.class);
            verify(evenements).save(capture.capture());

            assertThat(capture.getValue().getId())
                    .withFailMessage("""
                            Un identifiant est assigne avant l'ecriture.

                            Spring Data R2DBC emettrait un UPDATE pour une ligne \
                            jamais inseree, et le save() serait silencieusement sans \
                            effet : un evenement perdu, donc un trou dans \
                            l'historique d'arbitrage.""")
                    .isNull();
            assertThat(capture.getValue().getCreatedAt())
                    .withFailMessage("L'horodatage n'est pas pose : la colonne est "
                            + "NOT NULL et l'insertion echouerait.")
                    .isNotNull();
        }

        /**
         * L'evenement de creation n'a pas d'etat precedent. Sans lui,
         * l'historique d'une commande annulee n'indiquerait pas qui l'avait
         * passee.
         */
        @Test
        @DisplayName("la creation s'inscrit avec un etat de depart NUL")
        void creationSansEtatPrecedent() {
            UUID id = UUID.randomUUID();

            machine.inscrireLaCreation(id, "acheteur-7").block();

            ArgumentCaptor<EvenementCommande> capture =
                    ArgumentCaptor.forClass(EvenementCommande.class);
            verify(evenements).save(capture.capture());
            EvenementCommande inscrit = capture.getValue();

            assertThat(inscrit.getDeStatut())
                    .withFailMessage("""
                            L'evenement de creation porte un etat de depart.

                            La contrainte evenement_transition_reelle refuse \
                            de_statut = vers_statut, et une creation n'a pas \
                            d'etat precedent.""")
                    .isNull();
            assertThat(inscrit.getVersStatut()).isEqualTo(StatutCommande.A_PAYER);
            assertThat(inscrit.getRole()).isEqualTo(RoleDeTransition.ACHETEUR);
        }
    }

    @Nested
    @DisplayName("La metrique")
    class Metrique {

        /**
         * La transition est comptee dans la machine, et nulle part ailleurs :
         * un seul endroit l'applique, donc un seul endroit la compte. Compter
         * chez chaque appelant ferait oublier un chemin, et les autres
         * compteraient double — c'est le defaut qu'a eu
         * {@code recordAccountLockout} dans ce depot.
         */
        @Test
        @DisplayName("une transition perdue n'est PAS comptee")
        void transitionPerdueNEstPasComptee() {
            laBaseEstA(StatutCommande.PAYEE);
            double avant = compteurDeTransitions();

            machine.faireAvancer(commande, StatutCommande.PAYEE, "agent",
                            RoleDeTransition.ADMIN, null)
                    .onErrorResume(e -> Mono.empty())
                    .block();

            assertThat(compteurDeTransitions())
                    .withFailMessage("""
                            Une transition perdue a ete comptee.

                            Le compteur servirait alors a mesurer les tentatives et \
                            non les transitions, et l'alerte AucuneClotureDeCommande \
                            resterait verte sur des echecs repetes.""")
                    .isEqualTo(avant);
        }

        /**
         * La somme des transitions comptees, toutes etiquettes confondues.
         *
         * <p>La premiere version de cette methode rendait {@code 0d} EN DUR :
         * le test affirmait alors {@code 0 == 0} et ne prouvait rien, tout en
         * ayant l'air de verifier quelque chose. C'est la forme la plus
         * trompeuse d'un test, parce qu'elle est verte.
         */
        /**
         * Le controle a vide du test precedent : sans lui, un compteur qui
         * n'est JAMAIS incremente passerait les deux.
         */
        @Test
        @DisplayName("une transition reussie EST comptee, avec son etat et son role")
        void transitionReussieEstComptee() {
            laBaseEstA(StatutCommande.A_PAYER);
            Commande apres = new Commande();
            apres.setId(commande.getId());
            apres.setStatut(StatutCommande.PAYEE);
            when(commandes.findById(any(UUID.class))).thenReturn(Mono.just(apres));

            double avant = compteurDeTransitions();
            machine.faireAvancer(commande, StatutCommande.PAYEE, "agent",
                    RoleDeTransition.ADMIN, null).block();

            assertThat(compteurDeTransitions())
                    .withFailMessage("Une transition reussie n'est pas comptee : la "
                            + "metrique order.transitions.total resterait a zero, et "
                            + "l'alerte AucuneClotureDeCommande ne pourrait jamais "
                            + "distinguer « rien ne bouge » de « rien n'est mesure ».")
                    .isEqualTo(avant + 1);

            assertThat(registre.find(OrderMetrics.TRANSITIONS)
                    .tag("vers", "PAYEE").tag("role", "ADMIN").counter())
                    .withFailMessage("La transition n'est pas etiquetee par son etat "
                            + "d'arrivee et son role : les alertes filtrent sur "
                            + "vers=\"CLOTUREE\" et ne trouveraient rien.")
                    .isNotNull();
        }

        private double compteurDeTransitions() {
            return registre.find(OrderMetrics.TRANSITIONS).counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count)
                    .sum();
        }
    }
}
