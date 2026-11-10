package com.abdatytch.gateway_service.routing;

import org.junit.jupiter.api.DisplayName;
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
 * Les deux declarations du routage doivent s'accorder.
 *
 * <h2>Pourquoi deux declarations existent</h2>
 *
 * {@code GatewayRoutesConfig} declare le routage sous forme de
 * {@code .nest(path("/api/v1/..."), ...)} suivi d'un proxy vers un service.
 * {@code CheminCible.servicesParPrefixe()} reproduit cette correspondance,
 * parce que le mode maintenance doit pouvoir couper <b>un</b> service : le
 * filtre a besoin de savoir quel service une requete allait atteindre, et cette
 * connaissance n'etait accessible nulle part.
 *
 * <p>Extraire le routage lui-meme vers {@code CheminCible} aurait demande de
 * reecrire une configuration qui fonctionne, et le risque aurait depasse le mal.
 *
 * <h2>Ce que ce test remplace</h2>
 *
 * La <b>pretention</b> qu'il n'y a qu'une declaration. Ce depot a paye plusieurs
 * fois une correspondance recopiee : un seuil de verrouillage declare dans deux
 * endroits, une liste de chemins publics tenue par auth-service et par le
 * gateway, une cle Redis ecrite d'un cote et lue de l'autre. La divergence est
 * toujours <b>silencieuse</b>.
 *
 * <p>Ici, un prefixe ajoute au routage sans l'etre dans {@code CheminCible}
 * produirait une maintenance qui ne coupe rien pour ce chemin — sans erreur,
 * sans journal. Ce test echoue a la place.
 */
@DisplayName("Routage : CheminCible et GatewayRoutesConfig s'accordent")
class RoutageCoherentTest {

    private static final Path CONFIG_DES_ROUTES = Path.of(
            "src/main/java/com/abdatytch/gateway_service/config/GatewayRoutesConfig.java");

    /**
     * Les routes YAML, LUES AUSSI depuis la correction de l'angle mort de ce
     * test.
     *
     * <h2>Le defaut corrige</h2>
     *
     * Ce test ne lisait que {@code GatewayRoutesConfig.java}. Il s'annonce
     * pourtant comme le garant de la coherence du routage, alors que ce service
     * en porte <b>deux</b> mecanismes : le {@code RouterFunction} en Java et les
     * routes Spring Cloud Gateway en YAML.
     *
     * <p>L'angle mort n'avait jamais mordu parce qu'aucun prefixe n'etait
     * declare UNIQUEMENT en YAML : tous figuraient aussi dans le routeur Java.
     * Le premier a l'etre — le flux WebSocket de notification-service, qui ne
     * peut pas passer par le {@code RouterFunction} puisque {@code WebClient} ne
     * sait pas monter en protocole — a ete signale comme un « prefixe
     * fantome ».
     *
     * <p>Diagnostic a l'envers, donc : le prefixe etait bien route, c'est le
     * test qui ne regardait qu'une moitie du routage. {@code ModuleLivrableTest}
     * lit deja les deux, et son commentaire explique pourquoi — « n'en lire
     * qu'un rendrait ce garde-fou faux dans les deux sens : il accuserait des
     * services routes par l'autre, et finirait desactive ».
     */
    private static final Path ROUTES_YAML = Path.of(
            "../config-repo/dev/gateway-service-dev.yml");

