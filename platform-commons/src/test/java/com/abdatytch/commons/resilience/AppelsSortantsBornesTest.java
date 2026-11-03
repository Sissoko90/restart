package com.abdatytch.commons.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tout appel sortant est borne dans le temps.
 *
 * <h2>Le defaut que ce garde-fou ferme, trouve deux fois</h2>
 *
 * <ol>
 *   <li>{@code InterfaceServiceClient} avait <b>trois</b> appels et <b>zero</b>
 *       delai, alors que les trois autres clients WebClient du module portaient
 *       bien le leur. Or interface-service est l'<b>autorite JWT</b> : il signe
 *       les jetons de toute la plateforme et se trouve sur le chemin de chaque
 *       connexion ;</li>
 *   <li><b>aucun delai Feign n'etait configure</b>, donc les defauts
 *       s'appliquaient : 10 s a la connexion et <b>60 s a la lecture</b>.
 *       {@code UserServiceClient} est appele par {@code OAuth2ServiceImpl},
 *       donc sur le chemin de connexion : une instance de user-service figee
 *       retenait une connexion OAuth2 pendant une minute.</li>
 * </ol>
 *
 * <p>Et le meme trou existait dans <b>user-service</b> : son
 * {@code AuthServiceClient} Feign n'avait aucun delai configure non plus, et il
 * est appele depuis {@code UserMapper}, donc sur le chemin de chaque reponse
 * utilisateur. Trouve en posant la question a un autre module, ce qui est
 * exactement la raison de generaliser ce garde-fou plutot que de le laisser
 * dans auth-service.
 *
 * <h2>Pourquoi un test et non une relecture</h2>
 *
 * Les deux trous ont ete trouves par la meme question — « cet appel sortant
 * est-il borne ? » — posee une fois, a la main. Elle doit etre posee a
 * <b>chaque</b> transport et a chaque ajout, et c'est exactement ce qu'un
 * humain oublie : le client qu'on ajoute ressemble aux autres, et on suppose
 * qu'il herite de leurs garanties.
 *
 * <p>Une dependance <b>figee</b> — pas tombee, figee — est le cas le plus
 * courant et le plus traitre : sans delai, elle retient indefiniment la chaine
 * reactive appelante. Les requetes s'accumulent sans aboutir ni echouer, et
 * rien ne distingue cet etat d'une lenteur passagere.
 *
 * <h2>Et le disjoncteur ne remplace pas le delai</h2>
 *
 * Il en depend : sans delai, un appel fige ne se termine jamais, donc le
 * disjoncteur n'a aucun echec a compter et ne s'ouvre jamais. Le delai borne
 * chaque appel ; le disjoncteur evite de les payer tous.
 */
@DisplayName("Appels sortants — chacun doit etre borne, dans tous les modules")
class AppelsSortantsBornesTest {

    /** Un appel WebClient : {@code webClient.get()}, {@code .post()}, etc. */
    private static final Pattern APPEL_WEBCLIENT = Pattern.compile(
            "\\b\\w*[wW]ebClient\\s*\\.?\\s*\\n?\\s*\\.(get|post|put|patch|delete|method)\\s*\\(");

    /**
     * Fichiers dispenses de delai, avec leur raison.
     *
     * <p>Liste <b>fermee</b> : y ajouter un fichier demande de justifier
     * pourquoi son appel ne peut pas rester en attente indefiniment.
     */
    private static final Set<String> DISPENSES = Set.of(
            // Le SDK Twilio est bloquant et porte ses propres delais de
            // configuration ; l'appel est isole sur boundedElastic. Il n'expose
            // pas de point ou poser un .timeout(...) reactif.
            "TwilioSmsProvider.java");

    /**
     * Plancher de detection.
     *
     * <p>Si le balayage cesse de trouver des appels sortants, il rendrait zero
     * fichier et le test passerait sans rien verifier. Ce depot s'est deja fait
     * prendre plusieurs fois par un harnais vert qui ne mesurait plus rien.
     */
    private static final int PLANCHER_CLIENTS_EXAMINES = 4;

    // ==================================================================
    @Nested
    @DisplayName("WebClient")
    class AppelsWebClient {

