package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.LotDeCodesResponse;
import com.abdatytch.auth_service.entities.CodeDeSecours;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.CodeDeSecoursRepository;
import com.abdatytch.auth_service.service.impl.CodeDeSecoursServiceImpl;
import com.abdatytch.auth_service.service.impl.MotDePasseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les codes de secours, et les proprietes qui les rendent acceptables.
 *
 * <h2>Ce qu'un code de secours est</h2>
 *
 * Un contournement du second facteur, a usage unique. Donc un justificatif a
 * part entiere : jamais stocke en clair, consommable une seule fois, lie a un
 * compte precis, et mortel avec le secret qu'il secourt.
 *
 * <h2>Pourquoi un vrai BCrypt, et non un simulacre</h2>
 *
 * La propriete centrale — « le code n'est pas stocke » — ne se verifie pas
 * contre un encodeur simule qui rendrait ce qu'on lui demande. La force est
 * baissee a 4 pour que la suite reste rapide : c'est le meme algorithme, avec
 * moins de tours, et aucune des proprietes mesurees ici ne depend du nombre de
 * tours.
 */
@DisplayName("Codes de secours : un contournement du second facteur, traite comme tel")
class CodeDeSecoursServiceImplTest {

    private static final String UTILISATEUR = "utilisateur-1";
    private static final String IP = "41.223.10.5";
    private static final int PAR_LOT = 10;

    /** Caracteres bannis de l'alphabet : ils se confondent a la lecture. */
    private static final String AMBIGUS = "O0I1L";

    private List<CodeDeSecours> magasin;
    private CodeDeSecoursRepository depot;
    private CodeDeSecoursServiceImpl service;
    private User compte;

    @BeforeEach
    void setUp() {
        magasin = new ArrayList<>();
        depot = mock(CodeDeSecoursRepository.class);

        when(depot.save(any(CodeDeSecours.class))).thenAnswer(appel -> {
            CodeDeSecours code = appel.getArgument(0);
            magasin.removeIf(present -> present.getId() != null
                    && present.getId().equals(code.getId()));
            magasin.add(code);
            return Mono.just(code);
        });
        when(depot.findByUserId(anyString())).thenAnswer(appel ->
                Flux.fromIterable(pourLeCompte(appel.getArgument(0))));
        when(depot.findByUserIdAndConsommeLeIsNullAndInvalideLeIsNull(anyString()))
                .thenAnswer(appel -> Flux.fromIterable(pourLeCompte(appel.getArgument(0))
                        .stream().filter(CodeDeSecours::estUtilisable).toList()));

        service = new CodeDeSecoursServiceImpl(
                depot,
                // Force 4 : le vrai BCrypt, moins de tours. Voir la javadoc.
                new MotDePasseServiceImpl(new BCryptPasswordEncoder(4)),
                PAR_LOT);

        compte = new User();
        compte.setId(UTILISATEUR);
        compte.setTotpEnabled(true);
    }

    private List<CodeDeSecours> pourLeCompte(String userId) {
        return magasin.stream()
                .filter(code -> userId.equals(code.getUserId()))
                .toList();
    }

    private List<String> unLot() {
        LotDeCodesResponse lot = service.regenerer(compte).block();
        assertThat(lot).isNotNull();
        return lot.getCodes();
    }

    @Nested
    @DisplayName("Remise d'un lot")
    class Remise {

        @Test
        @DisplayName("sans second facteur actif, la remise est REFUSEE")
        void sansSecondFacteurRefuse() {
            // Des codes de secours ne secourent de rien quand il n'y a rien a
            // contourner. Les remettre laisserait croire a une protection
            // supplementaire la ou le compte n'en a aucune.
            compte.setTotpEnabled(false);

            StepVerifier.create(service.regenerer(compte))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.TWO_FA_NOT_ENABLED));

