package com.abdatytch.commons.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le disjoncteur des appels sortants, mis a l'epreuve.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * {@code DisjoncteurSortant} est le SEUL appliquant de resilience sortante de
 * la plateforme : ce depot n'embarque pas aspectjweaver, donc {@code @Retry},
 * {@code @CircuitBreaker} et {@code @RateLimiter} ne tissent rien — c'est ce
 * que {@code AnnotationsResilienceInertesTest} verrouille. Toute la protection
 * des appels vers user-service, media-service et notification-service passe
 * par cette classe, dans tous les modules.
 *
 * <p>Elle n'avait AUCUN test. Son instance etait visible dans Prometheus, a
 * l'etat {@code closed}, ce qui se lit comme une protection en bon ordre — et
 * c'est exactement la forme de preuve que ce depot a deja appris a refuser :
 * une serie a zero ne distingue pas « rien ne tombe » de « rien n'est
 * branche ». Mesure du 2026-11-13.
 *
 * <h2>Ce que chaque test fixe</h2>
 *
 * Ils ne verifient pas que resilience4j fonctionne — c'est son affaire. Ils
 * fixent les quatre decisions prises ICI : le seuil minimum, la mise en
 * court-circuit effective, la traduction du refus, et le sens exact du
 * predicat injecte.
 */
@DisplayName("DisjoncteurSortant — le seul appliquant de resilience sortante")
class DisjoncteurSortantTest {

    private static final String NOM = "dependance-de-test";

    /** Ce que le predicat de test refuse de compter comme une panne. */
    static final class DemandeInvalide extends RuntimeException {
        DemandeInvalide() {
            super("la demande etait mauvaise, la dependance a repondu");
        }
    }

    /** Ce que le module leve quand son disjoncteur est ouvert. */
    static final class RefusDuModule extends RuntimeException {
        RefusDuModule(String nom) {
            super("dependance indisponible : " + nom);
        }
    }

    private CircuitBreakerRegistry registre;
    private DisjoncteurSortant disjoncteur;

    /** Compte les abonnements reellement passes a l'appel protege. */
    private AtomicInteger tentatives;

    @BeforeEach
    void preparer() {
        registre = CircuitBreakerRegistry.ofDefaults();
        tentatives = new AtomicInteger();

        Predicate<Throwable> estUneIndisponibilite =
                erreur -> !(erreur instanceof DemandeInvalide);

        // Fenetre de 4, minimum 4, 50 % : deux echecs sur quatre ouvrent.
        disjoncteur = new DisjoncteurSortant(
                registre, estUneIndisponibilite, RefusDuModule::new,
                50f, 4, 4, 30L);
    }

    /** Un appel qui echoue, en comptant les abonnements. */
    private Mono<String> echoue(RuntimeException erreur) {
        return Mono.defer(() -> {
            tentatives.incrementAndGet();
            return Mono.error(erreur);
        });
    }

    /** Un appel qui reussit, en comptant les abonnements. */
    private Mono<String> reussit() {
        return Mono.fromCallable(() -> {
            tentatives.incrementAndGet();
            return "reponse";
        });
    }

    private void provoquer(int combien, RuntimeException erreur) {
        for (int i = 0; i < combien; i++) {
            disjoncteur.proteger(NOM, echoue(erreur))
                    .onErrorResume(e -> Mono.empty())
                    .block();
        }
    }

    private CircuitBreaker instance() {
        return registre.circuitBreaker(NOM, DisjoncteurSortant.CONFIGURATION);
    }

    @Nested
    @DisplayName("La declaration au demarrage")
    class Declaration {

        /**
         * Sans cet appel, l'instance n'existe qu'au PREMIER trafic : jusque-la,
         * ni {@code /actuator/circuitbreakers} ni Prometheus ne la connaissent.
         * Une alerte posee sur son etat ne se declencherait donc jamais avant
         * le premier appel — c'est-a-dire jamais au moment ou on en a besoin,
         * au demarrage d'un service qui ne joint pas sa dependance.
         */
        @Test
        @DisplayName("rend l'instance visible dans le registre, fermee, avant tout trafic")
        void visibleAvantTrafic() {
            assertThat(registre.find(NOM))
                    .withFailMessage("L'instance existe avant d'avoir ete declaree : "
                            + "le test ne prouve plus rien.")
                    .isEmpty();

            disjoncteur.declarer(NOM);

            assertThat(registre.find(NOM))
                    .withFailMessage("""
                            Le disjoncteur declare n'est pas dans le registre.

                            Micrometer s'abonne au REGISTRE : une instance creee \
                            hors de lui n'apparait ni dans Prometheus ni dans \
                            l'actuateur.""")
                    .isPresent();
            assertThat(instance().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(tentatives)
                    .withFailMessage("Declarer un disjoncteur a passe un appel.")
                    .hasValue(0);
        }
    }

    @Nested
    @DisplayName("L'ouverture")
    class Ouverture {

        /**
         * {@code minimumNumberOfCalls} existe pour cela : sans lui, le tout
         * premier appel en echec donne un taux de 100 % et ouvre le
         * disjoncteur. Un service redemarre couperait sa dependance sur un
         * unique aleas reseau.
         */
        @Test
        @DisplayName("n'ouvre pas sous le nombre minimum d'appels, meme a 100 % d'echec")
        void pasAvantLeMinimum() {
            provoquer(3, new IllegalStateException("panne"));

            assertThat(instance().getState())
                    .withFailMessage("Trois echecs sur un minimum de quatre ont ouvert "
                            + "le disjoncteur : le seuil minimum ne porte pas.")
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        }

