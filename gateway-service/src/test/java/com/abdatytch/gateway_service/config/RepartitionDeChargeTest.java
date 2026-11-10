package com.abdatytch.gateway_service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le Gateway repartit la charge entre les instances d'un service.
 *
 * <h2>Le defaut que ce garde-fou ferme</h2>
 *
 * La proxification resolvait l'instance aval par
 * {@code discoveryClient.getInstances(nom).next()} — la <b>premiere</b> que
 * Consul renvoie. Avec plusieurs instances d'un service, <b>une seule recevait
 * tout le trafic</b> ; les autres restaient inactives tout en etant saines et
 * enregistrees.
 *
 * <h2>Pourquoi personne ne l'avait vu</h2>
 *
 * Les routes declarees en YAML utilisent {@code uri: lb://...}, donc un
 * equilibrage correct. En lisant la configuration, on concluait que le Gateway
 * repartissait.
 *
 * <p>Mais ce {@code RouterFunction} a l'ordre <b>-1</b>
 * ({@code RouterFunctionMapping}) et Spring Cloud Gateway l'ordre <b>1</b>
 * ({@code RoutePredicateHandlerMapping}). Pour les douze groupes de chemins
 * declares en Java — auth, admin, media, quotas, settings — c'est le code qui
 * tranche, et les {@code lb://} correspondants ne sont jamais atteints.
 *
 * <p>Deux mecanismes de routage dont l'un masque l'autre, et une garantie lue
 * dans celui qui ne s'applique pas : c'est la meme forme que les annotations
 * Resilience4j inertes, ou que la liste noire d'adresses que personne
 * n'alimentait.
 *
 * <h2>Pourquoi un garde-fou de source</h2>
 *
 * Verifier la repartition par le comportement demanderait deux instances reelles
 * d'un service enregistrees dans un Consul reel. Ce que l'on peut verifier sans
 * cela, et qui suffit a empecher la regression, c'est que le choix de l'instance
 * passe par l'equilibreur et <b>non</b> par {@code getInstances(...).next()}.
 */
@DisplayName("Gateway — la charge est repartie entre les instances")
class RepartitionDeChargeTest {

    /**
     * Le choix d'instance, qui a QUITTE GatewayRoutesConfig.
     *
     * <h3>Pourquoi ce chemin a change</h3>
     *
     * La logique etait une methode privee de {@code GatewayRoutesConfig}. La
     * passerelle a desormais deux chemins qui proxifient — ce
     * {@code RouterFunction} pour l'HTTP, et {@code RelaisWebSocket} pour la
     * montee en protocole, que {@code WebClient} ne sait pas faire — et deux
     * copies auraient donne deux politiques d'equilibrage pour une seule
     * passerelle.
     *
     * <p>Ce test a correctement echoue au deplacement : il ne trouvait plus ce
     * qu'il garde. C'est exactement ce qu'un garde-fou doit faire quand son
     * sujet bouge — se taire aurait ete le defaut.
     */
    private static final Path SOURCE = Path.of(
            "src/main/java/com/abdatytch/gateway_service/routing/ChoixDInstance.java");

    /** Le motif exact du defaut : prendre la premiere instance venue. */
    private static final Pattern PREMIERE_INSTANCE = Pattern.compile(
            "getInstances\\s*\\([^)]*\\)\\s*\\n?\\s*\\.next\\s*\\(\\s*\\)");

    // ==================================================================
    @Nested
    @DisplayName("Le choix de l'instance")
    class ChoixDeLInstance {

        @Test
        @DisplayName("passe par l'equilibreur de charge")
        void passeParLEquilibreur() throws IOException {
            String contenu = sourceDesRoutes();

            assertThat(contenu)
                    .withFailMessage("""
                            La proxification ne passe pas par un equilibreur.

                            Avec plusieurs instances d'un service, une seule recevra tout \
                            le trafic. Les routes YAML en lb:// ne corrigent pas cela : ce \
                            RouterFunction a l'ordre -1 et les masque.""")
                    .contains("LoadBalancerClientFactory")
                    .contains(".choose()");
        }

        @Test
        @DisplayName("n'utilise plus getInstances(...).next() sur le chemin principal")
        void plusDePremiereInstance() throws IOException {
            String contenu = sourceDesRoutes();

            // `getInstances(...).next()` subsiste dans le REPLI, pour le cas ou
            // aucun equilibreur n'existe pour un service. Une seule occurrence
            // est donc attendue ; deux signifieraient que le chemin principal
            // l'utilise encore.
            List<String> occurrences = new ArrayList<>();
            // Commentaires NEUTRALISES avant comptage : la javadoc de
            // ChoixDInstance cite le defaut corrige, et sans cela ce test
            // echouerait sur sa propre documentation. Ce depot a deja commis
            // cette erreur avec un garde-fou de journalisation.
            var trouve = PREMIERE_INSTANCE.matcher(neutraliserCommentaires(contenu));
            while (trouve.find()) {
                occurrences.add(trouve.group().replaceAll("\\s+", " "));
            }

            assertThat(occurrences)
                    .withFailMessage("""
                            `getInstances(...).next()` apparait %d fois.

                            Une seule occurrence est admise : le repli journalise, pour le \
                            cas ou aucun equilibreur n'est configure. Deux ou plus \
                            signifient que le chemin principal prend encore la premiere \
                            instance venue, donc qu'aucune charge n'est repartie.

                            Occurrences : %s""", occurrences.size(), occurrences)
                    .hasSizeLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("une absence d'instance est une ERREUR, pas un Mono vide")
        void absenceDInstanceEstUneErreur() throws IOException {
            // Un Mono vide ferait se terminer la chaine sans reponse : le client
            // attendrait son propre delai d'expiration, et le journal ne
            // designerait rien. Ce depot a corrige cette confusion une dizaine
            // de fois.
            assertThat(sourceDesRoutes())
                    .withFailMessage("l'absence d'instance n'est pas traitee : une "
                            + "chaine qui se termine vide laisse le client attendre "
                            + "sans qu'aucun journal ne designe la cause")
                    .contains("Aucune instance disponible");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le repli reste visible")
    class RepliVisible {

        /**
         * Un repli silencieux sur « premiere instance » serait le defaut
         * d'origine, revenu sans bruit.
         */
        @Test
        @DisplayName("le repli sans equilibreur est journalise en avertissement")
        void repliJournalise() throws IOException {
            String contenu = sourceDesRoutes();

            assertThat(contenu)
                    .withFailMessage("le repli n'est pas journalise : un retour au "
                            + "comportement « premiere instance » passerait inaperçu")
                    .contains("log.warn")
                    .contains("n'est PAS reparti");
        }
    }

    /**
     * Neutralise commentaires et javadoc, en preservant les positions de ligne.
     *
     * <p>Sans cela, un garde-fou de source echoue sur le texte qui explique le
     * defaut qu'il interdit — et la reaction naturelle est alors de retirer
     * l'explication plutot que de corriger le test.
     */
    private static String neutraliserCommentaires(String source) {
        StringBuilder sortie = new StringBuilder(source.length());
        boolean dansBloc = false;
        boolean dansLigne = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char suivant = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (c == '\n') {
                dansLigne = false;
                sortie.append(c);
                continue;
            }
            if (!dansBloc && !dansLigne && c == '/' && suivant == '*') {
                dansBloc = true;
            } else if (!dansBloc && !dansLigne && c == '/' && suivant == '/') {
                dansLigne = true;
            }
            if (dansBloc && c == '*' && suivant == '/') {
                dansBloc = false;
                sortie.append("  ");
                i++;
                continue;
            }
            sortie.append(dansBloc || dansLigne ? ' ' : c);
        }
        return sortie.toString();
    }

    private static String sourceDesRoutes() throws IOException {
        assertThat(SOURCE)
                .withFailMessage("source introuvable : ce garde-fou serait vert sans "
                        + "rien verifier")
                .exists();
        return Files.readString(SOURCE);
    }
}
