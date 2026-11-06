package com.abdatytch.notification_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * Les comptes SMTP de ce service.
 *
 * <h2>TOUS declares explicitement, et c'est obligatoire</h2>
 *
 * {@code MailSenderAutoConfiguration} porte
 * {@code @ConditionalOnMissingBean(MailSender.class)} — verifie dans le bytecode
 * de spring-boot-mail 4.0.6 — et {@code JavaMailSender} etend
 * {@code MailSender}. Declarer UN seul expediteur fait donc reculer
 * l'auto-configuration, et l'autre disparait.
 *
 * <p>interface-service l'a paye : il declarait deux comptes, l'auto-configuration
 * reculait, et {@code spring.mail.*} y etait entierement inerte — un compte SMTP
 * configure, jamais utilise, et rien ne le signalait. Voir
 * {@code QuelExpediteurDeCourrielTest} de ce module-la.
 *
 * <p>Ici les deux sont donc declares a la main. Ajouter un troisieme plus tard
 * ne cassera rien, puisqu'il n'y a plus d'auto-configuration a faire reculer.
 *
 * <h2>Aucun {@code @Primary}</h2>
 *
 * Et c'est delibere. {@code @Primary} l'emporte sur la correspondance par nom de
 * parametre : dans interface-service, il faisait recevoir le compte
 * d'ADMINISTRATION a une injection nommee {@code mailSender}, et tous les
 * courriels ordinaires partaient par lui — avec un {@code From} qui ne lui
 * correspondait pas, donc refuses ou reecrits par le serveur distant.
 *
 * <p>Sans {@code @Primary}, une injection ambigue echoue au DEMARRAGE au lieu de
 * choisir silencieusement. C'est le bon moment pour echouer.
 *
 * <h2>Deux comptes, et pourquoi pas trois</h2>
 *
 * L'urgence d'une notification en designe deux :
 *
 * <ul>
 *   <li>{@code CRITIQUE} — alerte de securite, code de verification : le compte
 *       dedie, dont la reputation d'expedition n'est pas melee a celle du trafic
 *       ordinaire. Un courriel marque indesirable parce qu'une campagne a sature
 *       le compte commun est un code que personne ne recoit ;</li>
 *   <li>tout le reste par le compte ordinaire.</li>
 * </ul>
 *
 * <p>interface-service en a un TROISIEME, celui des alertes d'administration. Il
 * n'est pas repris ici, et ce n'est pas un oubli : il ne correspond a aucune
 * urgence mais a un DESTINATAIRE — un operateur, et non une personne inscrite.
 * {@code DemandeDeNotification} ne sait pas exprimer cela, et l'inventer par
 * convention sur le nom du modele serait exactement le genre de regle implicite
 * que ce depot paie en boucle.
 *
 * <p>Une alerte d'administration n'a d'ailleurs pas sa place dans la boite de
 * reception d'un utilisateur : comme le code de verification par SMS, c'est un
 * message qui n'est pas une notification.
 */
@Configuration
public class ConfigurationCourriel {

    /**
     * Les proprietes SMTP, en un seul endroit.
     *
     * <p>Recopiees par compte, elles finissent par differer sans raison — un
     * delai de trois secondes ici, de trente la, et un compte qui retient un
     * thread dix fois plus longtemps que l'autre sans que personne l'ait voulu.
     */
    private static Properties proprietesSmtp() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        // Bornes OBLIGATOIRES : sans elles, un serveur SMTP qui accepte la
        // connexion et ne repond plus retient un thread de boundedElastic
        // indefiniment. Le pool est borne, donc quelques messages suffisent a
        // bloquer tous les envois du service.
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "3000");
        props.put("mail.smtp.writetimeout", "5000");
        return props;
    }

    private static JavaMailSender construire(String hote, int port,
                                             String identifiant, String motDePasse) {
        JavaMailSenderImpl envoyeur = new JavaMailSenderImpl();
        envoyeur.setHost(hote);
        envoyeur.setPort(port);
        envoyeur.setUsername(identifiant);
        envoyeur.setPassword(motDePasse);
        envoyeur.getJavaMailProperties().putAll(proprietesSmtp());
        return envoyeur;
    }

    /** Le compte du trafic ordinaire. Nom du bean : {@code expediteurOrdinaire}. */
    @Bean
    public JavaMailSender expediteurOrdinaire(
            @Value("${spring.mail.host:}") String hote,
            @Value("${spring.mail.port:587}") int port,
            @Value("${spring.mail.username:}") String identifiant,
            @Value("${spring.mail.password:}") String motDePasse) {
        return construire(hote, port, identifiant, motDePasse);
    }

    /**
     * Le compte des notifications CRITIQUES.
     *
     * <p>Le repli vers le compte ordinaire est ecrit dans le YAML, et non en
     * defaut de {@code @Value} : un defaut de {@code @Value} ne s'applique que
     * si la propriete est ABSENTE, et une cle declaree avec une valeur vide
     * EXISTE. Un {@code port} vide aurait alors leve NumberFormatException au
     * demarrage, defaut imbrique ou non.
     *
     * <p>Un deuxieme compte est une amelioration de reputation d'expedition,
     * pas une condition pour envoyer : sans repli, ne pas le configurer aurait
     * coupe les alertes de securite — exactement celles qui doivent arriver.
     */
    @Bean
    public JavaMailSender expediteurCritique(
            @Value("${notification.email.critique.host:}") String hote,
            @Value("${notification.email.critique.port:587}") int port,
            @Value("${notification.email.critique.username:}")
            String identifiant,
            @Value("${notification.email.critique.password:}")
            String motDePasse) {
        return construire(hote, port, identifiant, motDePasse);
    }
}
