package com.abdatytch.security_service.service;

import com.abdatytch.security_service.dto.request.BlocageDePaysRequest;
import com.abdatytch.security_service.entities.PaysBloque;
import com.abdatytch.security_service.exception.SecurityPolicyException;
import com.abdatytch.security_service.repositories.PaysBloqueRepository;
import com.abdatytch.security_service.service.impl.PaysBloqueServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Les pays refuses, et les deux defauts du gateway corriges.
 *
 * <h2>D'ou vient cette surface</h2>
 *
 * De {@code GeoAdminController}, dans le gateway, qui tenait la liste dans un
 * ensemble Redis sans motif, sans auteur et sans historique. Deux defauts
 * accompagnaient cette implementation, et cette suite les ferme :
 *
 * <ol>
 *   <li>{@code isValidCountryCode} exigeait {@code [A-Z]{2}} et etait appele
 *       <b>avant</b> {@code toUpperCase()}. Bloquer {@code ml} etait donc refuse
 *       comme « code invalide », alors que la ligne suivante le normalisait.
 *       L'administrateur qui saisissait en minuscules recevait un refus
 *       incomprehensible ;</li>
 *   <li>{@code unblockCountry} ne validait <b>rien</b>. Un code fantaisiste
 *       rendait la meme reponse qu'un pays valide non bloque : aucun moyen de
 *       distinguer une faute de frappe d'un pays deja accepte.</li>
 * </ol>
 */
@DisplayName("Pays refuses : les deux defauts du gateway sont fermes")
class PaysBloqueServiceImplTest {

    private static final String AUTEUR = "admin-1";

    private Map<String, PaysBloque> magasin;
    private PaysBloqueServiceImpl service;

    @BeforeEach
    void setUp() {
        magasin = new HashMap<>();

        PaysBloqueRepository depot = mock(PaysBloqueRepository.class);
        when(depot.findById(anyString())).thenAnswer(appel ->
                Mono.justOrEmpty(magasin.get((String) appel.getArgument(0))));
        when(depot.save(any(PaysBloque.class))).thenAnswer(appel -> {
            PaysBloque pays = appel.getArgument(0);
            magasin.put(pays.getCodeIso(), pays);
            return Mono.just(pays);
        });
        when(depot.findByLeveeLeIsNull()).thenAnswer(appel ->
                Flux.fromIterable(magasin.values()).filter(PaysBloque::estActif));

        service = new PaysBloqueServiceImpl(depot);
    }

    private static BlocageDePaysRequest demande(String motif, String... codes) {
        return BlocageDePaysRequest.builder()
                .codesIso(List.of(codes))
                .motif(motif)
                .build();
    }

    // ==================================================================
    @Nested
    @DisplayName("La casse est libre : le defaut le plus visible")
    class CasseLibre {