        /**
         * Le cas qui compte : une fois ouvert, l'appel ne doit PAS avoir lieu.
         * Un disjoncteur qui change d'etat sans court-circuiter ne protege
         * rien — il ne fait que decrire la panne pendant qu'il la subit.
         */
        @Test
        @DisplayName("ouvert, l'appel n'est plus execute du tout")
        void ouvertCourtCircuite() {
            provoquer(4, new IllegalStateException("panne"));
            assertThat(instance().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            int avant = tentatives.get();
            disjoncteur.proteger(NOM, echoue(new IllegalStateException("panne")))
                    .onErrorResume(e -> Mono.empty())
                    .block();

            assertThat(tentatives.get())
                    .withFailMessage("""
                            L'appel a ete execute alors que le disjoncteur est OUVERT.

                            La dependance en panne continue d'etre sollicitee : le \
                            disjoncteur affiche un etat sans rien couper.""")
                    .isEqualTo(avant);
        }

        /**
         * {@code CallNotPermittedException} remontee brute serait classee en
         * 500 par le gestionnaire d'exceptions du module : le service
         * s'accuserait lui-meme au moment precis ou il se protege
         * correctement.
         */
        @Test
        @DisplayName("ouvert, l'appelant recoit l'erreur du MODULE, pas celle de resilience4j")
        void ouvertTraduitLeRefus() {
            provoquer(4, new IllegalStateException("panne"));

            StepVerifier.create(disjoncteur.proteger(NOM, reussit()))
                    .expectErrorSatisfies(erreur -> {
                        assertThat(erreur)
                                .withFailMessage("Le refus n'est pas traduit : le module "
                                        + "recevrait une exception de resilience4j.")
                                .isInstanceOf(RefusDuModule.class)
                                .isNotInstanceOf(CallNotPermittedException.class);
                        assertThat(erreur).hasMessageContaining(NOM);
                    })
                    .verify();
        }
    }

    @Nested
    @DisplayName("Le predicat injecte")
    class Predicat {

        /**
         * <h3>Refuse par le predicat vaut SUCCES, pas « ignore »</h3>
         *
         * resilience4j offre deux reglages distincts : {@code recordException},
         * pose ici, et {@code ignoreException}. Avec le premier, un appel dont
         * l'erreur est refusee par le predicat est compte comme un <b>succes</b>
         * dans la fenetre glissante ; avec le second, il ne serait pas compte
         * du tout.
         *
         * <p>Le choix fait ici est le bon pour ce qu'il represente — un 4xx dit
         * que la demande etait mauvaise, donc que la dependance a parfaitement
         * repondu, ce qui est bien un succes de la dependance. Mais la
         * difference n'etait ecrite nulle part, et elle a une consequence :
         * une rafale de demandes invalides fait BAISSER le taux d'echec, donc
         * repousse l'ouverture. Ce test la fixe pour qu'un passage a
         * {@code ignoreException} soit une decision, pas un glissement.
         */
        @Test
        @DisplayName("une erreur qu'il refuse n'ouvre jamais, meme en rafale")
        void erreurRefuseeNOuvrePas() {
            provoquer(20, new DemandeInvalide());

            assertThat(instance().getState())
                    .withFailMessage("""
                            Vingt demandes invalides ont ouvert le disjoncteur.

                            Une suite de requetes mal formees couperait l'acces a une \
                            dependance en parfait etat.""")
                    .isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(instance().getMetrics().getNumberOfSuccessfulCalls())
                    .withFailMessage("Une erreur refusee par le predicat n'est pas comptee "
                            + "comme un succes : le reglage a glisse de recordException "
                            + "vers ignoreException, et le taux d'echec ne se dilue plus.")
                    .isGreaterThan(0);
        }

        /**
         * L'inverse du precedent : le predicat doit bien laisser passer ce
         * qu'il ne refuse pas. Sans ce test, un predicat qui rendrait toujours
         * {@code false} passerait le test ci-dessus avec les honneurs.
         */
        @Test
        @DisplayName("une erreur qu'il accepte ouvre bien")
        void erreurAccepteeOuvre() {
            provoquer(4, new IllegalStateException("panne"));

            assertThat(instance().getState())
                    .withFailMessage("Le predicat ne compte AUCUNE erreur comme une "
                            + "panne : le disjoncteur ne s'ouvrira jamais.")
                    .isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    @DisplayName("Le retour a la normale")
    class Retablissement {

        /**
         * Un disjoncteur qui s'ouvre sans jamais se refermer est une panne
         * definitive deguisee en protection : la dependance revient, le service
         * reste coupe jusqu'au redemarrage.
         */
        @Test
        @DisplayName("a demi-ouvert, des appels qui reussissent le referment")
        void demiOuvertRefermeSurSucces() {
            provoquer(4, new IllegalStateException("panne"));
            assertThat(instance().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            instance().transitionToHalfOpenState();

            // permittedNumberOfCallsInHalfOpenState = 2
            for (int i = 0; i < 2; i++) {
                StepVerifier.create(disjoncteur.proteger(NOM, reussit()))
                        .expectNext("reponse")
                        .verifyComplete();
            }

            assertThat(instance().getState())
                    .withFailMessage("""
                            Le disjoncteur ne se referme pas sur des appels reussis.

                            La dependance est revenue et le service reste coupe.""")
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }
}
