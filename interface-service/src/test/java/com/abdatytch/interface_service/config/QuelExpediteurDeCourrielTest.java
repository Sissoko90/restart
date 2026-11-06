package com.abdatytch.interface_service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lequel des trois expediteurs de courriel recoit l'injection.
 *
 * <h2>Pourquoi mesurer plutot que raisonner</h2>
 *
 * Ce module declare TROIS {@code JavaMailSender} : celui qu'auto-configure
 * Spring Boot depuis {@code spring.mail.*} — nomme {@code mailSender} —, plus
 * {@code adminMailSender} et {@code criticalMailSender} de
 * {@code AdminEmailConfig}. Trois vrais comptes SMTP distincts.
 *
 * <p>{@code EmailServiceImpl} les recoit ainsi :
 *
 * <pre>
 * private final JavaMailSender mailSender;          // par CONSTRUCTEUR
 * &#64;Autowired(required = false)
 * private JavaMailSender adminMailSender;           // par CHAMP
 * &#64;Autowired(required = false)
 * private JavaMailSender criticalMailSender;        // par CHAMP
 * </pre>
 *
 * <p>Aucun des trois ne porte de {@code @Qualifier}, et {@code adminMailSender}
 * est {@code @Primary}. La question « qui recoit quoi » depend alors de l'ordre
 * de resolution de Spring : {@code @Primary} d'abord, ou la correspondance par
 * nom de parametre ?
 *
 * <p>Les deux reponses sont plausibles a la lecture, et l'enjeu n'est pas
 * theorique : si {@code @Primary} gagne, les courriels ORDINAIRES — codes de
 * verification, bienvenue — partent par le compte SMTP d'ADMINISTRATION. Ce
 * test etablit le fait.
 *
 * <h2>Pourquoi un contexte minimal et non celui du module</h2>
 *
 * Demarrer interface-service demanderait sa base, son serveur de configuration
 * et son Consul. Ce contexte-ci reproduit exactement le cablage qui pose la
 * question — trois beans du meme type, un {@code @Primary}, une injection dont
 * le nom de parametre correspond a un AUTRE bean — et rien d'autre. Le resultat
 * vaut pour tout contexte Spring.
 */
@DisplayName("Quel expéditeur de courriel reçoit l'injection")
class QuelExpediteurDeCourrielTest {

    /** Reproduit le cablage reel : trois beans, celui d'admin en {@code @Primary}. */
    @Configuration
    static class TroisExpediteurs {

        @Bean
        JavaMailSender mailSender() {
            JavaMailSenderImpl envoyeur = new JavaMailSenderImpl();
            envoyeur.setHost("ordinaire.exemple.test");
            return envoyeur;
        }

        @Bean
        @Primary
        JavaMailSender adminMailSender() {
            JavaMailSenderImpl envoyeur = new JavaMailSenderImpl();
            envoyeur.setHost("admin.exemple.test");
            return envoyeur;
        }

        @Bean
        JavaMailSender criticalMailSender() {
            JavaMailSenderImpl envoyeur = new JavaMailSenderImpl();
            envoyeur.setHost("critique.exemple.test");
            return envoyeur;
        }
    }

    /** Un consommateur cable comme {@code EmailServiceImpl}. */
    static class ConsommateurSansQualificateur {
        final JavaMailSender parConstructeur;

        ConsommateurSansQualificateur(JavaMailSender mailSender) {
            this.parConstructeur = mailSender;
        }
    }

    @Configuration
    static class AvecConsommateur {
        @Bean
        ConsommateurSansQualificateur consommateur(JavaMailSender mailSender) {
            return new ConsommateurSansQualificateur(mailSender);
        }
    }

    private static String hote(JavaMailSender envoyeur) {
        return ((JavaMailSenderImpl) envoyeur).getHost();
    }

