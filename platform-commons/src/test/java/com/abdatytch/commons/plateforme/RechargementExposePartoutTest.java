package com.abdatytch.commons.plateforme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tous les services peuvent recharger leur configuration.
 *
 * <h2>Le defaut que ce test ferme</h2>
 *
 * L'endpoint {@code refresh} n'etait expose par <b>aucun</b> des onze modules.
 * Le config-repo etait donc lu une seule fois, au demarrage : modifier une
 * valeur n'avait aucun effet jusqu'au prochain redemarrage, et rien ne le
 * disait. Un exploitant qui coupait {@code sms.enabled} pendant un incident
 * pouvait croire les SMS arretes alors qu'ils continuaient de se payer.
 *
 * <h2>La surcharge REMPLACE, elle n'ajoute pas</h2>
 *
 * C'est le piege que ce test surveille vraiment. {@code refresh} est declare une
 * fois dans {@code application-dev.yml}, pour les onze modules. Mais un module
 * qui redeclare {@code management.endpoints.web.exposure.include} dans son
 * propre fichier <b>remplace</b> la liste partagee : il ne s'y ajoute pas.
 *
 * <p>Deux modules surchargent deja cette liste — gateway-service, pour ses
 * limiteurs de debit, et notification-service. Un troisieme qui surchargerait
 * sans reporter {@code refresh} perdrait son rechargement <b>en silence</b> :
 * aucune erreur, aucun avertissement, simplement un 404 le jour ou quelqu'un
 * appelle.
 *
 * <p>Ce test lit donc les fichiers eux-memes, comme
 * {@code MetriquesDesAlertesTest} : ce qui compte vit dans un YAML que le
 * compilateur ne lit pas.
 */
@DisplayName("Rechargement de configuration exposé partout")
class RechargementExposePartoutTest {

    private static final String ENDPOINT = "refresh";

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

    /**
     * La ligne {@code include:} de l'exposition des endpoints, s'il y en a une.
     *
     * <p>Et non n'importe quel {@code include:} : les fichiers en portent
     * d'autres — {@code management.endpoint.health.group.readiness.include} dans
     * auth-service et user-service, par exemple. Les confondre aurait fait
     * echouer ce test sur une liste d'indicateurs de sante, ou pire, l'aurait
     * rendu vert en lisant la mauvaise ligne.
     *
     * <p>La reconnaissance porte sur le CONTENU : une liste d'endpoints contient
     * {@code health,} ou {@code prometheus}, celle d'un groupe de sante contient
     * {@code diskSpace} et {@code r2dbc}. C'est fragile, et c'est pourquoi le
     * test compte aussi combien de listes il a trouvees.
     */
    private static String ligneDExposition(String contenu) {
        return contenu.lines()
                .map(String::strip)
                .filter(ligne -> ligne.startsWith("include:"))
                .filter(ligne -> ligne.contains("health,") || ligne.contains("prometheus"))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("chaque fichier qui surcharge la liste d'endpoints y garde refresh")
    void chaqueSurchargeGardeRefresh() throws IOException {
        Path dossier = racineDuDepot().resolve("config-repo/dev");

        assertThat(dossier)
                .withFailMessage("config-repo/dev introuvable : ce garde-fou "
                        + "serait vert sans rien verifier")
                .isDirectory();

        List<String> sansRefresh = new ArrayList<>();
        int listesTrouvees = 0;

        try (Stream<Path> fichiers = Files.list(dossier)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".yml")).toList()) {
                String ligne = ligneDExposition(Files.readString(fichier));
                if (ligne == null) {
                    // Ce module herite de la liste partagee : rien a verifier.
                    continue;
                }
                listesTrouvees++;
                if (!ligne.contains(ENDPOINT)) {
                    sansRefresh.add(fichier.getFileName() + " → " + ligne);
                }
            }
        }

        // Le garde-fou se garde lui-meme : sans cette assertion, un changement
        // de forme du YAML le rendrait vert en ne trouvant plus aucune liste.
        // C'est l'erreur commise trois fois dans ce depot.
        assertThat(listesTrouvees)
                .withFailMessage("Aucune liste d'exposition d'endpoints trouvee "
                        + "dans config-repo/dev : ce garde-fou ne verifie plus rien")
                .isGreaterThanOrEqualTo(3);

        assertThat(sansRefresh)
                .withFailMessage("""
                        Ces fichiers surchargent la liste d'endpoints exposes SANS \
                        y reporter `%s` : %s

                        Une surcharge REMPLACE la liste d'application-dev.yml, \
                        elle ne s'y ajoute pas. Ces modules ne pourront pas \
                        recharger leur configuration, et POST /actuator/refresh \
                        rendra 404 — sans qu'aucune erreur ne l'annonce avant.""",
                        ENDPOINT, sansRefresh)
                .isEmpty();
    }

    @Test
    @DisplayName("la liste partagée l'expose, pour les modules qui ne surchargent pas")
    void listePartageeExposeRefresh() throws IOException {
        Path partage = racineDuDepot().resolve("config-repo/dev/application-dev.yml");
        String ligne = ligneDExposition(Files.readString(partage));

        assertThat(ligne)
                .withFailMessage("Aucune liste d'exposition dans application-dev.yml")
                .isNotNull();

        assertThat(ligne)
                .withFailMessage("""
                        `%s` manque dans la liste PARTAGEE : %s

                        C'est elle qui donne le rechargement aux modules qui ne \
                        surchargent pas — soit neuf des onze.""", ENDPOINT, ligne)
                .contains(ENDPOINT);
    }
}
