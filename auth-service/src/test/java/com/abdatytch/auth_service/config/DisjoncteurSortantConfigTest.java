package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.exception.AuthException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La regle du disjoncteur : qu'est-ce qu'un echec de dependance.
 *
 * <h2>La propriete la plus importante</h2>
 *
 * Un refus <b>metier</b> ne doit jamais ouvrir un disjoncteur. Il signale que
 * la dependance <b>fonctionne</b> : elle a repondu. Le compter transformerait
 * une rafale de refus legitimes en panne generale — et sur un chemin
 * d'authentification, une serie de mauvais mots de passe fermerait la connexion
 * pour tout le monde. L'attaquant obtiendrait le deni de service gratuitement.
 *
 * <h2>Et l'erreur symetrique, qui etait celle du depot</h2>
 *
 * La configuration d'origine declarait {@code recordExceptions} avec une liste
 * de noms de classes contenant des types de {@code RestTemplate}
 * ({@code ResourceAccessException}, {@code HttpServerErrorException}) sur une
 * pile qui utilise WebClient. Avec {@code recordExceptions} renseigne, tout ce
 * qui n'y figure pas compte comme un <b>succes</b> : aucun echec de transport
 * n'etait compte, et le disjoncteur n'aurait jamais pu s'ouvrir.
 *
 * <p>D'ou un predicat en code plutot qu'une liste en YAML : il se verifie a la
 * compilation, il suit un changement de client HTTP, et il se teste.
 */
@DisplayName("DisjoncteurSortant — ce qui compte comme un echec de dependance")
class DisjoncteurSortantConfigTest {

    @Test
    @DisplayName("une indisponibilite declaree compte")
    void indisponibiliteCompte() {
        assertThat(DisjoncteurSortantConfig.estUneIndisponibilite(
                new AuthException(ErrorCode.SERVICE_UNAVAILABLE)))
                .withFailMessage("une indisponibilite declaree ne compte pas : le "
                        + "disjoncteur ne s'ouvrirait jamais, et chaque appel paierait "
                        + "le delai maximal en entier")
                .isTrue();
    }

    @ParameterizedTest(name = "{0} ne compte pas")
    @EnumSource(value = ErrorCode.class, names = {
            "INVALID_CREDENTIALS", "ACCOUNT_LOCKED", "TWO_FACTOR_REQUIRED",
            "PHONE_NOT_VERIFIED", "USER_NOT_FOUND", "VALIDATION_ERROR"})
    @DisplayName("aucun refus metier ne compte comme un echec de dependance")
    void refusMetierNeComptePas(ErrorCode code) {
        assertThat(DisjoncteurSortantConfig.estUneIndisponibilite(
                new AuthException(code)))
                .withFailMessage("""
                        %s compte comme une panne de dependance.

                        Une rafale de ces refus ouvrirait le disjoncteur et fermerait \
                        le chemin pour TOUT LE MONDE — un attaquant obtiendrait le deni \
                        de service en presentant de mauvais identifiants.""", code)
                .isFalse();
    }

    /**
     * Le couplage avec {@code AfrikSmsProvider}, qui distingue desormais ses
     * deux familles d'echec.
     *
     * <p>Transport et refus du fournisseur rendaient le meme
     * {@code SMS_SERVICE_ERROR}. Poser un disjoncteur dessus l'aurait rendu
     * incapable de s'ouvrir : present, cable, et aveugle — exactement le defaut
     * retire ailleurs.
     */
    @Test
    @DisplayName("une panne du fournisseur SMS compte, son refus non")
    void fournisseurSmsPanneContreRefus() {
        assertThat(DisjoncteurSortantConfig.estUneIndisponibilite(
                new AuthException(ErrorCode.SMS_PROVIDER_UNAVAILABLE)))
                .withFailMessage("une panne du fournisseur SMS ne compte pas : le "
                        + "disjoncteur restera ferme et chaque OTP paiera le delai")
                .isTrue();

        assertThat(DisjoncteurSortantConfig.estUneIndisponibilite(
                new AuthException(ErrorCode.SMS_SERVICE_ERROR)))
                .withFailMessage("un refus du fournisseur compte comme une panne : une "
                        + "cle d'API invalide ouvrirait le disjoncteur au lieu de "
                        + "signaler la configuration")
                .isFalse();
    }

    @Test
    @DisplayName("une erreur non traduite compte, pour ne pas rendre le disjoncteur aveugle")
    void erreurInattendueCompte() {
        // Un oubli de traduction dans un client ne doit pas faire passer une
        // panne pour un succes : c'est l'erreur exactement inverse de celle de
        // la configuration d'origine, et elle est tout aussi silencieuse.
        assertThat(DisjoncteurSortantConfig.estUneIndisponibilite(
                new IllegalStateException("panne inattendue"))).isTrue();
        assertThat(DisjoncteurSortantConfig.estUneIndisponibilite(
                new java.io.IOException("socket fermee"))).isTrue();
    }

    @Test
    @DisplayName("la configuration est enregistree dans le registre Spring, pas a cote")
    void configurationDansLeRegistre() {
        // L'observabilite en depend : resilience4j-micrometer s'abonne aux
        // evenements du REGISTRE. Une instance creee par
        // CircuitBreaker.of(...) n'apparaitrait ni dans Prometheus ni dans
        // /actuator/circuitbreakers — un disjoncteur qu'on ne voit pas
        // s'ouvrir ne vaut pas grand-chose.
        CircuitBreakerRegistry registre = CircuitBreakerRegistry.ofDefaults();
        DisjoncteurSortantConfig.pour(registre, 50f, 10, 5, 30L);

        assertThat(registre.getConfiguration(com.abdatytch.commons.resilience.DisjoncteurSortant.CONFIGURATION))
                .withFailMessage("la configuration n'est pas dans le registre : les "
                        + "instances creees avec elle echapperaient aux metriques")
                .isPresent();
    }
}
