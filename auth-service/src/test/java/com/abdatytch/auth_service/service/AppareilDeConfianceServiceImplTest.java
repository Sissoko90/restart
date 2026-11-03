package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.response.DeviceInfo;
import com.abdatytch.auth_service.entities.AppareilDeConfiance;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.AppareilDeConfianceRepository;
import com.abdatytch.auth_service.service.impl.AppareilDeConfianceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Les appareils de confiance, et la propriete qui les rend acceptables.
 *
 * <h2>Ce qu'un appareil de confiance est</h2>
 *
 * Une dispense de second facteur. Donc un <b>justificatif de longue duree</b>,
 * et tout ce qui vaut pour un jeton de session vaut pour lui : il n'est jamais
 * stocke en clair, il porte une echeance, il appartient a un compte precis, et
 * il se revoque.
 *
 * <h2>Les trois tests qui comptent</h2>
 *
 * <ol>
 *   <li>le jeton n'est stocke que <b>hache</b> : une lecture de la base ne doit
 *       pas livrer des dispenses utilisables ;</li>
 *   <li>un jeton valide presente sur un <b>autre compte</b> n'accorde rien :
 *       sans ce controle, qui obtient un jeton se dispenserait du second facteur
 *       sur tout compte dont il connait le mot de passe ;</li>
 *   <li>l'echeance n'est <b>pas prolongee</b> par l'usage : une confiance qui se
 *       renouvelle a chaque connexion ne s'eteint jamais, ce qui revient a une
 *       confiance perpetuelle — et un vol d'appareil a un acces definitif.</li>
 * </ol>
 */
@DisplayName("Appareils de confiance : une dispense de second facteur, traitee comme telle")
class AppareilDeConfianceServiceImplTest {

    private static final String UTILISATEUR = "utilisateur-1";
    private static final String AUTRE = "utilisateur-2";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String IP = "41.223.10.5";
    private static final long VALIDITE_JOURS = 30;

    private Map<String, AppareilDeConfiance> magasin;
    private AppareilDeConfianceServiceImpl service;

    @BeforeEach
    void setUp() {
        magasin = new HashMap<>();

        AppareilDeConfianceRepository depot = mock(AppareilDeConfianceRepository.class);
        when(depot.save(any(AppareilDeConfiance.class))).thenAnswer(appel -> {
            AppareilDeConfiance appareil = appel.getArgument(0);
            magasin.put(appareil.getId(), appareil);
            return Mono.just(appareil);
        });
        when(depot.findById(anyString())).thenAnswer(appel ->
                Mono.justOrEmpty(magasin.get((String) appel.getArgument(0))));
        when(depot.findByEmpreinteJeton(anyString())).thenAnswer(appel -> {
            String empreinte = appel.getArgument(0);
            return Mono.justOrEmpty(magasin.values().stream()
                    .filter(a -> empreinte.equals(a.getEmpreinteJeton()))
                    .findFirst());
        });
        when(depot.findByUserIdAndRevoqueLeIsNull(anyString())).thenAnswer(appel -> {
            String userId = appel.getArgument(0);
            return Flux.fromIterable(magasin.values())
                    .filter(a -> userId.equals(a.getUserId()) && a.getRevoqueLe() == null);
        });

        // Le VRAI analyseur d'User-Agent, et non un simulacre : il est deja en
        // service dans le journal d'authentification, et c'est lui qui remplit
        // le nom par defaut. Un bouchon masquerait un nom vide.
        IDeviceInfoService analyseur =
                new com.abdatytch.auth_service.service.impl.DeviceInfoServiceImpl();

        service = new AppareilDeConfianceServiceImpl(depot, analyseur, VALIDITE_JOURS);
    }

