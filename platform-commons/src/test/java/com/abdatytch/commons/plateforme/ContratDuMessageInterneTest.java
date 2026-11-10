package com.abdatytch.commons.plateforme;

import com.abdatytch.commons.security.AutorisationInterne;
import com.abdatytch.commons.security.CheminInterne;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le contrat HTTP de {@code POST /internal/message}, entre le client partage
 * de platform-commons — employe par auth-service et interface-service — et
 * notification-service.
 *
 * <h2>Pourquoi ce garde-fou existe</h2>
 *
 * Les deux cotes decrivent la MEME charge utile dans DEUX records distincts, et
 * c'est inevitable : les modules ne partagent aucun module de contrat HTTP, et
 * en creer un les coupleraient a un rythme de livraison commun.
 *
 * <p>Le danger n'est pas la duplication, c'est le SILENCE de sa derive. Jackson
 * ignore un champ qu'il ne connait pas et laisse sa valeur par defaut :
 *
 * <ul>
 *   <li>un {@code modele} renomme d'un seul cote arrive {@code null} chez le
 *       consommateur, qui repond « modele introuvable » — un refus propre, et
 *       un code de verification que personne ne recoit ;</li>
 *   <li>un {@code remis} renomme arrive {@code false} chez le producteur, qui
 *       journalise « message non remis » pour chaque envoi reussi. La
 *       supervision verrait une panne totale sur un service en bonne sante ;</li>
 *   <li>un {@code destinataire} renomme fait echouer la validation
 *       {@code @NotBlank}, donc un 400 — le seul des trois qui se voie, et
 *       seulement a l'execution.</li>
 * </ul>
 *
 * <p>Aucun de ces trois cas ne casse une compilation, ne leve une exception au
 * demarrage, ni ne fait echouer un test unitaire : chaque module est juste de
 * son cote.
 *
 * <h2>Ce que ce depot a deja paye, exactement ici</h2>
 *
 * {@code ClientContactsUtilisateur} a ete livre dans un etat ou il ne POUVAIT
 * pas fonctionner — le filtre d'acces de user-service refusait ses appels en
 * 403 — et rien ne l'a dit. Le contrat verifie ci-dessous vient d'etre recrit
 * des deux cotes dans la meme passe : c'est le moment ou ce risque est le plus
 * eleve, et celui ou personne ne le regarde.
 *
 * <h2>Ce que ce test ne couvre pas</h2>
 *
 * Il compare des NOMS declares dans les sources. Il ne prouve pas qu'un appel
 * reel aboutit : cela demanderait les deux services en marche, et c'est le role
 * du controle de bout en bout. Il ferme la derive silencieuse, pas la panne
 * visible.
 *
 * <p>Meme famille et meme place que {@code ContratDesEvenementsTest}, qui garde
 * le contrat Kafka entre les deux memes modules.
 */
@DisplayName("Contrat de /internal/message — client et controleur doivent concorder")
class ContratDuMessageInterneTest {

    /**
     * Le client, qui vit desormais dans platform-commons.
     *
     * <p>Il etait dans auth-service. interface-service en a eu besoin pour ses
     * deux courriels d'OTP, et le recopier aurait fait deux clients d'un meme
     * protocole interne — avec deux manieres de lire la reponse et de traiter
     * le refus. Ce test compare donc le client PARTAGE au controleur.
     */
    private static final Path CLIENT = Path.of(
            "platform-commons/src/main/java/com/abdatytch/commons/"
                    + "notification/ClientMessagerieNotification.java");

    private static final Path CONTROLEUR = Path.of(
            "notification-service/src/main/java/com/abdatytch/notification_service/"
                    + "web/InternalMessageController.java");

    /** Le record envoye par le client, et celui que le controleur recoit. */
    private static final String RECORD_ENVOYE = "Corps";
    private static final String RECORD_RECU = "DemandeDeMessage";

    /** Le record rendu par le controleur, et celui que le client relit. */
    private static final String RECORD_RENDU = "Remise";
    private static final String RECORD_RELU = "Reponse";

