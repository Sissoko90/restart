package com.abdatytch.interface_service.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Liste noire d'adresses d'interface-service.
 *
 * <h2>Le défaut trouvé ici : le filtre était ENTIÈREMENT inerte</h2>
 *
 * Le compteur de tentatives échouées s'incrémentait dans un
 * {@code doOnError} :
 *
 * <pre>{@code
 * return chain.filter(exchange)
 *     .doOnError(throwable -> incrementFailedAttempts(...).subscribe());
 * }</pre>
 *
 * Or un échec d'authentification ne signale <b>aucune erreur Reactor</b> dans
 * cette plateforme : les contrôleurs rendent
 * {@code Mono.just(ResponseEntity.status(UNAUTHORIZED))} et les filtres posent
 * {@code setStatusCode(...)} puis écrivent — autant de complétions normales.
 *
 * <p>{@code doOnError} ne se déclenchait donc que sur une exception non
 * rattrapée, c'est-à-dire sur une <b>erreur 500</b>. Le compteur comptait nos
 * propres pannes, jamais les tentatives d'un attaquant. Le seuil n'était jamais
 * atteint, la liste noire ne se remplissait pas, et sa consultation rendait
 * toujours faux.
 *
 * <p>C'est la dixième occurrence du même motif dans ce dépôt — une protection
 * écrite, présente, et jamais atteinte — après {@code recordViolation} au
 * Gateway, qu'aucun code n'appelait.
 *
 * <h2>Deux défauts plus discrets, du même lot</h2>
 *
 * <ul>
 *   <li>l'incrémentation partait en {@code .subscribe()}, détachée de la
 *       requête : ses échecs étaient invisibles, et un compteur qui perd des
 *       incréments ne franchit jamais son seuil ;</li>
 *   <li>l'expiration de la clé de comptage n'était posée qu'au <b>premier</b>
 *       échec. Si cet appel échouait, la clé survivait sans expiration : le
 *       compteur ne redescendait plus et l'adresse finissait bloquée
 *       <b>définitivement</b>, à cause d'un incident Redis d'une seconde.</li>
 * </ul>
 */
@DisplayName("IpBlacklistFilter - refus des adresses inscrites")
class IpBlacklistFilterTest {

    private static final String IP = "203.0.113.7";
    private static final String PREFIXE = "security:ip:blacklist:";

