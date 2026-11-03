package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou : aucun justificatif ne doit être passé en argument de
 * journalisation.
 *
 * <h2>Ce que ce test a trouvé en étant écrit</h2>
 *
 * Dix sites, dans six fichiers, journalisaient en clair un jeton de
 * rafraîchissement, un jeton de réinitialisation de mot de passe, un jeton de
 * confirmation administrateur ou un <b>code de vérification à usage unique</b>.
 * Le plus grave — {@code "Code de vérification généré: {} pour: {}"} avec
 * {@code (code, phoneNumber)} — écrivait à {@code INFO} et sans condition tout
 * ce qu'il faut pour prendre un compte.
 *
 * <h2>Pourquoi un motif adjacent ne suffit pas</h2>
 *
 * interface-service masque déjà à l'écriture, par un {@code PatternLayout}
 * logback dont le motif exige {@code token}, {@code secret} ou
 * {@code password} <b>adjacent</b> au séparateur. Mesuré : trois des quatre
 * formes réellement présentes dans auth-service y échappaient, parce que le mot
 * surveillé était loin du deux-points :
 *
 * <pre>
 * "Rafraîchissement du token pour l'utilisateur: {}"   -- échappe
 * "Révocation du refresh token pour l'utilisateur: {}" -- échappe
 * "Code de vérification généré: {} pour: {}"           -- échappe, aucun mot surveillé
 * </pre>
 *
 * <p>Et ce filet n'existait que dans interface-service : auth-service,
 * gateway-service, user-service et media-service n'en avaient aucun.
 *
 * <h2>Le décalage qui rendait les fuites invisibles</h2>
 *
 * Quatre de ces gabarits annonçaient un identifiant et recevaient un
 * justificatif : « pour l'utilisateur: {} » avec le jeton, « pour type: {} »
 * avec le message contenant le code. Lire la chaîne de format seule ne révélait
 * rien — c'est l'<b>argument</b> qu'il faut regarder, et c'est ce que fait ce
 * test.
 */
@DisplayName("Aucun justificatif passé en argument de journalisation")
class AucunJustificatifDansLesJournauxTest {

    /**
     * Début d'un appel de journalisation.
     *
     * <p>La recherche se fait sur le contenu <b>entier</b> du fichier, et non
     * ligne par ligne, puis s'étend jusqu'au point-virgule. C'est nécessaire,
     * et vérifié par mutation : un balayage ligne par ligne ne voyait pas un
     * appel réparti sur plusieurs lignes —
     *
     * <pre>{@code
     * log.info(ServiceConstants.LOG_INFO_REFRESH_TOKEN,
     *         refreshTokenString);
     * }</pre>
     *
     * — c'est-à-dire précisément la forme que prennent les appels une fois le
     * masquage ajouté. Le garde-fou aurait donc été aveugle au retrait du
     * masquage qu'il est censé protéger.
     */
    private static final Pattern DEBUT_APPEL_LOG = Pattern.compile(
            "log\\.(?:info|warn|error|debug|trace)\\(");

    /**
     * Noms d'arguments qui désignent un justificatif.
     *
     * <p>La frontière de mot est posée <b>à gauche seulement</b>. C'est
     * délibéré, et vérifié par mutation : avec une frontière à droite,
     * {@code refreshTokenString} et {@code accessTokenValue} échappaient au
     * balayage. C'est précisément ainsi que la fuite de
     * {@code AuthServiceImpl} avait survécu — le premier balayage de ce dépôt
     * exigeait les deux frontières et l'avait manquée ; elle n'a été trouvée
     * qu'en lisant le code.
     *
     * <p>Le relâchement coûte peu : il fait apparaître {@code tokenId} et
     * {@code tokenType}, écartés par {@link #INNOCENT}. Il a en revanche
     * révélé une onzième fuite réelle, un code OTP dans interface-service.
     *
     * <h3>Pourquoi {@code message} y figure, malgré sa banalité</h3>
     *
     * Ce balayage a trouvé dix sites et en a manqué deux, dans le même
     * fichier — l'ancien {@code SmsServiceImpl.sendViaTwilio} :
     *
     * <pre>{@code
     * log.info(LOG_INFO_SENDING_VERIFICATION_SMS, phoneNumber, message);
     * log.debug(LOG_DEBUG_CODE_VALIDATION, message, phoneNumber);
     * }</pre>
     *
     * Tous deux écrivaient le code à usage unique, à {@code INFO} et sans
     * condition. Ils ont survécu parce que la variable s'appelle
     * {@code message}, un nom que j'avais écarté comme trop générique. Mesure
     * faite avant de l'ajouter : il fait apparaître <b>trois</b> sites dans
     * tout le dépôt, dont un déjà toléré, et <b>aucun</b> faux positif. Un
     * détecteur qui crie à tort finit désactivé ; celui-là ne crie pas.
     *
     * <p>{@code getMessage()} et {@code e.getMessage} restent écartés par
     * {@link #INNOCENT} : c'est la forme de loin la plus fréquente, et elle ne
     * divulgue rien.
     */
    private static final Pattern JUSTIFICATIF = Pattern.compile(
            "\\b(token|refreshToken|accessToken|jwt|secret|password|motDePasse|"
            + "apiKey|credential|otp|verificationCode|code|message)",
            Pattern.CASE_INSENSITIVE);

