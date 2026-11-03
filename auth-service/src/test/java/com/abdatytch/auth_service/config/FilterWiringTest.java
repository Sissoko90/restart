package com.abdatytch.auth_service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.web.server.WebFilter;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou contre la double exécution des filtres de sécurité.
 *
 * <h2>Le piège</h2>
 *
 * WebFlux place automatiquement dans la chaîne HTTP tout bean de type
 * {@code WebFilter}. Spring Security en est un lui-même, et les filtres qu'on
 * lui confie par {@code addFilterAt} s'exécutent à l'intérieur. Un filtre
 * annoté {@code @Component} <b>et</b> ajouté à la chaîne de sécurité traverse
 * donc les deux, et s'exécute <b>deux fois par requête</b>.
 *
 * <h2>Pourquoi ça compte</h2>
 *
 * C'était le cas des deux filtres de ce service :
 *
 * <ul>
 *   <li>{@code GatewayAccessWebFilter} validait la signature HMAC deux fois, et
 *       journalisait chaque refus d'accès direct en double — ce qui fausse toute
 *       alerte fondée sur un décompte de tentatives ;</li>
 *   <li>{@code JwtAuthenticationWebFilter} analysait le jeton deux fois sur le
 *       chemin de chaque requête.</li>
 * </ul>
 *
 * <p>Le même défaut, sur les sept filtres du Gateway, y divisait par deux le
 * plafond réel de la limitation de débit : 2 tentatives de connexion accordées
 * au lieu de 5. Rien dans les journaux ne le signalait, sinon des messages en
 * double, eux-mêmes pris pour du bruit.
 *
 * <p>Ce test échoue si un filtre destiné à la chaîne de sécurité redevient un
 * bean. Sa liste blanche recense les seuls filtres qui ont leur place dans la
 * chaîne globale, avec la raison de leur présence.
 */
@DisplayName("Câblage des filtres - aucun ne doit s'exécuter deux fois")
class FilterWiringTest {

    private static final String PAQUET_RACINE = "com.abdatytch.auth_service";

    /**
     * Filtres légitimement publiés comme beans, donc placés dans la chaîne
     * globale — et pour cette raison jamais ajoutés à la chaîne de sécurité.
     */
    private static final Set<String> FILTRES_CHAINE_GLOBALE = Set.of(
            // Refuse les charges compressées disproportionnées avant que
            // quiconque ne tente de les lire. Doit encadrer TOUT le trafic, y
            // compris ce que Spring Security laisse passer sans
            // authentification.
            "ZipBombProtectionFilter"
    );

    @Test
    @DisplayName("les filtres de la chaîne de sécurité ne sont pas des beans WebFilter")
    void aucunFiltreEnDouble() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(WebFilter.class));

        Set<String> publiesCommeBeans = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PAQUET_RACINE)) {
            String nomClasse = definition.getBeanClassName();
            if (nomClasse == null) {
                continue;
            }
            Class<?> classeFiltre;
            try {
                classeFiltre = Class.forName(nomClasse);
            } catch (ClassNotFoundException e) {
                throw new AssertionError("Classe introuvable pendant le scan : " + nomClasse, e);
            }

            // Le scan par type trouve TOUTES les classes de type WebFilter,
            // annotees ou non : seule la presence de @Component en fait un bean.
            // `findAnnotation` couvre aussi les meta-annotations (@Service,
            // @RestController...), qui font tout autant du filtre un bean.
            if (org.springframework.core.annotation.AnnotationUtils
                    .findAnnotation(classeFiltre,
                            org.springframework.stereotype.Component.class) == null) {
                continue;
            }

            String nomSimple = nomClasse.substring(nomClasse.lastIndexOf('.') + 1);
            if (!FILTRES_CHAINE_GLOBALE.contains(nomSimple)) {
                publiesCommeBeans.add(nomSimple);
            }
        }

        assertThat(publiesCommeBeans)
                .withFailMessage("""
                        Ces WebFilter sont publiés comme beans : %s

                        WebFlux les place dans la chaîne HTTP globale. S'ils sont
                        aussi ajoutés par addFilterAt dans SecurityConfig, ils
                        s'exécutent DEUX FOIS par requête.

                        Soit retirer @Component et les instancier dans
                        SecurityConfig, soit — s'ils ont vraiment leur place dans
                        la chaîne globale — les inscrire dans
                        FILTRES_CHAINE_GLOBALE avec la raison.""",
                        publiesCommeBeans)
                .isEmpty();
    }
}
