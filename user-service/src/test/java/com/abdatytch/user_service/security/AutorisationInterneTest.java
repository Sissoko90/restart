package com.abdatytch.user_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'autorisation de la surface inter-services.
 *
 * <h2>Ce que ce fichier verrouille</h2>
 *
 * La décision est passée par trois états avant d'arriver là, et chacun était
 * cassé d'une façon différente :
 *
 * <ol>
 *   <li>{@code hasRole("SERVICE")} dans la chaîne, le rôle accordé par un filtre
 *       qui gardait {@code /api/v1/internal/} — un préfixe que ce service ne
 *       sert pas. Le filtre ne se déclenchait jamais, personne n'obtenait le
 *       rôle, <b>tout était refusé</b> ;</li>
 *   <li>{@code permitAll} dans la chaîne, secret vérifié dans le contrôleur.
 *       L'endpoint existant était protégé, le prochain ajouté sous
 *       {@code /internal/**} ne l'aurait pas été ;</li>
 *   <li>la décision ici, dans la chaîne, sur le chemin réellement servi et le
 *       secret réellement envoyé.</li>
 * </ol>
 *
 * <p>Le cas qui mérite le plus un test est <b>secret non configuré</b>. La
 * logique inverse — « pas de secret, donc pas de vérification » — est un réflexe
 * fréquent, et elle transformerait une variable d'environnement oubliée en
 * ouverture complète de la surface.
 */
@DisplayName("AutorisationInterne — secret inter-services")
class AutorisationInterneTest {

    private static final String SECRET = "secret-interne-de-test";
    private static final String EN_TETE = "X-Internal-Secret";

    private AutorisationInterne autorisation;

    @BeforeEach
    void setUp() {
        autorisation = new AutorisationInterne();
        ReflectionTestUtils.setField(autorisation, "internalSecret", SECRET);
    }

    /** Contexte d'autorisation sur {@code /internal/users/auth/auth-123}. */
    private static AuthorizationContext requete(String valeurDeLenTete) {
        MockServerHttpRequest.BaseBuilder<?> builder =
                MockServerHttpRequest.get("/internal/users/auth/auth-123");
        if (valeurDeLenTete != null) {
            builder = builder.header(EN_TETE, valeurDeLenTete);
        }
        return new AuthorizationContext(MockServerWebExchange.from(builder.build()));
    }

    private void attendre(AuthorizationContext contexte, boolean accorde, String message) {
        StepVerifier.create(autorisation.authorize(Mono.empty(), contexte))
                .assertNext(resultat -> assertThat(resultat.isGranted())
                        .withFailMessage(message)
                        .isEqualTo(accorde))
                .verifyComplete();
    }

    @Test
    @DisplayName("le bon secret est accordé")
    void secretValide() {
        attendre(requete(SECRET), true, "un appel interne legitime a ete refuse");
    }

    @Test
    @DisplayName("un secret faux est refusé")
    void secretInvalide() {
        attendre(requete("mauvais-secret"), false, "un secret errone a ete accepte");
    }

    @Test
    @DisplayName("un en-tête absent est refusé")
    void enTeteAbsent() {
        attendre(requete(null), false, "un appel sans secret a ete accepte");
    }

    @Test
    @DisplayName("un en-tête vide est refusé")
    void enTeteVide() {
        attendre(requete(""), false, "un secret vide a ete accepte");
    }

    /**
     * Secret non configuré : <b>tout</b> est refusé.
     *
     * <p>Et non « rien n'est vérifié ». Le service démarre quand même — une
     * contrainte du projet interdit de refuser le démarrage sur une valeur
     * d'environnement manquante — mais il ne sert pas cette surface.
     */
    @Test
    @DisplayName("aucun secret configuré : tout est refusé, y compris un en-tête vide")
    void secretNonConfigure() {
        ReflectionTestUtils.setField(autorisation, "internalSecret", "");

        attendre(requete("n-importe-quoi"), false,
                "sans secret configure, un appel a ete accepte : une variable "
                        + "d'environnement oubliee ouvrirait toute la surface interne");
        attendre(requete(""), false,
                "un en-tete vide a ete fait correspondre a un secret vide");
        attendre(requete(null), false, "un appel sans en-tete a ete accepte");
    }

    @Test
    @DisplayName("secret nul en configuration : refusé aussi")
    void secretNul() {
        ReflectionTestUtils.setField(autorisation, "internalSecret", null);
        attendre(requete(SECRET), false,
                "un secret de configuration nul n'a pas ferme la surface");
    }

    /**
     * L'authentification n'est pas consultée.
     *
     * <p>Un appel de service à service ne porte pas de jeton d'utilisateur.
     * S'abonner à ce {@code Mono} ferait attendre une résolution inutile, et
     * surtout : faire dépendre la décision d'une authentification absente
     * reviendrait à refuser tous les appels légitimes — c'est précisément ce que
     * faisait la version {@code hasRole("SERVICE")}.
     */
    @Test
    @DisplayName("un Mono d'authentification en erreur ne change rien à la décision")
    void authenticationNonConsultee() {
        AuthorizationResult resultat = autorisation.authorize(
                        Mono.error(new IllegalStateException("ne doit pas etre souscrit")),
                        requete(SECRET))
                .block();

        assertThat(resultat).isNotNull();
        assertThat(resultat.isGranted())
                .withFailMessage("la decision depend de l'authentification, "
                        + "alors qu'un appel de service a service n'en porte pas")
                .isTrue();
    }
}
