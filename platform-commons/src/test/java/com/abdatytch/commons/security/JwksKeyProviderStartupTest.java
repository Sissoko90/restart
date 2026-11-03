package com.abdatytch.commons.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Hooks;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Démarrage d'un service quand l'autorité JWT n'est pas encore joignable.
 *
 * <h2>Le défaut que ces tests verrouillent</h2>
 *
 * {@code init()} terminait sa chaîne par un {@code subscribe()} sans consommateur
 * d'erreur. L'échec de récupération du JWKS — parfaitement normal si
 * interface-service démarre après les autres — n'avait donc aucun destinataire.
 * Reactor le signalait alors par {@code onErrorDropped}, c'est-à-dire une
 * {@code ErrorCallbackNotImplemented} accompagnée d'une pile de plus de cent
 * lignes au niveau ERROR, immédiatement après l'avertissement qui expliquait déjà
 * la situation.
 *
 * <p>Conséquence : un démarrage normal en environnement de développement
 * ressemblait à une panne grave, et la vraie erreur, quand elle survenait,
 * devenait indiscernable du bruit.
 *
 * <p>Ces tests observent le crochet {@code onErrorDropped} de Reactor. C'est la
 * seule façon de constater le défaut : la chaîne se comporte par ailleurs
 * correctement, et aucune assertion sur les clés ou les journaux ne l'aurait
 * révélé.
 */
@DisplayName("JwksKeyProvider — démarrage sans autorité JWT joignable")
class JwksKeyProviderStartupTest {

    private final AtomicReference<Throwable> erreurAbandonnee = new AtomicReference<>();

    private JwksKeyProvider provider;

    /**
     * Adresse locale dont le port vient d'être libéré : la connexion y sera
     * refusée.
     *
     * <p>Le port est obtenu puis relâché, plutôt qu'écrit en dur. Une adresse
     * codée dans un test finit par tomber sur un service qui écoute vraiment sur
     * le poste de quelqu'un, et le test devient alors incompréhensible.
     */
    private static String adresseInjoignable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return "http://127.0.0.1:" + port + "/dev/.well-known/jwks.json";
    }

    @BeforeEach
    void setUp() throws IOException {
        Hooks.onErrorDropped(erreurAbandonnee::set);

        provider = new JwksKeyProvider(WebClient.builder());
        ReflectionTestUtils.setField(provider, "jwksUrl", adresseInjoignable());
        ReflectionTestUtils.setField(provider, "refreshIntervalSeconds", 300);
        // Aucun réessai : le test porte sur le traitement de l'échec, pas sur la
        // temporisation. Avec les cinq réessais par défaut, il durerait une
        // trentaine de secondes pour la même conclusion.
        ReflectionTestUtils.setField(provider, "retryAttempts", 0);
    }

    @AfterEach
    void tearDown() {
        Hooks.resetOnErrorDropped();
    }

    @Test
    @DisplayName("l'échec de récupération n'est jamais abandonné par Reactor")
    void aucuneErreurAbandonnee() {
        provider.init();

        // L'échec est asynchrone : on lui laisse le temps de se produire.
        await().atMost(ofSeconds(10))
                .until(() -> !provider.isInitialized());

        assertThat(erreurAbandonnee.get())
                .withFailMessage("l'échec a été abandonné par Reactor (onErrorDropped) : "
                        + "une pile de plus de cent lignes est écrite au niveau ERROR à "
                        + "chaque démarrage sans autorité JWT, ce qui noie les vraies erreurs")
                .isNull();
    }

    @Test
    @DisplayName("init() rend la main immédiatement, sans attendre l'autorité")
    void demarrageNonBloquant() {
        // La garantie qui permet de démarrer les services dans n'importe quel
        // ordre. Une version bloquante ici immobiliserait le démarrage de chaque
        // service tant que l'autorité JWT n'est pas levée.
        long debut = System.currentTimeMillis();

        provider.init();

        assertThat(System.currentTimeMillis() - debut)
                .withFailMessage("init() a bloqué : le service ne peut plus démarrer avant "
                        + "l'autorité JWT")
                .isLessThan(1_000L);
    }

    @Test
    @DisplayName("sans clé disponible, le provider se déclare non initialisé")
    void nonInitialiseSansCle() {
        provider.init();

        await().atMost(ofSeconds(10)).until(() -> provider.getCacheSize() == 0);

        assertThat(provider.isInitialized()).isFalse();
        assertThat(provider.getDefaultPublicKey()).isNull();
        // Le locator doit rester utilisable : un appelant qui valide un jeton
        // pendant cette fenêtre reçoit `null` et refuse le jeton, plutôt que de
        // recevoir une exception inattendue.
        assertThat(provider.keyLocator()).isNotNull();
    }

    @Test
    @DisplayName("le rafraîchissement planifié consomme aussi son échec")
    void rafraichissementPlanifieSansAbandon() {
        provider.scheduledRefresh();

        await().atMost(ofSeconds(10)).until(() -> !provider.isInitialized());

        assertThat(erreurAbandonnee.get()).isNull();
    }
}