    /** Formes innocentes, qui contiennent un mot surveillé sans être le secret. */
    private static final Pattern INNOCENT = Pattern.compile(
            "tokenId|TokenId|tokenType|TokenType|getTokenId|getId\\(\\)|"
            + "SecretMasking|getMessage\\(\\)|e\\.getMessage|"
            + "\\buserId\\b|\\bdeveloperId\\b|\\bprojectId\\b|"
            + "secretHeader|internalSecret\\s*==|isRevoked|getIsRevoked|"
            // credentialId identifie un authentificateur WebAuthn. Il circule
            // dans les assertions et n'est pas un secret : le connaitre ne
            // permet rien, la suppression verifiant la propriete et refusant de
            // facon identique qu'il soit inconnu ou qu'il appartienne a un
            // autre. C'est un identifiant, au meme titre qu'un userId.
            + "credentialId|getCredentialId|"
            // Forme « x != null ? PRESENT : ABSENT » : seul un STATUT est
            // journalise, jamais la valeur. Deux sites l'emploient
            // (FeignClientInterceptor, UserMapper) et c'est exactement la bonne
            // pratique — les signaler apprendrait a ignorer ce test.
            + "!=\\s*null\\s*\\?");

    /**
     * Sites tolérés, avec la raison.
     *
     * <p>Chaque entrée est une dette assumée, pas un oubli. Les deux présentes
     * sont les affichages de développement, protégés par
     * {@code auth.dev.log-verification-codes} qui vaut <b>faux</b> par défaut.
     */
    private static final Set<String> TOLERES = Set.of(
            // Affichage du code en developpement local, faux par defaut.
            "EmailServiceImpl.java:journaliserCodeEnDeveloppement",
            "SmsServiceImpl.java:journaliserCodeEnDeveloppement",
            // `code` designe ici un code de TYPE DE DOCUMENT, pas un code a
            // usage unique. Le mot `code` est dans la liste surveillee parce
            // que, sans lui, la fuite la plus grave du lot echappait au
            // balayage : la variable du code de verification s'appelle
            // justement `code`. Verifie par mutation — le retirer rend ce
            // garde-fou aveugle a l'OTP.
            "AdminDocumentTypeController.java:updateRoleAssignment");

    private record Site(String fichier, int ligne, String extrait) {
    }

    /**
     * Nombre d'appels de journalisation réellement examinés.
     *
     * <p>Vérifié contre un plancher, et c'est le contrôle qui protège le
     * garde-fou de lui-même. {@link #neutraliserCommentairesEtChaines} efface
     * des portions du fichier : un automate trop gourmand — un bloc de
     * commentaire jamais fermé, un guillemet mal apparié — effacerait le code
     * au lieu des commentaires, et ce test deviendrait vert <b>en n'examinant
     * plus rien</b>. C'est la forme de succès la plus trompeuse, et ce dépôt l'a
     * déjà rencontrée deux fois : des tests Testcontainers ignorés derrière une
     * compilation verte, et un balayage dont le chemin était faux.
     */
    private int appelsExamines;

    /**
     * Plancher du nombre d'appels à examiner.
     *
     * <p>Volontairement bien en dessous du compte réel, pour ne pas devoir
     * l'ajuster à chaque ajout de journalisation : il n'est pas là pour mesurer
     * le dépôt, mais pour détecter un effondrement du balayage.
     */
    private static final int PLANCHER_APPELS_EXAMINES = 400;

