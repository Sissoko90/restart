package com.abdatytch.gateway_service.security;

import com.abdatytch.commons.security.ClesRedisSecurite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Le mode maintenance, qui n'etait applique par personne.
 *
 * <h2>Ce que cette suite protege</h2>
 *
 * {@code setting-service} ecrivait {@code system.maintenance-mode}, le
 * journalisait en {@code WARN}, et aucun code ne le lisait. Un administrateur
 * voyait la confirmation et les journaux ; le trafic passait. Le motif le plus
 * frequent de ce depot : une protection ecrite, confirmee a son auteur, et
 * jamais atteinte.
 *
 * <p>Le filtre est donc la premiere chose a tester <b>par son effet</b>, et non
 * par sa presence : un filtre enregistre qui laisse tout passer reproduirait le
 * defaut a l'identique.
 */
@DisplayName("MaintenanceWebFilter — la coupure est reellement appliquee")
class MaintenanceWebFilterTest {

    private Map<String, String> cache;
    private AtomicBoolean chaineAtteinte;
    private WebFilterChain chaine;
    private MaintenanceWebFilter filtre;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        cache = new HashMap<>();
        chaineAtteinte = new AtomicBoolean(false);

        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        ReactiveHashOperations<String, String, String> hachage =
                mock(ReactiveHashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(hachage);
        when(hachage.get(anyString(), anyString())).thenAnswer(appel ->
                Mono.justOrEmpty(cache.get((String) appel.getArgument(1))));

        // Mono.defer dans la chaine : sans lui, le drapeau serait leve a
        // l'ASSEMBLAGE et le test passerait meme si le filtre refuse. C'est
        // exactement le defaut corrige dans les autres tests de filtres de
        // cette passerelle.
        chaine = echange -> Mono.defer(() -> {
            chaineAtteinte.set(true);
            return Mono.empty();
        });

        filtre = new MaintenanceWebFilter(redis);
    }

