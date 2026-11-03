package com.abdatytch.gateway_service;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Chargement du fichier {@code .env} de la racine du depot, en repli de
 * l'environnement reel.
 *
 * <h2>Ce que cette classe imprimait</h2>
 *
 * Chaque variable etait ecrite sur la sortie standard, masquee seulement si son
 * nom contenait {@code SECRET}, {@code PASSWORD} ou {@code TOKEN}. Ce filtre
 * laissait passer, en clair et a chaque demarrage :
 *
 * <ul>
 *   <li>{@code JWT_DEV_PRIVATE_KEY} — la cle qui signe <b>tous</b> les jetons
 *       de la plateforme. C'est la meme cle dont on vient d'empecher
 *       l'empaquetage dans le JAR livre : la sortir du JAR pour la deverser
 *       dans les journaux ne deplace le probleme que d'un cran, et Loki
 *       conserve plus longtemps qu'un artefact ;</li>
 *   <li>{@code ENCRYPTION_MASTER_KEY}, {@code RECOVERY_RSA_PRIVATE_KEY},
 *       {@code GATEWAY_API_KEY}, {@code MINIO_ACCESS_KEY} ;</li>
 *   <li>identifiants de bases, comptes de messagerie, {@code SMS_ACCOUNT_SID},
 *       {@code OAUTH2_*_CLIENT_ID} ;</li>
 *   <li>les coordonnees des comptes d'administration.</li>
 * </ul>
 *
 * Soit 153 des 182 variables. Un filtre par liste de mots interdits echoue
 * toujours ainsi : il faut enumerer ce qui est sensible, alors que <b>tout</b>
 * ce qui vient d'un fichier de secrets l'est. Aucune valeur n'est donc plus
 * journalisee, seul leur nombre l'est.
 *
 * <p>{@code System.setProperty} a egalement ete retire — il recopiait chaque
 * secret dans les proprietes systeme de la JVM, ou elles apparaissent dans les
 * vidages memoire, sans qu'aucun code du depot ne les y lise — et
 * {@code addFirst} remplace par {@code addLast}, pour que l'environnement reel
 * et le coffre gardent la priorite sur un fichier de repli.
 */
public class DotenvConfig implements EnvironmentPostProcessor {

    /**
     * Journal obtenu statiquement, et <b>non injecte au constructeur</b>.
     *
     * <p>Spring Boot sait injecter un {@link Log} differe dans un
     * {@code EnvironmentPostProcessor}, qui rejoue ses messages une fois la
     * journalisation prete. Deux tentatives ont echoue : par la cle
     * {@code spring.factories} de l'interface depreciee, le journal arrivait
     * {@code null} et le premier appel levait une
     * {@code NullPointerException} ; par la cle non depreciee, l'instanciation
     * echouait <b>en silence</b> et le chargeur ne tournait plus du tout — les
     * services demarraient alors avec des valeurs vides, exactement la panne
     * que cette classe est censee rendre visible.
     *
     * <p>Un chargeur de configuration de repli ne doit jamais dependre d'un
     * mecanisme d'injection pour fonctionner. Le constructeur est donc sans
     * argument, et l'avertissement decisif passe par {@code System.err}, qui
     * fonctionne avant tout appender.
     */
    private static final Log log = LogFactory.getLog(DotenvConfig.class);

    /** Nombre de niveaux remontes en cherchant la racine du depot. */
    private static final int PROFONDEUR_MAX = 5;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        File racine = trouverRacine();
        if (racine == null) {
            log.warn("Aucun fichier .env trouve en remontant depuis "
                    + System.getProperty("user.dir"));
        // Ecrit AUSSI sur la sortie d'erreur, et pas seulement dans le journal.
        //
        // Un EnvironmentPostProcessor s'execute avant l'initialisation de la
        // journalisation. Spring Boot peut injecter un journal differe qui
        // rejoue ses messages ensuite, mais selon le mecanisme
        // d'enregistrement il ne le fournit pas — verifie sur cette
        // plateforme : aucune ligne de cette classe n'apparait au demarrage,
        // alors que les valeurs chargees sont bien presentes dans
        // l'environnement.
        //
        // Ce message precis est le seul diagnostic d'une panne qui, sinon, est
        // totalement silencieuse : des services qui demarrent avec des champs
        // vides sans que rien n'explique pourquoi. Il doit atteindre
        // l'operateur meme quand aucun appender n'existe encore.
        System.err.println("[dotenv] AUCUN FICHIER .env TROUVE en remontant depuis "
                + System.getProperty("user.dir"));
            return;
        }

        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(racine.getAbsolutePath())
                    .ignoreIfMissing()
                    .load();

            // DECLARED_IN_ENV_FILE est indispensable : sans filtre,
            // entries() rend AUSSI tout l'environnement du processus.
            // La source ne contenait donc pas les quelques cles du
            // fichier mais la centaine de variables de la machine, et
            // l'ancien addFirst les promouvait au-dessus
            // d'application.yml et des arguments -D de la ligne de
            // commande. Le journal annoncait un nombre de variables de
            // repli sans rapport avec le contenu du fichier.
            Map<String, Object> valeurs = new HashMap<>();
            dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)
                    .forEach(entree -> valeurs.put(entree.getKey(), entree.getValue()));

            // En dernier : l'environnement reel et le coffre gardent la main.
            environment.getPropertySources()
                    .addLast(new MapPropertySource("dotenvProperties", valeurs));

            log.info(valeurs.size() + " variables de repli chargees depuis "
                    + new File(racine, ".env").getAbsolutePath());

            // La presence de ce secret est verifiee, jamais sa valeur : c'est
            // lui seul qui distingue un appel passe par le Gateway d'un appel
            // direct aux services.
            if (dotenv.get("GATEWAY_SECRET_KEY") == null) {
                log.error("GATEWAY_SECRET_KEY absent : la signature des requetes "
                        + "interservice ne pourra pas etre produite");
            }
        } catch (Exception e) {
            log.error("Lecture de .env impossible depuis "
                    + racine.getAbsolutePath() + " : " + e.getMessage());
        }
    }

    /**
     * Remonte l'arborescence depuis le repertoire de travail jusqu'a trouver un
     * {@code .env}, ce qui rend le demarrage independant du repertoire depuis
     * lequel la commande est lancee.
     */
    private File trouverRacine() {
        File courant = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int niveau = 0; niveau <= PROFONDEUR_MAX && courant != null; niveau++) {
            if (new File(courant, ".env").isFile()) {
                return courant;
            }
            courant = courant.getParentFile();
        }
        return null;
    }
}
