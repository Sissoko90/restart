package com.abdatytch.security_service.service;

import com.abdatytch.security_service.entities.SurchargeDeParametre;
import com.abdatytch.security_service.enums.ParametreDeSecurite;
import com.abdatytch.security_service.exception.SecurityPolicyException;
import com.abdatytch.security_service.repositories.SurchargeDeParametreRepository;
import com.abdatytch.security_service.service.impl.PolitiqueDeSecuriteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ce que le typage apporte, et que le magasin cle/valeur n'avait pas.
 *
 * <h2>Pourquoi ce service existe</h2>
 *
 * {@code setting-service} portait la politique de securite en cle/valeur libre :
 * toute cle acceptee, toute valeur acceptee, aucun auteur conserve. Trois
 * consequences, et cette suite les ferme une par une :
 *
 * <ol>
 *   <li>une faute de frappe dans la cle creait un parametre <b>fantome</b> que
 *       rien ne lisait. L'administrateur voyait son changement enregistre, et la
 *       lecture retombait sur le defaut ;</li>
 *   <li>une valeur du mauvais type passait a l'ecriture et ne se manifestait
 *       qu'a la lecture, loin de l'ecriture qui l'avait causee ;</li>
 *   <li>aucune borne : un seuil de tentatives a zero verrouille tout le monde a
 *       la premiere requete, et c'est un entier parfaitement valide.</li>
 * </ol>
 */
@DisplayName("Politique de securite : le typage que le cle/valeur n'avait pas")
class PolitiqueDeSecuriteServiceImplTest {

    private static final String AUTEUR = "admin-1";

    private Map<String, SurchargeDeParametre> magasin;
    private List<String> supprimees;
    private PolitiqueDeSecuriteServiceImpl politique;

    @BeforeEach
    void setUp() {
        magasin = new HashMap<>();
        supprimees = new ArrayList<>();

        SurchargeDeParametreRepository depot = mock(SurchargeDeParametreRepository.class);

        when(depot.findAll()).thenAnswer(appel -> Flux.fromIterable(magasin.values()));
        when(depot.findById(anyString())).thenAnswer(appel ->
                Mono.justOrEmpty(magasin.get((String) appel.getArgument(0))));
        when(depot.save(any(SurchargeDeParametre.class))).thenAnswer(appel -> {
            SurchargeDeParametre surcharge = appel.getArgument(0);
            magasin.put(surcharge.getCle(), surcharge);
            return Mono.just(surcharge);
        });
        when(depot.deleteById(anyString())).thenAnswer(appel -> {
            String cle = appel.getArgument(0);
            supprimees.add(cle);
            magasin.remove(cle);
            return Mono.empty();
        });

        politique = new PolitiqueDeSecuriteServiceImpl(depot);
    }

    // ==================================================================
    @Nested
    @DisplayName("Une cle inconnue est REFUSEE, et non creee")
    class CleInconnue {