    private MockServerWebExchange appel(String chemin) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(chemin).build());
    }

    private void maintenanceSur(String service, String message, String echeance) {
        cache.put(service, message + "|" + echeance);
    }

    // ==================================================================
    @Nested
    @DisplayName("Sans maintenance, rien ne change")
    class SansMaintenance {

        @Test
        @DisplayName("un cache vide laisse passer")
        void cacheVideLaissePasser() {
            var echange = appel("/api/v1/auth-sdk/login");

            StepVerifier.create(filtre.filter(echange, chaine)).verifyComplete();

            assertThat(chaineAtteinte)
                    .withFailMessage("le filtre refuse alors qu'aucune maintenance "
                            + "n'est declaree")
                    .isTrue();
        }

        /**
         * Une panne de Redis LAISSE PASSER, et c'est delibere.
         *
         * <p>L'alternative — refuser en cas de doute — ferait d'une panne de
         * Redis une coupure de toute la plateforme, alors qu'aucune maintenance
         * n'etait declaree. Une maintenance, elle, est annoncee : le service en
         * travaux refuse de lui-meme. {@code IpBlacklistWebFilter} a fait le
         * meme choix, pour la meme raison.
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("un cache illisible laisse passer, et ne coupe pas la plateforme")
        void cacheIllisibleLaissePasser() {
            ReactiveRedisTemplate<String, String> casse = mock(ReactiveRedisTemplate.class);
            ReactiveHashOperations<String, String, String> hachage =
                    mock(ReactiveHashOperations.class);
            when(casse.<String, String>opsForHash()).thenReturn(hachage);
            when(hachage.get(anyString(), anyString()))
                    .thenReturn(Mono.error(new IllegalStateException("Redis injoignable")));

            var echange = appel("/api/v1/auth-sdk/login");

            StepVerifier.create(new MaintenanceWebFilter(casse).filter(echange, chaine))
                    .verifyComplete();

            assertThat(chaineAtteinte)
                    .withFailMessage("""
                            Une panne de Redis a coupe la requete.

                            Refuser en cas de doute transforme une panne du cache en \
                            coupure de toute la plateforme, alors qu'aucune \
                            maintenance n'etait declaree.""")
                    .isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Maintenance globale")
    class MaintenanceGlobale {

        @Test
        @DisplayName("une maintenance globale refuse en 503, avec le message")
        void globaleRefuse() {
            maintenanceSur(ClesRedisSecurite.MAINTENANCE_TOUTE_PLATEFORME,
                    "Mise a jour en cours", "2026-10-13T14:00:00Z");
            var echange = appel("/api/v1/auth-sdk/login");

            StepVerifier.create(filtre.filter(echange, chaine)).verifyComplete();

            assertThat(chaineAtteinte)
                    .withFailMessage("""
                            La requete est passee malgre une maintenance globale.

                            C'etait l'etat d'origine : setting-service ecrivait \
                            system.maintenance-mode et personne ne le lisait.""")
                    .isFalse();
            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("503 dit « reessaie plus tard » ; 500 accuserait "
                            + "la plateforme et 404 ferait chercher une erreur de chemin")
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(echange.getResponse().getHeaders().getFirst("X-Maintenance-Until"))
                    .isEqualTo("2026-10-13T14:00:00Z");
        }

        /**
         * Les sondes passent, meme en maintenance globale.
         *
         * <p>Couper {@code /actuator/health} ferait sortir toutes les instances
         * du pool, et l'orchestrateur les redemarrerait en boucle : la
         * maintenance deviendrait la panne qu'elle devait eviter.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/actuator/health", "/actuator/prometheus", "/actuator/info"})
        @DisplayName("les sondes passent malgre une maintenance globale")
        void sondesPassentToujours(String chemin) {
            maintenanceSur(ClesRedisSecurite.MAINTENANCE_TOUTE_PLATEFORME,
                    "Tout est coupe", "");

            StepVerifier.create(filtre.filter(appel(chemin), chaine)).verifyComplete();

            assertThat(chaineAtteinte)
                    .withFailMessage("""
                            La sonde %s est coupee.

                            Toutes les instances sortiraient du pool et \
                            l'orchestrateur les redemarrerait en boucle : la \
                            maintenance deviendrait la panne qu'elle devait \
                            eviter.""", chemin)
                    .isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Maintenance d'un seul service")
    class MaintenanceCiblee {

        /**
         * Votre demande explicite : couper un service sans interrompre les
         * autres.
         */
        @ParameterizedTest(name = "{0} -> coupe : {1}")
        @CsvSource({
                "/api/v1/auth-sdk/login,        true",
                "/api/v1/auth-admin/accounts,   true",
                "/api/v1/users/42,              false",
                "/api/v1/media/documents,       false",
                "/api/v1/settings,              false",
        })
        @DisplayName("seul le service coupe refuse")
        void seulLeServiceVise(String chemin, boolean doitEtreCoupe) {
            maintenanceSur("auth-service", "Auth en travaux", "");

            StepVerifier.create(filtre.filter(appel(chemin.trim()), chaine))
                    .verifyComplete();

            assertThat(chaineAtteinte.get())
                    .withFailMessage("%s : attendu coupe=%s, obtenu coupe=%s",
                            chemin, doitEtreCoupe, !chaineAtteinte.get())
                    .isEqualTo(!doitEtreCoupe);
        }

        /**
         * L'ordre de la table de routage est la regle.
         *
         * <p>{@code /api/v1/admin/documents} va chez media-service, et
         * {@code /api/v1/admin/**} chez interface-service. Si le prefixe le plus
         * court etait teste d'abord, couper media-service ne couperait pas ses
         * documents, et couper interface-service couperait les leurs.
         */
        @Test
        @DisplayName("les chemins /admin les plus specifiques vont au bon service")
        void prefixesAdminOrdonnes() {
            maintenanceSur("media-service", "Documents en travaux", "");

            StepVerifier.create(filtre.filter(appel("/api/v1/admin/documents/7"), chaine))
                    .verifyComplete();
            assertThat(chaineAtteinte)
                    .withFailMessage("/api/v1/admin/documents n'a pas ete rattache a "
                            + "media-service : le prefixe /api/v1/admin l'a capture")
                    .isFalse();

            chaineAtteinte.set(false);
            StepVerifier.create(filtre.filter(appel("/api/v1/admin/stats"), chaine))
                    .verifyComplete();
            assertThat(chaineAtteinte)
                    .withFailMessage("/api/v1/admin/stats a ete coupe alors que la "
                            + "maintenance ne vise que media-service")
                    .isTrue();
        }

        @Test
        @DisplayName("un chemin hors routage passe")
        void cheminHorsRoutagePasse() {
            // Swagger, un chemin inconnu : une coupure annoncee pour un service
            // n'a aucune raison de les bloquer.
            maintenanceSur("auth-service", "Auth en travaux", "");

            StepVerifier.create(filtre.filter(appel("/swagger-ui.html"), chaine))
                    .verifyComplete();

            assertThat(chaineAtteinte).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le corps rendu reste du JSON valide")
    class CorpsValide {

        /**
         * Un message contenant des guillemets ne doit pas casser le JSON.
         *
         * <p>« retour prevu a 14h00 » s'ecrit volontiers avec des guillemets, et
         * un client qui recoit du JSON malforme pendant une maintenance conclut
         * a une panne — exactement ce que le message devait eviter.
         */
        @Test
        @DisplayName("un message avec guillemets et retours a la ligne est echappe")
        void messageEchappe() {
            maintenanceSur(ClesRedisSecurite.MAINTENANCE_TOUTE_PLATEFORME,
                    "Mise a jour \"majeure\"\nretour a 14h", "");
            var echange = appel("/api/v1/users/1");

            StepVerifier.create(filtre.filter(echange, chaine)).verifyComplete();

            // Le corps est ecrit dans la reponse simulee : on verifie qu'il est
            // lisible comme du JSON, et non seulement qu'il est non vide.
            String corps = echange.getResponse().getBodyAsString().block();
            assertThat(corps).isNotNull();
            assertThat(corps)
                    .withFailMessage("le guillemet n'est pas echappe : le JSON est "
                            + "invalide, et le client conclura a une panne")
                    .contains("\\\"majeure\\\"")
                    .contains("\\n");
        }

        @Test
        @DisplayName("une echeance absente ne produit pas de champ vide")
        void echeanceAbsente() {
            maintenanceSur(ClesRedisSecurite.MAINTENANCE_TOUTE_PLATEFORME,
                    "Coupure sans terme", "");
            var echange = appel("/api/v1/users/1");

            StepVerifier.create(filtre.filter(echange, chaine)).verifyComplete();

            String corps = echange.getResponse().getBodyAsString().block();
            assertThat(corps).doesNotContain("jusqua");
            assertThat(echange.getResponse().getHeaders().getFirst("X-Maintenance-Until"))
                    .isNull();
        }
    }
}
