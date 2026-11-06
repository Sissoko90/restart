package com.abdatytch.commons.config;

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
 * Chargement du fichier {@code .env} de la racine du depot.
 *
 * <p>config-service resout lui-meme les substitutions des fichiers de
 * {@code config-repo} avant de les servir : {@code ${SUPER_ADMIN_EMAIL:}} dans
 * {@code auth-service-dev.yml} est remplace par la valeur presente dans
 * <b>l'environnement de config-service</b>, pas dans celui d'auth-service. Ce
 * fichier est donc la source de repli de toute la plateforme quand le coffre
 * n'est pas joignable. S'il n'est pas trouve, chaque service demarre avec des
 * valeurs vides, sans qu'aucun ne signale pourquoi.
 *
 * <h2>Trois defauts corriges</h2>
 *
 * <ol>
 *   <li><b>{@code directory("..")} etait relatif au repertoire de travail.</b>
 *       Lance depuis {@code config-service/}, le chemin designait la racine et
 *       tout fonctionnait ; lance depuis la racine — ce que fait
 *       {@code mvn -pl config-service spring-boot:run} — il designait le
 *       repertoire au-dessus du depot, ou il n'y a pas de {@code .env}.
 *       Combine a {@code ignoreIfMissing()}, <b>l'echec etait silencieux</b> :
 *       le serveur servait des valeurs vides sans erreur. La racine est
 *       maintenant trouvee en remontant l'arborescence.</li>
 *
 *   <li><b>{@code System.setProperty} recopiait chaque secret dans les
 *       proprietes systeme de la JVM.</b> Elles se retrouvent dans les vidages
 *       memoire et sont lisibles par tout code du meme processus. Le
 *       commentaire justifiait cette recopie par une forme de substitution
 *       prefixee par {@code env.} — qui n'est employee nulle part dans le
 *       depot.</li>
 *
 *   <li><b>{@code addFirst} donnait au fichier la priorite sur les vraies
 *       variables d'environnement.</b> Un {@code .env} oublie sur un serveur
 *       ecrasait donc la configuration injectee par l'orchestrateur ou le
 *       coffre : l'inverse de ce qu'on attend d'un repli. Il est desormais
 *       ajoute en dernier, et ne sert que pour ce que l'environnement reel ne
 *       fournit pas.</li>
 * </ol>
 */
public class ChargeurDotenv implements EnvironmentPostProcessor {

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
    private static final Log log = LogFactory.getLog(ChargeurDotenv.class);

    /** Nombre de niveaux remontes en cherchant la racine du depot. */
    private static final int PROFONDEUR_MAX = 5;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        File racine = trouverRacine();
        if (racine == null) {
            log.warn("Aucun fichier .env trouve en remontant depuis "
                    + System.getProperty("user.dir")
                    + ". Les substitutions des fichiers de config-repo qui n'ont pas de "
                    + "valeur dans l'environnement reel resteront vides.");
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

            // En DERNIER : l'environnement reel et le coffre gardent la main.
            // Ce fichier ne comble que ce qu'ils ne fournissent pas.
            environment.getPropertySources()
                    .addLast(new MapPropertySource("dotenvProperties", valeurs));

            // Le nombre de variables, jamais leur contenu : ce journal part
            // vers Loki.
            log.info(valeurs.size() + " variables de repli chargees depuis "
                    + new File(racine, ".env").getAbsolutePath());
        } catch (Exception e) {
            log.error("Lecture de .env impossible depuis "
                    + racine.getAbsolutePath() + " : " + e.getMessage());
        }
    }

    private File trouverRacine() {
        return trouverRacine(new File(System.getProperty("user.dir")));
    }

    /**
     * Remonte l'arborescence depuis {@code depart} jusqu'a trouver un
     * {@code .env}, ou {@code null} apres {@value #PROFONDEUR_MAX} niveaux.
     *
     * <p>Rend le demarrage independant du repertoire depuis lequel la commande
     * est lancee — racine du depot, module, ou image conteneur.
     *
     * <p>Le repertoire de depart est un parametre, et non lu directement depuis
     * {@code user.dir}, pour que la remontee soit verifiable sans toucher a une
     * propriete globale de la JVM.
     */
    File trouverRacine(File depart) {
        File courant = depart.getAbsoluteFile();
        for (int niveau = 0; niveau <= PROFONDEUR_MAX && courant != null; niveau++) {
            File candidat = new File(courant, ".env");
            if (candidat.isFile()) {
                return courant;
            }
            courant = courant.getParentFile();
        }
        return null;
    }
}