        @Test
        @DisplayName("chaque fichier qui appelle WebClient pose un delai maximal")
        void chaqueAppelEstBorne() throws IOException {
            List<Path> avecAppel = new ArrayList<>();
            List<String> sansDelai = new ArrayList<>();

            for (Path source : sourcesPrincipales()) {
                String contenu = Files.readString(source);
                if (!APPEL_WEBCLIENT.matcher(contenu).find()) {
                    continue;
                }
                avecAppel.add(source);
                String nom = source.getFileName().toString();
                if (DISPENSES.contains(nom)) {
                    continue;
                }
                if (!contenu.contains(".timeout(")) {
                    sansDelai.add(nom);
                }
            }

            assertThat(avecAppel)
                    .withFailMessage("aucun appel WebClient trouve : l'analyse est "
                            + "cassee, et ce garde-fou ne verifie plus rien")
                    .hasSizeGreaterThanOrEqualTo(PLANCHER_CLIENTS_EXAMINES);

            assertThat(sansDelai)
                    .withFailMessage("""
                            Ces fichiers appellent WebClient sans delai maximal : %s

                            Une dependance FIGEE — pas tombee, figee — retiendra \
                            indefiniment la chaine reactive appelante. Les requetes \
                            s'accumuleront sans aboutir ni echouer, et rien ne \
                            distinguera cet etat d'une lenteur passagere.

                            Poser un .timeout(duree) dont la valeur vient de la \
                            configuration, puis traduire l'expiration en un code nomme \
                            UNAVAILABLE — ce qui rend 503 et permet au disjoncteur de \
                            compter la panne.""", sansDelai)
                    .isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Feign")
    class AppelsFeign {

        /**
         * Feign n'expose aucun point ou poser un {@code .timeout(...)} : son
         * delai vit <b>uniquement</b> dans la configuration. Un client Feign
         * sans configuration de delai est donc borne a 60 s en lecture, et rien
         * dans le code ne le laisse voir.
         */
        @Test
        @DisplayName("tout module ayant un client Feign configure ses delais")
        void delaisFeignConfigures() throws IOException {
            java.util.Map<String, List<String>> parModule = new java.util.LinkedHashMap<>();
            for (Path source : sourcesPrincipales()) {
                if (Files.readString(source).contains("@FeignClient")) {
                    parModule.computeIfAbsent(module(source), m -> new ArrayList<>())
                            .add(source.getFileName().toString());
                }
            }

            List<String> sansDelai = new ArrayList<>();
            for (var entree : parModule.entrySet()) {
                Path configuration = racineDuDepot()
                        .resolve("config-repo/dev/" + entree.getKey() + "-dev.yml");
                if (!Files.exists(configuration)) {
                    sansDelai.add(entree.getKey() + " (configuration introuvable)");
                    continue;
                }
                String contenu = Files.readString(configuration);
                if (!contenu.contains("openfeign:")
                        || !contenu.contains("readTimeout")
                        || !contenu.contains("connectTimeout")) {
                    sansDelai.add(entree.getKey() + " " + entree.getValue());
                }
            }

            assertThat(sansDelai)
                    .withFailMessage("""
                            Ces modules ont des clients Feign sans delai configure : %s

                            Les defauts de Feign s'appliquent alors : 10 s a la connexion \
                            et 60 s a la LECTURE.

                            Attendu sous spring.cloud.openfeign.client.config.default — \
                            prefixe de spring-cloud-openfeign 5.x, verifie dans les \
                            metadonnees du jar. L'ancien `feign.client.config` ne serait \
                            PAS lu, et silencieusement.""", sansDelai)
                    .isEmpty();
        }
    }

    // ==================================================================

    /** Les sources de production de TOUS les modules. */
    private static List<Path> sourcesPrincipales() throws IOException {
        try (Stream<Path> fichiers = Files.walk(racineDuDepot())) {
            return fichiers
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .sorted()
                    .toList();
        }
    }

    /** {@code module} d'un chemin de source, pour localiser sa configuration. */
    private static String module(Path source) {
        return racineDuDepot().relativize(source).getName(0).toString();
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
