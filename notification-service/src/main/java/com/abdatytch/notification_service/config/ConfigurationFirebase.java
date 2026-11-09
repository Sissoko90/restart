package com.abdatytch.notification_service.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Le client Firebase Cloud Messaging, s'il est configure.
 *
 * <h2>LE DEMARRAGE N'EST JAMAIS BLOQUE</h2>
 *
 * Politique du depot : aucune valeur absente de l'environnement ne doit
 * empecher un service de demarrer. Sans fichier d'identifiants, cette classe
 * rend donc un bean {@code null} — et {@code CanalPush} se declare inactif,
 * refuse chaque demande, et le dit dans le compte rendu.
 *
 * <p>Un bean {@code null} plutot qu'un {@code @ConditionalOnProperty} : avec la
 * condition, l'absence du bean serait invisible au demarrage, et l'injection
 * du canal echouerait pour une raison sans rapport avec la cause. Ici, le
 * journal dit exactement ce qui manque et ce que cela coupe.
 *
 * <h2>LE FICHIER N'EST PAS DANS LE DEPOT, ET NE DOIT JAMAIS Y ETRE</h2>
 *
 * Le compte de service Firebase est un JSON qui porte une <b>cle privee RSA</b>.
 * Elle autorise a pousser vers TOUS les appareils de l'application, et a
 * signer des jetons pour l'ensemble du projet Google. C'est un secret de
 * premier rang, du meme ordre que la cle de signature des JWT.
 *
 * <p>Il est donc designe par un CHEMIN, hors de l'arborescence du depot, et
 * jamais par son contenu. Ni variable d'environnement portant le JSON entier
 * — elle finirait dans un vidage memoire ou dans {@code /actuator/env} — ni
 * fichier commite, meme chiffre.
 *
 * <p>Aucune valeur du fichier n'est journalisee. Le journal cite le
 * {@code project_id} et l'adresse du compte de service, qui sont des
 * identifiants publics, et jamais la cle.
 *
 * <h2>Pas de {@code @RefreshScope}, et c'est obligatoire</h2>
 *
 * {@code FirebaseApp.initializeApp} refuse d'etre appele deux fois avec le meme
 * nom : il leve {@code IllegalStateException}. Un rafraichissement de
 * configuration recreerait ce bean et ferait echouer l'initialisation. Changer
 * d'identifiants demande donc un redemarrage, et c'est acceptable — on ne
 * change pas une cle de compte de service a chaud.
 */
@Configuration
public class ConfigurationFirebase {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationFirebase.class);

    /**
     * Le nom de l'instance Firebase de ce service.
     *
     * <p>Nomme plutot que l'instance par defaut : {@code FirebaseApp} est un
     * registre statique de portee JVM. Une instance nommee ne collisionne pas
     * avec celle qu'un autre composant pourrait creer, et le message d'erreur
     * d'une double initialisation nomme le coupable.
     */
    static final String NOM_INSTANCE = "notification-service";

    /**
     * La portee demandee au jeton d'acces.
     *
     * <p>{@code firebase.messaging} seule, et non {@code cloud-platform} : le
     * compte de service a potentiellement acces a tout le projet Google, et
     * rien n'oblige ce service a demander plus que l'envoi de messages. Un
     * jeton vole porte alors moins de pouvoir.
     */
    private static final List<String> PORTEES =
            List.of("https://www.googleapis.com/auth/firebase.messaging");

    @Bean
    public FirebaseMessaging firebaseMessaging(
            @Value("${notification.push.firebase.credentials-path:}") String chemin) {

        if (chemin == null || chemin.isBlank()) {
            log.warn("notification.push.firebase.credentials-path n'est pas "
                    + "configure : le canal PUSH refusera toute remise. "
                    + "Renseigner FIREBASE_CREDENTIALS_PATH avec le chemin du "
                    + "compte de service, HORS du depot.");
            return null;
        }

        Path fichier = Path.of(chemin);
        if (!Files.isReadable(fichier)) {
            // ERROR et non WARN : une valeur est posee, donc quelqu'un a voulu
            // activer le push. Un chemin faux est une erreur de deploiement, pas
            // un choix — contrairement a l'absence de valeur ci-dessus.
            log.error("Compte de service Firebase illisible a {} : le canal PUSH "
                    + "refusera toute remise. Verifier le chemin et les droits "
                    + "du fichier.", fichier);
            return null;
        }

        try (InputStream flux = Files.newInputStream(fichier)) {
            GoogleCredentials identifiants =
                    GoogleCredentials.fromStream(flux).createScoped(PORTEES);

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(identifiants)
                    .build();

            FirebaseApp instance = instanceExistante();
            if (instance == null) {
                instance = FirebaseApp.initializeApp(options, NOM_INSTANCE);
            }

            // Le project_id est un identifiant PUBLIC — il figure dans l'URL de
            // l'API et dans la configuration des clients mobiles. La cle privee,
            // elle, n'apparait nulle part.
            log.info("Canal PUSH : client FCM initialise pour le projet {}",
                    instance.getOptions().getProjectId());
            return FirebaseMessaging.getInstance(instance);

        } catch (IOException illisible) {
            // Le MESSAGE de l'exception est journalise, jamais le contenu du
            // fichier : un JSON mal forme produit une erreur qui peut citer la
            // portion fautive, et cette portion pourrait etre la cle.
            log.error("Compte de service Firebase inexploitable : le canal PUSH "
                    + "refusera toute remise ({})", illisible.getClass().getSimpleName());
            return null;
        } catch (RuntimeException refus) {
            log.error("Initialisation FCM impossible : le canal PUSH refusera "
                    + "toute remise ({})", refus.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * L'instance deja enregistree, ou {@code null}.
     *
     * <p>{@code FirebaseApp} est un registre STATIQUE de portee JVM : dans une
     * suite de tests qui charge deux contextes, la seconde initialisation
     * leverait {@code IllegalStateException}. Relire l'instance existante rend
     * cette classe rejouable sans la rendre dependante de l'ordre des tests.
     */
    private static FirebaseApp instanceExistante() {
        return FirebaseApp.getApps().stream()
                .filter(app -> NOM_INSTANCE.equals(app.getName()))
                .findFirst()
                .orElse(null);
    }
}
