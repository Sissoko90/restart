package com.abdatytch.market_service.client;

import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Le profil lu A TRAVERS la couche de codecs de WebClient.
 *
 * <h2>Pourquoi ce test ne double pas {@code ProfilDeserialiseTest}</h2>
 *
 * Celui-la verifie l'{@code ObjectMapper} du module. Celui-ci verifie le chemin
 * REELLEMENT emprunte a l'execution : {@code WebClientConfig} declare un
 * {@code WebClient.builder()} nu, donc ses codecs ne sont pas forcement ceux du
 * mapper du module.
 *
 * <p>La difference ne se verrait pas en journal : un champ non lie reste nul,
 * et une adresse nulle fait simplement qu'aucun courriel ne part.
 */
@DisplayName("ClientProfilUtilisateur — lecture a travers WebClient")
class ClientProfilUtilisateurTest {

    /** La reponse reelle de user-service, relevee le 2026-11-13. */
    private static final String REPONSE = """
            {"id":"3bd0d9f5-2b1f-4b7a-9e0c-6a1b2c3d4e5f",
             "authUserId":"11111111-1111-1111-1111-111111111111",
             "email":"vendeur@exemple.invalid","phoneNumber":null,
             "firstName":"Vendeur","lastName":"Essai","status":"ACTIVE",
             "role":"USER","city":"Bamako","country":"ML","preferences":{}}
            """;

    /** Un disjoncteur transparent : ce test mesure la lecture, pas la resilience. */
    private static final IDisjoncteurSortant PASSANT = new IDisjoncteurSortant() {
        @Override
        public <T> Mono<T> proteger(String nom, Mono<T> appel) {
            return appel;
        }

        @Override
        public void declarer(String nom) {
            // rien a declarer dans un test
        }
    };

    private ClientProfilUtilisateur client(String corps) {
        WebClient.Builder constructeur = WebClient.builder()
                .exchangeFunction(requete -> Mono.just(ClientResponse
                        .create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(corps)
                        .build()));
        return new ClientProfilUtilisateur(constructeur, PASSANT,
                "http://user-service:8083", "secret", 3);
    }

    @Test
    @DisplayName("le pays ET l'adresse traversent la couche de codecs")
    void paysEtAdresseLus() {
        StepVerifier.create(client(REPONSE).pour("11111111-1111-1111-1111-111111111111"))
                .assertNext(profil -> {
                    org.assertj.core.api.Assertions.assertThat(profil.pays())
                            .withFailMessage("""
                                    Le pays n'est pas lu a travers WebClient.

                                    Un particulier ne verrait alors aucun article.""")
                            .isEqualTo("ML");
                    org.assertj.core.api.Assertions.assertThat(profil.adresse())
                            .withFailMessage("""
                                    L'adresse n'est pas lue a travers WebClient.

                                    Aucun courriel de decision ne partirait, et RIEN ne \
                                    le signalerait : la chaine se termine sans envoi.""")
                            .isEqualTo("vendeur@exemple.invalid");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("sans adresse de service, aucun appel n'est tente")
    void sansUrl() {
        var client = new ClientProfilUtilisateur(WebClient.builder(), PASSANT,
                "  ", "secret", 3);
        StepVerifier.create(client.pour("u-1"))
                .expectNext(ClientProfilUtilisateur.INCONNU)
                .verifyComplete();
    }
}