    /**
     * Racine du dépôt, trouvée en remontant l'arborescence.
     *
     * <p>Le répertoire de travail d'un test Maven est celui du module. Remonter
     * jusqu'au POM parent est la seule façon fiable d'atteindre les sources des
     * autres modules.
     */
    private static Path racineDuDepot() {
        Path courant = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && courant != null; i++) {
            if (Files.exists(courant.resolve("docker-compose-minimal.yml"))) {
                return courant;
            }
            courant = courant.getParent();
        }
        throw new AssertionError(
                "Racine du dépôt introuvable depuis " + Path.of("").toAbsolutePath());
    }

    private List<Site> balayer() throws IOException {
        Path racine = racineDuDepot();
        List<Site> sites = new ArrayList<>();

        try (Stream<Path> fichiers = Files.walk(racine)) {
            List<Path> sources = fichiers
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList();

            // Le balayage doit trouver des fichiers. Sans ce controle, un chemin
            // errone rendrait ce garde-fou vert en n'examinant rien — la forme
            // la plus trompeuse de succes, deja rencontree dans ce depot.
            assertThat(sources)
                    .withFailMessage("le balayage n'a trouvé AUCUNE source : "
                            + "ce test serait vert sans rien vérifier")
                    .hasSizeGreaterThan(200);

            for (Path source : sources) {
                String brut = Files.readString(source);
                String[] lignes = brut.split("\n");
                // Les commentaires et les chaines sont neutralises AVANT la
                // recherche, en conservant les positions. Voir
                // neutraliserCommentairesEtChaines.
                String contenu = neutraliserCommentairesEtChaines(brut);
                Matcher m = DEBUT_APPEL_LOG.matcher(contenu);

                while (m.find()) {
                    appelsExamines++;
                    int fin = contenu.indexOf(';', m.end());
                    if (fin < 0) {
                        continue;
                    }
                    String appelBrut = contenu.substring(m.end(), fin);

                    // Les litteraux de chaine sont RETIRES avant toute analyse.
                    //
                    // Sans cela, deux faux positifs apparaissaient, et un
                    // detecteur qui crie a tort finit desactive — la lecon est
                    // deja inscrite dans ce depot au sujet de
                    // XssProtectionWebFilter :
                    //
                    //  - une virgule a l'INTERIEUR du message (« Admin non
                    //    trouvé, impossible de... ») faisait prendre la fin du
                    //    message pour un argument ;
                    //  - un argument litteral (« "JWT token" ») etait signale
                    //    alors qu'il ne divulgue rien.
                    //
                    // Ce qui reste apres retrait, ce sont les VARIABLES et les
                    // appels — c'est-a-dire ce qui peut porter un secret.
                    String appel = appelBrut.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "");
                    int virgule = appel.indexOf(',');
                    if (virgule < 0) {
                        continue;   // pas d'argument : rien a divulguer
                    }
                    String arguments = appel.substring(virgule + 1);
                    if (!JUSTIFICATIF.matcher(arguments).find()
                            || INNOCENT.matcher(arguments).find()) {
                        continue;
                    }

                    final int ligneDebut = numeroDeLigne(contenu, m.start());
                    String nom = source.getFileName().toString();
                    if (TOLERES.stream().anyMatch(t -> t.startsWith(nom + ":")
                            && contexteCorrespond(lignes, ligneDebut - 1, t))) {
                        continue;
                    }
                    // L'extrait du RAPPORT est pris dans le texte d'origine :
                    // les positions sont conservees par la neutralisation, et
                    // un rapport ou le gabarit est remplace par des espaces
                    // serait illisible.
                    sites.add(new Site(nom, ligneDebut,
                            brut.substring(m.end(), fin).replaceAll("\\s+", " ").strip()));
                }
            }
        }
        return sites;
    }

    /**
     * Remplace le contenu des commentaires et des chaînes par des espaces, en
     * <b>conservant les positions</b> et les retours à la ligne.
     *
     * <h3>Ce que l'absence de cette étape a produit</h3>
     *
     * Le balayage portait sur le texte brut du fichier, commentaires compris.
     * Documenter une fuite corrigée en citant la ligne fautive —
     *
     * <pre>{@code log.info(LOG_INFO_SENDING_VERIFICATION_SMS, phoneNumber, message);}</pre>
     *
     * — dans la javadoc de la classe qui l'avait corrigée suffisait donc à
     * faire échouer ce test. Trois faux positifs sur quatre détections, tous
     * dans de la javadoc. Un détecteur qui crie à tort finit désactivé, et la
     * seule issue acceptable aurait été de cesser de documenter les défauts
     * corrigés : exactement l'inverse de ce qu'il faut faire.
     *
     * <h3>Pourquoi les chaînes d'abord, et pourquoi un automate</h3>
     *
     * Les chaînes sont traitées dans le même passage, et non par une expression
     * régulière préalable, pour deux raisons précises :
     *
     * <ul>
     *   <li>{@code "http://exemple"} contient {@code //}. Retirer les
     *       commentaires d'abord effacerait la fin de la ligne — et pourrait
     *       donc cacher une vraie fuite qui s'y trouve ;</li>
     *   <li>un guillemet non apparié dans un commentaire ferait déborder une
     *       expression régulière de chaîne sur le code qui suit.</li>
     * </ul>
     *
     * Seul un automate qui lit une fois, dans l'ordre, en sachant dans quel
     * état il est, traite correctement les deux.
     */
    private static String neutraliserCommentairesEtChaines(String source) {
        char[] sortie = source.toCharArray();
        final int n = sortie.length;
        int i = 0;

        while (i < n) {
            char c = source.charAt(i);
            char suivant = i + 1 < n ? source.charAt(i + 1) : '\0';

            if (c == '/' && suivant == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    sortie[i++] = ' ';
                }
            } else if (c == '/' && suivant == '*') {
                sortie[i++] = ' ';
                sortie[i++] = ' ';
                while (i < n && !(source.charAt(i) == '*'
                        && i + 1 < n && source.charAt(i + 1) == '/')) {
                    // Les retours a la ligne sont conserves : les numeros de
                    // ligne du rapport doivent rester justes.
                    if (source.charAt(i) != '\n') {
                        sortie[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    sortie[i++] = ' ';
                }
                if (i < n) {
                    sortie[i++] = ' ';
                }
            } else if (c == '"' && suivant == '"' && i + 2 < n && source.charAt(i + 2) == '"') {
                // Bloc de texte. Sept sources main en contiennent, et sans ce
                // cas l'automate prendrait « """ » pour une chaine vide suivie
                // d'un guillemet ouvrant : le corps du bloc serait alors lu
                // comme du code des qu'il contient lui-meme un guillemet, ce
                // qui est le cas du JSON litteral.
                sortie[i++] = ' ';
                sortie[i++] = ' ';
                sortie[i++] = ' ';
                while (i + 2 < n && !(source.charAt(i) == '"'
                        && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"')) {
                    if (source.charAt(i) != '\n') {
                        sortie[i] = ' ';
                    }
                    i++;
                }
                for (int j = 0; j < 3 && i < n; j++) {
                    sortie[i++] = ' ';
                }
            } else if (c == '"' || c == '\'') {
                char delimiteur = c;
                sortie[i++] = ' ';
                while (i < n && source.charAt(i) != delimiteur) {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        sortie[i++] = ' ';
                    }
                    if (i < n) {
                        if (source.charAt(i) != '\n') {
                            sortie[i] = ' ';
                        }
                        i++;
                    }
                }
                if (i < n) {
                    sortie[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(sortie);
    }

    /** Numéro de ligne (1-indexé) d'une position dans le contenu du fichier. */
    private int numeroDeLigne(String contenu, int position) {
        int ligne = 1;
        for (int i = 0; i < position && i < contenu.length(); i++) {
            if (contenu.charAt(i) == '\n') {
                ligne++;
            }
        }
        return ligne;
    }

    /** La ligne tolérée appartient-elle bien à la méthode nommée dans la liste ? */
    private boolean contexteCorrespond(String[] lignes, int indice, String tolere) {
        String methode = tolere.substring(tolere.indexOf(':') + 1);
        for (int i = indice; i >= Math.max(0, indice - 12); i--) {
            if (lignes[i].contains(methode)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("aucun jeton, code ou mot de passe n'est passé à un logger")
    void aucunJustificatifJournalise() throws IOException {
        List<Site> sites = balayer();

        assertThat(appelsExamines)
                .withFailMessage("seulement %d appels de journalisation examinés : "
                        + "la neutralisation des commentaires a probablement effacé "
                        + "du code, et ce test serait vert sans rien vérifier",
                        appelsExamines)
                .isGreaterThan(PLANCHER_APPELS_EXAMINES);

        assertThat(sites)
                .withFailMessage("""
                        Ces appels passent un justificatif à un logger :

                        %s

                        Un jeton dans les journaux permet de rejouer une session ;
                        un code de vérification permet de prendre un compte. Les
                        journaux partent dans Loki, sont exportés et joints aux
                        rapports d'incident.

                        Corriger au POINT D'APPEL avec SecretMasking :
                          - masquer(...)         pour un jeton long (empreinte)
                          - masquerCodeCourt(...) pour un code à usage unique

                        Ne PAS compter sur le masquage d'appender : son motif exige
                        un mot surveillé adjacent au séparateur, et il n'existe que
                        dans interface-service.

                        Si un site est légitime, l'inscrire dans TOLERES avec sa
                        raison.""",
                        sites.stream()
                                .map(s -> "  " + s.fichier() + ":" + s.ligne()
                                        + "\n      " + s.extrait())
                                .reduce("", (a, b) -> a + b + "\n"))
                .isEmpty();
    }
}
