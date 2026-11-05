package com.abdatytch.notification_service.config;

import com.abdatytch.commons.security.CheminsPublics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Securite WebFlux de notification-service.
 *
 * <p>Le service n'est joignable qu'a travers la passerelle : la signature HMAC
 * est verifiee par {@code GatewayAccessWebFilter}, qui s'execute AVANT cette
 * chaine ({@code HIGHEST_PRECEDENCE + 10} contre {@code -100}). Le jeton
 * identifie ensuite l'appelant et porte son role.
 *
 * <h2>Les chemins publics viennent de la MEME source que le filtre d'acces</h2>
 *
 * {@code CheminsPublics} de platform-commons. Deux listes tenues separement se
 * contredisent en silence : ce depot en avait cinq, divergentes, et trois
 * defauts de la meme famille en sont sortis — un controle qui designe plus
 * large que son intention.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    private static final CheminsPublics CHEMINS_PUBLICS = CheminsPublics.sondes()
            .pageDErreur()
            .documentationViaPasserelle()
            .construire();

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // API sans etat : aucune session, l'identite vient du jeton a
                // chaque requete.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.mode(
                                org.springframework.security.web.server.header
                                        .XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; frame-ancestors 'none'; form-action 'self'"))
                )
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(CHEMINS_PUBLICS.motifsPermitAll()).permitAll()
                        // Actuator au-dela des sondes : reserve a l'administration.
                        // `/actuator/prometheus` figure dans les sondes, pour que
                        // la collecte fonctionne sans role.
                        .pathMatchers("/actuator/**").hasRole("ADMIN")
                        // Les appels de SERVICE A SERVICE.
                        //
                        // Ils arrivent signes par la passerelle mais sans jeton
                        // d'utilisateur : un service qui demande une notification
                        // n'agit pour personne en particulier. Le role SERVICE
                        // est porte par les jetons de service.
                        .pathMatchers("/internal/**").hasAnyRole("SERVICE", "ADMIN")
                        // Tout le reste — la boite de reception et le flux temps
                        // reel — exige un porteur authentifie. Le filtrage par
                        // destinataire se fait ENSUITE, dans les requetes.
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(convertisseurDeRoles())))
                .build();
    }

    /**
     * Lit le claim {@code roles}, que le convertisseur par defaut ignore.
     *
     * <h3>Le defaut que cela evite</h3>
     *
     * Le convertisseur par defaut de Spring Security lit {@code scope} ou
     * {@code scp} et prefixe {@code SCOPE_} : il ne produit <b>jamais</b> de
     * {@code ROLE_*}. Les jetons de cette plateforme portent un claim
     * {@code roles}, donc sans ce convertisseur tout {@code hasRole(...)}
     * refuserait — y compris pour un administrateur legitime, et avec un 403 qui
     * ne mentionne aucun role.
     *
     * <p>Meme correction que celle apportee a interface-service.
     *
     * <h3>Le prefixe n'est ajoute que s'il manque</h3>
     *
     * {@code UserRole.expand()} d'auth-service rend deja des valeurs comme
     * {@code ROLE_ADMIN}. Prefixer aveuglement donnerait
     * {@code ROLE_ROLE_ADMIN}, qui ne correspond a aucune regle.
     */
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> convertisseurDeRoles() {
        var converti = new org.springframework.security.oauth2.server.resource
                .authentication.JwtAuthenticationConverter();
        converti.setJwtGrantedAuthoritiesConverter(SecurityConfig::habilitations);
        return new ReactiveJwtAuthenticationConverterAdapter(converti);
    }

    private static Collection<org.springframework.security.core.GrantedAuthority>
            habilitations(Jwt jeton) {
        Collection<org.springframework.security.core.GrantedAuthority> habilitations =
                new ArrayList<>();

        Object brut = jeton.getClaim("roles");
        List<String> roles;
        if (brut instanceof String unique) {
            // Le claim peut etre une CHAINE ou une LISTE selon l'emetteur.
            // N'en traiter qu'une forme refuserait la moitie des jetons.
            roles = List.of(unique.split("[,\\s]+"));
        } else if (brut instanceof Collection<?> plusieurs) {
            roles = plusieurs.stream().map(String::valueOf).toList();
        } else {
            roles = List.of();
        }

        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String nettoye = role.trim();
            habilitations.add(new SimpleGrantedAuthority(
                    nettoye.startsWith("ROLE_") ? nettoye : "ROLE_" + nettoye));
        }
        return habilitations;
    }

    /**
     * Pour les tests : construit le jeton d'authentification comme la chaine le
     * ferait.
     */
    public static JwtAuthenticationToken jetonDAuthentification(Jwt jeton) {
        return new JwtAuthenticationToken(jeton, habilitations(jeton));
    }
}
