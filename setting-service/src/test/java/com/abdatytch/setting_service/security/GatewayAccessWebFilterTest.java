package com.abdatytch.setting_service.security;

import com.abdatytch.commons.security.HMACSignatureUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le filtre d'accès de setting-service, exercé pour la première fois.
 *
 * <h2>Le trou que ce test comble</h2>
 *
 * La politique des chemins publics a été migrée vers {@code CheminsPublics} de
 * platform-commons dans <b>cinq</b> services. L'objet lui-même est largement
 * testé, mais son <b>intégration</b> ne l'était que dans media-service et
 * auth-service. Ce module n'avait <b>aucun</b> test de couche HTTP : deux
 * fichiers de test, tous au niveau service.
 *
 * <p>La compilation prouve le câblage. Rien ne prouvait le comportement — ni
 * qu'une sonde passe, ni qu'un chemin métier soit refusé sans signature, ni que
 * le contrôle de réseau porte sur le pair TCP et non sur un en-tête forgeable.
 *
 * <p>C'est le symptôme que ce dépôt a appris à craindre plus que n'importe
 * quelle règle suspecte : « pas de test de la chaîne HTTP ».
 */
@DisplayName("setting-service - le filtre d'acces de la passerelle")
class GatewayAccessWebFilterTest {

    private static final String SECRET = "secret-de-test-pour-la-signature-hmac";
    private static final String PAIR_INTERNE = "10.0.0.5";
    private static final String PAIR_EXTERNE = "203.0.113.7";

    private final GatewayAccessWebFilter filtre = creerFiltre(SECRET);

    private static GatewayAccessWebFilter creerFiltre(String secret) {
        var f = new GatewayAccessWebFilter(new HMACSignatureUtil());
        ReflectionTestUtils.setField(f, "gatewaySecretKey", secret);
        return f;
    }

    private record Resultat(HttpStatus statut, boolean transmise) {
    }

    /**
     * Joue une requête à travers le filtre.
     *
     * <p>L'adresse du pair est posée explicitement : c'est elle, et non
     * l'en-tête {@code X-Client-Ip}, que le contrôle de réseau doit lire
     * depuis la correction de ce défaut.
     */
    private Resultat jouer(String chemin, String pair, boolean signee, String enTeteIpForge) {
        var constructeur = MockServerHttpRequest
                .method(HttpMethod.GET, URI.create(chemin))
                .remoteAddress(new InetSocketAddress(pair, 54321));

        if (signee) {
            String horodatage = String.valueOf(System.currentTimeMillis());
            String idRequete = UUID.randomUUID().toString();
            var hmac = new HMACSignatureUtil();
            String donnees = hmac.buildSignatureData(horodatage, chemin, "GET", idRequete);
            constructeur = constructeur
                    .header("X-Gateway-Timestamp", horodatage)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature", hmac.generateSignature(SECRET, donnees));
        }
        if (enTeteIpForge != null) {
            constructeur = constructeur.header("X-Client-Ip", enTeteIpForge);
        }

        MockServerWebExchange echange = MockServerWebExchange.from(constructeur.build());
        AtomicBoolean transmise = new AtomicBoolean(false);
        WebFilterChain chaine = e -> {
            transmise.set(true);
            return Mono.empty();
        };

        filtre.filter(echange, chaine).block();

        var brut = echange.getResponse().getStatusCode();
        return new Resultat(brut == null ? null : HttpStatus.resolve(brut.value()),
                transmise.get());
    }

