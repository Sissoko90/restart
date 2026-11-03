package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.dto.request.OAuth2CallbackRequest;
import com.abdatytch.auth_service.dto.response.OAuth2AuthResponse;
import com.abdatytch.auth_service.service.IAuthService;
import com.abdatytch.auth_service.service.IOAuth2Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Le rappel en {@code form_post}, la forme qu'Apple impose.
 *
 * <h2>Pourquoi un endpoint de plus, et pas un parametre de plus</h2>
 *
 * Apple <b>exige</b> {@code response_mode=form_post} des que le perimetre
 * demande {@code name} ou {@code email}, et refuse l'autorisation sinon. Le
 * rappel arrive donc en {@code POST} avec un corps de formulaire, la ou Google
 * et Facebook reviennent en {@code GET} avec des parametres de requete. Les deux
 * formes doivent coexister.
 *
 * <h2>Ce que ces tests protegent</h2>
 *
 * Surtout la lecture du champ {@code user}. Apple n'a aucun point de profil et
 * ne transmet le nom qu'ici, a la <b>premiere</b> autorisation : il ne repassera
 * jamais. Un oubli de lecture ne produit aucune erreur — seulement des comptes
 * anonymes, decouverts bien plus tard et irrattrapables.
 */
@DisplayName("Rappel Apple en form_post")
class RappelAppleFormPostTest {

    private IOAuth2Service oauth2Service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        oauth2Service = Mockito.mock(IOAuth2Service.class);
        AuthController controleur = new AuthController(
                Mockito.mock(IAuthService.class), oauth2Service, new ObjectMapper());

        client = WebTestClient.bindToController(controleur).build();

