package com.abdatytch.security_service.service;

import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.dto.request.PoseDeRegleIpRequest;
import com.abdatytch.security_service.entities.RegleDIp;
import com.abdatytch.security_service.enums.MotifDeRegleIp;
import com.abdatytch.security_service.exception.RessourceIntrouvableException;
import com.abdatytch.security_service.exception.SecurityPolicyException;
import com.abdatytch.security_service.repositories.RegleDIpRepository;
import com.abdatytch.security_service.service.impl.RegleDIpServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Les regles de blocage d'adresses.
 *
 * <h2>Ce que ferme cette suite</h2>
 *
 * La surface n'existait pas : la table {@code regle_ip}, son entite, son depot
 * et la publication vers Redis etaient ecrits, mais aucun service ni controleur
 * ne permettait a une regle de naitre. Le depot n'etait consomme que par la
 * publication, qui republiait indefiniment une table vide.
 *
 * <p>Chaque cas ci-dessous nomme une facon dont un blocage pourrait etre
 * <b>confirme a son auteur sans jamais s'appliquer</b>. C'est le motif dominant
 * de ce depot, et sur cette surface il a plusieurs formes distinctes : le
 * prefixe CIDR que l'applicateur ne sait pas lire, l'echeance deja passee, et
 * l'adresse ecrite sous une forme que le gateway ne formera jamais.
 */
@DisplayName("Regles d'adresses : un blocage confirme doit bloquer")
class RegleDIpServiceImplTest {

    private static final String AUTEUR = "admin-1";

    private Map<String, RegleDIp> magasin;
    private RegleDIpServiceImpl service;

    @BeforeEach
    void setUp() {
        // LinkedHashMap : l'ordre d'insertion est stable, donc les assertions
        // sur le tri mesurent le tri du service et non celui de la table de
        // hachage.
        magasin = new LinkedHashMap<>();

        RegleDIpRepository depot = mock(RegleDIpRepository.class);
        when(depot.findById(anyString())).thenAnswer(appel ->
                Mono.justOrEmpty(magasin.get((String) appel.getArgument(0))));
        when(depot.save(any(RegleDIp.class))).thenAnswer(appel -> {
            RegleDIp regle = appel.getArgument(0);
            magasin.put(regle.getId(), regle);
            return Mono.just(regle);
        });
        when(depot.findByLeveeLeIsNull()).thenAnswer(appel ->
                Flux.fromIterable(magasin.values())
                        .filter(regle -> regle.getLeveeLe() == null));
        when(depot.findByAdresseAndLeveeLeIsNull(anyString())).thenAnswer(appel -> {
            String adresse = appel.getArgument(0);
            return Flux.fromIterable(magasin.values())
                    .filter(regle -> regle.getLeveeLe() == null)
                    .filter(regle -> adresse.equals(regle.getAdresse()));
        });

        service = new RegleDIpServiceImpl(depot);
    }

    private static PoseDeRegleIpRequest demande(String adresse, MotifDeRegleIp motif,
                                                Instant expireLe) {
        return PoseDeRegleIpRequest.builder()
                .adresse(adresse)
                .motif(motif)
                .expireLe(expireLe)
                .build();
    }

    /** Une regle deja en base, posee a la main pour construire un etat de depart. */
    private RegleDIp deja(String adresse, Instant expireLe, Instant creeLe) {
        RegleDIp regle = RegleDIp.builder()
                .id(UUID.randomUUID().toString())
                .adresse(adresse)
                .motif(MotifDeRegleIp.DECISION_ADMINISTRATIVE)
                .expireLe(expireLe)
                .posePar(AUTEUR)
                .creeLe(creeLe)
                .build()
                .dejaEnBase();
        magasin.put(regle.getId(), regle);
        return regle;
    }

    @Nested
    @DisplayName("L'adresse enregistree est celle que le gateway formera")
    class FormeDeLAdresse {

