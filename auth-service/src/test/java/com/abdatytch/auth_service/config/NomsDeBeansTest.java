package com.abdatytch.auth_service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Collisions de noms de beans dans les classes de configuration.
 *
 * <h2>La panne que ce test previent</h2>
 *
 * {@link SuperAdminSeeder} est une classe {@code @Configuration} qui portait une
 * methode {@code @Bean} du <b>meme nom qu'elle</b> :
 *
 * <pre>
 * &#64;Configuration
 * public class SuperAdminSeeder {
 *     &#64;Bean
 *     public ApplicationRunner superAdminSeeder(...) { ... }
 * }
 * </pre>
 *
 * Spring enregistre la classe sous le nom {@code superAdminSeeder}, puis la
 * methode {@code @Bean} sous ce meme nom, et refuse la seconde definition. Le
 * service <b>ne demarrait pas</b> :
 * {@code A bean with that name has already been defined and overriding is disabled}.
 *
 * <h2>Pourquoi le test de contexte ne l'a pas vu</h2>
 *
 * {@code AuthServiceApplicationTests} charge pourtant le contexte complet, et il
 * passait. Le conflit ne se declenchait qu'en profil {@code dev}, parce que
 * {@code @EnableFeignClients} etait declare <b>sans {@code basePackages}</b> :
 * Feign balayait alors tout le paquet de l'application et re-enregistrait les
 * classes de configuration deja scannees. D'ou les deux origines distinctes dans
 * le message d'erreur — {@code defined in file} et
 * {@code defined in class path resource}.
 *
 * <p>Les deux causes sont corrigees. Ce test garde la premiere, qui est la plus
 * facile a reintroduire : la seconde depend d'une annotation qu'on ne touche
 * presque jamais, la premiere d'un nom de methode qu'on ecrit a chaque nouveau
 * seeder. Nommer une methode {@code @Bean} comme sa classe parait naturel.
 *
 * <p>Le controle est statique — il lit les octets des classes compilees sans
 * demarrer de contexte — donc il reste valable quel que soit le profil, ce qui
 * est precisement ce qui manquait.
 */
@DisplayName("Noms de beans - aucune collision classe / methode @Bean")
class NomsDeBeansTest {

    private static final String PAQUET = "com/abdatytch/auth_service";

    @Test
    @DisplayName("aucune methode @Bean ne porte le nom de sa classe @Configuration")
    void aucuneCollision() throws Exception {
        List<String> collisions = new ArrayList<>();

        for (Class<?> configuration : classesDeConfiguration()) {
            // Nom par defaut d'un bean scanne : le nom simple de la classe
            // decapitalise. C'est celui que Spring attribue a la classe
            // @Configuration elle-meme.
            String nomDeLaClasse = decapitaliser(configuration.getSimpleName());

            for (Method methode : configuration.getDeclaredMethods()) {
                if (!methode.isAnnotationPresent(Bean.class)) {
                    continue;
                }
                // Un @Bean nomme explicitement ne prend pas le nom de la methode.
                Bean annotation = methode.getAnnotation(Bean.class);
                String[] nomsExplicites = annotation.name();
                String nomDuBean = (nomsExplicites.length > 0)
                        ? nomsExplicites[0]
                        : methode.getName();

                if (nomDuBean.equals(nomDeLaClasse)) {
                    collisions.add(configuration.getSimpleName() + "." + methode.getName()
                            + "() produit le bean '" + nomDuBean
                            + "', qui est deja le nom de la classe elle-meme");
                }
            }
        }

        assertThat(collisions)
                .withFailMessage("""
                        Collision de nom de bean. Spring enregistre la classe \
                        @Configuration sous son nom decapitalise, puis la methode \
                        @Bean sous le meme nom, et refuse la seconde definition : \
                        le service ne demarre pas.

                        Renommer la methode, par exemple en suffixant son type \
                        (superAdminApplicationRunner), ou donner un nom explicite \
                        a @Bean.

                        %s""", String.join("\n", collisions))
                .isEmpty();
    }

    @Test
    @DisplayName("le balayage trouve bien des classes - sinon le test ne prouve rien")
    void leBalayageNEstPasVide() throws Exception {
        // Sans ce controle, une erreur de chemin rendrait le test precedent
        // vert en n'examinant rien du tout : la forme la plus trompeuse de
        // succes, puisqu'elle ressemble exactement a une absence de probleme.
        assertThat(classesDeConfiguration())
                .withFailMessage("aucune classe @Configuration trouvee sous " + PAQUET
                        + " : le chemin de balayage est faux, et le test ne verifie rien")
                .isNotEmpty();
    }

    private List<Class<?>> classesDeConfiguration() throws IOException, ClassNotFoundException {
        var resolveur = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory lecteur = new CachingMetadataReaderFactory(resolveur);

        List<Class<?>> trouvees = new ArrayList<>();
        Resource[] ressources = resolveur.getResources("classpath*:" + PAQUET + "/**/*.class");

        for (Resource ressource : ressources) {
            var metadonnees = lecteur.getMetadataReader(ressource).getAnnotationMetadata();
            if (!metadonnees.hasAnnotation(Configuration.class.getName())) {
                continue;
            }
            trouvees.add(Class.forName(metadonnees.getClassName()));
        }
        return trouvees;
    }

    /** Regle de nommage de Spring : {@code SuperAdminSeeder} donne {@code superAdminSeeder}. */
    private String decapitaliser(String nom) {
        if (nom.isEmpty()) {
            return nom;
        }
        // Deux majuscules initiales sont conservees telles quelles par Spring
        // (JwtConfig reste jwtConfig, mais JWTConfig reste JWTConfig).
        if (nom.length() > 1 && Character.isUpperCase(nom.charAt(1))) {
            return nom;
        }
        return Character.toLowerCase(nom.charAt(0)) + nom.substring(1);
    }
}
