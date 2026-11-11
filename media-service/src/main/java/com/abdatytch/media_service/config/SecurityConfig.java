package com.abdatytch.media_service.config;

import com.abdatytch.commons.security.AutorisationInterne;
import com.abdatytch.media_service.constants.SecurityConstants;
import com.abdatytch.media_service.security.GatewayAccessWebFilter;
import com.abdatytch.media_service.security.JwtAuthenticationWebFilter;
import com.abdatytch.media_service.security.RoleAuthorizationWebFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Configuration de sécurité WebFlux
 * Architecture réactive avec filtres non-bloquants
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final GatewayAccessWebFilter gatewayAccessWebFilter;
    private final JwtAuthenticationWebFilter jwtAuthenticationWebFilter;
    private final RoleAuthorizationWebFilter roleAuthorizationWebFilter;

    /**
     * La regle d'autorisation des appels inter-services.
     *
     * <h3>Elle est nee avec le premier endpoint interne de ce module</h3>
     *
     * Le commentaire ci-dessous annoncait exactement ce risque : « un endpoint
     * interne ajoute demain serait ne sans aucune protection, et de bonne foi ».
     * {@code POST /internal/media/liens} est cet endpoint, et il arrive avec sa
     * garde.
     *
     * <p>Un secret vide ne laisse RIEN passer : {@link AutorisationInterne}
     * refuse chaque appel et le journalise — ferme a la requete, pas au
     * demarrage.
     */
    @Bean
    public AutorisationInterne autorisationInterne(
            @Value("${security.internal-secret:}") String secretInterne) {
        return new AutorisationInterne(secretInterne);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, AutorisationInterne autorisationInterne) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(cors -> cors.disable())
            .authorizeExchange(auth -> auth
                // Les chemins publics viennent de la MEME source que le filtre
                // d'acces, via SecurityConstants : un chemin n'est joignable que
                // s'il franchit les DEUX couches, et deux listes tenues
                // separement se contredisent en silence. C'est ainsi que
                // `/swagger-ui/**` se trouvait exempte de signature dans
                // certains services tout en etant bloque par leur chaine
                // Security — une documentation annoncee ouverte et injoignable.
                //
                // La documentation OpenAPI n'y figure plus : voir
                // SecurityConstants, ou le choix est explicite et motive.
                .pathMatchers(SecurityConstants.motifsPublicsSpring()).permitAll()

                // Le reste de l'actuator exige ADMIN. Sans cette regle,
                // anyExchange().authenticated() suffisait a l'atteindre : tout
                // porteur de jeton valide pouvait lire la configuration.
                .pathMatchers("/actuator/**").hasRole("ADMIN")
                
                // `/api/v1/internal/**` a ete RETIRE d'ici.
                //
                // Troisieme et derniere copie d'une regle vide : /api/v1 est le
                // prefixe public que la passerelle retire, et ce module ne sert
                // aucun chemin /internal/**. Les deux autres copies vivaient
                // dans SecurityConstants.PUBLIC_ENDPOINTS, lu par les deux
                // filtres — donc la meme regle exemptait aussi du controle
                // Gateway et de la validation du jeton.
                //
                // Rien n'etait ouvert, faute de chemin correspondant. Ce qui
                // etait dangereux, c'est qu'un endpoint interne ajoute demain
                // serait ne sans aucune protection, et de bonne foi.

                // Les chemins d'administration exigent le role, ici et non
                // seulement dans le filtre : /admin/documents permet de lire et
                // de supprimer les pieces justificatives de n'importe qui.
                // LES APPELS DE SERVICE A SERVICE.
                //
                // Ce qui les authentifie n'est pas un jeton : un appel de
                // service n'agit pour personne. C'est le secret partage,
                // compare a temps constant, plus le pair TCP prive verifie par
                // GatewayAccessWebFilter.
                //
                // AVANT la regle /admin/** et avant anyExchange : les matchers
                // sont evalues dans l'ordre, et un chemin interne n'a ni role
                // ni identite a presenter.
                .pathMatchers("/internal/**").access(autorisationInterne)

                .pathMatchers("/admin/**").hasRole("ADMIN")

                // anyExchange().permitAll() a ete REMPLACE par authenticated().
                //
                // Le commentaire d'origine disait « authentification gérée par
                // filtres custom », et c'etait exact : JwtAuthenticationWebFilter
                // et RoleAuthorizationWebFilter font le travail. Mais cela place
                // toute la protection du service dans des filtres, et laisse la
                // couche d'autorisation declarative dire « tout est public ».
                //
                // Deux consequences. Un endpoint ajoute demain est ouvert par
                // defaut au lieu d'etre ferme. Et si un filtre cesse de couvrir
                // un chemin — par une liste blanche elargie, un @Order modifie,
                // un bean non enregistre — il n'y a plus rien derriere. C'est
                // exactement ce qui s'etait produit avec /actuator/ exempte en
                // bloc dans le filtre Gateway.
                //
                // authenticated() ne retire rien aux filtres : il ajoute le
                // plancher qui manquait.
                .anyExchange().authenticated()
            )
            // Ajouter les filtres custom (ils gèrent l'authentification)
            .addFilterAt(gatewayAccessWebFilter, SecurityWebFiltersOrder.FIRST)
            .addFilterAt(jwtAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .addFilterAfter(roleAuthorizationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .build();
    }
}
