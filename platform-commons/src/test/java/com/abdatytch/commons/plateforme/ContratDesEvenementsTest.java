package com.abdatytch.commons.plateforme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le contrat d'événements entre auth-service et user-service.
 *
 * <h2>Le défaut que ce garde-fou ferme</h2>
 *
 * auth-service publie <b>trois</b> types d'événements sur le même topic
 * {@code user-events}, avec la même clef et des charges utiles qui ne diffèrent
 * que par la présence de champs. user-service désérialisait donc <b>tout</b> en
 * {@code UserCreatedEvent}.
 *
 * <p>La conséquence la plus coûteuse portait sur l'inscription OAuth2 : la
 * complétion de profil était lue comme une création, le compte existait déjà, et
 * le traitement s'arrêtait là. Numéro, date de naissance, adresse et biographie
 * saisis juste après une inscription Google ou Facebook n'arrivaient
 * <b>jamais</b> dans le profil.
 *
 * <p>Le type voyage désormais dans un en-tête Kafka, {@code event-type}.
 *
 * <h2>Pourquoi un test de source, et dans platform-commons</h2>
 *
 * Les deux services ne partagent aucun module de contrat d'événements : le nom
 * de l'en-tête et les trois valeurs de type sont donc <b>dupliqués</b>, une fois
 * chez le producteur et une fois chez le consommateur. C'est inévitable en
 * l'état, et c'est le genre de duplication qui dérive.
 *
 * <p>Et sa dérive serait <b>silencieuse</b> : renommer l'en-tête d'un seul côté
 * ne casse aucune compilation, ne lève aucune erreur, et fait simplement cesser
 * la reconnaissance de tous les événements. Le consommateur les acquitterait
 * comme « type non traité », en DEBUG. Rien dans les journaux ne dirait qu'un
 * contrat vient d'être rompu.
 *
 * <p>Ce module est le seul qui voit les deux arbres de sources, d'où sa place
 * ici — comme {@code MetriquesDesAlertesTest} et {@code ModuleLivrableTest}.
 *
 * <h2>Ce que ce test ne couvre pas</h2>
 *
 * Il compare des <b>constantes déclarées</b>. Il ne vérifie pas qu'un message
 * réellement publié porte l'en-tête, ni que l'aiguillage du consommateur traite
 * chaque type : cela demanderait un courtier. Les tests unitaires des deux
 * classes couvrent leur part.
 */
@DisplayName("Contrat d'evenements — producteur et consommateur doivent concorder")
class ContratDesEvenementsTest {

    private static final Path PRODUCTEUR = Path.of(
            "auth-service/src/main/java/com/abdatytch/auth_service/"
                    + "service/impl/EventPublisherImpl.java");

    private static final Path CONSOMMATEUR = Path.of(
            "user-service/src/main/java/com/abdatytch/user_service/"
                    + "listener/UserEventListener.java");

    /**
     * Les constantes qui forment le contrat.
     *
     * <p>Liste <b>fermée</b> et nommée : un comptage se contenterait d'un
     * contrat à demi recopié.
     */
    private static final List<String> CONTRAT = List.of(
            "EN_TETE_TYPE",
            "TYPE_COMPTE_CREE",
            "TYPE_CONNEXION",
            "TYPE_PROFIL_OAUTH2_COMPLETE");

    /**
     * {@code static final String NOM = "valeur";}
     *
     * <p>{@code [A-Z0-9_]} et non {@code [A-Z_]} : la premiere version refusait
     * les chiffres, donc {@code TYPE_PROFIL_OAUTH2_COMPLETE} n'etait reconnu
     * d'<b>aucun</b> cote — et le test le signalait comme absent du producteur.
     * Un faux positif trouve au premier lancement, par le garde-fou sur
     * lui-meme.
     */
    private static final Pattern CONSTANTE = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Z0-9_]+)\\s*=\\s*\"([^\"]*)\"");

    @Test
    @DisplayName("l'en-tete et les trois types valent la meme chose des deux cotes")
    void lesDeuxCotesConcordent() throws IOException {
        Map<String, String> producteur = constantesDe(PRODUCTEUR);
        Map<String, String> consommateur = constantesDe(CONSOMMATEUR);

        Map<String, String> ecarts = new LinkedHashMap<>();
        for (String nom : CONTRAT) {
            String chezLeProducteur = producteur.get(nom);
            String chezLeConsommateur = consommateur.get(nom);

            if (chezLeProducteur == null) {
                ecarts.put(nom, "absente du producteur (EventPublisherImpl)");
            } else if (chezLeConsommateur == null) {
                ecarts.put(nom, "absente du consommateur (UserEventListener)");
            } else if (!chezLeProducteur.equals(chezLeConsommateur)) {
                ecarts.put(nom, "producteur = \"" + chezLeProducteur
                        + "\" mais consommateur = \"" + chezLeConsommateur + "\"");
            }
        }

        assertThat(ecarts)
                .withFailMessage("""
                        Le contrat d'evenements entre auth-service et user-service \
                        ne concorde plus :

                        %s
                        Rien ne le signalerait a l'execution : un en-tete renomme d'un \
                        seul cote ne casse aucune compilation. Le consommateur \
                        acquitterait chaque message comme « type non traite », en DEBUG, \
                        et la completion de profil OAuth2 cesserait d'arriver — \
                        exactement le defaut que ce garde-fou existe pour fermer.

                        Les deux fichiers a tenir d'accord :
                          - %s
                          - %s""",
                        lisible(ecarts), PRODUCTEUR, CONSOMMATEUR)
                .isEmpty();
    }

    @Test
    @DisplayName("les deux fichiers sont bien lus")
    void lesDeuxFichiersSontLus() throws IOException {
        // Sans cette assertion, un chemin devenu faux rendrait deux cartes
        // vides, aucun ecart, et le garde-fou passerait en ne verifiant rien.
        // Ce depot s'est deja fait prendre par un harnais vert qui ne mesurait
        // plus rien.
        assertThat(constantesDe(PRODUCTEUR))
                .as("constantes lues dans %s", PRODUCTEUR)
                .containsKeys("EN_TETE_TYPE", "TYPE_COMPTE_CREE");
        assertThat(constantesDe(CONSOMMATEUR))
                .as("constantes lues dans %s", CONSOMMATEUR)
                .containsKeys("EN_TETE_TYPE", "TYPE_COMPTE_CREE");
    }

    private static Map<String, String> constantesDe(Path relatif) throws IOException {
        Path fichier = racineDuDepot().resolve(relatif);
        assertThat(fichier)
                .withFailMessage("fichier introuvable : %s — le chemin a change "
                        + "et ce garde-fou ne protege plus rien", fichier)
                .exists();

        Map<String, String> trouvees = new LinkedHashMap<>();
        Matcher m = CONSTANTE.matcher(Files.readString(fichier));
        while (m.find()) {
            trouvees.put(m.group(1), m.group(2));
        }
        return trouvees;
    }

    private static String lisible(Map<String, String> ecarts) {
        StringBuilder sb = new StringBuilder();
        ecarts.forEach((nom, cause) ->
                sb.append("  - ").append(nom).append(" : ").append(cause).append('\n'));
        return sb.toString();
    }

    private static Path racineDuDepot() {
        Path courant = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && courant != null; i++) {
            if (Files.exists(courant.resolve("docker-compose-minimal.yml"))) {
                return courant;
            }
            courant = courant.getParent();
        }
        throw new AssertionError(
                "Racine du depot introuvable depuis " + Path.of("").toAbsolutePath());
    }
}
