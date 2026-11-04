package com.abdatytch.config_service.config;

import com.abdatytch.commons.security.PrivateNetworks;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Le premier contrôle d'accès de config-service.
 *
 * <h2>Ce qui était ouvert</h2>
 *
 * Ce module n'avait <b>aucune</b> chaîne de sécurité — deux classes en tout,
 * l'application et le chargeur de {@code .env}. Tout ce qu'il sert répondait
 * donc à quiconque atteignait le port 8888 :
 *
 * <ul>
 *   <li>les endpoints de configuration {@code /{application}/{profil}}, qui
 *       livrent le contenu de {@code config-repo}. Ils ne divulguent pas les
 *       <b>valeurs</b> des secrets — config-service sert les fichiers avec les
 *       {@code ${VAR}} non résolus, c'est chaque service qui résout depuis son
 *       propre environnement — mais ils livrent la <b>cartographie</b> : le nom
 *       de chaque propriété, et donc la confirmation de quels secrets
 *       existent ;</li>
 *   <li>{@code POST /actuator/refresh}, qui recharge la configuration.</li>
 * </ul>
 *
 * <h2>Pourquoi un contrôle de RÉSEAU et non une authentification</h2>
 *
 * Décision prise explicitement, et le compromis mérite d'être écrit.
 *
 * <p>Spring Cloud Config porte nativement l'authentification HTTP, et c'est le
 * mécanisme le plus solide. Mais il faudrait poser les identifiants dans les
 * <b>onze</b> clients, et leur import est {@code optional:} avec
 * {@code fail-fast: false} : un identifiant faux produit un 401 qui est
 * <b>avalé</b>. Les onze services démarreraient alors sans leur couche
 * {@code config-repo}, pour échouer plus tard sur une substitution non résolue
 * ou tourner sur des valeurs par défaut. Un contrôle qui dégrade onze services
 * en silence est un mauvais contrôle, quelle que soit sa solidité théorique.
 *
 * <p>Le contrôle de réseau n'a <b>aucun</b> couplage : aucun client à modifier,
 * aucun identifiant à distribuer. Il ferme ce qui était réellement ouvert —
 * l'accès depuis l'extérieur.
 *
 * <h2>Ce qu'il ne protège PAS, et il faut le dire</h2>
 *
 * Pas d'un voisin sur le même réseau privé. En conteneurs, « réseau privé »
 * englobe tout autre conteneur de la même passerelle. C'est une barrière de
 * plus, qui écarte l'exposition accidentelle du port sur Internet — pas une
 * authentification.
 *
 * <p>L'authentification HTTP reste donc à poser, avec les onze clients et la
 * décision sur {@code fail-fast} traitées d'un seul bloc.
 *
 * <h2>L'adresse lue est celle du PAIR TCP</h2>
 *
 * {@code getRemoteAddr()} et jamais {@code X-Forwarded-For} ni
 * {@code X-Real-IP}. Ce dépôt a corrigé ce défaut <b>trois fois</b> dans des
 * filtres différents : une décision de sécurité fondée sur un en-tête est une
 * décision que l'appelant prend à votre place. Aucun proxy ne s'interpose entre
 * un service et config-service.
 *
 * <h2>Une chaîne SERVLET, et c'est une exception dans ce dépôt</h2>
 *
 * {@link SecurityFilterChain} et {@link HttpSecurity}, non leurs équivalents
 * WebFlux. Ce module est le seul à tourner sur Tomcat :
 * {@code spring-cloud-config-server} amène {@code spring-boot-starter-web}.
 * Recopier la configuration d'un autre service ne compilerait pas.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Les sondes, nommées une par une.
     *
     * <p>Consul interroge {@code /actuator/health} et ne porte aucun jeton. Les
     * fermer ferait sortir l'instance du pool, et l'orchestrateur la
     * redémarrerait en boucle.
     *
     * <p>Nommées, et <b>non</b> {@code /actuator/**} en bloc : ce dépôt a
     * corrigé cette exemption trop large dans cinq filtres sur six. Elle
     * ouvrait {@code env}, {@code loggers} — modifiable par POST — et
     * {@code httptrace}, qui livre les en-têtes des derniers échanges.
     *
     * <p>{@code /actuator/prometheus} ne figure pas ici : l'exposition de ce
     * module est limitée à {@code health,info,refresh} dans son
     * {@code application.yml}, donc ce chemin n'existe pas. L'exempter
     * annoncerait une ouverture sans objet.
     */
    private static final String[] SONDES = {
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info"
    };

    @Bean
    public SecurityFilterChain chaineDeSecurite(HttpSecurity http) throws Exception {
        return http
                // Aucun formulaire, aucune session, aucune authentification
                // HTTP : ce service est appele par d'autres services, jamais par
                // un navigateur. Laisser httpBasic actif ferait reclamer des
                // identifiants qu'aucun client ne porte.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(Customizer.withDefaults())

                .authorizeHttpRequests(requetes -> requetes
                        .requestMatchers(SONDES).permitAll()
                        // TOUT le reste : les endpoints de configuration comme
                        // le reste d'actuator, refresh compris.
                        .anyRequest().access((authentification, contexte) ->
                                new AuthorizationDecision(
                                        estSurUnReseauPrive(contexte.getRequest()))))

                // 403 et non une page de connexion : il n'y a aucun identifiant
                // a proposer, donc rediriger ou reclamer une authentification
                // ferait chercher un acces qui n'existe pas.
                .exceptionHandling(erreurs -> erreurs
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)))
                .build();
    }

    /**
     * Le pair TCP est-il sur un réseau privé ?
     *
     * <p>La définition vient de {@code PrivateNetworks}, dans
     * platform-commons : elle n'est pas recopiée ici. Cette classe existe parce
     * qu'une copie écrite à la main portait {@code startsWith("172.")}, ce qui
     * accepte {@code 172.0.0.0/8} alors que la plage privée s'arrête à
     * {@code 172.31}.
     *
     * <p>{@code localhost} est traité à part : {@code getRemoteAddr()} peut
     * rendre le nom plutôt que l'adresse selon la configuration du conteneur,
     * et les autres filtres du dépôt font la même exception.
     */
    private boolean estSurUnReseauPrive(HttpServletRequest requete) {
        String pair = requete.getRemoteAddr();
        boolean autorise = "localhost".equals(pair) || PrivateNetworks.contient(pair);

        if (!autorise) {
            // L'adresse est journalisee, jamais le contenu servi. Le chemin
            // nomme l'application visee, ce qui suffit au diagnostic.
            log.warn("Acces refuse a la configuration depuis {} sur {}",
                    pair, requete.getRequestURI());
        }
        return autorise;
    }

    /**
     * Dit une fois, au démarrage, sur quoi repose la protection.
     *
     * <p>Sans ce message, un exploitant devant un 403 chercherait un identifiant
     * qui n'existe pas. Et il doit rester visible que ce contrôle n'écarte pas
     * un voisin du même réseau.
     */
    @jakarta.annotation.PostConstruct
    void annoncerLEtat() {
        log.info("Configuration servie aux seuls appelants d'un reseau prive "
                + "(pair TCP). Les sondes health et info restent ouvertes. "
                + "Ce controle n'ecarte PAS un voisin du meme reseau : "
                + "l'authentification HTTP reste a poser.");
    }
}
