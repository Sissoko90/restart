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
 * UN SEUL module sait parler SMTP.
 *
 * <h2>L'invariant, et ce qu'il a coute d'etablir</h2>
 *
 * notification-service est le seul expediteur de courriel de la plateforme.
 * Tous les autres passent par son endpoint {@code POST /internal/message}.
 *
 * <p>Ce n'etait pas le cas. Trois modules composaient et envoyaient des
 * courriels :
 *
 * <ul>
 *   <li><b>auth-service</b>, avec son propre {@code EmailServiceImpl} et son
 *       moteur de modeles en base — retire, table {@code message_templates}
 *       archivee par sa migration V9. Deux moteurs de modeles portaient les
 *       memes textes, et les deux DIVERGEAIENT deja ;</li>
 *   <li><b>interface-service</b>, avec TROIS {@code JavaMailSender} declares a
 *       la main, deux gabarits HTML et un logo integre. Son prenom etait insere
 *       sans echappement, ses gabarits etaient en anglais quand les modeles
 *       texte sont en francais, et deux de ses boutons pointaient sur
 *       {@code href="#"} ;</li>
 *   <li><b>notification-service</b>, celui qui reste.</li>
 * </ul>
 *
 * <h2>Pourquoi un garde-fou plutot qu'une note</h2>
 *
 * Le piege est a la portee d'un seul geste : {@code MailSenderAutoConfiguration}
 * porte {@code @ConditionalOnMissingBean(MailSender.class)}, donc la PRESENCE
 * d'un {@code JavaMailSender} fait reculer l'auto-configuration entiere. Dans
 * interface-service, cela a rendu {@code spring.mail.*} — trois vraies clefs
 * SMTP — totalement inerte, sans aucun signal.
 *
 * <p>Et un second expediteur revient naturellement : il suffit qu'un module ait
 * besoin d'envoyer un courriel et que la dependance soit a portee. Ce test rend
 * ce geste visible au moment ou il est fait, pas au moment ou deux textes ont
 * diverge.
 *
 * <h2>Ce qui est verifie</h2>
 *
 * Deux faits, et le second seul ne suffirait pas :
 *
 * <ul>
 *   <li>aucun module sauf notification-service ne declare
 *       {@code spring-boot-starter-mail} ;</li>
 *   <li>aucun module sauf notification-service ne mentionne
 *       {@code JavaMailSender} dans ses sources principales — car la dependance
 *       peut arriver transitivement.</li>
 * </ul>
 */
@DisplayName("Courriel — un seul module sait parler SMTP")
class UnSeulExpediteurDeCourrielTest {

    /** Le seul module autorise a envoyer. */
    private static final String EXPEDITEUR = "notification-service";

    private static final String STARTER = "spring-boot-starter-mail";

    /** Le type dont la seule presence fait reculer l'auto-configuration. */
    private static final String TYPE_EXPEDITEUR = "JavaMailSender";

    /**
     * Les commentaires XML sont retires avant la recherche.
     *
     * <p>Les POM de ce depot EXPLIQUENT les retraits, donc ils citent
     * {@code spring-boot-starter-mail} dans un commentaire. Sans ce retrait, ce
     * test echouerait sur la documentation du retrait qu'il verifie — ce depot
     * s'est deja fait prendre deux fois ainsi.
     */
    private static String sansCommentairesXml(String pom) {
        return pom.replaceAll("(?s)<!--.*?-->", " ");
    }

    @Test
    @DisplayName("seul notification-service declare le starter mail")
    void unSeulStarter() throws IOException {
        List<String> declarants = new ArrayList<>();
        for (Path module : modules()) {
            String nom = module.getFileName().toString();
            String pom = sansCommentairesXml(Files.readString(module.resolve("pom.xml")));
            if (pom.contains("<artifactId>" + STARTER + "</artifactId>")) {
                declarants.add(nom);
            }
        }

        assertThat(declarants)
                .withFailMessage("""
                        Modules declarant %s : %s

                        Un seul est attendu : %s. Tous les autres passent par son \
                        endpoint POST /internal/message.

                        Un second expediteur n'ajoute pas une capacite, il ajoute \
                        un endroit ou la configuration d'expedition peut etre \
                        fausse — et l'autoconfiguration de Spring recule des \
                        qu'un JavaMailSender existe, ce qui rend spring.mail.* \
                        inerte SANS AUCUN SIGNAL.""",
                        STARTER, declarants, EXPEDITEUR)
                .containsExactly(EXPEDITEUR);
    }

