package com.abdatytch.interface_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;


@Configuration
public class AdminEmailConfig {

    @Value("${admin.mail.host}")
    private String host;

    @Value("${admin.mail.port}")
    private int port;

    @Value("${admin.mail.username}")
    private String username;

    @Value("${admin.mail.password}")
    private String password;

    @Value("${admin.mail.from}")
    private String from;

    @Value("${admin.critical.mail.host}")
    private String criticalHost;

    @Value("${admin.critical.mail.port}")
    private int criticalPort;

    @Value("${admin.critical.mail.username}")
    private String criticalUsername;

    @Value("${admin.critical.mail.password}")
    private String criticalPassword;

    @Value("${admin.critical.mail.from}")
    private String criticalFrom;

    @Value("${spring.mail.host:}")
    private String hoteOrdinaire;

    @Value("${spring.mail.port:587}")
    private int portOrdinaire;

    @Value("${spring.mail.username:}")
    private String identifiantOrdinaire;

    @Value("${spring.mail.password:}")
    private String motDePasseOrdinaire;

    /**
     * L'expediteur des courriels ORDINAIRES, declare a la main.
     *
     * <h2>Pourquoi a la main, alors que Spring Boot le fait</h2>
     *
     * Parce qu'il ne le faisait plus. {@code MailSenderAutoConfiguration} porte
     * {@code @ConditionalOnMissingBean(MailSender.class)} — verifie dans le
     * bytecode de spring-boot-mail 4.0.6 — et {@code JavaMailSender} etend
     * {@code MailSender}. La seule presence de {@code adminMailSender} suffisait
     * donc a faire RECULER l'auto-configuration.
     *
     * <p>Consequence mesuree : aucun bean {@code mailSender} n'existait, et
     * {@code spring.mail.*} — EMAIL_HOST, EMAIL_USERNAME, EMAIL_PASSWORD —
     * etait entierement inerte. Un des trois comptes SMTP reels n'etait jamais
     * utilise, et rien ne le signalait. Voir
     * {@code QuelExpediteurDeCourrielTest}.
     *
     * <p>Le nom du bean est {@code mailSender}, celui qu'attendait l'injection
     * d'origine.
     */
    @Bean
    public JavaMailSender mailSender() {
        JavaMailSenderImpl envoyeur = new JavaMailSenderImpl();
        envoyeur.setHost(hoteOrdinaire);
        envoyeur.setPort(portOrdinaire);
        envoyeur.setUsername(identifiantOrdinaire);
        envoyeur.setPassword(motDePasseOrdinaire);
        envoyeur.getJavaMailProperties().putAll(proprietesSmtp());
        return envoyeur;
    }

    /**
     * Les proprietes SMTP, en un seul endroit.
     *
     * <p>Elles etaient recopiees a l'identique dans les deux expediteurs — six
     * lignes chacune. Un troisieme en aurait fait dix-huit, et c'est ainsi
     * qu'un delai finit par differer d'un compte a l'autre sans raison.
     */
    private static java.util.Properties proprietesSmtp() {
        java.util.Properties props = new java.util.Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "3000");
        props.put("mail.smtp.writetimeout", "5000");
        return props;
    }

    /**
     * L'expediteur des alertes d'ADMINISTRATION.
     *
     * <h2>{@code @Primary} a ete RETIRE, et c'etait un defaut vivant</h2>
     *
     * Avec lui, l'injection par constructeur d'{@code EmailServiceImpl} —
     * {@code EmailServiceImpl(JavaMailSender mailSender)}, sans qualificateur —
     * recevait CE bean. Mesure faite : {@code @Primary} l'emporte sur la
     * correspondance par nom de parametre.
     *
     * <p>Tous les courriels ordinaires — codes de verification, reinitialisation
     * de mot de passe — partaient donc par le compte d'administration, avec un
     * {@code From} qui ne lui correspond pas. Un serveur SMTP refuse ou reecrit
     * un expediteur qui n'est pas le compte authentifie ; Gmail refuse.
     *
     * <p>Et {@code admin.mail.enabled} vaut {@code true} par defaut : le defaut
     * etait actif sans que personne ne l'ait choisi.
     */
    @Bean
    @ConditionalOnProperty(name = "admin.mail.enabled", havingValue = "true")
    public JavaMailSender adminMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        mailSender.getJavaMailProperties().putAll(proprietesSmtp());

        return mailSender;
    }

    @Bean
    @ConditionalOnProperty(name = "admin.critical.mail.enabled", havingValue = "true")
    public JavaMailSender criticalMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(criticalHost);
        mailSender.setPort(criticalPort);
        mailSender.setUsername(criticalUsername);
        mailSender.setPassword(criticalPassword);

        mailSender.getJavaMailProperties().putAll(proprietesSmtp());

        return mailSender;
    }
}