    // ==================================================================
    @Nested
    @DisplayName("Les sondes passent sans signature")
    class Sondes {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/health", "/actuator/health/readiness",
                "/actuator/info", "/actuator/prometheus"
        })
        @DisplayName("Consul et Prometheus atteignent leurs sondes")
        void sondesPassent(String chemin) {
            // Ni l'un ni l'autre ne porte de signature de passerelle. Les
            // refuser ferait croire le service mort.
            assertThat(jouer(chemin, PAIR_EXTERNE, false, null).transmise())
                    .withFailMessage("la sonde %s est refusee : Consul croira le "
                            + "service mort", chemin)
                    .isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/env", "/actuator/loggers", "/actuator/httptrace",
                "/actuator/heapdump", "/actuator"
        })
        @DisplayName("le reste d'actuator exige la signature")
        void resteDActuatorProtege(String chemin) {
            assertThat(jouer(chemin, PAIR_EXTERNE, false, null).transmise())
                    .withFailMessage("%s est exempte de signature : env rend la "
                            + "configuration, loggers permet d'ecrire les niveaux, "
                            + "httptrace rend les dernieres requetes", chemin)
                    .isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les chemins metier exigent la passerelle")
    class CheminsMetier {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/settings/system", "/settings/users/user-1/theme", "/admin/settings/system/x"})
        @DisplayName("sans signature, refuse en 403")
        void sansSignatureRefuse(String chemin) {
            Resultat r = jouer(chemin, PAIR_INTERNE, false, null);

            assertThat(r.transmise())
                    .withFailMessage("%s a traverse le filtre SANS signature de "
                            + "passerelle : l'appel direct au port fonctionne, et la "
                            + "passerelle n'est plus la seule porte", chemin)
                    .isFalse();
            assertThat(r.statut()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/settings/system", "/settings/users/user-1/theme", "/admin/settings/system/x"})
        @DisplayName("avec une signature valide depuis un reseau prive, passe")
        void signatureValidePasse(String chemin) {
            assertThat(jouer(chemin, PAIR_INTERNE, true, null).transmise())
                    .withFailMessage("%s est refuse malgre une signature valide : le "
                            + "service devient injoignable PAR la passerelle", chemin)
                    .isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le controle de reseau porte sur le pair TCP")
    class ControleDeReseau {

        /**
         * Le défaut corrigé : l'en-tête était cru.
         *
         * <p>{@code GatewayClientIp.of()} privilégie {@code X-Client-Ip}, que
         * le Gateway pose mais qu'un appelant <b>direct</b> peut forger. Un
         * {@code X-Client-Ip: 10.0.0.1} franchissait donc ce contrôle.
         *
         * <p>Cela comptait parce que le secret HMAC figure dans l'historique
         * Git : ce contrôle est censé être le <b>dernier rempart</b>.
         */
        @Test
        @DisplayName("un en-tete X-Client-Ip forge ne fait pas passer pour interne")
        void enTeteForgeNeSuffitPas() {
            Resultat r = jouer("/settings/system", PAIR_EXTERNE, true, "10.0.0.1");

            assertThat(r.transmise())
                    .withFailMessage("un appelant externe est passe en posant "
                            + "X-Client-Ip: 10.0.0.1. Le controle de reseau lit un "
                            + "en-tete que l'appelant choisit, donc il ne protege rien.")
                    .isFalse();
            assertThat(r.statut()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("un pair externe est refuse meme avec une signature valide")
        void pairExterneRefuse() {
            assertThat(jouer("/settings/system", PAIR_EXTERNE, true, null).transmise())
                    .isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La documentation : signature exigee, jeton non")
    class Documentation {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/v3/api-docs", "/swagger-ui/index.html", "/webjars/x.png"})
        @DisplayName("non signee, elle est refusee")
        void nonSigneeRefusee(String chemin) {
            assertThat(jouer(chemin, PAIR_EXTERNE, false, null).transmise())
                    .withFailMessage("%s est lisible sans signature : la documentation "
                            + "decrit toute la surface d'API et devient accessible en "
                            + "attaquant le port du service en direct", chemin)
                    .isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/v3/api-docs", "/swagger-ui/index.html"})
        @DisplayName("signee, elle passe")
        void signeePasse(String chemin) {
            assertThat(jouer(chemin, PAIR_INTERNE, true, null).transmise()).isTrue();
        }

        /**
         * Le contournement par encodage, fermé.
         *
         * <p>Le filtre décidait sur le chemin <b>brut</b> alors que les motifs
         * Spring comparent des segments <b>décodés</b>. Ici la conséquence est
         * inverse de celle du Gateway : un chemin encodé n'était pas reconnu
         * comme public, donc il exigeait une signature — fail-closed. Mais
         * l'asymétrie entre les deux couches restait, et c'est elle qui ouvrait
         * la garde de documentation du Gateway.
         */
        @Test
        @DisplayName("un chemin de documentation encode est traite comme tel")
        void cheminEncodeReconnu() {
            assertThat(jouer("/%73wagger-ui/index.html", PAIR_INTERNE, true, null).transmise())
                    .isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Sans secret configure, tout est refuse")
    class SansSecret {

        @Test
        @DisplayName("un secret vide ferme le service au lieu de l'ouvrir")
        void secretVideFermeTout() {
            var sansSecret = creerFiltre("");
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.method(HttpMethod.GET, URI.create("/settings/system"))
                            .remoteAddress(new InetSocketAddress(PAIR_INTERNE, 54321))
                            .build());
            AtomicBoolean transmise = new AtomicBoolean(false);
            sansSecret.filter(echange, e -> {
                transmise.set(true);
                return Mono.empty();
            }).block();

            assertThat(transmise.get())
                    .withFailMessage("un secret de passerelle vide laisse passer : le "
                            + "service serait ouvert par une variable d'environnement "
                            + "oubliee")
                    .isFalse();
        }
    }
}