            assertThat(magasin).isEmpty();
        }

        @Test
        @DisplayName("le refus passe par la chaine, et non par une levee a l'assemblage")
        void refusDansLaChaine() {
            // Une levee a l'assemblage sortirait du Mono : WebFlux rendrait 500
            // au lieu du refus voulu. Le piege a ete paye plusieurs fois dans ce
            // depot, d'ou le Mono.defer et d'ou ce test.
            compte.setTotpEnabled(false);

            Mono<LotDeCodesResponse> chaine = service.regenerer(compte);

            // L'assemblage seul ne doit RIEN lever : l'erreur n'arrive qu'a la
            // souscription.
            assertThat(chaine).isNotNull();
            StepVerifier.create(chaine).verifyError(AuthException.class);
        }

        @Test
        @DisplayName("le lot compte le nombre annonce, et autant de lignes")
        void lotComplet() {
            List<String> codes = unLot();

            assertThat(codes).hasSize(PAR_LOT);
            assertThat(magasin).hasSize(PAR_LOT);
        }

        @Test
        @DisplayName("les codes du lot sont tous DIFFERENTS")
        void codesDistincts() {
            // Un tirage qui se repete reduirait le lot sans que personne le
            // voie : dix codes affiches, moins de dix codes utilisables.
            assertThat(unLot()).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("aucun code n'est stocke en clair")
        void aucunCodeEnClair() {
            List<String> codes = unLot();

            // La propriete centrale. Un code lisible en base serait un
            // contournement du second facteur a la portee de qui lit la base.
            for (String code : codes) {
                assertThat(magasin)
                        .as("le code %s ne doit apparaitre dans aucune empreinte", code)
                        .noneMatch(ligne -> ligne.getEmpreinte().contains(code));
            }
        }

        @Test
        @DisplayName("l'empreinte est un BCrypt, et le debut fait quatre caracteres")
        void empreinteEtDebut() {
            List<String> codes = unLot();

            assertThat(magasin).allSatisfy(ligne -> {
                assertThat(ligne.getEmpreinte()).startsWith("$2");
                assertThat(ligne.getDebut()).hasSize(4);
            });
            // Le debut stocke est bien celui du code remis : sans cela, la
            // personne ne reconnaitrait pas ses codes sur son papier.
            assertThat(magasin).allSatisfy(ligne ->
                    assertThat(codes).anyMatch(code -> code.startsWith(ligne.getDebut())));
        }

        @Test
        @DisplayName("les codes evitent les caracteres qui se confondent")
        void alphabetSansAmbiguite() {
            // Un code est recopie A LA MAIN depuis un papier. La confusion entre
            // O et zero est la premiere cause de saisie fausse — et une saisie
            // fausse compte comme un echec, donc rapproche du verrouillage
            // quelqu'un qui avait le bon code.
            assertThat(unLot()).allSatisfy(code ->
                    assertThat(code).doesNotContainAnyWhitespaces()
                            .matches("[A-Z2-9]{10}")
                            .doesNotContain(AMBIGUS.split("")));
        }

        @Test
        @DisplayName("une regeneration INVALIDE le lot precedent, sans le dire consomme")
        void regenerationInvalideLePrecedent() {
            List<String> premier = unLot();

            LotDeCodesResponse second = service.regenerer(compte).block();

            assertThat(second).isNotNull();
            assertThat(second.getLotPrecedentInvalide()).isTrue();

            // Les anciennes lignes sont INVALIDEES, pas consommees. La
            // distinction est visible par la personne : « consomme » signale un
            // usage, et lui ferait soupçonner une intrusion alors qu'elle a
            // simplement demande de nouveaux codes.
            List<CodeDeSecours> anciens = magasin.stream()
                    .filter(ligne -> premier.stream()
                            .anyMatch(code -> code.startsWith(ligne.getDebut())))
                    .toList();
            assertThat(anciens).isNotEmpty();
            assertThat(anciens).allSatisfy(ligne -> {
                assertThat(ligne.getInvalideLe()).isNotNull();
                assertThat(ligne.getConsommeLe()).isNull();
                assertThat(ligne.estUtilisable()).isFalse();
            });
        }

        @Test
        @DisplayName("un code du lot precedent ne sert plus")
        void ancienCodeNeSertPlus() {
            // La consequence qui compte : deux lots valides a la fois
            // doubleraient la surface sans que la personne le sache.
            String ancien = unLot().get(0);
            service.regenerer(compte).block();

            StepVerifier.create(service.consommer(compte, ancien, IP))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("au premier lot, rien n'est annonce comme invalide")
        void premierLotRienAInvalider() {
            LotDeCodesResponse lot = service.regenerer(compte).block();

            assertThat(lot).isNotNull();
            assertThat(lot.getLotPrecedentInvalide()).isFalse();
        }
    }

    @Nested
    @DisplayName("Consommation")
    class Consommation {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("un code absent est refuse sans toucher au depot")
        void codeAbsentRefuse(String code) {
            StepVerifier.create(service.consommer(compte, code, IP))
                    .expectNext(false)
                    .verifyComplete();

            // Sans ce court-circuit, chaque requete sans code paierait une
            // lecture — et dix comparaisons BCrypt.
            verify(depot, never())
                    .findByUserIdAndConsommeLeIsNullAndInvalideLeIsNull(anyString());
        }

        @Test
        @DisplayName("un code valide est consomme, avec sa date et son adresse")
        void codeValideConsomme() {
            String code = unLot().get(3);

            StepVerifier.create(service.consommer(compte, code, IP))
                    .expectNext(true)
                    .verifyComplete();

            CodeDeSecours consomme = magasin.stream()
                    .filter(ligne -> code.startsWith(ligne.getDebut()))
                    .findFirst().orElseThrow();
            assertThat(consomme.getConsommeLe()).isNotNull();
            // L'adresse est ce qui permet a la personne de dire « cet usage
            // n'est pas le mien ».
            assertThat(consomme.getConsommeIp()).isEqualTo(IP);
            assertThat(consomme.getInvalideLe()).isNull();
        }

        @Test
        @DisplayName("le MEME code ne sert pas deux fois")
        void usageUnique() {
            String code = unLot().get(0);

            StepVerifier.create(service.consommer(compte, code, IP))
                    .expectNext(true).verifyComplete();

            // La propriete qui definit un code a usage unique. Sans elle, un
            // code intercepte une fois ouvrirait le compte indefiniment.
            StepVerifier.create(service.consommer(compte, code, IP))
                    .expectNext(false).verifyComplete();
        }

        @Test
        @DisplayName("consommer un code n'affecte pas les autres")
        void lesAutresRestent() {
            List<String> codes = unLot();
            service.consommer(compte, codes.get(0), IP).block();

            StepVerifier.create(service.consommer(compte, codes.get(1), IP))
                    .expectNext(true).verifyComplete();
        }

        @Test
        @DisplayName("un code inconnu est refuse")
        void codeInconnuRefuse() {
            unLot();

            StepVerifier.create(service.consommer(compte, "ZZZZZZZZZZ", IP))
                    .expectNext(false).verifyComplete();
        }

        @Test
        @DisplayName("un code d'un AUTRE compte n'ouvre pas celui-ci")
        void codeDUnAutreCompteRefuse() {
            String codeDeLAutre = unLot().get(0);

            User autre = new User();
            autre.setId("utilisateur-2");
            autre.setTotpEnabled(true);

            // Sans ce controle, un code obtenu sur un compte dispenserait du
            // second facteur sur tout compte dont on connait le mot de passe.
            StepVerifier.create(service.consommer(autre, codeDeLAutre, IP))
                    .expectNext(false).verifyComplete();
        }

        @Test
        @DisplayName("un code est accepte en minuscules et avec des espaces")
        void saisieToleree() {
            // Un code recopie depuis un papier arrive souvent ainsi. Refuser
            // pour cela ferait compter un echec a quelqu'un qui avait le bon
            // code — et le rapprocherait du verrouillage.
            String code = unLot().get(0);
            String recopie = " " + code.substring(0, 5).toLowerCase()
                    + " " + code.substring(5).toLowerCase() + " ";

            StepVerifier.create(service.consommer(compte, recopie, IP))
                    .expectNext(true).verifyComplete();
        }

        @Test
        @DisplayName("un compte sans aucun code refuse, sans erreur")
        void aucunCodeRefuse() {
            StepVerifier.create(service.consommer(compte, "ABCDEFGHJK", IP))
                    .expectNext(false).verifyComplete();
        }

        @Test
        @DisplayName("un depot illisible refuse le code, il ne fait pas ECHOUER la connexion")
        void depotIllisibleRefuse() {
            when(depot.findByUserIdAndConsommeLeIsNullAndInvalideLeIsNull(anyString()))
                    .thenReturn(Flux.error(new IllegalStateException("base indisponible")));

            // Une panne sur CETTE table ne doit pas empecher une connexion par
            // TOTP : elle ne vaut que « code de secours refuse ».
            StepVerifier.create(service.consommer(compte, "ABCDEFGHJK", IP))
                    .expectNext(false).verifyComplete();
        }

        @Test
        @DisplayName("aucun echec n'est compte ici : le frein appartient au second facteur")
        void aucunComptageIci() {
            // Le comptage a ete place ici, et c'etait un defaut : cette methode
            // avale ses erreurs de lecture, donc une table illisible faisait
            // disparaitre le frein du second facteur tout entier — la force
            // brute sur le TOTP devenait gratuite.
            //
            // Ce test fixe la frontiere : ce service ne touche pas au
            // verrouillage. Sa seule dependance de comportement est le depot.
            assertThat(CodeDeSecoursServiceImpl.class.getDeclaredFields())
                    .noneMatch(champ -> champ.getType().getSimpleName()
                            .contains("Lockout"));
        }
    }

    @Nested
    @DisplayName("Etat rendu a la personne")
    class Etat {

        @Test
        @DisplayName("l'etat compte les restants et le total")
        void comptes() {
            List<String> codes = unLot();
            service.consommer(compte, codes.get(0), IP).block();

            StepVerifier.create(service.etat(UTILISATEUR))
                    .assertNext(etat -> {
                        assertThat(etat.getRestants()).isEqualTo(PAR_LOT - 1L);
                        assertThat(etat.getTotal()).isEqualTo(PAR_LOT);
                        assertThat(etat.getCodes()).hasSize(PAR_LOT);
                        // Message absent tant qu'il reste des codes : un
                        // avertissement permanent cesse d'etre lu.
                        assertThat(etat.getMessage()).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("l'etat n'expose NI le code NI son empreinte")
        void rienDeSecret() {
            List<String> codes = unLot();

            StepVerifier.create(service.etat(UTILISATEUR))
                    .assertNext(etat -> {
                        String rendu = etat.getCodes().toString();
                        // Aucun code entier, aucune empreinte BCrypt.
                        assertThat(rendu).doesNotContain(codes);
                        assertThat(rendu).doesNotContain("$2");
                        // Mais le debut, suivi de points : c'est ce qui permet
                        // de reconnaitre un code sur son papier.
                        assertThat(etat.getCodes())
                                .allSatisfy(vue -> assertThat(vue.getDebut())
                                        .hasSize(7).endsWith("..."));
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("un lot entierement consomme porte l'avertissement")
        void lotEpuiseAvertit() {
            for (String code : unLot()) {
                service.consommer(compte, code, IP).block();
            }

            StepVerifier.create(service.etat(UTILISATEUR))
                    .assertNext(etat -> {
                        assertThat(etat.getRestants()).isZero();
                        // Le seul moment utile pour avertir : apres, la personne
                        // n'a plus de sortie si elle perd son appareil.
                        assertThat(etat.getMessage()).isNotBlank();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("un compte sans code rend zero, et non un flux vide")
        void aucunCode() {
            // Un Mono vide ferait se terminer la reponse sans corps : l'appelant
            // ne saurait ni « zero » ni « erreur ».
            StepVerifier.create(service.etat(UTILISATEUR))
                    .assertNext(etat -> {
                        assertThat(etat.getRestants()).isZero();
                        assertThat(etat.getTotal()).isZero();
                        assertThat(etat.getCodes()).isEmpty();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("un code consomme reste visible, avec son adresse")
        void traceConservee() {
            // « Effacer n'est pas supprimer la ligne » : un code consomme que la
            // personne ne reconnait pas est le signal le plus clair qu'elle
            // puisse recevoir d'une intrusion. Une suppression l'effacerait.
            String code = unLot().get(0);
            service.consommer(compte, code, IP).block();

            StepVerifier.create(service.etat(UTILISATEUR))
                    .assertNext(etat -> assertThat(etat.getCodes())
                            .anySatisfy(vue -> {
                                assertThat(vue.getUtilisable()).isFalse();
                                assertThat(vue.getConsommeLe()).isNotNull();
                                assertThat(vue.getConsommeIp()).isEqualTo(IP);
                                assertThat(vue.getRemplace()).isFalse();
                            }))
                    .verifyComplete();
        }

        @Test
        @DisplayName("les codes utilisables sont rendus en premier")
        void utilisablesDAbord() {
            List<String> codes = unLot();
            service.consommer(compte, codes.get(0), IP).block();

            StepVerifier.create(service.etat(UTILISATEUR))
                    .assertNext(etat -> assertThat(etat.getCodes().get(0).getUtilisable())
                            .as("c'est l'ordre dans lequel la personne les cherche")
                            .isTrue())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Invalidation du lot")
    class Invalidation {

        @Test
        @DisplayName("invalider marque tout le lot, et rend son nombre")
        void invalideTout() {
            unLot();

            StepVerifier.create(service.invaliderLeLot(UTILISATEUR))
                    .expectNext((long) PAR_LOT)
                    .verifyComplete();

            assertThat(magasin).allSatisfy(ligne -> {
                assertThat(ligne.getInvalideLe()).isNotNull();
                // Invalide, PAS consomme : la personne n'a rien utilise.
                assertThat(ligne.getConsommeLe()).isNull();
            });
        }

        @Test
        @DisplayName("un code invalide ne sert plus")
        void codeInvalideNeSertPlus() {
            String code = unLot().get(0);
            service.invaliderLeLot(UTILISATEUR).block();

            StepVerifier.create(service.consommer(compte, code, IP))
                    .expectNext(false).verifyComplete();
        }

        @Test
        @DisplayName("invalider un compte sans lot rend zero")
        void sansLotZero() {
            // L'invariant « le lot suit le secret » est appele a chaque
            // changement de secret, y compris au premier enrolement ou aucun
            // lot n'existe. Il doit y etre sans effet, et non lever.
            StepVerifier.create(service.invaliderLeLot(UTILISATEUR))
                    .expectNext(0L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("un code DEJA consomme n'est pas reecrit en invalide")
        void consommeResteConsomme() {
            String code = unLot().get(0);
            service.consommer(compte, code, IP).block();

            service.invaliderLeLot(UTILISATEUR).block();

            CodeDeSecours consomme = magasin.stream()
                    .filter(ligne -> code.startsWith(ligne.getDebut()))
                    .findFirst().orElseThrow();
            // Sinon la trace d'un usage reel — celle qui revele une intrusion —
            // serait recouverte par une invalidation de routine.
            assertThat(consomme.getConsommeLe()).isNotNull();
            assertThat(consomme.getInvalideLe()).isNull();
        }

        @Test
        @DisplayName("tout le lot porte le MEME horodatage d'invalidation")
        void memeHorodatage() {
            unLot();
            service.invaliderLeLot(UTILISATEUR).block();

            List<LocalDateTime> marques = magasin.stream()
                    .map(CodeDeSecours::getInvalideLe)
                    .distinct()
                    .toList();
            // Un LocalDateTime.now() par ligne les disperserait sur quelques
            // millisecondes, et rien ne dirait plus qu'elles ont ete invalidees
            // ensemble.
            assertThat(marques).hasSize(1);
        }
    }
}
