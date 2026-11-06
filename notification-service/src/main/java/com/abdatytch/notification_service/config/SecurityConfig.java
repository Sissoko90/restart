package com.abdatytch.notification_service.config;

import com.abdatytch.commons.security.AutorisationInterne;
import com.abdatytch.commons.security.CheminsPublics;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * La regle d'autorisation des appels inter-services.
     *
     * <p>Le mecanisme vit dans platform-commons, le {@code @Bean} ici : ce
     * module ne scanne pas {@code com.abdatytch.commons}, et un
     * {@code @Component} y serait inerte. Meme partage que pour
     * {@code DisjoncteurSortant}.
     *
     * <p>Defaut vide, et non absence de defaut : la politique du projet interdit
     * de refuser le demarrage faute d'une variable. Sans secret,
     * {@link AutorisationInterne} refuse chaque appel et le journalise — ferme a
     * la requete, pas au demarrage.
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
                        // Ce matcher exigeait hasAnyRole("SERVICE", "ADMIN"), et
                        // c'etait FAUX. Releve sur tout le depot :
                        // JWT_CLAIM_SERVICE_ROLE, JWT_SERVICE_SUBJECT et
                        // JWT_TYPE_SERVICE sont declares dans les constantes
                        // d'auth-service et utilises NULLE PART. Aucun code
                        // n'emet de jeton portant le role SERVICE, donc cette
                        // regle exigeait un jeton que personne ne delivre et
                        // aurait refuse tout appel inter-services.
                        //
                        // Un appel de service ne porte pas de jeton
                        // d'utilisateur — il n'agit pour personne. Ce qui
                        // l'authentifie est le secret partage, compare a temps
                        // constant par AutorisationInterne.
                        .pathMatchers("/internal/**").access(autorisationInterne)
                        // L'ADMINISTRATION, et cette regle MANQUAIT.
                        //
                        // media-service, quotas-service, setting-service,
                        // user-service et auth-service la portent tous. Ce
                        // module, non — alors que le javadoc d'AdminSmsController
                        // affirmait en etre protege et qualifiait ses
                        // @PreAuthorize de simple doublon « qui ne la remplace
                        // pas ».
                        //
                        // Sans elle, /admin/sms/** tombait dans
                        // anyExchange().authenticated() : la chaine laissait
                        // passer TOUT porteur authentifie, et seule l'annotation
                        // refusait. Elle fonctionne — @EnableReactiveMethodSecurity
                        // est bien actif sur cette classe — donc il n'y avait pas
                        // de trou ouvert ; mais la defense en profondeur decrite
                        // n'existait pas, et ce depot a deja trouve
                        // trente-trois @PreAuthorize rendues inertes par l'absence
                        // de cette annotation. Reposer sur elle seule, c'est
                        // reposer sur la couche qui a deja disparu une fois.
                        //
                        // AVANT anyExchange, et c'est obligatoire : les matchers
                        // sont evalues dans l'ordre, donc place apres, ce controle
                        // ne serait jamais atteint. CouvertureDesCheminsAdminTest
                        // garde la presence ET la position.
                        .pathMatchers("/admin/**").hasRole("ADMIN")
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