    private ReactiveStringRedisTemplate redis;
    private ReactiveValueOperations<String, String> valeurs;
    private IpBlacklistFilter filtre;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = Mockito.mock(ReactiveStringRedisTemplate.class);
        valeurs = Mockito.mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valeurs);
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(redis.getExpire(anyString())).thenReturn(Mono.just(Duration.ofSeconds(900)));
        when(redis.delete(anyString())).thenReturn(Mono.just(1L));
        when(valeurs.increment(anyString())).thenReturn(Mono.just(1L));
        when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));

        filtre = new IpBlacklistFilter(redis);
        ReflectionTestUtils.setField(filtre, "enabled", true);
        ReflectionTestUtils.setField(filtre, "cacheTtl", 1800L);
        ReflectionTestUtils.setField(filtre, "maxAttempts", 5);
        ReflectionTestUtils.setField(filtre, "blockDuration", 3600L);
        ReflectionTestUtils.setField(filtre, "redisKeyPrefix", PREFIXE);
    }

    /**
     * Traverse le filtre depuis l'adresse donnée, la suite de chaîne répondant
     * le statut indiqué.
     *
     * @param statutAval statut que le reste de la chaîne pose, ou {@code null}
     *                   pour une réponse qui aboutit normalement
     */
    private MockServerWebExchange appel(String ipClient, HttpStatus statutAval) {
        MockServerWebExchange echange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/login")
                        .header("X-Client-Ip", ipClient)
                        .build());
        AtomicBoolean atteinte = new AtomicBoolean(false);
        echange.getAttributes().put("atteinte", atteinte);

        filtre.filter(echange, suite -> {
            atteinte.set(true);
            if (statutAval != null) {
                // C'est ainsi que la plateforme refuse : un statut pose, puis une
                // completion NORMALE. Jamais une erreur Reactor.
                echange.getResponse().setStatusCode(statutAval);
            }
            return Mono.empty();
        }).block();

        return echange;
    }

    private boolean passe(MockServerWebExchange echange) {
        return ((AtomicBoolean) echange.getAttributes().get("atteinte")).get();
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : les échecs sont enfin comptés")
    class EchecsComptes {

        @Test
        @DisplayName("un 401 en aval INCRÉMENTE le compteur")
        void refus401Compte() {
            // CE TEST A TROUVE LE DEFAUT.
            //
            // La suite de chaine pose un 401 et se termine normalement — la
            // seule facon dont cette plateforme refuse. L'ancien doOnError ne
            // voyait rien, donc le compteur restait a zero indefiniment.
            appel(IP, HttpStatus.UNAUTHORIZED);

            verify(valeurs)
                    .increment(PREFIXE + "attempts:" + IP);
        }

        @Test
        @DisplayName("un 403 en aval incrémente aussi")
        void refus403Compte() {
            appel(IP, HttpStatus.FORBIDDEN);

            verify(valeurs).increment(anyString());
        }

        @Test
        @DisplayName("une réponse qui ABOUTIT n'incrémente pas")
        void succesNeComptePas() {
            // Compter les succes finirait par bloquer les utilisateurs
            // legitimes : c'est l'erreur symetrique, et elle serait pire.
            appel(IP, HttpStatus.OK);

            verify(valeurs, never()).increment(anyString());
        }

        @ParameterizedTest(name = "statut {0}")
        @ValueSource(ints = {400, 404, 429, 500, 503})
        @DisplayName("ni une requête malformée, ni un 404, ni nos propres pannes ne comptent")
        void autresStatutsNeComptentPas(int statut) {
            // Un 400 signale une requete malformee, un 404 un chemin inexistant,
            // un 5xx NOTRE panne. Aucun des trois n'est une tentative d'acces a
            // compter contre l'appelant — et compter nos 500 est precisement ce
            // que faisait l'ancien doOnError.
            appel(IP, HttpStatus.valueOf(statut));

            verify(valeurs, never()).increment(anyString());
        }

        @Test
        @DisplayName("au seuil atteint, l'adresse est INSCRITE en liste noire")
        void inscriptionAuSeuil() {
            when(valeurs.increment(anyString())).thenReturn(Mono.just(5L));

            appel(IP, HttpStatus.UNAUTHORIZED);

            verify(valeurs).set(
                    Mockito.eq(PREFIXE + IP), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("sous le seuil, aucune inscription")
        void pasDInscriptionSousLeSeuil() {
            when(valeurs.increment(anyString())).thenReturn(Mono.just(3L));

            appel(IP, HttpStatus.UNAUTHORIZED);

            verify(valeurs, never()).set(
                    Mockito.eq(PREFIXE + IP), anyString(), any(Duration.class));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Décision sur une adresse inscrite")
    class Decision {

        @Test
        @DisplayName("une adresse inscrite est refusée en 403, sans atteindre la suite")
        void adresseInscriteRefusee() {
            when(redis.hasKey(PREFIXE + IP)).thenReturn(Mono.just(true));

            var echange = appel(IP, null);

            assertThat(passe(echange))
                    .withFailMessage("une adresse inscrite atteint le service")
                    .isFalse();
            assertThat(echange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("la clé consultée est celle partagée avec le Gateway")
        void memeClePartagee() {
            // Les trois composants utilisaient un prefixe different : chacun
            // voyait sa propre liste, et aucune decision de blocage n'etait
            // appliquee ailleurs qu'a l'endroit ou elle avait ete prise.
            appel(IP, null);

            verify(redis).hasKey(PREFIXE + IP);
        }

        @Test
        @DisplayName("une adresse non inscrite passe")
        void adresseLibrePasse() {
            assertThat(passe(appel(IP, null))).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Adresses exemptées de comptage")
    class Exemptees {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "127.0.0.1", "::1", "localhost",
                "10.0.0.5", "192.168.1.20", "172.21.0.2"
        })
        @DisplayName("un appelant interne n'est jamais compté ni inscrit")
        void appelantInterne(String ip) {
            // La plateforme se couperait elle-meme : le Gateway et les autres
            // services appellent depuis des adresses privees.
            appel(ip, HttpStatus.UNAUTHORIZED);

            verify(valeurs, never()).increment(anyString());
            verify(redis, never()).hasKey(anyString());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"172.0.0.1", "172.217.16.1", "100.64.0.1"})
        @DisplayName("une adresse publique proche d'une privée EST comptée")
        void publiqueProcheDUnePrivee(String ip) {
            // 172.217.16.1 appartient a Google : l'exempter du comptage
            // reviendrait a offrir une plage entiere a qui veut essayer des mots
            // de passe sans jamais etre bloque.
            appel(ip, HttpStatus.UNAUTHORIZED);

            verify(valeurs).increment(anyString());
        }

        @Test
        @DisplayName("le filtre désactivé court-circuite tout")
        void desactive() {
            ReflectionTestUtils.setField(filtre, "enabled", false);

            assertThat(passe(appel(IP, HttpStatus.UNAUTHORIZED))).isTrue();
            verify(redis, never()).hasKey(anyString());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Expiration de la clé de comptage")
    class Expiration {

        @Test
        @DisplayName("l'expiration est posée au premier échec")
        void poseeAuPremierEchec() {
            when(valeurs.increment(anyString())).thenReturn(Mono.just(1L));

            appel(IP, HttpStatus.UNAUTHORIZED);

            verify(redis).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("une expiration MANQUANTE est reposée")
        void expirationReparee() {
            // LE DEFAUT. L'expiration n'etait posee qu'au premier echec : si cet
            // appel echouait, la cle survivait sans expiration, le compteur ne
            // redescendait plus, et l'adresse finissait bloquee definitivement a
            // cause d'un incident Redis d'une seconde.
            when(valeurs.increment(anyString())).thenReturn(Mono.just(3L));
            when(redis.getExpire(anyString())).thenReturn(Mono.just(Duration.ofSeconds(-1)));

            appel(IP, HttpStatus.UNAUTHORIZED);

            verify(redis).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("une expiration VALIDE n'est pas reposée")
        void expirationConservee() {
            // La reposer a chaque echec ferait glisser la fenetre : un attaquant
            // essayant un mot de passe toutes les vingt minutes n'atteindrait
            // jamais le seuil.
            when(valeurs.increment(anyString())).thenReturn(Mono.just(3L));

            appel(IP, HttpStatus.UNAUTHORIZED);

            verify(redis, never()).expire(anyString(), any(Duration.class));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Panne de Redis - ouverture assumée")
    class PanneRedis {

        @Test
        @DisplayName("Redis injoignable laisse passer la requête")
        void redisInjoignable() {
            // Choix delibere, a ne pas « corriger » par reflexe : fermer sur
            // panne offrirait un deni de service complet de la plateforme a qui
            // ferait tomber Redis. Le compromis inverse vaut pour la revocation
            // de jetons, ou c'est l'identite du porteur qui est en cause.
            when(redis.hasKey(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));

            assertThat(passe(appel(IP, null)))
                    .withFailMessage("une panne de Redis ferme la plateforme à tout "
                            + "le monde : le remède devient pire que le mal")
                    .isTrue();
        }

        @Test
        @DisplayName("une réponse VIDE de Redis ne perd pas la requête")
        void reponseVide() {
            // Le piege repertorie dans ce depot : un Mono vide traverse le
            // flatMap sans le declencher, et la requete n'est NI transmise NI
            // repondue. onErrorResume ne s'applique pas — il n'y a pas
            // d'erreur, juste une absence de valeur.
            when(redis.hasKey(anyString())).thenReturn(Mono.empty());

            assertThat(passe(appel(IP, null)))
                    .withFailMessage("une réponse vide de Redis perd la requête : "
                            + "ni suite de chaîne, ni réponse")
                    .isTrue();
        }

        @Test
        @DisplayName("un échec de comptage ne fait pas échouer la requête")
        void echecDeComptage() {
            // La requete a deja ete traitee : cette etape n'est qu'un
            // enregistrement. La faire echouer transformerait un refus 401
            // legitime en 500.
            when(valeurs.increment(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));

            var echange = appel(IP, HttpStatus.UNAUTHORIZED);

            assertThat(passe(echange)).isTrue();
            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("un échec de comptage transforme le refus en "
                            + "erreur serveur")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
