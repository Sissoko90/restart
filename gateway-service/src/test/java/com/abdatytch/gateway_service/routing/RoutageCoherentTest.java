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
     * <h3>Limitee a la surface {@code /api/v1}, deliberement</h3>
     *
     * {@code CheminCible.servicesParPrefixe()} ne decrit que cette surface.
     * {@code /.well-known/jwks.json} reste donc hors champ, et c'est a signaler
     * plutot qu'a masquer : cette route pointe sur <b>auth-service</b>, alors
     * que le seul detenteur du JWKS est
     * {@code interface-service.DevKeyManagementController}. Question ouverte et
     * distincte — la corriger deplacerait la decouverte des cles de signature
     * de toute la plateforme.
     */
    /**
     * Les routes declarees en YAML : {@code Path=...} et {@code uri: lb://...}.
     *
     * <p>Lecture ligne a ligne et non par expression reguliere sur tout le
     * fichier : les deux informations sont dans des cles distinctes d'un meme
     * bloc, et un motif global apparierait le {@code Path} d'une route avec
     * l'{@code uri} de la suivante.
     *
     * <p>Les lignes de commentaire sont ignorees : ce fichier explique
     * longuement pourquoi certaines routes vivent ici et d'autres dans le
     * {@code RouterFunction}, et ces explications citent forcement des chemins.
     * Un garde-fou qui interdit de documenter ce qu'il garde finit par etre
     * desactive — ce depot l'a appris trois fois.
     */
    private static Map<String, String> routesYaml() throws IOException {
        Map<String, String> declare = new LinkedHashMap<>();
        if (!Files.isRegularFile(ROUTES_YAML)) {
            return declare;
        }

        java.util.Set<String> duReacteur = modulesDuReacteur();

        String prefixes = null;
        String service = null;
        for (String ligne : Files.readAllLines(ROUTES_YAML)) {
            String nue = ligne.trim();
            if (nue.startsWith("#")) {
                continue;
            }
            // Un nouveau bloc de route remet les deux a zero : sans cela, une
            // route sans Path heriterait de celui de la precedente.
            if (nue.startsWith("- id:")) {
                prefixes = null;
                service = null;
            }
            if (nue.startsWith("- Path=")) {
                prefixes = nue.substring("- Path=".length()).trim();
            }
            if (nue.startsWith("uri: lb://")) {
                service = nue.substring("uri: lb://".length()).trim();
            }
            if (prefixes != null && service != null) {
                // SEULS les services du reacteur sont retenus.
                //
                // Le YAML declare aussi des routes vers tenant-service — dont
                // AUCUN dossier n'existe dans ce depot — et vers geo-service et
                // payment-service, qui sont encore des squelettes hors
                // reacteur. Exiger que CheminCible les connaisse reviendrait a
                // lui faire declarer des services qui ne peuvent pas repondre,
                // et le mode maintenance couvrirait des chemins vides.
                //
                // Ces routes fantomes sont un defaut A PART : une requete vers
                // /api/v1/tenants/** echoue a la resolution Consul, donc en 503
                // ou en 500, et non en 404 propre.
                if (duReacteur.contains(service)) {
                    // Un predicat peut porter PLUSIEURS chemins separes par des
                    // virgules : Path=/api/v1/developers,/api/v1/projects. Les
                    // traiter comme une seule chaine produirait un prefixe qui
                    // ne correspond a rien.
                    for (String prefixe : prefixes.split(",")) {
                        String propre = prefixe.replace("/**", "").trim();
                        if (!propre.isEmpty()) {
                            declare.put(propre, service);
                        }
                    }
                }
                prefixes = null;
                service = null;
            }
        }
        return declare;
    }

    /** Les modules reellement construits, lus dans le POM racine. */
    private static java.util.Set<String> modulesDuReacteur() throws IOException {
        Path racine = Path.of("../pom.xml");
        if (!Files.isRegularFile(racine)) {
            return java.util.Set.of();
        }
        java.util.Set<String> modules = new java.util.LinkedHashSet<>();
        Matcher m = Pattern.compile("<module>([^<]+)</module>")
                .matcher(Files.readString(racine));
        while (m.find()) {
            modules.add(m.group(1).trim());
        }
        return modules;
    }

    private static Map<String, String> routesDirectes(String source) {
        Map<String, String> declare = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "\\.(?:GET|POST|PUT|DELETE)\\(\"(/api/v1/[^\"]+)\"[^;]*?\"([a-z-]+-service)\"")
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

        Map<String, String> declare = new LinkedHashMap<>(routesDirectes(source));
        declare.putAll(routesYaml());
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