        Mockito.when(oauth2Service.redirectUriDuFournisseur(anyString()))
                .thenReturn("https://exemple.test/auth/apple/rappel");
        Mockito.when(oauth2Service.handleOAuth2Callback(any(), any(), any()))
                .thenReturn(Mono.just(OAuth2AuthResponse.builder()
                        .profileComplete(true)
                        .accessToken("at")
                        .refreshToken("rt")
                        .expiresIn(3600L)
                        .build()));
        // Mono : l'URL porte desormais un code de remise retenu dans Redis,
        // donc sa construction est reactive. Les jetons ne sont plus dans la
        // chaine de requete — voir CodeDeRemiseOAuth2Redis.
        Mockito.when(oauth2Service.urlDeRedirectionApresRappel(any()))
                .thenReturn(Mono.just("https://front.test/auth/callback?code=xyz"));
        Mockito.when(oauth2Service.urlDErreur(anyString(), anyString()))
                .thenReturn("https://front.test/auth/callback?success=false");
    }

    private static MultiValueMap<String, String> formulaire(String... paires) {
        MultiValueMap<String, String> champs = new LinkedMultiValueMap<>();
        for (int i = 0; i < paires.length; i += 2) {
            champs.add(paires[i], paires[i + 1]);
        }
        return champs;
    }

    private void poster(MultiValueMap<String, String> champs, int statutAttendu) {
        client.post().uri("/auth/oauth2/callback/apple")
                .body(BodyInserters.fromFormData(champs))
                .exchange()
                .expectStatus().isEqualTo(statutAttendu);
    }

    private OAuth2CallbackRequest requeteTransmise() {
        ArgumentCaptor<OAuth2CallbackRequest> capteur =
                ArgumentCaptor.forClass(OAuth2CallbackRequest.class);
        Mockito.verify(oauth2Service).handleOAuth2Callback(capteur.capture(), any(), any());
        return capteur.getValue();
    }

    // ==================================================================
    @Nested
    @DisplayName("Le corps de formulaire est lu")
    class CorpsLu {

        @Test
        @DisplayName("code et state sont transmis au service, et la reponse est un 302")
        void codeEtStateTransmis() {
            poster(formulaire("code", "code-apple-123", "state", "etat-abc"), 302);

            OAuth2CallbackRequest transmise = requeteTransmise();
            assertThat(transmise.getCode()).isEqualTo("code-apple-123");
            assertThat(transmise.getState())
                    .withFailMessage("le state du formulaire n'atteint pas le "
                            + "service : la verification anti-falsification ne "
                            + "porterait alors sur rien")
                    .isEqualTo("etat-abc");
            assertThat(transmise.getProvider()).isEqualTo("apple");
        }

        /**
         * L'unique occasion de lire le nom.
         *
         * <p>Apple ne l'envoie qu'a la premiere autorisation. Non lu ce
         * jour-la, il est perdu definitivement — sauf si la personne revoque
         * l'acces dans les reglages de son appareil, ce qu'on ne peut pas lui
         * demander. C'est la cause connue des comptes Apple anonymes.
         */
        @Test
        @DisplayName("le nom du champ user atteint le service")
        void nomDuChampUserTransmis() {
            poster(formulaire(
                    "code", "code-apple-123",
                    "state", "etat-abc",
                    "user", "{\"name\":{\"firstName\":\"Awa\",\"lastName\":\"Diallo\"}}"), 302);

            OAuth2CallbackRequest transmise = requeteTransmise();
            assertThat(transmise.getPrenomDuRappel())
                    .withFailMessage("""
                            Le nom transmis par Apple n'atteint pas le service.

                            Apple ne l'envoie qu'a la PREMIERE autorisation et ne \
                            le renverra jamais : le compte restera anonyme \
                            definitivement, sans qu'aucune erreur le signale.""")
                    .isEqualTo("Awa");
            assertThat(transmise.getNomDuRappel()).isEqualTo("Diallo");
        }

        @Test
        @DisplayName("un rappel sans champ user passe quand meme")
        void sansChampUser() {
            // L'etat de toutes les connexions sauf la premiere. Le refuser
            // bloquerait presque tout le trafic Apple.
            poster(formulaire("code", "code-apple-123", "state", "etat-abc"), 302);

            OAuth2CallbackRequest transmise = requeteTransmise();
            assertThat(transmise.getPrenomDuRappel()).isNull();
            assertThat(transmise.getNomDuRappel()).isNull();
        }

        @Test
        @DisplayName("un champ user illisible ne refuse pas la connexion")
        void champUserIllisible() {
            // Le champ ne sert qu'a l'etat civil : l'identite vient du « sub »
            // du id_token verifie. Mieux vaut un compte sans nom qu'une
            // personne qui ne peut pas entrer.
            poster(formulaire("code", "c", "state", "e", "user", "pas du json"), 302);

            assertThat(requeteTransmise().getPrenomDuRappel()).isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Rappels en erreur")
    class RappelsEnErreur {

        @Test
        @DisplayName("un rappel portant une erreur redirige sans appeler le service")
        void rappelEnErreur() {
            poster(formulaire("error", "user_cancelled_authorize", "state", "e"), 302);

            Mockito.verify(oauth2Service, Mockito.never())
                    .handleOAuth2Callback(any(), any(), any());
        }

        @Test
        @DisplayName("un rappel sans code redirige sans appeler le service")
        void rappelSansCode() {
            // Sans code il n'y a rien a echanger : appeler le service
            // produirait une erreur plus loin, avec un message qui ne designe
            // pas la cause.
            poster(formulaire("state", "etat-abc"), 302);

            Mockito.verify(oauth2Service, Mockito.never())
                    .handleOAuth2Callback(any(), any(), any());
        }

        @Test
        @DisplayName("un state absent est transmis TEL QUEL, et non remplace")
        void stateAbsentTransmisTelQuel() {
            // Le rappel GET remplacait un state absent par la chaine vide, ce
            // qui faisait ressembler une absence a une presence : le service ne
            // pouvait plus distinguer « aucun state » de « state vide », et le
            // journal ne nommait pas la bonne cause.
            poster(formulaire("code", "code-apple-123"), 302);

            assertThat(requeteTransmise().getState())
                    .withFailMessage("un state absent a ete remplace par une "
                            + "valeur : le service ne peut plus nommer la cause "
                            + "du refus")
                    .isNull();
        }
    }
}
