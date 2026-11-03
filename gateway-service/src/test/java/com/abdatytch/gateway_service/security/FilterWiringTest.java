package com.abdatytch.gateway_service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.web.server.WebFilter;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou contre la double exécution des filtres de sécurité.
 *
 * <p><b>Le piège.</b> WebFlux place automatiquement dans la chaîne HTTP tout
 * bean de type {@code WebFilter}. Spring Security en est un lui-même, et les
 * filtres qu'on lui confie par {@code addFilterAt} s'exécutent à l'intérieur.
 * Un filtre annoté {@code @Component} <b>et</b> ajouté à la chaîne de sécurité
 * traverse donc les deux, et s'exécute deux fois par requête.
 *
 * <p><b>Pourquoi ça compte.</b> C'était le cas des sept filtres du Gateway.
 * Le compteur de débit était incrémenté deux fois : le plafond réel valait la
 * moitié de l'annoncé — 2 tentatives de connexion accordées au lieu de 5. Rien
 * dans les journaux ne le signalait, sinon des messages en double, eux-mêmes
 * pris pour du bruit.
 *
 * <p>Ce test échoue si un filtre destiné à la chaîne de sécurité redevient un
 * bean. Sa liste blanche recense les seuls filtres qui ont leur place dans la
 * chaîne globale, avec la raison de leur présence.
 */
@DisplayName("Câblage des filtres — aucun ne doit s'exécuter deux fois")
class FilterWiringTest {

    private static final String BASE_PACKAGE = "com.abdatytch.gateway_service";

    /**
     * Filtres légitimement publiés comme beans, donc placés dans la chaîne
     * globale — et pour cette raison jamais ajoutés à la chaîne de sécurité.
     */
    private static final Set<String> GLOBAL_CHAIN_FILTERS = Set.of(
            // Signe les requêtes sortantes vers les services et purge les
            // en-têtes d'identité. Doit encadrer TOUT le trafic proxifié, y
            // compris ce que Spring Security laisse passer sans authentification.
            "GatewaySignatureFilter",
            // Refuse les charges compressées disproportionnées avant que
            // quiconque ne tente de les lire.
            "ZipBombProtectionFilter",
            // Garde la documentation agrégée par authentification HTTP dédiée.
            //
            // Sa place est dans la chaîne GLOBALE, et non dans la chaîne de
            // sécurité, pour deux raisons :
            //
            //  1. il doit s'exécuter AVANT GatewaySignatureFilter, pour ne pas
            //     signer ni proxifier une requête qu'on va refuser. La chaîne
            //     de sécurité vient après ;
            //  2. cette chaîne laisse ces chemins en permitAll, parce qu'un
            //     navigateur chargeant Swagger ne porte aucun jeton JWT. C'est
            //     donc ce filtre, et lui seul, qui juge — l'y déplacer ferait
            //     de permitAll une véritable ouverture.
            "DocumentationBasicAuthWebFilter"
    );

    @Test
    @DisplayName("les filtres de la chaîne de sécurité ne sont pas des beans WebFilter")
    void aucunFiltreEnDouble() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(WebFilter.class));

        Set<String> publishedAsBeans = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> filterClass;
            try {
                filterClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new AssertionError("Classe introuvable pendant le scan : " + className, e);
            }
            // `findAnnotation` couvre aussi les méta-annotations (@Service,
            // @RestController…), qui font tout autant du filtre un bean.
            if (org.springframework.core.annotation.AnnotationUtils
                    .findAnnotation(filterClass, org.springframework.stereotype.Component.class) != null) {
                publishedAsBeans.add(className.substring(className.lastIndexOf('.') + 1));
            }
        }

        assertThat(publishedAsBeans)
                .withFailMessage("""
                        Ces filtres sont publiés comme beans WebFilter : %s
                        Attendu : %s

                        Un filtre à la fois bean et ajouté par addFilterAt s'exécute DEUX
                        FOIS par requête — les compteurs de rate limiting sont doublés et
                        le plafond réel vaut la moitié de l'annoncé.

                        Un filtre destiné à la chaîne de sécurité ne doit pas porter
                        @Component : il est construit dans SecurityConfig. S'il a
                        légitimement sa place dans la chaîne globale, l'ajouter ici avec
                        la raison."""
,
                        publishedAsBeans, GLOBAL_CHAIN_FILTERS)
                .isEqualTo(new TreeSet<>(GLOBAL_CHAIN_FILTERS));
    }

    @Test
    @DisplayName("les filtres de sécurité restent instanciables hors conteneur Spring")
    void filtresConstruitsExplicitement() {
        // Corollaire du test précédent : ces filtres reçoivent leurs dépendances
        // par constructeur, jamais par injection de champ. Sans cela, les sortir
        // du conteneur les laisserait avec des champs nuls.
        ClientIpResolver resolver = new ClientIpResolver("");

        List<WebFilter> filters = List.of(
                new PathTraversalWebFilter(resolver),
                new XssProtectionWebFilter(resolver),
                new BodyXssScannerWebFilter(resolver),
                new com.abdatytch.gateway_service.filter.ApiVersionWebFilter()
        );

        assertThat(filters).doesNotContainNull();
    }
}