    /**
     * Retire les commentaires <b>sans toucher aux chaines</b>.
     *
     * <h2>Pourquoi une regex ne suffit pas, et le defaut qu'elle a produit</h2>
     *
     * La premiere version de ce test retirait les blocs par une expression
     * reguliere cherchant un debut puis une fin de commentaire. Or les motifs de
     * routage s'ecrivent {@code path("/api/v1/users/...")} avec une double
     * etoile : la sequence « barre oblique + etoile » y apparait <b>dans une
     * chaine</b>, et la regex l'a prise pour un debut de commentaire. Elle a donc
     * avale tout le code jusqu'a la fin de commentaire suivante — c'est-a-dire
     * l'essentiel des declarations de routes.
     *
     * <p>Le meme caractere a d'ailleurs casse la compilation de cette javadoc
     * quand la regex y figurait en clair : elle contenait une fin de
     * commentaire, qui terminait le bloc avant sa derniere ligne.
     *
     * <p>Resultat : le test ne lisait <b>aucune</b> route. Sans l'assertion
     * {@code isNotEmpty} posee par precaution, il aurait passe en ne comparant
     * rien — un garde-fou vide, qui est exactement ce que cette suite existe
     * pour empecher ailleurs.
     *
     * <p>D'ou cet analyseur : il suit l'etat « dans une chaine » et ne retire un
     * commentaire que hors chaine. Les commentaires doivent etre retires parce
     * qu'ils citent des noms de service, et le test lirait alors une
     * correspondance dans sa propre documentation — le piege qui a deja fait
     * passer {@code RepartitionDeChargeTest} sur sa javadoc.
     */
    private static String sansCommentaires(String source) {
        StringBuilder sortie = new StringBuilder(source.length());
        boolean dansUneChaine = false;
        boolean dansUnCommentaireDeLigne = false;
        boolean dansUnCommentaireDeBloc = false;

        for (int i = 0; i < source.length(); i++) {
            char courant = source.charAt(i);
            char suivant = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (dansUnCommentaireDeLigne) {
                if (courant == '\n') {
                    dansUnCommentaireDeLigne = false;
                    sortie.append(courant);
                }
                continue;
            }
            if (dansUnCommentaireDeBloc) {
                if (courant == '*' && suivant == '/') {
                    dansUnCommentaireDeBloc = false;
                    i++;
                }
                continue;
            }
            if (dansUneChaine) {
                // Un antislash echappe le caractere suivant : sans ce saut,
                // \" fermerait la chaine et la suite du code serait lue comme
                // une chaine.
                if (courant == '\\') {
                    sortie.append(courant);
                    if (suivant != '\0') {
                        sortie.append(suivant);
                        i++;
                    }
                    continue;
                }
                if (courant == '"') {
                    dansUneChaine = false;
                }
                sortie.append(courant);
                continue;
            }
            if (courant == '"') {
                dansUneChaine = true;
                sortie.append(courant);
                continue;
            }
            if (courant == '/' && suivant == '/') {
                dansUnCommentaireDeLigne = true;
                continue;
            }
            if (courant == '/' && suivant == '*') {
                dansUnCommentaireDeBloc = true;
                i++;
                continue;
            }
            sortie.append(courant);
        }
        return sortie.toString();
    }

    /**
     * Les routes declarees sans {@code nest}, directement sur un chemin complet.
     *
     * <p>Forme {@code .GET("/api/v1/x", request -> proxy...(..., "y-service",
     * ...))}. Elle sert la documentation agregee, dont chaque service n'expose
     * qu'un seul chemin : un {@code nest} avec {@code /**} y ouvrirait un
     * sous-arbre que personne ne sert.
     *
     * <h3>La lacune que cette methode comble</h3>
     *
     * L'extraction ne connaissait que {@code .nest(path("...")}. Les routes
     * declarees en {@code .GET("...")} etaient donc <b>invisibles</b> de ce
     * test : ni verifiees, ni exigees dans {@code CheminCible}. Une route
     * pouvait exister sans que le mode maintenance sache quel service elle
     * atteint — exactement la divergence que ce test est charge d'empecher.
     *
     * <h3>Limitee aux surfaces que {@code CheminCible} decrit</h3>
     *
     * {@code /api/v1} et {@code /dev}. La seconde a ete ajoutee en routant
     * brievement {@code /dev/.well-known/jwks.json} : l'extraction ne
     * connaissait que {@code /api/v1}, elle ignorait donc cette route, et
     * {@code aucunPrefixeFantome} accusait un prefixe pourtant servi. Un
     * garde-fou qui se trompe de sens est aussi nuisible qu'un garde-fou
     * aveugle — il fait corriger du code correct.
     *
     * <p>Cette route a ete retiree depuis, le chemin canonique suffisant. La
     * surface {@code /dev} reste reconnue ici pour que la prochaine route de ce
     * genre ne soit pas invisible : c'est le cout d'une ligne, contre un angle
     * mort qui vient d'en couter six.
     * {@code /.well-known/jwks.json} reste donc hors champ de la comparaison.
     *
     * <p>Ce javadoc affirmait que cette route pointe sur <b>auth-service</b>,
     * et presentait la chose comme une question ouverte. Elle ne l'est plus :
     * la route a ete corrigee vers interface-service, seul detenteur des cles
     * privees, et {@code CheminCible} ramene le chemin standard sur
     * {@code /dev/.well-known/jwks.json}, celui que le controleur sert
     * reellement. Le commentaire decrivait un defaut deja ferme — et une
     * documentation qui signale un faux probleme envoie chercher au mauvais
     * endroit, ce qui coute plus cher qu'un silence.
     */