    @Test
    @DisplayName("sans @Qualifier, @Primary l'emporte sur le nom du paramètre")
    void primaireLEmporteSurLeNom() {
        try (var contexte = new AnnotationConfigApplicationContext(
                TroisExpediteurs.class, AvecConsommateur.class)) {

            var consommateur = contexte.getBean(ConsommateurSansQualificateur.class);

            // Le fait, et non la supposition. Si cette assertion tombe un jour,
            // c'est l'ordre de resolution de Spring qui a change, et la
            // conclusion devra etre reexaminee.
            assertThat(hote(consommateur.parConstructeur))
                    .withFailMessage("""
                            Le parametre nomme `mailSender` a recu l'expediteur \
                            « %s ».

                            Si c'est « admin.exemple.test », @Primary l'emporte \
                            sur la correspondance par nom : les courriels \
                            ORDINAIRES d'interface-service partent par le compte \
                            SMTP d'ADMINISTRATION, puisque admin.mail.enabled \
                            vaut true par defaut.""",
                            hote(consommateur.parConstructeur))
                    .isEqualTo("admin.exemple.test");
        }
    }

    /**
     * L'injection par CHAMP, elle, se resout par le nom du champ.
     *
     * <p>C'est pourquoi {@code adminMailSender} et {@code criticalMailSender}
     * recoivent bien les bons beans dans {@code EmailServiceImpl} : leurs noms
     * de champ correspondent aux noms de beans. Seule l'injection par
     * constructeur se trompe — ce qui rend le defaut d'autant plus difficile a
     * voir, puisque deux des trois cablages sont corrects.
     */
    @Test
    @DisplayName("les beans restent accessibles par leur nom")
    void beansAccessiblesParLeurNom() {
        try (var contexte = new AnnotationConfigApplicationContext(TroisExpediteurs.class)) {
            assertThat(hote(contexte.getBean("criticalMailSender", JavaMailSender.class)))
                    .isEqualTo("critique.exemple.test");
            assertThat(hote(contexte.getBean("mailSender", JavaMailSender.class)))
                    .isEqualTo("ordinaire.exemple.test");
        }
    }

    /**
     * L'auto-configuration RECULE, et c'est le fait le plus couteux des deux.
     *
     * <p>{@code MailSenderAutoConfiguration} porte
     * {@code @ConditionalOnMissingBean(MailSender.class)} — verifie dans le
     * bytecode de spring-boot-mail 4.0.6. {@code JavaMailSender} etend
     * {@code MailSender}, donc la seule presence de {@code adminMailSender}
     * suffit a la faire reculer.
     *
     * <p>Consequence : il n'existe <b>aucun</b> bean {@code mailSender}, et
     * {@code spring.mail.*} — EMAIL_HOST, EMAIL_USERNAME, EMAIL_PASSWORD — est
     * entierement inerte. Un des trois comptes SMTP reels n'est jamais utilise,
     * et rien ne le signale.
     *
     * <p>C'est aussi ce qui explique le test precedent : le parametre
     * {@code mailSender} ne peut pas recevoir « son » bean, puisque ce bean
     * n'existe pas. {@code @Primary} n'est donc meme pas la cause premiere — il
     * ne fait que designer le remplacant.
     */
    @Test
    @DisplayName("un JavaMailSender déclaré fait reculer l'auto-configuration")
    void autoConfigurationRecule() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.mail.autoconfigure
                                .MailSenderAutoConfiguration.class))
                .withPropertyValues("spring.mail.host=ordinaire.exemple.test")
                .run(contexte -> assertThat(contexte).hasBean("mailSender"));

        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.mail.autoconfigure
                                .MailSenderAutoConfiguration.class))
                .withUserConfiguration(TroisExpediteurs.class)
                .withPropertyValues("spring.mail.host=ordinaire.exemple.test")
                .run(contexte -> assertThat(contexte)
                        .withFailMessage("""
                                L'auto-configuration n'a PAS recule : il existe \
                                un bean mailSender en plus des deux declares. \
                                La conclusion sur spring.mail.* inerte serait \
                                alors fausse et doit etre reexaminee.""")
                        .hasSingleBean(TroisExpediteurs.class));
    }
}