        /**
         * Le parametre fantome, et pourquoi il etait pire qu'une erreur.
         */
        @Test
        @DisplayName("ecrire une cle absente du catalogue est refuse")
        void cleAbsenteRefusee() {
            StepVerifier.create(politique.changer("security.seuil-qui-nexiste-pas", "5", AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .withFailMessage("""
                                    Une cle inconnue a ete acceptee.

                                    Le magasin cle/valeur remplace faisait cela : une \
                                    faute de frappe creait un parametre que rien ne \
                                    lisait. L'administrateur voyait son changement \
                                    enregistre, et la lecture retombait sur le defaut \
                                    — un changement de securite sans effet, et sans \
                                    erreur.""")
                            .isInstanceOf(SecurityPolicyException.class));

            assertThat(magasin)
                    .withFailMessage("la cle fantome a ete ecrite en base")
                    .isEmpty();
        }

        @Test
        @DisplayName("lire une cle absente du catalogue est refuse aussi")
        void lectureDUneCleAbsente() {
            StepVerifier.create(politique.parametre("security.inexistant"))
                    .verifyError(SecurityPolicyException.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le type est verifie a l'ECRITURE")
    class TypeVerifie {

        /**
         * Le piege de {@code Boolean.parseBoolean}.
         *
         * <p>Il rend {@code false} pour <b>toute</b> chaine non reconnue :
         * « oui », « 1 » et « peut-etre » deviendraient silencieusement
         * « desactive ». Sur une politique de securite, une valeur mal ecrite qui
         * devient « protection coupee » est le pire des comportements possibles.
         */
        @ParameterizedTest(name = "« {0} » n'est pas un booleen")
        @ValueSource(strings = {"oui", "1", "vrai", "on", "peut-etre", "TRUE_"})
        @DisplayName("un booleen mal ecrit est refuse, et ne devient pas « false »")
        void booleenMalEcritRefuse(String valeur) {
            String cle = ParametreDeSecurite.SECOND_FACTEUR_OBLIGATOIRE_ADMIN.getCle();

            StepVerifier.create(politique.changer(cle, valeur, AUTEUR))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .withFailMessage("""
                                    « %s » a ete accepte comme booleen.

                                    Boolean.parseBoolean rend false pour toute chaine \
                                    non reconnue : la valeur serait devenue \
                                    « protection coupee » sans que personne le \
                                    demande.""", valeur)
                            .isInstanceOf(SecurityPolicyException.class));
        }

        @Test
        @DisplayName("true et false, dans n'importe quelle casse, sont acceptes")
        void booleenAccepte() {
            String cle = ParametreDeSecurite.SECOND_FACTEUR_OBLIGATOIRE_ADMIN.getCle();

            for (String valeur : new String[]{"true", "FALSE", "True"}) {
                StepVerifier.create(politique.changer(cle, valeur, AUTEUR))
                        .assertNext(vue -> assertThat(vue.getValeur())
                                .isEqualTo(valeur.toLowerCase()))
                        .verifyComplete();
            }
        }

        @ParameterizedTest(name = "« {0} » n'est pas un entier")
        @ValueSource(strings = {"cinq", "5.5", "", "  ", "0x5", "5,5", "-"})
        @DisplayName("un entier mal ecrit est refuse")
        void entierMalEcritRefuse(String valeur) {
            String cle = ParametreDeSecurite.TENTATIVES_AVANT_BLOCAGE.getCle();

            StepVerifier.create(politique.changer(cle, valeur, AUTEUR))
                    .verifyError(SecurityPolicyException.class);
        }

        @Test
        @DisplayName("les espaces autour de la valeur sont retires")
        void espacesRetires() {
            // Un espace colle par un copier-coller ne doit pas faire echouer un
            // changement de politique : la valeur est trimee avant validation.
            String cle = ParametreDeSecurite.TENTATIVES_AVANT_BLOCAGE.getCle();

            StepVerifier.create(politique.changer(cle, "  7 ", AUTEUR))
                    .assertNext(vue -> assertThat(vue.getValeur()).isEqualTo("7"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("un entier est normalise : « 05 » et « 5 » donnent la meme valeur")
        void entierNormalise() {
            // Deux ecritures de la meme valeur rendraient une comparaison de
            // chaines fausse cote applicateur, qui lit du texte dans Redis.
            String cle = ParametreDeSecurite.TENTATIVES_AVANT_BLOCAGE.getCle();

            StepVerifier.create(politique.changer(cle, "05", AUTEUR))
                    .assertNext(vue -> assertThat(vue.getValeur()).isEqualTo("5"))
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les bornes sont verifiees, et nommees")
    class BornesVerifiees {

        /**
         * Pourquoi le type ne suffit pas.
         *
         * <p>Zero et un million sont des entiers valides. Le premier verrouille
         * tout le monde a la premiere requete, le second n'offre aucune
         * protection.
         */
        @Test
        @DisplayName("un seuil sous le minimum est refuse")
        void seuilTropBasRefuse() {
            String cle = ParametreDeSecurite.TENTATIVES_AVANT_BLOCAGE.getCle();

            StepVerifier.create(politique.changer(cle, "0", AUTEUR))
                    .verifyErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(SecurityPolicyException.class);
                        // Le refus NOMME la borne : « valeur hors bornes » sans
                        // dire laquelle oblige l'administrateur a deviner, et il
                        // finira par essayer des valeurs au hasard sur une
                        // surface de securite.
                        assertThat(erreur.getMessage())
                                .withFailMessage("le refus ne dit pas quelle borne a "
                                        + "ete franchie : %s", erreur.getMessage())
                                .contains("3")
                                .contains("20");
                    });
        }

        @Test
        @DisplayName("un seuil au-dessus du maximum est refuse")
        void seuilTropHautRefuse() {
            String cle = ParametreDeSecurite.TENTATIVES_AVANT_BLOCAGE.getCle();

            StepVerifier.create(politique.changer(cle, "1000000", AUTEUR))
                    .verifyError(SecurityPolicyException.class);
        }

        @Test
        @DisplayName("les bornes incluses sont acceptees")
        void bornesInclusesAcceptees() {
            String cle = ParametreDeSecurite.TENTATIVES_AVANT_BLOCAGE.getCle();

            for (String limite : new String[]{"3", "20"}) {
                StepVerifier.create(politique.changer(cle, limite, AUTEUR))
                        .expectNextCount(1)
                        .verifyComplete();
            }
        }

        /**
         * La session d'administration a un plafond plus bas que celle d'un
         * client.
         *
         * <p>Une session d'administration oubliee sur un poste partage donne
         * acces a la surface la plus sensible de la plateforme. Un parametre
         * unique aurait oblige a choisir entre gener les clients et exposer
         * l'administration.
         */
        @Test
        @DisplayName("la session admin plafonne plus bas que la session utilisateur")
        void plafondAdminPlusBas() {
            assertThat(ParametreDeSecurite.DUREE_SESSION_ADMIN.getMaximum())
                    .withFailMessage("""
                            Le plafond de session admin n'est pas plus bas que celui \
                            des utilisateurs.

                            Les deux parametres existent precisement pour que les \
                            exigences puissent differer ; un plafond identique les \
                            rendrait redondants.""")
                    .isLessThan(ParametreDeSecurite.DUREE_SESSION_UTILISATEUR.getMaximum());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Remise au defaut : on SUPPRIME la surcharge")
    class RemiseAuDefaut {

        /**
         * Ecrire le defaut figerait la valeur.
         *
         * <p>Un changement de defaut dans une version ulterieure ne
         * s'appliquerait plus jamais a cette installation, qui se croirait
         * pourtant remise a zero.
         */
        @Test
        @DisplayName("remettre au defaut supprime la ligne, et n'y ecrit pas le defaut")
        void remiseSupprimeLaLigne() {
            String cle = ParametreDeSecurite.TENTATIVES_AVANT_BLOCAGE.getCle();
            politique.changer(cle, "10", AUTEUR).block();

            StepVerifier.create(politique.remettreAuDefaut(cle, AUTEUR))
                    .assertNext(vue -> {
                        assertThat(vue.getSurcharge()).isFalse();
                        assertThat(vue.getValeur()).isEqualTo(
                                ParametreDeSecurite.TENTATIVES_AVANT_BLOCAGE.getValeurParDefaut());
                    })
                    .verifyComplete();

            assertThat(supprimees)
                    .withFailMessage("""
                            La surcharge n'a pas ete supprimee.

                            Si le defaut y est ecrit a la place, la valeur est figee : \
                            un changement de defaut dans une version ulterieure ne \
                            s'appliquera plus jamais a cette installation, qui se \
                            croit pourtant remise a zero.""")
                    .containsExactly(cle);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La politique publiee est COMPLETE")
    class PolitiqueComplete {

        /**
         * Publier les seules surcharges obligerait chaque applicateur a
         * connaitre les defauts.
         *
         * <p>Donc a les porter en double, dans le gateway et dans auth-service,
         * et a les maintenir d'accord avec le catalogue. Trois copies d'une meme
         * valeur — ce que ce service existe pour supprimer.
         */
        @Test
        @DisplayName("tous les parametres du catalogue sont publies, defauts compris")
        void tousLesParametresPublies() {
            StepVerifier.create(politique.politiqueComplete())
                    .assertNext(publiee -> {
                        assertThat(publiee)
                                .withFailMessage("la politique publiee est incomplete : "
                                        + "les applicateurs devraient alors connaitre "
                                        + "les defauts, donc les porter en double")
                                .hasSize(ParametreDeSecurite.values().length);
                        for (ParametreDeSecurite parametre : ParametreDeSecurite.values()) {
                            assertThat(publiee).containsKey(parametre.getCle());
                        }
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("une surcharge remplace le defaut dans la publication")
        void surchargeRemplaceLeDefaut() {
            String cle = ParametreDeSecurite.TENTATIVES_AVANT_BLOCAGE.getCle();
            politique.changer(cle, "12", AUTEUR).block();

            StepVerifier.create(politique.politiqueComplete())
                    .assertNext(publiee -> assertThat(publiee.get(cle)).isEqualTo("12"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("une surcharge orpheline n'est PAS publiee")
        void surchargeOrphelineIgnoree() {
            // Cas d'un parametre retire du code alors que sa ligne subsiste.
            // La publier ferait apparaitre dans Redis un parametre que plus
            // personne ne lit, et qu'aucune interface ne permet d'enlever.
            magasin.put("security.parametre-retire-du-code", SurchargeDeParametre.builder()
                    .cle("security.parametre-retire-du-code")
                    .valeur("peu importe")
                    .modifiePar(AUTEUR)
                    .build());

            StepVerifier.create(politique.politiqueComplete())
                    .assertNext(publiee -> assertThat(publiee)
                            .doesNotContainKey("security.parametre-retire-du-code"))
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le catalogue lui-meme est coherent")
    class CatalogueCoherent {

        /**
         * Chaque defaut doit passer sa propre validation.
         *
         * <p>Un defaut hors bornes serait un piege parfait : il s'applique sans
         * jamais etre valide, puisque la validation ne porte que sur les
         * ecritures. Personne ne pourrait le corriger sans franchir la borne.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(ParametreDeSecurite.class)
        @DisplayName("le defaut de chaque parametre respecte son type et ses bornes")
        void defautValide(ParametreDeSecurite parametre) {
            StepVerifier.create(politique.changer(parametre.getCle(),
                            parametre.getValeurParDefaut(), AUTEUR))
                    .expectNextCount(1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("aucune cle n'est declaree deux fois")
        void aucuneCleEnDouble() {
            // Deux entrees de meme cle : la seconde masquerait la premiere a
            // l'ecriture, et la publication n'en garderait qu'une — sans
            // qu'aucune erreur ne le signale.
            long distinctes = java.util.Arrays.stream(ParametreDeSecurite.values())
                    .map(ParametreDeSecurite::getCle)
                    .distinct()
                    .count();

            assertThat(distinctes)
                    .withFailMessage("deux parametres partagent une cle : l'un masque "
                            + "l'autre en silence")
                    .isEqualTo(ParametreDeSecurite.values().length);
        }

        @Test
        @DisplayName("tout parametre numerique porte ses deux bornes")
        void parametresNumeriquesBornes() {
            List<String> sansBornes = new ArrayList<>();
            for (ParametreDeSecurite parametre : ParametreDeSecurite.values()) {
                boolean numerique = switch (parametre.getType()) {
                    case ENTIER, DUREE_MINUTES -> true;
                    case BOOLEEN, TEXTE -> false;
                };
                if (numerique
                        && (parametre.getMinimum() == null || parametre.getMaximum() == null)) {
                    sansBornes.add(parametre.name());
                }
            }

            assertThat(sansBornes)
                    .withFailMessage("""
                            Ces parametres numeriques n'ont pas de bornes : %s

                            Le type ne suffit pas. Zero et un million sont des \
                            entiers valides : le premier verrouille tout le monde a \
                            la premiere requete, le second n'offre aucune \
                            protection.""", sansBornes)
                    .isEmpty();
        }
    }
}
