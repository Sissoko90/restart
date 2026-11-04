package com.abdatytch.config_service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 : le paquet est `boot.webmvc.test.autoconfigure`, et non
// l'ancien `boot.test.autoconfigure.web.servlet`. Verifie dans le jar.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La chaîne de sécurité de config-service, exercée pour de VRAI.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * Le module vient de recevoir son premier contrôle d'accès. Une protection
 * qu'aucun test n'appelle est une protection qu'on <b>croit</b> en place : c'est
 * ce qui avait rendu media-service entièrement injoignable — deux défauts
 * indépendants, chacun suffisant — sans qu'aucun test ne le dise.
 *
 * <p>Et le risque est ici des deux côtés. Fermer trop ferait perdre leur
 * configuration aux onze clients, <b>en silence</b> : leur import est
 * {@code optional:} avec {@code fail-fast: false}, donc un refus n'est pas une
 * erreur visible mais une couche de configuration qui disparaît. Les cas
 * d'atteignabilité comptent donc autant que les cas de refus.
 *
 * <h2>Une chaîne SERVLET</h2>
 *
 * {@code MockMvc} et non {@code WebTestClient} : ce module est le seul du dépôt
 * à tourner sur Tomcat. {@code spring-cloud-config-server} amène
 * {@code spring-boot-starter-web}, vérifié par {@code dependency:tree}.
 *
 * <p>C'est aussi pourquoi il n'a pas de test BlockHound, et pourquoi le tableau
 * de bord marque sa colonne {@code BLOC} en « s.o. » : BlockHound ne surveille
 * que les fils portant le marqueur {@code NonBlocking} de Reactor, et aucun fil
 * de travail de Tomcat ne le porte. Un tel test installerait le détecteur, son
 * contrôle négatif passerait, et les cas réels ne mesureraient rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Le depot de configuration pointe sur un repertoire du poste, dont le
        // chemin depend de la machine. Le test n'a besoin d'aucun fichier : il
        // mesure le VERDICT de la chaine, pas le contenu servi. Un repertoire
        // inexistant suffit, et evite de dependre de l'arborescence locale.
        "spring.cloud.config.server.native.search-locations=file:///tmp/restart-config-absent",
        "spring.cloud.consul.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "management.endpoints.web.exposure.include=health,info,refresh"
})
@DisplayName("config-service - la chaine de securite")
class ChaineReelleTest {

    /** Une adresse publique : l'appelant qu'on veut écarter. */
    private static final String ADRESSE_EXTERNE = "203.0.113.7";

    /** Un voisin du réseau privé : un service de la plateforme. */
    private static final String ADRESSE_PRIVEE = "10.1.2.3";

    /**
     * Le client, avec la chaîne de filtres COMPLÈTE.
     *
     * <p>{@code @AutoConfigureMockMvc} applique les filtres servlet enregistrés
     * dans le contexte, dont {@code springSecurityFilterChain}. C'est ce qui
     * fait que ce test mesure la sécurité et non le seul routage : un
     * {@code MockMvcBuilders.standaloneSetup} ou un
     * {@code webAppContextSetup} sans filtres monterait le
     * {@code DispatcherServlet} nu, et <b>tous</b> ces tests passeraient en
     * n'exerçant rien.
     *
     * <p>Et non {@code apply(springSecurity())} : cela exigerait
     * {@code spring-security-test}, que ce dépôt a retiré ailleurs. Une
     * dépendance de plus pour obtenir ce que l'auto-configuration donne déjà.
     */
    @Autowired
    private MockMvc client;

    /**
     * Appelle un chemin depuis une adresse donnée.
     *
     * <p>{@code MockMvc} ne pose aucune adresse de pair par défaut, et la
     * chaîne décide sur {@code getRemoteAddr()}. La poser explicitement est ce
     * qui fait que ce test mesure la règle plutôt que son absence.
     */
    private MockHttpServletRequestBuilder depuis(HttpMethod methode, String chemin,
                                                 String adresse) {
        return request(methode, chemin).with(requete -> {
            requete.setRemoteAddr(adresse);
            return requete;
        });
    }

    // ==================================================================
    @Nested
    @DisplayName("Les onze clients restent servis")
    class ClientsServis {

