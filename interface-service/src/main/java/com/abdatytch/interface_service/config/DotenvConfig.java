package com.abdatytch.interface_service.config;

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
 * <h2>Deux defauts corriges</h2>
 *
 * <ol>
 *   <li><b>Le chargement se faisait dans un bloc {@code static} d'une classe
 *       {@code @Configuration}</b>, donc au premier chargement de la classe par
 *       le scanner de composants. Cela tombe pendant le rafraichissement du
 *       contexte, apres que {@code ConfigDataEnvironmentPostProcessor} a lu
 *       {@code application.yml} : les substitutions de ce fichier ne voyaient
 *       jamais le contenu de {@code .env}. Seules les lectures tardives —
 *       {@code @Value} sur un bean construit plus tard — fonctionnaient, et
 *       uniquement grace aux proprietes systeme. Un {@code EnvironmentPostProcessor}
 *       s'execute avant toute liaison de configuration.</li>
 *
 *   <li><b>{@code System.setProperty} etait le seul mecanisme d'exposition.</b>
 *       Il recopiait chaque secret dans les proprietes systeme de la JVM, ou
 *       elles subsistent pour la duree du processus et apparaissent dans les
 *       vidages memoire. C'est particulierement mal venu ici : ce service est
 *       l'autorite de signature JWT, il detient
 *       {@code JWT_DEV_PRIVATE_KEY} et {@code RECOVERY_RSA_PRIVATE_KEY}. Les
 *       valeurs passent maintenant par un {@code MapPropertySource}, qui vit
 *       dans l'environnement Spring et disparait avec le contexte.</li>
 * </ol>
 *
 * <p>Le fichier est ajoute <b>en dernier</b> : l'environnement reel et le coffre
 * gardent la priorite, ce qui est la definition d'un repli. L'ancien code avait
 * la bonne intention — il testait {@code getProperty}/{@code getenv} avant
 * d'ecrire — mais l'exprimait par une condition plutot que par l'ordre des
 * sources.
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
                    .ignoreIfMalformed()
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

            environment.getPropertySources()
                    .addLast(new MapPropertySource("dotenvProperties", valeurs));

            // Le nombre de variables, jamais leur contenu.
            log.info(valeurs.size() + " variables de repli chargees depuis "
                    + new File(racine, ".env").getAbsolutePath());
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
