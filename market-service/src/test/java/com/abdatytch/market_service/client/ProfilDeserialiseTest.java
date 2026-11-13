package com.abdatytch.market_service.client;

import com.abdatytch.market_service.config.JacksonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le contrat de lecture du profil, cote market-service.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * {@code ClientProfilUtilisateur.Profil} ne lit que trois champs d'un DTO qui
 * en porte une vingtaine. Un champ renomme chez user-service, ou mal lie ici,
 * ne produit AUCUNE erreur : la valeur reste nulle, et la consequence est
 * silencieuse — un pays nul restreint la portee d'un particulier a rien, une
 * adresse nulle fait qu'aucun courriel ne part.
 *
 * <p>Le JSON ci-dessous est celui que rend reellement
 * {@code GET /internal/users/auth/{id}}, releve le 2026-11-13.
 */
@DisplayName("Le profil lu chez user-service")
class ProfilDeserialiseTest {

    /** Reponse reelle de user-service, abregee a ce qui compte plus deux champs ignores. */
    private static final String REPONSE = """
            {
              "id": "3bd0d9f5-2b1f-4b7a-9e0c-6a1b2c3d4e5f",
              "authUserId": "11111111-1111-1111-1111-111111111111",
              "email": "vendeur@exemple.invalid",
              "phoneNumber": null,
              "countryCode": null,
              "firstName": "Vendeur",
              "lastName": "Essai",
              "status": "ACTIVE",
              "role": "USER",
              "city": "Bamako",
              "country": "ML",
              "preferences": {},
              "createdAt": "2026-11-13T20:06:33.55997"
            }
            """;

    @Test
    @DisplayName("les trois champs attendus sont lus, et les autres ignores")
    void troisChampsLus() throws Exception {
        var profil = new JacksonConfig().objectMapper()
                .readValue(REPONSE, ClientProfilUtilisateur.Profil.class);

        assertThat(profil.pays())
                .withFailMessage("""
                        Le pays n'est pas lu.

                        Un pays nul restreint la portee d'un particulier a rien : il \
                        ne verrait aucun article.""")
                .isEqualTo("ML");

        assertThat(profil.adresse())
                .withFailMessage("""
                        L'adresse n'est pas lue.

                        Aucun courriel de decision ne partirait, et RIEN ne le \
                        signalerait : la chaine se termine simplement sans envoi.""")
                .isEqualTo("vendeur@exemple.invalid");
    }

    @Test
    @DisplayName("un champ absent donne null, sans lever")
    void champAbsent() throws Exception {
        var profil = new JacksonConfig().objectMapper()
                .readValue("{\"country\":\"SN\"}", ClientProfilUtilisateur.Profil.class);

        assertThat(profil.pays()).isEqualTo("SN");
        assertThat(profil.adresse()).isNull();
    }

    @Test
    @DisplayName("une adresse vide vaut une adresse absente")
    void adresseVide() throws Exception {
        var profil = new JacksonConfig().objectMapper()
                .readValue("{\"email\":\"   \"}", ClientProfilUtilisateur.Profil.class);

        assertThat(profil.adresse())
                .withFailMessage("Une adresse blanche serait utilisee comme destinataire.")
                .isNull();
    }
}
