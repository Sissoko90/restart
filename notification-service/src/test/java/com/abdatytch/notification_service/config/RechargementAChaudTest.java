package com.abdatytch.notification_service.config;

import com.abdatytch.notification_service.service.impl.CanalCourriel;
import com.abdatytch.notification_service.service.impl.CanalPush;
import com.abdatytch.notification_service.service.impl.CanalSms;
import com.abdatytch.notification_service.service.impl.SmsServiceImpl;
import com.abdatytch.notification_service.service.sms.PlafondJournalierSms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.core.annotation.AnnotationUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le rechargement a chaud de la configuration tient vraiment.
 *
 * <h2>Pourquoi deux verifications, et pas une</h2>
 *
 * Recharger demande DEUX pieces independantes, et chacune est inutile sans
 * l'autre :
 *
 * <ol>
 *   <li>l'endpoint {@code refresh} expose dans le config-repo ;</li>
 *   <li>{@code @RefreshScope} sur les beans qui lisent la configuration.</li>
 * </ol>
 *
 * <p>Oublier la premiere : l'appel rend 404, et c'est visible tout de suite.
 * Oublier la seconde est bien pire — l'appel repond {@code 200} avec la liste
 * des cles modifiees, et <b>rien ne change</b>. C'est la panne qu'on ne voit
 * pas, et la raison de ce test.
 *
 * <h2>Ce que ce test ne peut PAS faire, et pourquoi il lit des fichiers</h2>
 *
 * Le profil de test n'utilise pas le serveur de configuration :
 * {@code application-test.yml} ne declare aucun endpoint, donc demarrer un
 * contexte ici ne dirait rien de ce que le config-repo expose en vrai.
 *
 * <p>La verification porte donc sur les deux sources elles-memes — le fichier du
 * config-repo et les annotations des classes. C'est la meme approche que
 * {@code MetriquesDesAlertesTest}, pour la meme raison : ce qui compte vit dans
 * un fichier que le compilateur ne lit pas.
 */
@DisplayName("Rechargement à chaud de la configuration")
class RechargementAChaudTest {

    /**
     * Les beans dont la configuration doit pouvoir changer sans redemarrage.
     *
     * <p>Ce sont les interrupteurs d'incident : couper un canal, relever un
     * plafond de depense trop bas. Ce qui n'y figure pas le fait expres — un
     * {@code @Scheduled} sur un bean rechargeable continue de tourner avec
     * l'ancienne instance. Voir {@link RechargementAChaud}.
     */
    private static final Class<?>[] BEANS_RECHARGEABLES = {
            CanalSms.class,
            CanalCourriel.class,
            CanalPush.class,
            SmsServiceImpl.class,
            PlafondJournalierSms.class,
    };

    @Nested
    @DisplayName("Les beans portent @RefreshScope")
    class BeansAnnotes {

        @Test
        @DisplayName("tous les beans d'interrupteur sont rechargeables")
        void tousAnnotes() {
            for (Class<?> bean : BEANS_RECHARGEABLES) {
                assertThat(AnnotationUtils.findAnnotation(bean, RefreshScope.class))
                        .withFailMessage("""
                                %s ne porte pas @RefreshScope.

                                Sa configuration restera celle du demarrage, et \
                                POST /actuator/refresh repondra 200 sans rien \
                                changer — la panne qui ne se voit pas.""",
                                bean.getSimpleName())
                        .isNotNull();
            }
        }

        /**
         * Le pendant du test precedent.
         *
         * <p>Un {@code @Scheduled} est enregistre une fois au demarrage, avec la
         * reference du bean d'alors. Sur un bean {@code @RefreshScope}, le
         * planificateur garde cette ancienne instance : la tache continue de
         * tourner avec l'ancienne configuration, sans que rien ne le signale.
         *
         * <p>Ce test fige donc l'inverse : ces deux classes ne doivent
         * <b>jamais</b> devenir rechargeables.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "com.abdatytch.notification_service.scheduler.RepriseDesRemises",
                "com.abdatytch.notification_service.service.sms.SurveillanceDuSoldeSms",
        })
        @DisplayName("les beans planifiés ne sont PAS rechargeables")
        void beansPlanifiesNonRechargeables(String nomDeClasse) throws ClassNotFoundException {
            Class<?> bean = Class.forName(nomDeClasse);

            assertThat(AnnotationUtils.findAnnotation(bean, RefreshScope.class))
                    .withFailMessage("""
                            %s porte @RefreshScope ET un @Scheduled.

                            Le planificateur garde la reference du bean cree au \
                            demarrage : apres un rechargement, la tache tourne \
                            avec l'ancienne configuration — ou cesse de tourner. \
                            Ses reglages demandent un redemarrage, et c'est \
                            acceptable : ce sont des cadences, pas des \
                            interrupteurs d'incident.""",
                            bean.getSimpleName())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("L'endpoint est exposé dans le config-repo")
    class EndpointExpose {

        @Test
        @DisplayName("refresh figure dans la liste des endpoints exposés")
        void refreshExpose() throws IOException {
            Path fichier = racineDuDepot()
                    .resolve("config-repo/dev/notification-service-dev.yml");

            assertThat(fichier)
                    .withFailMessage("Fichier de configuration introuvable : "
                            + "ce garde-fou serait vert sans rien verifier")
                    .exists();

            String contenu = Files.readString(fichier);

            // La ligne `include:` et non le mot `refresh` n'importe ou : le
            // fichier PARLE de refresh dans ses commentaires, et chercher le mot
            // nu aurait rendu ce test vert sur sa propre explication. Ce depot a
            // deja paye cette erreur sur deux autres garde-fous.
            String include = contenu.lines()
                    .map(String::strip)
                    .filter(ligne -> ligne.startsWith("include:"))
                    .findFirst()
                    .orElse("");

            assertThat(include)
                    .withFailMessage("""
                            `refresh` n'est pas expose dans \
                            management.endpoints.web.exposure.include.

                            Ligne trouvee : %s

                            POST /actuator/refresh repondra 404, et la \
                            configuration ne pourra pas etre modifiee sans \
                            redemarrage.""", include.isEmpty() ? "AUCUNE" : include)
                    .contains("refresh");
        }

        private static Path racineDuDepot() {
            Path courant = Path.of("").toAbsolutePath();
            for (int niveau = 0; niveau < 5 && courant != null; niveau++) {
                if (Files.isDirectory(courant.resolve("config-repo"))) {
                    return courant;
                }
                courant = courant.getParent();
            }
            throw new IllegalStateException("Racine du depot introuvable");
        }
    }
}
