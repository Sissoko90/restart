package com.abdatytch.gateway_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refus des adresses en liste noire.
 *
 * <h2>Le choix de conception a verifier ici</h2>
 *
 * Ce filtre est <b>ouvert en cas de panne</b> : si Redis est injoignable, la
 * requete passe. C'est l'inverse du compromis retenu pour la revocation de
 * jetons, ou l'indisponibilite du cache fait refuser.
 *
 * <p>La difference tient a ce qui est en cause. Pour la revocation, c'est
 * l'identite du porteur : accepter un jeton peut-etre revoque, c'est accepter
 * quelqu'un dont on ne sait plus s'il a le droit. Ici, c'est une adresse
 * reputee hostile : fermer sur panne reviendrait a refuser <b>toute</b> la
 * plateforme a <b>tout le monde</b> pour se proteger de quelques adresses — le
 * remede serait pire que le mal, et un attaquant obtiendrait un deni de service
 * complet en faisant simplement tomber Redis.
 *
 * <p>Ce compromis doit rester un choix explicite et teste, pas un effet de bord
 * de la gestion d'erreur.
 *
 * <h2>Rappel du defaut corrige</h2>
 *
 * Ce filtre fonctionnait correctement mais ne servait a rien : la methode qui
 * devait inscrire les adresses, {@code recordViolation}, n'etait appelee nulle
 * part. Elle vit desormais dans {@link SecurityViolationRecorder}, branche sur
 * les filtres qui detectent.
 */
@DisplayName("IpBlacklistWebFilter - refus des adresses inscrites")
class IpBlacklistWebFilterTest {

    private static final String IP = "203.0.113.7";

    private ReactiveRedisTemplate<String, String> redis;
    private IpBlacklistWebFilter filtre;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = Mockito.mock(ReactiveRedisTemplate.class);
        ClientIpResolver resolveur = Mockito.mock(ClientIpResolver.class);
        when(resolveur.resolve(Mockito.any())).thenReturn(IP);
        filtre = new IpBlacklistWebFilter(redis, resolveur);
    }

    /** Rend {@code true} si la requete a atteint la suite de la chaine. */
    private boolean passe() {
        MockServerWebExchange echange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login").build());
        AtomicBoolean atteinte = new AtomicBoolean(false);

        filtre.filter(echange, suite -> {
            atteinte.set(true);
            return Mono.empty();
        }).block();

        if (!atteinte.get()) {
            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("requete bloquee sans statut de refus")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        return atteinte.get();
    }

    // ==================================================================
    @Nested
    @DisplayName("Decision")
    class Decision {

        @Test
        @DisplayName("une adresse inscrite est refusee en 403")
        void adresseInscriteRefusee() {
            when(redis.hasKey(anyString())).thenReturn(Mono.just(true));

            assertThat(passe()).isFalse();
        }

        @Test
        @DisplayName("une adresse non inscrite passe")
        void adresseLibrePasse() {
            when(redis.hasKey(anyString())).thenReturn(Mono.just(false));

            assertThat(passe()).isTrue();
        }

        @Test
        @DisplayName("la cle consultee est EXACTEMENT celle que l'enregistreur ecrit")
        void memeCleQueLEnregistreur() {
            // Le defaut d'origine n'etait pas un mauvais prefixe mais une absence
            // d'ecrivain. Il reste que lecteur et ecrivain doivent s'accorder :
            // des prefixes divergents produiraient la meme panne sous une autre
            // forme — une liste noire qui se remplit et que personne ne consulte.
            when(redis.hasKey(anyString())).thenReturn(Mono.just(false));

            passe();

            verify(redis).hasKey(SecurityViolationRecorder.BLACKLIST_KEY_PREFIX + IP);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Panne de Redis - ouverture assumee")
    class PanneRedis {

        @Test
        @DisplayName("Redis injoignable laisse passer la requete")
        void redisInjoignableLaissePasser() {
            // Choix delibere, a ne pas « corriger » par reflexe : fermer sur
            // panne offrirait a un attaquant un deni de service complet de la
            // plateforme en faisant tomber Redis.
            when(redis.hasKey(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));

            assertThat(passe())
                    .withFailMessage("une panne de Redis ferme la plateforme a tout "
                            + "le monde : le remede devient pire que le mal")
                    .isTrue();
        }

        @Test
        @DisplayName("une reponse vide de Redis laisse passer, sans perdre la requete")
        void reponseVideLaissePasser() {
            // CE TEST A TROUVE UN DEFAUT REEL.
            //
            // Avec un Mono vide, le flatMap ne s'executait jamais : la requete
            // n'etait ni transmise ni repondue, et le client attendait
            // indefiniment. onErrorResume ne s'appliquait pas — il n'y a pas
            // d'erreur, juste une absence de valeur.
            //
            // C'est l'une des cinq formes du piege Mono<Void> repertoriees dans
            // ce depot, rencontree ici en conditions reelles. Corrige par un
            // defaultIfEmpty pose sur le Mono<Boolean>, et non sur le Mono<Void>
            // final — l'y poser declencherait le repli a chaque requete
            // acceptee, puisque chain.filter() se termine vide en cas de succes.
            when(redis.hasKey(anyString())).thenReturn(Mono.empty());

            assertThat(passe())
                    .withFailMessage("une reponse vide de Redis perd la requete : "
                            + "ni suite de chaine, ni reponse, donc un client en "
                            + "attente jusqu'a son delai d'expiration")
                    .isTrue();
        }
    }
}