        /**
         * Le defaut exact : valider avant de normaliser.
         */
        @ParameterizedTest(name = "« {0} » est accepte et normalise")
        @ValueSource(strings = {"ml", "ML", "mL", " ml ", "Ml"})
        @DisplayName("un code en minuscules ou avec espaces est accepte")
        void codeEnMinusculesAccepte(String saisie) {
            StepVerifier.create(service.refuser(demande("Motif", saisie), AUTEUR))
                    .assertNext(vue -> assertThat(vue.getCodeIso())
                            .withFailMessage("""
                                    « %s » n'a pas ete normalise en « ML ».

                                    Le defaut d'origine validait [A-Z]{2} AVANT \
                                    toUpperCase : « ml » etait donc refuse comme \
                                    code invalide, alors que la ligne suivante le \
                                    normalisait. L'administrateur recevait un refus \
                                    incomprehensible.""", saisie)
                            .isEqualTo("ML"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("deux ecritures du meme pays dans une demande ne font qu'une ligne")
        void doublonsAbsorbes() {
            // Sans normalisation avant deduplication, « ml » et « ML »
            // creeraient deux lignes dont une seule serait lue.
            StepVerifier.create(service.refuser(demande("Motif", "ml", "ML", "Ml"), AUTEUR))
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(magasin).hasSize(1).containsKey("ML");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un code invalide est refuse, et nomme")
    class CodeInvalide {

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = {"M", "MLI", "M1", "12", "", "  ", "FRANCE"})
        @DisplayName("ce qui n'est pas un ISO alpha-2 est refuse")
        void codeNonIsoRefuse(String code) {
            StepVerifier.create(service.refuser(demande("Motif", code), AUTEUR))
                    .verifyError(SecurityPolicyException.class);
        }

        @Test
        @DisplayName("le refus cite le code fautif")
        void refusCiteLeCode() {
            // Un refus « code invalide » sans dire lequel oblige a chercher dans
            // une liste de deux cents codes.
            StepVerifier.create(service.refuser(demande("Motif", "ML", "FRANCE"), AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur.getMessage())
                            .withFailMessage("le refus ne cite pas le code fautif : %s",
                                    erreur.getMessage())
                            .contains("FRANCE"));
        }

        @Test
        @DisplayName("un code invalide n'ecrit AUCUNE ligne, meme les valides de la liste")
        void aucuneEcriturePartielle() {
            // Normalisation de la liste entiere AVANT la premiere ecriture : une
            // demande partiellement appliquee laisserait l'administrateur
            // ignorer ce qui a pris effet.
            StepVerifier.create(service.refuser(demande("Motif", "ML", "FRANCE"), AUTEUR))
                    .verifyError(SecurityPolicyException.class);

            assertThat(magasin)
                    .withFailMessage("ML a ete ecrit malgre le refus de la demande : "
                            + "l'administrateur ignore ce qui a pris effet")
                    .isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Accepter distingue la faute de frappe du pays non refuse")
    class AcceptationPrecise {

        /**
         * Le second defaut : {@code unblockCountry} ne validait rien.
         */
        @Test
        @DisplayName("accepter un code invalide est refuse, et non traite comme introuvable")
        void codeInvalideALAcceptation() {
            StepVerifier.create(service.accepter("FRANCE", AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur.getMessage())
                            .withFailMessage("""
                                    Un code invalide est traite comme un pays \
                                    introuvable.

                                    C'etait le defaut : unblockCountry ne validait \
                                    rien, donc une faute de frappe et un pays deja \
                                    accepte rendaient la meme reponse.""")
                            .contains("deux lettres"));
        }

        @Test
        @DisplayName("accepter un pays valide mais non refuse rend une erreur nommee")
        void paysNonRefuse() {
            StepVerifier.create(service.accepter("ML", AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur.getMessage())
                            .contains("n'est pas refuse"));
        }

        @Test
        @DisplayName("accepter leve le refus SANS supprimer la ligne")
        void acceptationConserveLaLigne() {
            // « Effacer n'est pas supprimer la ligne » : savoir qu'un pays a ete
            // refuse puis accepte, par qui et quand, est ce qu'un journal
            // d'audit doit montrer.
            service.refuser(demande("Trafic automatise", "ml"), AUTEUR).blockLast();

            StepVerifier.create(service.accepter("ml", "admin-2"))
                    .assertNext(vue -> {
                        assertThat(vue.getActif()).isFalse();
                        assertThat(vue.getLeveePar()).isEqualTo("admin-2");
                        // Le motif d'origine reste lisible apres la levee.
                        assertThat(vue.getMotif()).isEqualTo("Trafic automatise");
                    })
                    .verifyComplete();

            assertThat(magasin)
                    .withFailMessage("la ligne a ete supprimee : la decision et sa "
                            + "levee disparaissent de l'historique")
                    .containsKey("ML");
        }

        @Test
        @DisplayName("accepter deux fois le meme pays est refuse la seconde fois")
        void doubleAcceptationRefusee() {
            // Un succes ferait croire qu'un refus vient d'etre leve, donc qu'il
            // s'appliquait.
            service.refuser(demande("Motif", "ML"), AUTEUR).blockLast();
            service.accepter("ML", AUTEUR).block();

            StepVerifier.create(service.accepter("ML", AUTEUR))
                    .verifyError(SecurityPolicyException.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Refuser de nouveau un pays deja refuse")
    class RefusDeNouveau {

        @Test
        @DisplayName("un pays deja refuse voit son motif mis a jour, sans erreur")
        void motifMisAJour() {
            service.refuser(demande("Premier motif", "ML"), "admin-1").blockLast();

            StepVerifier.create(service.refuser(demande("Motif revise", "ML"), "admin-2"))
                    .assertNext(vue -> {
                        assertThat(vue.getMotif()).isEqualTo("Motif revise");
                        assertThat(vue.getBloquePar()).isEqualTo("admin-2");
                    })
                    .verifyComplete();

            assertThat(magasin).hasSize(1);
        }

        @Test
        @DisplayName("un pays accepte puis refuse de nouveau reprend la MEME ligne")
        void memeLigneApresLevee() {
            // Une seconde ligne rendrait l'historique illisible, et la cle
            // primaire l'interdit de toute façon : sans cette reprise, le
            // refus echouerait sur une violation de contrainte.
            service.refuser(demande("Motif", "ML"), AUTEUR).blockLast();
            service.accepter("ML", AUTEUR).block();

            StepVerifier.create(service.refuser(demande("De nouveau", "ML"), AUTEUR))
                    .assertNext(vue -> {
                        assertThat(vue.getActif())
                                .withFailMessage("le refus n'a pas ete reactive")
                                .isTrue();
                        assertThat(vue.getLeveeLe()).isNull();
                    })
                    .verifyComplete();

            assertThat(magasin).hasSize(1);
        }
    }
}