        @ParameterizedTest(name = "{0} est enregistree comme {1}")
        @CsvSource({
                "41.223.10.5,       41.223.10.5",
                "041.223.010.005,   41.223.10.5",
                "2001:db8::1,       2001:db8:0:0:0:0:0:1",
        })
        @DisplayName("La forme canonique est stockee, pas la saisie")
        void formeCanonique(String saisie, String attendue) {
            StepVerifier.create(service.poser(
                            demande(saisie, MotifDeRegleIp.DECISION_ADMINISTRATIVE, null), AUTEUR))
                    .assertNext(posee -> assertThat(posee.getAdresse()).isEqualTo(attendue))
                    .verifyComplete();

            assertThat(magasin.values())
                    .extracting(RegleDIp::getAdresse)
                    .containsExactly(attendue);
        }

        @Test
        @DisplayName("Les espaces autour de la saisie sont absorbes")
        void espaces() {
            StepVerifier.create(service.poser(
                            demande("  203.0.113.7  ", MotifDeRegleIp.DECISION_ADMINISTRATIVE,
                                    null), AUTEUR))
                    .assertNext(posee ->
                            assertThat(posee.getAdresse()).isEqualTo("203.0.113.7"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deux ecritures d'une meme machine sont la MEME regle")
        void deuxEcrituresUneSeuleMachine() {
            // Sans normalisation, ces deux appels creeraient deux lignes, et le
            // gateway n'en consulterait qu'une : l'administrateur aurait bloque
            // deux fois et une seule aurait un effet.
            StepVerifier.create(service.poser(
                            demande("41.223.10.5", MotifDeRegleIp.DECISION_ADMINISTRATIVE, null),
                            AUTEUR))
                    .expectNextCount(1)
                    .verifyComplete();

            StepVerifier.create(service.poser(
                            demande("041.223.010.005", MotifDeRegleIp.DECISION_ADMINISTRATIVE,
                                    null), AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(SecurityPolicyException.class)
                            .hasMessage(SecurityConstants.ADRESSE_DEJA_BLOQUEE));

            assertThat(magasin).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Ce qui serait confirme sans jamais bloquer est refuse")
    class RefusDesReglesSansEffet {

        @ParameterizedTest
        @ValueSource(strings = {"41.223.0.0/16", "203.0.113.7/32", "::/0", "2001:db8::/32"})
        @DisplayName("Un prefixe CIDR est refuse, et le refus dit POURQUOI")
        void cidrRefuse(String prefixe) {
            // L'applicateur fait un hasKey sur une cle exacte : une regle de
            // prefixe serait enregistree, publiee, et ne correspondrait a aucune
            // requete.
            //
            // Le message distingue ce refus de « adresse illisible » : sans
            // cela, l'administrateur croirait a une faute de frappe et
            // reessaierait une autre ecriture du meme prefixe.
            StepVerifier.create(service.poser(
                            demande(prefixe, MotifDeRegleIp.DECISION_ADMINISTRATIVE, null),
                            AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(SecurityPolicyException.class)
                            .hasMessage(SecurityConstants.CIDR_NON_APPLICABLE));

            assertThat(magasin).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"999.1.1.1", "256.0.0.1", "1.2.3", "1.2.3.4.5",
                "localhost", "exemple.test", "abcdef", ""})
        @DisplayName("Une adresse illisible est refusee")
        void adresseIllisible(String saisie) {
            StepVerifier.create(service.poser(
                            demande(saisie, MotifDeRegleIp.DECISION_ADMINISTRATIVE, null),
                            AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(SecurityPolicyException.class)
                            .hasMessage(SecurityConstants.ADRESSE_INVALIDE));

            assertThat(magasin).isEmpty();
        }

        @Test
        @DisplayName("Le refus ne reprend PAS la saisie de l'appelant")
        void refusSansEchoDeLaSaisie() {
            // Le gestionnaire d'exceptions journalise le message du refus : y
            // mettre la saisie ferait entrer une valeur fournie par l'appelant
            // dans les journaux.
            String saisie = "<script>alert(1)</script>";

            StepVerifier.create(service.poser(
                            demande(saisie, MotifDeRegleIp.DECISION_ADMINISTRATIVE, null),
                            AUTEUR))
                    .verifyErrorSatisfies(erreur ->
                            assertThat(erreur.getMessage()).doesNotContain("script"));
        }

        @Test
        @DisplayName("Une echeance deja passee est refusee")
        void echeancePassee() {
            // Elle serait ecartee par estActive des l'ecriture : enregistree,
            // publiee, et sans effet.
            Instant hier = Instant.now().minus(Duration.ofDays(1));

            StepVerifier.create(service.poser(
                            demande("203.0.113.7", MotifDeRegleIp.FORCE_BRUTE, hier), AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(SecurityPolicyException.class)
                            .hasMessage(SecurityConstants.ECHEANCE_DEJA_PASSEE));

            assertThat(magasin).isEmpty();
        }
    }

    @Nested
    @DisplayName("Permanent interdit a une detection automatique")
    class PermanentEtDetection {

        @ParameterizedTest
        @EnumSource(value = MotifDeRegleIp.class,
                names = {"FORCE_BRUTE", "DEBIT_EXCESSIF", "ATTAQUE_APPLICATIVE"})
        @DisplayName("Une detection sans echeance est refusee PAR LE SERVICE")
        void detectionSansEcheance(MotifDeRegleIp motif) {
            // Verifie sur le service et non seulement sur le DTO : la validation
            // declarative ne s'execute que sur le chemin HTTP. Un filtre qui
            // posera une detection en appelant le service directement
            // contournerait la regle, et un faux positif deviendrait permanent.
            StepVerifier.create(service.poser(demande("203.0.113.7", motif, null), AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(SecurityPolicyException.class)
                            .hasMessage(SecurityConstants.PERMANENT_INTERDIT_POUR_DETECTION));

            assertThat(magasin).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(value = MotifDeRegleIp.class,
                names = {"DECISION_ADMINISTRATIVE", "SIGNALEMENT_EXTERNE"})
        @DisplayName("Une decision humaine peut etre permanente")
        void decisionHumainePermanente(MotifDeRegleIp motif) {
            // Quelqu'un a regarde et a tranche : c'est le seul cas ou une regle
            // sans echeance se justifie.
            StepVerifier.create(service.poser(demande("203.0.113.7", motif, null), AUTEUR))
                    .assertNext(posee -> {
                        assertThat(posee.getExpireLe()).isNull();
                        assertThat(posee.getActive()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Une detection AVEC echeance est acceptee")
        void detectionAvecEcheance() {
            Instant demain = Instant.now().plus(Duration.ofDays(1));

            StepVerifier.create(service.poser(
                            demande("203.0.113.7", MotifDeRegleIp.FORCE_BRUTE, demain), AUTEUR))
                    .assertNext(posee -> assertThat(posee.getExpireLe()).isEqualTo(demain))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Doublons")
    class Doublons {

        @Test
        @DisplayName("Une regle ACTIVE fait obstacle a une nouvelle pose")
        void regleActiveFaitObstacle() {
            // Remplacer en silence serait plus souple mais dangereux : une
            // decision permanente pourrait etre rabaissee a une heure sans que
            // personne le voie.
            deja("203.0.113.7", null, Instant.now());

            StepVerifier.create(service.poser(
                            demande("203.0.113.7", MotifDeRegleIp.DECISION_ADMINISTRATIVE, null),
                            AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(SecurityPolicyException.class)
                            .hasMessage(SecurityConstants.ADRESSE_DEJA_BLOQUEE));
        }

        @Test
        @DisplayName("Une regle EXPIREE et non levee ne fait pas obstacle")
        void regleExpireeNeFaitPasObstacle() {
            // Elle ne bloque plus rien : refuser a cause d'elle obligerait a
            // lever une regle qui ne s'applique deja plus.
            deja("203.0.113.7", Instant.now().minus(Duration.ofHours(1)),
                    Instant.now().minus(Duration.ofDays(2)));

            StepVerifier.create(service.poser(
                            demande("203.0.113.7", MotifDeRegleIp.DECISION_ADMINISTRATIVE, null),
                            AUTEUR))
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(magasin).hasSize(2);
        }

        @Test
        @DisplayName("Une autre adresse ne fait pas obstacle")
        void autreAdresse() {
            deja("203.0.113.7", null, Instant.now());

            StepVerifier.create(service.poser(
                            demande("203.0.113.8", MotifDeRegleIp.DECISION_ADMINISTRATIVE, null),
                            AUTEUR))
                    .expectNextCount(1)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Levee")
    class Levee {

        @Test
        @DisplayName("La levee enregistre l'auteur et la date, et GARDE la ligne")
        void leveeGardeLaLigne() {
            RegleDIp posee = deja("203.0.113.7", null, Instant.now());

            StepVerifier.create(service.lever(posee.getId(), "admin-2"))
                    .assertNext(levee -> {
                        assertThat(levee.getLeveePar()).isEqualTo("admin-2");
                        assertThat(levee.getLeveeLe()).isNotNull();
                        assertThat(levee.getActive()).isFalse();
                        // L'auteur d'origine survit a la levee : « qui a bloque »
                        // et « qui a debloque » sont deux questions distinctes.
                        assertThat(levee.getPosePar()).isEqualTo(AUTEUR);
                    })
                    .verifyComplete();

            // « Effacer n'est pas supprimer la ligne » : la decision et sa levee
            // doivent rester lisibles.
            assertThat(magasin).containsKey(posee.getId());
        }

        @Test
        @DisplayName("Un identifiant inconnu rend RessourceIntrouvable, donc 404")
        void identifiantInconnu() {
            // Le TYPE porte le statut. Une SecurityPolicyException nue rendrait
            // un 400, et permettrait de distinguer un identifiant existant d'un
            // identifiant inconnu — donc d'enumerer les regles.
            StepVerifier.create(service.lever("inconnu", AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(RessourceIntrouvableException.class)
                            .hasMessage(SecurityConstants.REGLE_IP_INTROUVABLE));
        }

        @Test
        @DisplayName("Lever deux fois rend une erreur, pas un succes")
        void leveeDeuxFois() {
            // Un succes ferait croire qu'un blocage courait encore.
            RegleDIp posee = deja("203.0.113.7", null, Instant.now());

            StepVerifier.create(service.lever(posee.getId(), AUTEUR))
                    .expectNextCount(1)
                    .verifyComplete();

            StepVerifier.create(service.lever(posee.getId(), AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(SecurityPolicyException.class)
                            .hasMessage(SecurityConstants.REGLE_IP_DEJA_LEVEE));
        }
    }

    @Nested
    @DisplayName("Consultation")
    class Consultation {

        @Test
        @DisplayName("Les actives d'abord, puis les plus recentes")
        void triActivesDAbord() {
            Instant maintenant = Instant.now();
            deja("203.0.113.1", maintenant.minus(Duration.ofHours(1)),
                    maintenant.minus(Duration.ofDays(3)));            // expiree
            deja("203.0.113.2", null, maintenant.minus(Duration.ofDays(2)));   // active ancienne
            deja("203.0.113.3", null, maintenant.minus(Duration.ofHours(2)));  // active recente

            StepVerifier.create(service.regles())
                    .assertNext(regle -> assertThat(regle.getAdresse()).isEqualTo("203.0.113.3"))
                    .assertNext(regle -> assertThat(regle.getAdresse()).isEqualTo("203.0.113.2"))
                    .assertNext(regle -> {
                        // L'expiree figure dans la liste, marquee inactive : une
                        // echeance passee n'est pas une levee, et savoir qu'une
                        // adresse a ete bloquee est ce qu'on vient chercher ici.
                        assertThat(regle.getAdresse()).isEqualTo("203.0.113.1");
                        assertThat(regle.getActive()).isFalse();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Une regle levee ne figure pas dans la liste")
        void leveeAbsente() {
            RegleDIp posee = deja("203.0.113.7", null, Instant.now());
            StepVerifier.create(service.lever(posee.getId(), AUTEUR))
                    .expectNextCount(1)
                    .verifyComplete();

            StepVerifier.create(service.regles()).verifyComplete();
        }
    }
}