    private static User compte(String id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private String accorder(String userId, String nom) {
        return service.accorder(compte(userId), nom, USER_AGENT, IP)
                .block()
                .getJeton();
    }

    // ==================================================================
    @Nested
    @DisplayName("Le jeton n'est jamais stocke en clair")
    class JetonHache {

        @Test
        @DisplayName("la base ne contient que l'empreinte, jamais le jeton")
        void baseSansJetonEnClair() {
            String jeton = accorder(UTILISATEUR, "Mon portable");

            assertThat(magasin).hasSize(1);
            AppareilDeConfiance ligne = magasin.values().iterator().next();

            assertThat(ligne.getEmpreinteJeton())
                    .withFailMessage("""
                            Le jeton est stocke en clair.

                            Il dispense du second facteur : qui lit la base — \
                            sauvegarde, copie d'environnement, injection SQL — \
                            pourrait se connecter sans code pendant toute sa \
                            validite.""")
                    .isNotEqualTo(jeton)
                    // SHA-256 en hexadecimal : 64 caracteres.
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("l'empreinte est deterministe : le meme jeton reconnait l'appareil")
        void empreinteDeterministe() {
            String jeton = accorder(UTILISATEUR, "Mon portable");

            StepVerifier.create(service.estDeConfiance(UTILISATEUR, jeton))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("deux appareils ont deux jetons differents")
        void jetonsDistincts() {
            String premier = accorder(UTILISATEUR, "Portable");
            String second = accorder(UTILISATEUR, "Bureau");

            assertThat(second)
                    .withFailMessage("le meme jeton a ete emis deux fois : revoquer "
                            + "un appareil revoquerait l'autre")
                    .isNotEqualTo(premier);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un jeton n'accorde rien sur un AUTRE compte")
    class ClosionParCompte {

        /**
         * Le test le plus important de cette suite.
         *
         * <p>Sans ce controle, qui obtient le jeton d'un appareil — par un
         * journal, une sauvegarde, un poste partage — se dispenserait du second
         * facteur sur <b>n'importe quel</b> compte dont il connait le mot de
         * passe. La dispense deviendrait universelle.
         */
        @Test
        @DisplayName("un jeton valide presente sur un autre compte est refuse")
        void jetonDUnAutreCompteRefuse() {
            String jeton = accorder(UTILISATEUR, "Portable");

            StepVerifier.create(service.estDeConfiance(AUTRE, jeton))
                    .assertNext(deConfiance -> assertThat(deConfiance)
                            .withFailMessage("""
                                    Un jeton d'un autre compte a accorde la dispense.

                                    Qui obtient un jeton se dispenserait alors du \
                                    second facteur sur tout compte dont il connait \
                                    le mot de passe : la dispense deviendrait \
                                    universelle.""")
                            .isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("le refus sur un autre compte ne met pas a jour le dernier usage")
        void refusNeToucheRien() {
            // Sinon la personne verrait son appareil « utilise » a un moment ou
            // elle ne s'en est pas servie, sans comprendre pourquoi — et
            // l'indice d'une tentative sur son jeton serait brouille.
            String jeton = accorder(UTILISATEUR, "Portable");
            service.estDeConfiance(AUTRE, jeton).block();

            assertThat(magasin.values().iterator().next().getUtiliseLe())
                    .withFailMessage("le dernier usage a ete mis a jour par un refus")
                    .isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'echeance est obligatoire, et ne se prolonge pas")
    class EcheanceFerme {

        @Test
        @DisplayName("une confiance accordee porte une echeance")
        void echeancePosee() {
            accorder(UTILISATEUR, "Portable");
            AppareilDeConfiance ligne = magasin.values().iterator().next();

            assertThat(ligne.getExpireLe())
                    .withFailMessage("""
                            Aucune echeance n'a ete posee.

                            Une confiance perpetuelle transforme un vol \
                            d'appareil en acces definitif sans second facteur, \
                            et personne ne pense a faire le menage dans une \
                            liste qui n'expire pas.""")
                    .isNotNull()
                    .isAfter(LocalDateTime.now().plusDays(VALIDITE_JOURS - 1));
        }

        /**
         * L'usage ne prolonge pas la confiance.
         *
         * <p>Une echeance repoussee a chaque connexion ne s'atteint jamais pour
         * un appareil utilise quotidiennement : la confiance devient
         * perpetuelle, et l'echeance n'est plus qu'une decoration.
         */
        @Test
        @DisplayName("se servir de l'appareil ne repousse PAS son echeance")
        void usageNeProlongePas() {
            String jeton = accorder(UTILISATEUR, "Portable");
            LocalDateTime echeanceInitiale =
                    magasin.values().iterator().next().getExpireLe();

            service.estDeConfiance(UTILISATEUR, jeton).block();
            service.estDeConfiance(UTILISATEUR, jeton).block();

            assertThat(magasin.values().iterator().next().getExpireLe())
                    .withFailMessage("""
                            L'echeance a ete repoussee par l'usage.

                            Un appareil utilise chaque jour ne l'atteindrait \
                            jamais : la confiance devient perpetuelle et \
                            l'echeance n'est qu'une decoration.""")
                    .isEqualTo(echeanceInitiale);
        }

        @Test
        @DisplayName("un appareil echu n'est plus de confiance")
        void appareilEchuRefuse() {
            String jeton = accorder(UTILISATEUR, "Portable");
            AppareilDeConfiance ligne = magasin.values().iterator().next();
            ligne.setExpireLe(LocalDateTime.now().minusMinutes(1));

            StepVerifier.create(service.estDeConfiance(UTILISATEUR, jeton))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("l'usage met a jour la date de dernier usage")
        void usageEnregistre() {
            // C'est ce qui permet a la personne de distinguer un appareil
            // qu'elle utilise d'un appareil oublie dans sa liste.
            String jeton = accorder(UTILISATEUR, "Portable");
            service.estDeConfiance(UTILISATEUR, jeton).block();

            assertThat(magasin.values().iterator().next().getUtiliseLe()).isNotNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un jeton douteux fait DEMANDER le code, il ne leve pas")
    class JamaisDErreur {

        /**
         * Pourquoi aucune de ces situations n'est une erreur.
         *
         * <p>Un jeton invalide signifie « demandez le second facteur », ce qui
         * est le comportement par defaut. Lever ferait echouer la connexion
         * d'une personne presentant un jeton perime, alors qu'elle doit
         * simplement saisir son code.
         */
        @ParameterizedTest(name = "jeton « {0} »")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "jeton-forge-par-un-attaquant", "a"})
        @DisplayName("un jeton absent, vide ou inconnu rend false sans lever")
        void jetonDouteuxRendFaux(String jeton) {
            StepVerifier.create(service.estDeConfiance(UTILISATEUR, jeton))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("une panne de base fait demander le code, elle ne refuse pas la connexion")
        void panneDeBaseNEmpechePasLaConnexion() {
            // Refuser transformerait un incident de base en impossibilite de se
            // connecter, alors que le parcours complet — mot de passe puis code
            // — reste possible.
            AppareilDeConfianceRepository casse = mock(AppareilDeConfianceRepository.class);
            when(casse.findByEmpreinteJeton(anyString()))
                    .thenReturn(Mono.error(new IllegalStateException("base injoignable")));

            var fragile = new AppareilDeConfianceServiceImpl(casse,
                    new com.abdatytch.auth_service.service.impl.DeviceInfoServiceImpl(),
                    VALIDITE_JOURS);

            StepVerifier.create(fragile.estDeConfiance(UTILISATEUR, "un-jeton"))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Nommage et revocation")
    class NommageEtRevocation {

        @Test
        @DisplayName("sans nom fourni, le nom est deduit de l'User-Agent")
        void nomDeduit() {
            // Sans nom lisible, une liste d'appareils ne permet pas de
            // reconnaitre celui qu'on veut revoquer : « appareil 3 » ne designe
            // rien.
            service.accorder(compte(UTILISATEUR), null, USER_AGENT, IP).block();

            assertThat(magasin.values().iterator().next().getNom())
                    .withFailMessage("le nom deduit est vide ou inutilisable")
                    .contains("Chrome")
                    .contains("Mac");
        }

        @Test
        @DisplayName("revoquer rend l'appareil inutilisable, et CONSERVE la ligne")
        void revocationConserveLaLigne() {
            String jeton = accorder(UTILISATEUR, "Portable");
            String id = magasin.values().iterator().next().getId();

            StepVerifier.create(service.revoquer(UTILISATEUR, id)).verifyComplete();

            StepVerifier.create(service.estDeConfiance(UTILISATEUR, jeton))
                    .expectNext(false)
                    .verifyComplete();

            assertThat(magasin)
                    .withFailMessage("la ligne a ete supprimee : savoir qu'un "
                            + "appareil a eu la confiance et l'a perdue disparait "
                            + "de l'historique")
                    .containsKey(id);
            assertThat(magasin.get(id).getRevoquePar()).isEqualTo("proprietaire");
        }

        @Test
        @DisplayName("revoquer l'appareil d'autrui rend une absence, pas un refus")
        void revocationDAutruiRefusee() {
            // 404 et non 403 : un 403 confirmerait que l'appareil existe et
            // appartient a quelqu'un d'autre, ce qui se parcourt.
            accorder(UTILISATEUR, "Portable");
            String id = magasin.values().iterator().next().getId();

            StepVerifier.create(service.revoquer(AUTRE, id))
                    .verifyError(AuthException.class);

            assertThat(magasin.get(id).getRevoqueLe())
                    .withFailMessage("l'appareil d'autrui a ete revoque")
                    .isNull();
        }

        @Test
        @DisplayName("tout revoquer d'un coup, et le nombre est rendu")
        void revoquerTous() {
            accorder(UTILISATEUR, "Portable");
            accorder(UTILISATEUR, "Bureau");
            accorder(AUTRE, "Appareil d'un autre");

            StepVerifier.create(service.revoquerTous(UTILISATEUR))
                    .expectNext(2L)
                    .verifyComplete();

            long restants = magasin.values().stream()
                    .filter(a -> a.getRevoqueLe() == null)
                    .count();
            assertThat(restants)
                    .withFailMessage("l'appareil d'un autre compte a ete revoque")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("la liste n'expose ni le jeton ni son empreinte")
        void listeSansJeton() {
            String jeton = accorder(UTILISATEUR, "Portable");

            StepVerifier.create(service.duCompte(UTILISATEUR))
                    .assertNext(vue -> {
                        String rendu = vue.toString();
                        assertThat(rendu)
                                .withFailMessage("""
                                        Le jeton figure dans la reponse : il dispense \
                                        du second facteur, donc il devient utilisable \
                                        par qui lit les journaux du client.""")
                                .doesNotContain(jeton);

                        // Assertion volontairement plus LARGE que « ne contient
                        // pas CETTE empreinte » : aucune suite de 64 caracteres
                        // hexadecimaux ne doit sortir. Comparer a l'empreinte
                        // attendue aurait demande de recalculer le hachage dans
                        // le test — donc d'en porter une seconde copie, qui
                        // resterait d'accord avec elle-meme si la production
                        // changeait d'algorithme.
                        assertThat(rendu)
                                .withFailMessage("""
                                        Une empreinte figure dans la reponse : elle \
                                        permet de verifier qu'un jeton devine \
                                        correspond.""")
                                .doesNotMatch("(?s).*\\b[0-9a-f]{64}\\b.*");

                        assertThat(vue.getNom()).isEqualTo("Portable");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("la liste ecarte les appareils echus")
        void listeSansEchus() {
            // Les montrer ferait croire a une confiance encore active, et
            // pousserait a « revoquer » ce qui ne vaut plus rien pendant que le
            // vrai appareil reste.
            accorder(UTILISATEUR, "Portable");
            magasin.values().iterator().next()
                    .setExpireLe(LocalDateTime.now().minusDays(1));

            StepVerifier.create(service.duCompte(UTILISATEUR)).verifyComplete();
        }
    }
}