    private static final Pattern CONSTANTE = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Z0-9_]+)\\s*=\\s*\"([^\"]*)\"");

    @Nested
    @DisplayName("La charge utile")
    class ChargeUtile {

        /**
         * Les cinq champs de la demande portent le MEME nom des deux cotes.
         *
         * <p>Comparaison par ensemble et non par liste ordonnee : l'ordre des
         * composants d'un record n'a aucune importance pour Jackson, qui apparie
         * par nom. Un test sur l'ordre echouerait sur un changement sans
         * consequence, et un garde-fou qui crie a tort finit desactive.
         */
        @Test
        @DisplayName("la demande envoyée et la demande reçue ont les mêmes champs")
        void demandeConcorde() throws IOException {
            List<String> envoyes = composantsDuRecord(CLIENT, RECORD_ENVOYE);
            List<String> recus = composantsDuRecord(CONTROLEUR, RECORD_RECU);

            assertThat(envoyes)
                    .withFailMessage("""
                            Les champs envoyes par %s ne sont pas ceux attendus \
                            par %s.

                            envoyes : %s
                            attendus : %s

                            Jackson ignore en SILENCE un champ qu'il ne connait \
                            pas et laisse sa valeur par defaut. Un `modele` \
                            renomme arrive donc null, et le consommateur repond \
                            « modele introuvable » : un refus propre dans les \
                            journaux, et un code de verification que personne ne \
                            recoit.""",
                            RECORD_ENVOYE, RECORD_RECU, envoyes, recus)
                    .containsExactlyInAnyOrderElementsOf(recus);
        }

        /**
         * Les deux champs de la reponse concordent aussi.
         *
         * <p>Celui-la est le plus sournois des deux sens. Un {@code remis}
         * renomme cote controleur arriverait {@code false} chez le client, pour
         * CHAQUE envoi reussi : auth-service journaliserait « message non
         * remis » en continu, et la metrique des alertes de securite compterait
         * une panne totale sur un service en parfait etat.
         */
        @Test
        @DisplayName("la réponse rendue et la réponse relue ont les mêmes champs")
        void reponseConcorde() throws IOException {
            List<String> rendus = composantsDuRecord(CONTROLEUR, RECORD_RENDU);
            List<String> relus = composantsDuRecord(CLIENT, RECORD_RELU);

            assertThat(relus)
                    .withFailMessage("""
                            Les champs relus par %s ne sont pas ceux rendus par \
                            %s.

                            relus : %s
                            rendus : %s

                            Un `remis` renomme arriverait false pour chaque envoi \
                            REUSSI : auth-service journaliserait « non remis » en \
                            continu, et la supervision verrait une panne totale \
                            sur un service en bonne sante.""",
                            RECORD_RELU, RECORD_RENDU, relus, rendus)
                    .containsExactlyInAnyOrderElementsOf(rendus);
        }
    }

    @Nested
    @DisplayName("Le chemin et l'en-tête")
    class CheminEtEnTete {

        @Test
        @DisplayName("les deux côtés nomment le même chemin")
        void memeChemin() throws IOException {
            String chezLeClient = constantesDe(CLIENT).get("CHEMIN");
            String chezLeControleur = constantesDe(CONTROLEUR).get("CHEMIN");

            assertThat(chezLeClient)
                    .withFailMessage("CHEMIN absent de %s", CLIENT)
                    .isNotNull();
            assertThat(chezLeControleur)
                    .withFailMessage("CHEMIN absent de %s", CONTROLEUR)
                    .isNotNull();

            assertThat(chezLeClient)
                    .withFailMessage("""
                            Le client appelle « %s » et le controleur ecoute sur \
                            « %s ».

                            L'appel rendrait 404, et le client traite tout echec \
                            comme « message non parti » — sans distinguer une \
                            route absente d'un fournisseur en panne. Le defaut \
                            serait cherche du cote du fournisseur SMS.""",
                            chezLeClient, chezLeControleur)
                    .isEqualTo(chezLeControleur);
        }

        /**
         * Le client envoie l'en-tete que l'APPLICATEUR compare.
         *
         * <p>Comparaison contre {@link AutorisationInterne#EN_TETE_SECRET} et
         * non contre le controleur, et c'est delibere : le controleur ne verifie
         * rien. Le secret est applique dans la chaine de securite, pour couvrir
         * la surface {@code /internal/**} entiere — y compris les endpoints que
         * personne n'a encore ecrits.
         *
         * <p>Une divergence ici produirait un 403 sur chaque appel, avec un
         * message parlant de secret et non de nom d'en-tete : le defaut serait
         * cherche du cote des valeurs.
         */
        @Test
        @DisplayName("le client envoie l'en-tête que la chaîne de sécurité compare")
        void memeEnTete() throws IOException {
            String chezLeClient = constantesDe(CLIENT).get("EN_TETE_SECRET");

            assertThat(chezLeClient)
                    .withFailMessage("EN_TETE_SECRET absent de %s", CLIENT)
                    .isNotNull();

            assertThat(chezLeClient)
                    .withFailMessage("""
                            Le client envoie « %s » et AutorisationInterne \
                            compare « %s ».

                            Chaque appel interne serait refuse en 403, avec un \
                            message parlant de secret absent ou invalide — jamais \
                            de nom d'en-tete. Le defaut serait cherche du cote \
                            des valeurs, et non des noms.""",
                            chezLeClient, AutorisationInterne.EN_TETE_SECRET)
                    .isEqualTo(AutorisationInterne.EN_TETE_SECRET);
        }

        /**
         * Le chemin est bien SOUS la surface protegee.
         *
         * <p>S'il en sortait — par une faute de frappe, ou par un deplacement —
         * la chaine de securite ne lui appliquerait plus le secret, et il
         * tomberait dans {@code anyExchange().authenticated()}. Il deviendrait
         * alors appelable par tout porteur d'un jeton utilisateur : n'importe
         * quel compte pourrait faire envoyer un SMS, donc depenser des credits.
         */
        @Test
        @DisplayName("le chemin reste sous le préfixe /internal, donc protégé")
        void sousLePrefixeInterne() throws IOException {
            String chemin = constantesDe(CONTROLEUR).get("CHEMIN");

            assertThat(chemin)
                    .withFailMessage("""
                            « %s » n'est pas sous « %s ».

                            La chaine de securite n'y appliquerait plus le secret \
                            inter-services : le chemin tomberait dans \
                            anyExchange().authenticated() et deviendrait appelable \
                            par tout porteur d'un jeton utilisateur. N'importe \
                            quel compte pourrait faire envoyer un SMS.""",
                            chemin, CheminInterne.PREFIXE)
                    .startsWith(CheminInterne.PREFIXE + "/");
        }
    }

    @Nested
    @DisplayName("Le garde-fou se garde lui-même")
    class AutoControle {

        /**
         * Sans cette assertion, un chemin devenu faux ou une analyse cassee
         * rendrait des listes vides, aucun ecart, et ce test passerait en ne
         * verifiant RIEN. Ce depot s'est deja fait prendre deux fois par un
         * harnais vert qui ne mesurait plus rien.
         */
        @Test
        @DisplayName("les quatre records sont réellement lus et non vides")
        void lesRecordsSontLus() throws IOException {
            assertThat(composantsDuRecord(CLIENT, RECORD_ENVOYE))
                    .as("composants de %s dans %s", RECORD_ENVOYE, CLIENT)
                    .contains("canal", "destinataire", "modele", "variables", "urgence");
            assertThat(composantsDuRecord(CONTROLEUR, RECORD_RECU))
                    .as("composants de %s dans %s", RECORD_RECU, CONTROLEUR)
                    .contains("canal", "destinataire", "modele", "variables", "urgence");
            assertThat(composantsDuRecord(CONTROLEUR, RECORD_RENDU))
                    .as("composants de %s dans %s", RECORD_RENDU, CONTROLEUR)
                    .contains("remis", "motif");
            assertThat(composantsDuRecord(CLIENT, RECORD_RELU))
                    .as("composants de %s dans %s", RECORD_RELU, CLIENT)
                    .contains("remis", "motif");
        }

        /**
         * L'analyse sait traiter un type GENERIQUE.
         *
         * <p>{@code Map<String, String> variables} contient une virgule a
         * l'interieur de ses chevrons. Une decoupe naive sur la virgule y verrait
         * deux composants, dont un nomme « String » — et le test comparerait
         * alors des listes fausses des DEUX cotes, donc egales. Il passerait en
         * mesurant n'importe quoi.
         */
        @Test
        @DisplayName("un type générique ne casse pas la découpe en composants")
        void generiqueBienDecoupe() {
            List<String> composants = composantsDe(
                    "@NotNull CanalDeNotification canal, "
                            + "@NotBlank String destinataire, "
                            + "Map<String, String> variables, "
                            + "Map<String, List<String>> imbrique, "
                            + "DemandeDeNotification.Urgence urgence");

            assertThat(composants).containsExactly(
                    "canal", "destinataire", "variables", "imbrique", "urgence");
        }
    }

    // ==================================================================

    /**
     * Les noms des composants d'un record, dans l'ordre de declaration.
     *
     * <p>Lit la source plutot que la classe compilee : ce module ne depend
     * d'aucun des deux autres, et ne peut donc pas charger leurs types.
     */
    private static List<String> composantsDuRecord(Path relatif, String nomDuRecord)
            throws IOException {
        String source = sourceDe(relatif);

        int debut = source.indexOf("record " + nomDuRecord + "(");
        assertThat(debut)
                .withFailMessage("record %s introuvable dans %s — renomme ou "
                        + "deplace, et ce garde-fou ne protege plus rien",
                        nomDuRecord, relatif)
                .isGreaterThanOrEqualTo(0);

        int ouvrante = source.indexOf('(', debut);
        int profondeur = 0;
        int fermante = -1;
        for (int i = ouvrante; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                profondeur++;
            } else if (c == ')') {
                profondeur--;
                if (profondeur == 0) {
                    fermante = i;
                    break;
                }
            }
        }
        assertThat(fermante)
                .withFailMessage("parenthese fermante du record %s introuvable "
                        + "dans %s", nomDuRecord, relatif)
                .isGreaterThan(ouvrante);

        return composantsDe(source.substring(ouvrante + 1, fermante));
    }

    /**
     * Decoupe la liste des composants, en respectant les chevrons.
     *
     * <p>La profondeur des chevrons est suivie parce qu'un type generique porte
     * des virgules : {@code Map<String, String>} n'est pas deux composants.
     * Voir {@code generiqueBienDecoupe}, qui fixe ce comportement.
     *
     * <p>Visible pour le test, et c'est volontaire : cette decoupe est la seule
     * partie du garde-fou qui pourrait se tromper en silence.
     */
    static List<String> composantsDe(String liste) {
        List<String> noms = new ArrayList<>();
        int profondeur = 0;
        int debut = 0;

        for (int i = 0; i <= liste.length(); i++) {
            boolean fin = i == liste.length();
            char c = fin ? ',' : liste.charAt(i);

            if (c == '<') {
                profondeur++;
            } else if (c == '>') {
                profondeur--;
            } else if (c == ',' && profondeur == 0) {
                String fragment = liste.substring(debut, i).strip();
                if (!fragment.isEmpty()) {
                    // Le NOM est le dernier mot : tout ce qui precede est une
                    // annotation ou un type, et ni l'un ni l'autre ne compte
                    // pour Jackson, qui apparie par nom.
                    String[] mots = fragment.split("\\s+");
                    noms.add(mots[mots.length - 1]);
                }
                debut = i + 1;
            }
        }
        return noms;
    }

    private static Map<String, String> constantesDe(Path relatif) throws IOException {
        Map<String, String> trouvees = new LinkedHashMap<>();
        Matcher m = CONSTANTE.matcher(sourceDe(relatif));
        while (m.find()) {
            trouvees.put(m.group(1), m.group(2));
        }
        return trouvees;
    }

    private static String sourceDe(Path relatif) throws IOException {
        Path fichier = racineDuDepot().resolve(relatif);
        assertThat(fichier)
                .withFailMessage("fichier introuvable : %s — le chemin a change "
                        + "et ce garde-fou ne protege plus rien", fichier)
                .exists();
        return Files.readString(fichier);
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