    private static Map<String, String> routesDirectes(String source) {
        Map<String, String> declare = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "\\.(?:GET|POST|PUT|DELETE)\\(\"((?:/api/v1|/dev)/[^\"]+)\""
                        + "[^;]*?\"([a-z-]+-service)\"")
                .matcher(source);
        while (m.find()) {
            declare.put(m.group(1).replace("/**", ""), m.group(2));
        }
        return declare;
    }

    /**
     * Lit la correspondance telle que le routage la declare.
     *
     * <p>Chaque {@code .nest(path("X"), ...)} est suivi, dans son bloc, du nom du
     * service proxifie. Le bloc est borne a ce qui precede le {@code .nest}
     * suivant, pour qu'un prefixe sans proxy ne prenne pas le service du
     * suivant.
     *
     * <p>Les routes declarees sans {@code nest} sont lues par
     * {@link #routesDirectes(String)}.
     */
    private static Map<String, String> routageDeclare() throws IOException {
        String source = sansCommentaires(Files.readString(CONFIG_DES_ROUTES));

        // LE YAML N'EST PLUS LU COMME UNE SOURCE DE ROUTAGE.
        //
        // Il ne l'a jamais ete en verite : gateway-service ne declare pas
        // spring-cloud-starter-gateway, donc aucun moteur ne lisait ses routes.
        // Elles etaient en outre indentees sous `geolocation:`. Ce test les
        // comptait pourtant comme reelles, en cherchant des chaines ligne par
        // ligne — une lecture de TEXTE ne peut voir ni l'indentation d'une
        // clef, ni l'absence du moteur qui la lirait.
        //
        // Consequence : six prefixes declares par CheminCible et servis par
        // aucune route passaient pour routes. Cinq ont rejoint le
        // RouterFunction ; le sixieme est dans PrefixesSansRoutage.
        //
        // Le YAML est desormais verifie par aucuneRouteEnYaml() : il ne doit
        // plus declarer de route du tout.
        Map<String, String> declare = new LinkedHashMap<>(routesDirectes(source));
        String[] blocs = source.split("\\.nest\\(path\\(\"");

        for (int i = 1; i < blocs.length; i++) {
            String bloc = blocs[i];
            String prefixe = bloc.substring(0, bloc.indexOf('"'));
            Matcher service = Pattern.compile("\"([a-z-]+-service)\"").matcher(bloc);
            if (service.find()) {
                // Le suffixe /** n'appartient pas au prefixe : CheminCible
                // compare des segments, pas des motifs.
                declare.put(prefixe.replace("/**", ""), service.group(1));
            }
        }
        return declare;
    }

    @Test
    @DisplayName("tout prefixe route est connu de CheminCible, et vers le meme service")
    void toutPrefixeRouteEstConnu() throws IOException {
        Map<String, String> declare = routageDeclare();
        Map<String, String> connu = CheminCible.servicesParPrefixe();

        assertThat(declare)
                .withFailMessage("aucune route n'a ete lue dans GatewayRoutesConfig : "
                        + "la forme des declarations a change et ce test ne protege "
                        + "plus rien")
                .isNotEmpty();

        List<String> desaccords = new ArrayList<>();
        for (Map.Entry<String, String> route : declare.entrySet()) {
            String attendu = route.getValue();
            String obtenu = connu.get(route.getKey());
            if (obtenu == null) {
                desaccords.add(route.getKey() + " route vers " + attendu
                        + " mais est inconnu de CheminCible");
            } else if (!obtenu.equals(attendu)) {
                desaccords.add(route.getKey() + " route vers " + attendu
                        + " mais CheminCible dit " + obtenu);
            }
        }

        assertThat(desaccords)
                .withFailMessage("""
                        Le routage et CheminCible ne s'accordent pas :

                        %s

                        Consequence : le mode maintenance ne couperait pas ces \
                        chemins, ou couperait le mauvais service. Sans erreur et \
                        sans journal — une maintenance activee qui laisse passer \
                        est exactement le defaut que ce filtre corrige.""",
                        String.join("\n", desaccords))
                .isEmpty();
    }

    @Test
    @DisplayName("CheminCible ne declare aucun prefixe qui ne soit route")
    void aucunPrefixeFantome() throws IOException {
        Map<String, String> declare = routageDeclare();

        List<String> fantomes = CheminCible.servicesParPrefixe().keySet().stream()
                .filter(prefixe -> !declare.containsKey(prefixe))
                // Les exceptions assumees, motivees dans PrefixesSansRoutage.
                .filter(prefixe -> !PrefixesSansRoutage.contient(prefixe))
                .toList();

        assertThat(fantomes)
                .withFailMessage("""
                        CheminCible connait des prefixes que le routage ne declare \
                        pas : %s

                        Un prefixe fantome fait croire qu'une maintenance protege un \
                        chemin qui n'existe pas, et masque le fait que le vrai chemin \
                        n'est pas couvert.""", fantomes)
                .isEmpty();
    }

    /**
     * Le fichier de configuration ne declare plus AUCUNE route.
     *
     * <h2>Ce que ce test empeche de revenir</h2>
     *
     * {@code config-repo/dev/gateway-service-dev.yml} portait onze routes
     * {@code spring.cloud.gateway}, avec predicats, retraits de prefixe,
     * coupe-circuits et chemins de repli. Aucune n'a jamais rien route :
     * gateway-service ne declare pas {@code spring-cloud-starter-gateway}, et
     * le bloc etait de surcroit indente sous la clef {@code geolocation:}.
     *
     * <p>Six prefixes n'existaient que la : la boite de reception de
     * notification-service et ses endpoints d'appareil — donc les
     * notifications push mobiles —, la surface developpeur d'interface-service,
     * ses deux chemins {@code /dev}, et le flux temps reel.
     *
     * <p>Ce test refuse qu'une route y soit reecrite. Elle serait inerte, et
     * surtout elle redonnerait a croire qu'un chemin est servi.
     *
     * <h2>Pourquoi les motifs ne sont pas cites en clair</h2>
     *
     * Ils sont assembles a l'execution. Ecrire {@code "uri: lb://"} dans ce
     * fichier de test y mettrait la chaine cherchee — et ce depot s'est deja
     * fait prendre deux fois par un garde-fou que sa propre documentation
     * satisfaisait : un commentaire de POM qui contenait le nom de l'artefact
     * exige, et une javadoc ou le test trouvait sa correspondance.
     */
    @Test
    @DisplayName("la configuration YAML ne declare aucune route")
    void aucuneRouteEnYaml() throws IOException {
        assertThat(ROUTES_YAML)
                .withFailMessage("%s est introuvable : ce test ne verifie rien",
                        ROUTES_YAML)
                .isRegularFile();

        String motifDeChemin = "Path" + "=";
        String adresseEquilibree = "uri:" + " lb" + "://";

        List<String> declarations = Files.readAllLines(ROUTES_YAML).stream()
                .map(String::trim)
                .filter(ligne -> !ligne.startsWith("#"))
                .filter(ligne -> ligne.contains(motifDeChemin)
                        || ligne.contains(adresseEquilibree))
                .toList();

        assertThat(declarations)
                .withFailMessage("""
                        Des routes ont ete declarees dans %s :

                        %s

                        Elles seraient INERTES : gateway-service ne declare pas \
                        spring-cloud-starter-gateway, donc aucun moteur ne les \
                        lirait. Onze routes y ont vecu ainsi, et six prefixes \
                        n'existaient que la — dont la boite de reception de \
                        notification-service et ses endpoints d'appareil.

                        Une route de cette passerelle s'ecrit dans \
                        GatewayRoutesConfig.""",
                        ROUTES_YAML, String.join("\n", declarations))
                .isEmpty();
    }

    /**
     * Les exceptions assumees sont exactement celles qui le sont.
     *
     * <h2>Pourquoi pincer cette liste</h2>
     *
     * {@link PrefixesSansRoutage} est la porte de sortie de
     * {@link #aucunPrefixeFantome()}. Une porte de sortie sans serrure finit
     * par accueillir tout ce qui derange : il suffirait d'y inscrire un
     * prefixe pour faire taire le test qui signale qu'il n'est pas route.
     *
     * <p>Ce test exige donc que chaque entree soit vraiment un prefixe connu de
     * {@code CheminCible}, qu'elle soit vraiment non routee — une entree
     * devenue inutile est un mensonge au meme titre — et il fige le nombre.
     * Grandir demande de modifier ce test, donc de le decider.
     */
    @Test
    @DisplayName("PrefixesSansRoutage ne contient que des exceptions reelles")
    void exceptionsReellesEtPincees() throws IOException {
        Map<String, String> declare = routageDeclare();
        Map<String, String> connu = CheminCible.servicesParPrefixe();

        assertThat(PrefixesSansRoutage.motifs())
                .withFailMessage("""
                        La liste des prefixes non routes a change de taille.

                        Elle ne doit contenir que le flux WebSocket, que \
                        WebClient ne sait pas proxifier. Tout autre prefixe non \
                        route est un defaut a corriger dans \
                        GatewayRoutesConfig, pas une exception a inscrire.""")
                .hasSize(1);

        List<String> incoherentes = new ArrayList<>();
        PrefixesSansRoutage.motifs().forEach((prefixe, motif) -> {
            if (!connu.containsKey(prefixe)) {
                incoherentes.add(prefixe + " est inconnu de CheminCible : "
                        + "une exception a un prefixe qui n'existe pas");
            }
            if (declare.containsKey(prefixe)) {
                incoherentes.add(prefixe + " EST route par GatewayRoutesConfig : "
                        + "l'exception n'a plus de raison d'etre, la retirer");
            }
            if (motif == null || motif.isBlank()) {
                incoherentes.add(prefixe + " n'a pas de motif");
            }
        });

        assertThat(incoherentes)
                .withFailMessage("%s", String.join("\n", incoherentes))
                .isEmpty();
    }

    /**
     * L'ordre compte, et le test le verifie.
     *
     * <p>{@code /api/v1/admin} capturerait {@code /api/v1/admin/documents} s'il
     * etait teste d'abord : couper media-service ne couperait alors pas ses
     * documents, et couper interface-service couperait les leurs.
     */
    @Test
    @DisplayName("un prefixe plus specifique precede toujours le plus general")
    void prefixesSpecifiquesEnPremier() {
        List<String> ordre = new ArrayList<>(CheminCible.servicesParPrefixe().keySet());

        List<String> mal = new ArrayList<>();
        for (int i = 0; i < ordre.size(); i++) {
            for (int j = i + 1; j < ordre.size(); j++) {
                String avant = ordre.get(i);
                String apres = ordre.get(j);
                // « avant » est un prefixe de « apres » : il le capturera, donc
                // « apres » ne sera jamais atteint.
                if (apres.startsWith(avant + "/")) {
                    mal.add(avant + " precede " + apres + ", qu'il capturera");
                }
            }
        }

        assertThat(mal)
                .withFailMessage("""
                        Un prefixe general precede un prefixe plus specifique : %s

                        Le plus general gagne, puisque la premiere correspondance \
                        l'emporte. Le chemin specifique serait rattache au mauvais \
                        service, et une maintenance couperait autre chose que ce \
                        qu'elle annonce.""", mal)
                .isEmpty();
    }
}
