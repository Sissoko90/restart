package com.abdatytch.order_service.scheduler;

import com.abdatytch.order_service.dto.response.CommandeResponse;
import com.abdatytch.order_service.entities.Commande;
import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.metrics.OrderMetrics;
import com.abdatytch.order_service.repository.CommandeRepository;
import com.abdatytch.order_service.repository.RetourRepository;
import com.abdatytch.order_service.service.ICommandeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les deux balayages, mis a l'epreuve.
 *
 * <h2>Ce que ces tests fixent</h2>
 *
 * L'ABSORPTION PAR COMMANDE. Une commande qui resiste ne doit pas empecher de
 * traiter les suivantes : c'est la difference entre « une commande en retard »
 * et « plus aucune commande cloturee », et la seconde se solde par des
 * boutiques impayees.
 *
 * <p>Les balayeurs s'abonnent eux-memes — ils sont appeles par
 * {@code @Scheduled} et ne rendent rien. Les tests verifient donc les EFFETS :
 * quelles transitions ont ete demandees, et combien de fois.
 */
@DisplayName("BalayeurDesCommandes")
class BalayeurDesCommandesTest {

    private CommandeRepository commandes;
    private RetourRepository retours;
    private ICommandeService service;
    private OrderMetrics metriques;
    private SimpleMeterRegistry registre;
    private BalayeurDesCommandes balayeur;

    @BeforeEach
    void preparer() {
        commandes = mock(CommandeRepository.class);
        retours = mock(RetourRepository.class);
        service = mock(ICommandeService.class);
        registre = new SimpleMeterRegistry();
        metriques = new OrderMetrics(registre);
        balayeur = new BalayeurDesCommandes(commandes, retours, service, metriques, 200);
    }

    private Commande commande(String numero) {
        Commande c = new Commande();
        c.setId(UUID.randomUUID());
        c.setNumero(numero);
        c.setStatut(StatutCommande.A_PAYER);
        return c;
    }

    private CommandeResponse reponse() {
        return new CommandeResponse(UUID.randomUUID(), "CMD-X", StatutCommande.ANNULEE,
                UUID.randomUUID(), "ML", 0L, null, null, "XOF",
                null, null, null, null, null, null, null, null, List.of());
    }

    @Nested
    @DisplayName("L'annulation des impayees")
    class Impayees {

        /**
         * Chaque commande echue est annulee, avec le role SYSTEME : personne ne
         * l'a decidee, et un audit doit pouvoir separer ce qu'un humain a voulu
         * de ce qu'une echeance a produit.
         */
        @Test
        @DisplayName("chaque impayee echue est annulee avec le role SYSTEME")
        void impayeesAnnuleesParLeSysteme() {
            when(commandes.impayeesEchues(any(LocalDateTime.class), anyInt()))
                    .thenReturn(Flux.just(commande("CMD-1"), commande("CMD-2")));
            when(service.fairePasserA(any(UUID.class), any(StatutCommande.class),
                    any(), any(), any())).thenReturn(Mono.just(reponse()));

            balayeur.annulerLesImpayees();

            verify(service, times(2)).fairePasserA(any(UUID.class),
                    eq(StatutCommande.ANNULEE), eq("balayeur"),
                    eq(RoleDeTransition.SYSTEME), any());
        }

        /**
         * <h3>LE test de ce fichier</h3>
         *
         * La premiere commande echoue, la seconde doit quand meme etre traitee.
         * Sans cette absorption, une seule commande recalcitrante bloquerait
         * tout le balayage — et le stock de toutes les suivantes resterait
         * retenu.
         */
        @Test
        @DisplayName("une commande qui echoue n'empeche pas de traiter les suivantes")
        void echecAbsorbeParCommande() {
            Commande recalcitrante = commande("CMD-KO");
            Commande suivante = commande("CMD-OK");

            when(commandes.impayeesEchues(any(LocalDateTime.class), anyInt()))
                    .thenReturn(Flux.just(recalcitrante, suivante));
            when(service.fairePasserA(eq(recalcitrante.getId()), any(), any(), any(), any()))
                    .thenReturn(Mono.error(new IllegalStateException("etat change")));
            when(service.fairePasserA(eq(suivante.getId()), any(), any(), any(), any()))
                    .thenReturn(Mono.just(reponse()));

            balayeur.annulerLesImpayees();

            verify(service).fairePasserA(eq(suivante.getId()),
                    eq(StatutCommande.ANNULEE), any(), any(), any());
        }

        @Test
        @DisplayName("aucune impayee : aucune transition demandee")
        void aucuneImpayee() {
            when(commandes.impayeesEchues(any(LocalDateTime.class), anyInt()))
                    .thenReturn(Flux.empty());

            balayeur.annulerLesImpayees();

            verify(service, times(0)).fairePasserA(any(), any(), any(), any(), any());
        }