    @Test
    @DisplayName("seul notification-service manipule un JavaMailSender")
    void unSeulTypeDExpediteur() throws IOException {
        List<String> fautifs = new ArrayList<>();
        for (Path module : modules()) {
            String nom = module.getFileName().toString();
            if (nom.equals(EXPEDITEUR)) {
                continue;
            }
            Path sources = module.resolve("src/main/java");
            if (!Files.isDirectory(sources)) {
                continue;
            }
            try (Stream<Path> fichiers = Files.walk(sources)) {
                for (Path source : fichiers
                        .filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (sansCommentairesJava(Files.readString(source))
                            .contains(TYPE_EXPEDITEUR)) {
                        fautifs.add(nom + " : " + source.getFileName());
                    }
                }
            }
        }

        assertThat(fautifs)
                .withFailMessage("""
                        Ces sources manipulent un %s hors de %s : %s

                        Le starter peut arriver transitivement, donc verifier le \
                        POM ne suffit pas. C'est le TYPE qui fait reculer \
                        l'auto-configuration, et c'est lui qui trahit un second \
                        expediteur.""", TYPE_EXPEDITEUR, EXPEDITEUR, fautifs)
                .isEmpty();
    }

    /**
     * Retire les commentaires Java SANS toucher aux chaines.
     *
     * <h3>Pourquoi, et ce que son absence a produit</h3>
     *
     * La premiere version de ce test cherchait {@code JavaMailSender} dans le
     * texte brut des sources. Il a echoue sur
     * {@code ClientMessagerieNotification}, dont le javadoc EXPLIQUE que ce
     * client remplace un {@code JavaMailSender} — la documentation du retrait
     * faisait echouer le test qui verifie le retrait.
     *
     * <p>C'est la troisieme fois dans ce depot qu'un garde-fou lit du TEXTE la
     * ou il doit lire une STRUCTURE : un commentaire de POM qui contenait
     * l'artefact exige, un test qui comptait des routes YAML sans voir leur
     * indentation, et celui-ci.
     *
     * <h3>Un automate et non une expression reguliere</h3>
     *
     * {@code /*} apparait dans les litteraux de motif de chemin — ce depot
     * ecrit {@code path("/api/v1/x/&#42;&#42;")} — et une regex avale alors
     * tout le code jusqu'au prochain {@code *}{@code /}. Ce defaut exact a
     * rendu {@code CouvertureDesCheminsAdminTest} rouge sur du code correct.
     */
    private static String sansCommentairesJava(String source) {
        StringBuilder sortie = new StringBuilder(source.length());
        boolean chaine = false;
        boolean ligne = false;
        boolean bloc = false;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            String deux = i + 1 < source.length() ? source.substring(i, i + 2) : "";
            if (ligne) {
                if (c == '\n') {
                    ligne = false;
                    sortie.append(c);
                }
            } else if (bloc) {
                if (deux.equals("*/")) {
                    bloc = false;
                    i += 2;
                    continue;
                }
            } else if (chaine) {
                sortie.append(c);
                if (c == '\\' && i + 1 < source.length()) {
                    sortie.append(source.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    chaine = false;
                }
            } else {
                if (deux.equals("//")) {
                    ligne = true;
                    i += 2;
                    continue;
                }
                if (deux.equals("/*")) {
                    bloc = true;
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    chaine = true;
                }
                sortie.append(c);
            }
            i++;
        }
        return sortie.toString();
    }

    /** Le garde-fou se garde : sans modules trouves, il ne verifie rien. */
    @Test
    @DisplayName("le balayage trouve bien les modules")
    void leBalayageFonctionne() throws IOException {
        assertThat(modules())
                .withFailMessage("aucun module trouve depuis %s : le balayage est "
                        + "casse et ce test ne protege plus rien", racineDuDepot())
                .hasSizeGreaterThan(8);
    }

    private static List<Path> modules() throws IOException {
        try (Stream<Path> contenu = Files.list(racineDuDepot())) {
            return contenu.filter(Files::isDirectory)
                    .filter(dossier -> Files.exists(dossier.resolve("pom.xml")))
                    .sorted()
                    .toList();
        }
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