        /**
         * Le cas qui compte le plus, et celui qu'on oublie.
         *
         * <p>Un refus ici ne produirait pas d'erreur visible : l'import des
         * clients est {@code optional:}, donc ils démarreraient sans leur
         * couche {@code config-repo} et échoueraient plus tard sur une
         * substitution non résolue. Le symptôme serait à des minutes et à un
         * service de distance de la cause.
         *
         * <p>Un 404 est un succès du point de vue de ce test : la requête a
         * traversé la sécurité et Spring Cloud Config a simplement répondu
         * qu'il n'a pas ce fichier — le répertoire de recherche est
         * volontairement inexistant. Ce qu'on mesure est l'absence de 403.
         */
        @ParameterizedTest(name = "depuis {0}")
        @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "192.168.1.40", "172.20.0.5"})
        @DisplayName("La configuration est servie depuis un reseau prive")
        void configurationServieDepuisReseauPrive(String adresse) throws Exception {
            client.perform(depuis(HttpMethod.GET, "/auth-service/dev", adresse))
                    .andExpect(status().is(pas403()));
        }

        @Test
        @DisplayName("Les sondes passent, meme depuis l'exterieur")
        void sondesOuvertes() throws Exception {
            // Consul et un collecteur ne viennent pas forcement du meme hote, et
            // ne portent aucun jeton. Les fermer ferait sortir l'instance du
            // pool, et l'orchestrateur la redemarrerait en boucle.
            client.perform(depuis(HttpMethod.GET, "/actuator/health", ADRESSE_EXTERNE))
                    .andExpect(status().isOk());
            client.perform(depuis(HttpMethod.GET, "/actuator/info", ADRESSE_EXTERNE))
                    .andExpect(status().isOk());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Depuis l'exterieur, rien ne passe")
    class ExterieurRefuse {

        /**
         * La cartographie de la configuration n'est plus lisible de l'extérieur.
         *
         * <p>Ces endpoints ne divulguent pas les <b>valeurs</b> des secrets —
         * config-service sert les fichiers avec les {@code ${VAR}} non résolus —
         * mais ils livrent le nom de chaque propriété, donc la confirmation de
         * quels secrets existent et où.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/auth-service/dev",
                "/interface-service/dev",
                "/application/dev",
                "/gateway-service/dev"})
        @DisplayName("Les endpoints de configuration sont refuses")
        void configurationRefusee(String chemin) throws Exception {
            client.perform(depuis(HttpMethod.GET, chemin, ADRESSE_EXTERNE))
                    .andExpect(status().isForbidden());
        }

        /**
         * {@code refresh} est une ÉCRITURE, et elle était ouverte.
         *
         * <p>{@code POST /actuator/refresh} recharge la configuration. Rien de
         * légitime ne l'appelle hors d'un exploitant.
         */
        @Test
        @DisplayName("POST /actuator/refresh est refuse depuis l'exterieur")
        void refreshRefuse() throws Exception {
            client.perform(depuis(HttpMethod.POST, "/actuator/refresh", ADRESSE_EXTERNE))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Mais un exploitant du reseau prive peut encore rafraichir")
        void refreshPossibleDepuisReseauPrive() throws Exception {
            // L'autre moitie, celle qu'on oublie : une protection qui refuse
            // aussi l'appelant legitime n'est pas une protection, c'est une
            // panne — et elle a le meme visage vert.
            client.perform(depuis(HttpMethod.POST, "/actuator/refresh", ADRESSE_PRIVEE))
                    .andExpect(status().is(pas403()));
        }

        /**
         * Une adresse illisible ou hors plage est refusée, jamais acceptée.
         *
         * <p>{@code 172.32.0.1} est juste <b>au-delà</b> de la plage privée, qui
         * s'arrête à {@code 172.31} : c'est exactement le cas qu'un
         * {@code startsWith("172.")} acceptait à tort, et la raison d'être de
         * {@code PrivateNetworks}.
         *
         * <p>Le repli inverse — accepter ce qu'on ne sait pas lire — est le
         * défaut que ce dépôt a corrigé le plus souvent.
         */
        @ParameterizedTest(name = "pair = {0}")
        @ValueSource(strings = {"pas-une-adresse", "172.32.0.1", "11.0.0.1", "8.8.8.8"})
        @DisplayName("Une adresse hors plage privee ou illisible est refusee")
        void adresseHorsPlageRefusee(String adresse) throws Exception {
            client.perform(depuis(HttpMethod.GET, "/auth-service/dev", adresse))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * Tout sauf 403.
     *
     * <p>Les cas d'atteignabilité ne peuvent pas exiger 200 : le répertoire de
     * recherche est volontairement inexistant, donc Spring Cloud Config répond
     * ce qu'il veut — souvent 404. Exiger 200 ferait échouer le test pour une
     * raison étrangère à ce qu'il mesure, et exiger 404 figerait un détail
     * d'implémentation de Spring Cloud. Ce qu'on mesure est l'absence de refus.
     */
    private static org.hamcrest.Matcher<Integer> pas403() {
        return org.hamcrest.Matchers.not(org.hamcrest.Matchers.is(403));
    }
}
