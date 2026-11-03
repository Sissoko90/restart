package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.service.IDisjoncteurSortant;
import com.abdatytch.auth_service.service.oauth2.ClesPubliquesApple;
import com.abdatytch.auth_service.service.oauth2.FournisseurApple;
import com.abdatytch.auth_service.service.oauth2.SecretClientApple;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Construction des fournisseurs OAuth2.
 *
 * <h2>Pourquoi Apple est construit ici et non par annotation</h2>
 *
 * Google et Facebook sont de simples {@code @Component} : leurs identifiants
 * sont des chaines, et leur absence se constate sans rien casser. Apple non — sa
 * cle privee doit etre <b>lue et validee</b>, et {@link SecretClientApple} leve
 * a la construction si elle est illisible. Un {@code @Component} aurait donc
 * fait echouer le demarrage du service des que la variable {@code APPLE_PRIVATE_KEY}
 * est vide, c'est-a-dire tant qu'Apple n'est pas active.
 *
 * <p>La construction conditionnelle est exactement ce qui evite ce refus de
 * demarrage : sans configuration complete, le fournisseur Apple existe, repond
 * {@code estConfigure() == false}, et seul un parcours Apple est refuse. Les
 * connexions par telephone, par courriel et par mot de passe ne sont pas
 * concernees.
 *
 * <h2>Le RestTemplate est borne, et c'est une correction</h2>
 *
 * Celui de {@code OAuth2ServiceImpl} etait un {@code new RestTemplate()} : aucun
 * delai de connexion, aucun delai de lecture. Un fournisseur qui accepte la
 * connexion puis cesse de repondre retenait alors un fil de
 * {@code boundedElastic} <b>indefiniment</b>. Ce pool est borne ; assez de
 * connexions bloquees et plus aucune operation d'I/O bloquante du service ne
 * passe, y compris la verification des mots de passe, qui n'a aucun rapport avec
 * OAuth2. C'est le meme defaut que celui corrige sur la passerelle : sans delai,
 * le disjoncteur ne voit jamais d'echec et ne s'ouvre donc jamais.
 */
@Slf4j
@Configuration
public class FournisseursOAuth2Config {

    /**
     * {@link RestTemplate} partage par Google et Facebook, avec des delais.
     *
     * <h2>Les deux delais ne se remplacent pas</h2>
     *
     * Celui de connexion couvre un hote injoignable ; celui de lecture couvre un
     * hote qui <b>accepte</b> la connexion puis cesse de repondre. C'est le
     * second qui compte le plus ici : sans lui, le fil reste pris aussi
     * longtemps que l'autre bout le veut bien.
     *
     * <h2>Pourquoi la fabrique est construite a la main</h2>
     *
     * {@code RestTemplateBuilder} n'est pas disponible : il vient d'une
     * auto-configuration que ce service n'active pas, etant entierement
     * WebFlux. La classe est pourtant sur le chemin de classes, donc le code
     * compilait et ne tombait qu'au demarrage du contexte — ce qu'a montre le
     * test de chargement.
     */
    @Bean
    public RestTemplate oauth2RestTemplate(
            @Value("${oauth2.http.connect-timeout-seconds:5}") long delaiDeConnexion,
            @Value("${oauth2.http.read-timeout-seconds:10}") long delaiDeLecture) {
        SimpleClientHttpRequestFactory fabrique = new SimpleClientHttpRequestFactory();
        fabrique.setConnectTimeout(Duration.ofSeconds(delaiDeConnexion));
        fabrique.setReadTimeout(Duration.ofSeconds(delaiDeLecture));
        return new RestTemplate(fabrique);
    }

    @Bean
    public ClesPubliquesApple clesPubliquesApple(
            WebClient.Builder constructeurWebClient,
            IDisjoncteurSortant disjoncteur,
            @Value("${oauth2.apple.services-id:}") String servicesId,
            @Value("${oauth2.apple.timeout-seconds:5}") long delaiEnSecondes) {
        return new ClesPubliquesApple(
                constructeurWebClient, disjoncteur, servicesId, delaiEnSecondes);
    }

    /**
     * Fournisseur Apple, configure ou non.
     *
     * <p>Le bean est cree dans les deux cas. Ce qui varie est le
     * {@link SecretClientApple}, {@code null} quand la configuration est
     * incomplete — et c'est lui que {@code estConfigure()} regarde.
     */
    @Bean
    public FournisseurApple fournisseurApple(
            WebClient.Builder constructeurWebClient,
            ClesPubliquesApple clesPubliquesApple,
            IDisjoncteurSortant disjoncteur,
            @Value("${oauth2.apple.team-id:}") String teamId,
            @Value("${oauth2.apple.services-id:}") String servicesId,
            @Value("${oauth2.apple.key-id:}") String keyId,
            @Value("${oauth2.apple.private-key:}") String clePrivee,
            @Value("${oauth2.apple.redirect-uri:}") String redirectUri,
            @Value("${oauth2.apple.timeout-seconds:5}") long delaiEnSecondes) {

        SecretClientApple secretClient = construireLeSecret(
                teamId, servicesId, keyId, clePrivee);

        return new FournisseurApple(constructeurWebClient, clesPubliquesApple,
                disjoncteur, secretClient, servicesId, redirectUri, delaiEnSecondes);
    }

    /**
     * Produit le generateur de secret, ou {@code null} si Apple n'est pas
     * configure.
     *
     * <h2>Deux absences a ne pas confondre</h2>
     *
     * Une configuration <b>vide</b> est un etat normal : Apple n'est pas active,
     * on le journalise une fois et le service demarre. Une cle <b>presente mais
     * illisible</b> est une erreur de deploiement : quelqu'un a renseigne la
     * variable et s'est trompe. Elle est donc journalisee en {@code ERROR} et
     * nommement, parce que le silence ferait decouvrir la faute a la premiere
     * connexion Apple d'une personne reelle.
     *
     * <p>Elle ne fait pourtant pas echouer le demarrage, et c'est la consigne
     * tenue ici : une cle Apple mal copiee ne doit pas rendre le service
     * d'authentification entier indisponible.
     */
    private SecretClientApple construireLeSecret(String teamId, String servicesId,
                                                 String keyId, String clePrivee) {
        boolean complet = estRenseigne(teamId) && estRenseigne(servicesId)
                && estRenseigne(keyId) && estRenseigne(clePrivee);
        if (!complet) {
            log.info("Sign in with Apple non configure : le fournisseur restera inactif");
            return null;
        }
        try {
            SecretClientApple secret = new SecretClientApple(
                    teamId, servicesId, keyId, clePrivee);
            log.info("Sign in with Apple configure (equipe {}, cle {})", teamId, keyId);
            return secret;
        } catch (IllegalArgumentException e) {
            // Le message de l'exception ne contient aucun fragment de cle :
            // c'est garanti par SecretClientApple et verifie par son test.
            log.error("Cle privee Apple presente mais illisible : le fournisseur "
                    + "restera inactif. {}", e.getMessage());
            return null;
        }
    }

    private static boolean estRenseigne(String valeur) {
        return valeur != null && !valeur.isBlank();
    }
}