        /**
         * Une panne de la requete elle-meme ne doit pas propager une exception
         * hors du balayeur : {@code @Scheduled} l'avalerait, mais le prochain
         * passage doit avoir lieu.
         */
        @Test
        @DisplayName("une panne de la requete ne fait pas remonter d'exception")
        void panneDeRequeteAbsorbee() {
            when(commandes.impayeesEchues(any(LocalDateTime.class), anyInt()))
                    .thenReturn(Flux.error(new IllegalStateException("base injoignable")));

            // Aucune exception ne doit sortir : l'assertion est l'absence de
            // levee. Un throw ici tuerait le fil d'ordonnancement.
            balayeur.annulerLesImpayees();
        }
    }

    @Nested
    @DisplayName("La cloture des livrees")
    class Clotures {

        /**
         * C'est le passage qui rend une commande PAYABLE. {@code CLOTUREE} est
         * l'etat que payment-service attendra pour verser : sans lui, aucune
         * boutique n'est jamais payee.
         */
        @Test
        @DisplayName("chaque livree hors fenetre est cloturee")
        void livreesCloturees() {
            when(commandes.aCloturer(any(LocalDateTime.class), anyInt()))
                    .thenReturn(Flux.just(commande("CMD-3")));
            when(service.fairePasserA(any(UUID.class), any(StatutCommande.class),
                    any(), any(), any())).thenReturn(Mono.just(reponse()));

            balayeur.cloturerLesLivrees();

            verify(service).fairePasserA(any(UUID.class), eq(StatutCommande.CLOTUREE),
                    eq("balayeur"), eq(RoleDeTransition.SYSTEME), any());
        }

        @Test
        @DisplayName("une cloture qui echoue n'empeche pas les suivantes")
        void clotureEchoueeAbsorbee() {
            Commande ko = commande("CMD-KO");
            Commande ok = commande("CMD-OK");

            when(commandes.aCloturer(any(LocalDateTime.class), anyInt()))
                    .thenReturn(Flux.just(ko, ok));
            when(service.fairePasserA(eq(ko.getId()), any(), any(), any(), any()))
                    .thenReturn(Mono.error(new IllegalStateException("retour ouvert")));
            when(service.fairePasserA(eq(ok.getId()), any(), any(), any(), any()))
                    .thenReturn(Mono.just(reponse()));

            balayeur.cloturerLesLivrees();

            verify(service).fairePasserA(eq(ok.getId()), eq(StatutCommande.CLOTUREE),
                    any(), any(), any());
        }

        @Test
        @DisplayName("une panne de la requete de cloture est absorbee")
        void panneDeClotureAbsorbee() {
            when(commandes.aCloturer(any(LocalDateTime.class), anyInt()))
                    .thenReturn(Flux.error(new IllegalStateException("base injoignable")));

            balayeur.cloturerLesLivrees();
        }
    }

    @Nested
    @DisplayName("Le releve des files")
    class ReleveDesFiles {

        /**
         * Les trois jauges sont alimentees par un recomptage periodique, et non
         * par une jauge a fonction de lecture : cette fonction est SYNCHRONE et
         * bloquerait sur du SQL depuis le fil du releve Prometheus.
         */
        @Test
        @DisplayName("les trois jauges sont renseignees")
        void troisJaugesRenseignees() {
            when(commandes.compterParStatut(StatutCommande.A_PAYER)).thenReturn(Mono.just(7L));
            when(retours.compterEnAttenteDArbitrage()).thenReturn(Mono.just(3L));
            when(commandes.compterParStatut(StatutCommande.LIVREE)).thenReturn(Mono.just(11L));

            balayeur.releverLesFiles();

            assertThat(jauge("commandes_a_payer")).isEqualTo(7d);
            assertThat(jauge("retours_a_arbitrer"))
                    .withFailMessage("""
                            La file d'arbitrage n'est pas relevee.

                            L'alerte FileDArbitrageFigee porte sur cette jauge : sans \
                            elle, elle reste verte pendant que les acheteurs \
                            attendent.""")
                    .isEqualTo(3d);
            assertThat(jauge("commandes_a_cloturer")).isEqualTo(11d);
        }

        /**
         * {@code Mono.zip} abandonne des qu'une source est vide. Une des trois
         * requetes qui ne rend rien laisserait les TROIS jauges a leur valeur
         * precedente, sans erreur — et les alertes porteraient sur des chiffres
         * perimes qu'on croirait frais.
         */
        @Test
        @DisplayName("une panne de comptage laisse les jauges inchangees sans lever")
        void panneDeComptageAbsorbee() {
            when(commandes.compterParStatut(StatutCommande.A_PAYER))
                    .thenReturn(Mono.error(new IllegalStateException("injoignable")));
            when(retours.compterEnAttenteDArbitrage()).thenReturn(Mono.just(3L));
            when(commandes.compterParStatut(StatutCommande.LIVREE)).thenReturn(Mono.just(11L));

            balayeur.releverLesFiles();

            assertThat(jauge("retours_a_arbitrer"))
                    .withFailMessage("Une jauge a ete ecrite alors que le zip a echoue : "
                            + "le releve serait partiel et incoherent.")
                    .isZero();
        }

        private double jauge(String nature) {
            var g = registre.find(OrderMetrics.FILES).tag("nature", nature).gauge();
            return g == null ? -1d : g.value();
        }
    }
}
