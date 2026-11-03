package com.abdatytch.interface_service.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.WebFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verrouille la séquence d'exécution des filtres de sécurité.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * {@code IpBlacklistFilter} et {@code RateLimitFilter} décident à partir de
 * l'en-tête {@code X-Client-Ip}, qui porte l'adresse de l'utilisateur final.
 * C'est la bonne source, mais elle n'est digne de confiance <b>que</b> parce que
 * {@code GatewayAccessFilter} a d'abord établi que la requête vient du Gateway —
 * lequel purge et repose cet en-tête.
 *
 * <p>Si l'un de ces deux filtres passait devant le contrôle de provenance, un
 * appelant direct choisirait son adresse en posant l'en-tête lui-même : liste
 * noire contournée en changeant de valeur, plafond de débit contourné en la
 * faisant tourner.
 *
 * <h2>Ce qui était fragile</h2>
 *
 * Quatre des huit filtres n'avaient aucun {@code @Order}. Spring trie les beans
 * non ordonnés en dernier, donc l'invariant tenait — <b>par accident</b>. Il
 * suffisait d'annoter {@code IpBlacklistFilter} d'un {@code @Order(0)}, geste
 * naturel puisqu'on veut refuser une adresse hostile le plus tôt possible, pour
 * que la liste noire se mette à croire un en-tête non vérifié. Rien n'aurait
 * échoué, rien n'aurait été journalisé.
 */
@DisplayName("Ordre des filtres - la provenance avant ceux qui lui font confiance")
class OrdreDesFiltresTest {

    /** Filtres dont la décision repose sur {@code X-Client-Ip}. */
    private static final List<String> DEPENDENT_DE_LA_PROVENANCE = List.of(
            "IpBlacklistFilter", "RateLimitFilter");

    private record Filtre(String nom, Integer ordre) {

        /**
         * Ordre effectif pour un tri, y compris quand il n'est pas déclaré.
         *
         * <p>Spring trie les beans non ordonnés à {@code LOWEST_PRECEDENCE},
         * donc en dernier. Le représenter ainsi plutôt que par {@code null}
         * évite que les tests de séquence échouent par
         * {@code NullPointerException} : un test qui s'effondre au lieu de
         * donner son message laisse le lecteur devant une pile d'appels alors
         * qu'une phrase suffisait. {@code tousOrdonnes} est le test qui signale
         * l'ordre manquant, et c'est à lui de le faire.
         */
        int ordreEffectif() {
            return ordre == null ? Integer.MAX_VALUE : ordre;
        }
    }

    private static Path racineDesFiltres() {
        Path courant = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && courant != null; i++) {
            Path candidat = courant.resolve(
                    "interface-service/src/main/java/com/abdatytch/interface_service/filter");
            if (Files.isDirectory(candidat)) {
                return candidat;
            }
            candidat = courant.resolve(
                    "src/main/java/com/abdatytch/interface_service/filter");
            if (Files.isDirectory(candidat)) {
                return candidat;
            }
            courant = courant.getParent();
        }
        throw new AssertionError("Paquet des filtres introuvable depuis "
                + Path.of("").toAbsolutePath());
    }

    /**
     * Les filtres déclarés, par réflexion sur les classes compilées.
     *
     * <p>La liste est construite depuis les fichiers du paquet plutôt que d'être
     * écrite à la main : un filtre ajouté demain entre dans ce test sans que
     * personne n'y pense, ce qui est tout l'intérêt.
     */
    private List<Filtre> filtresDeclares() throws IOException {
        List<Filtre> filtres = new ArrayList<>();
        try (Stream<Path> fichiers = Files.list(racineDesFiltres())) {
            for (Path f : fichiers.filter(p -> p.toString().endsWith(".java")).toList()) {
                String nom = f.getFileName().toString().replace(".java", "");
                Class<?> classe;
                try {
                    classe = Class.forName(
                            "com.abdatytch.interface_service.filter." + nom);
                } catch (ClassNotFoundException e) {
                    throw new AssertionError("Classe non compilée : " + nom, e);
                }
                if (!WebFilter.class.isAssignableFrom(classe)) {
                    continue;   // OrdreDesFiltres lui-meme, par exemple
                }
                Order ordre = AnnotationUtils.findAnnotation(classe, Order.class);
                filtres.add(new Filtre(nom, ordre == null ? null : ordre.value()));
            }
        }

        // Sans ce controle, un chemin errone rendrait le test vert en
        // n'examinant rien — la forme la plus trompeuse de succes, deja
        // rencontree dans ce depot.
        assertThat(filtres)
                .withFailMessage("aucun filtre trouvé : ce test serait vert sans "
                        + "rien vérifier")
                .hasSizeGreaterThanOrEqualTo(8);
        return filtres;
    }

    @Test
    @DisplayName("tout filtre WebFilter déclare un ordre explicite")
    void tousOrdonnes() throws IOException {
        List<String> sansOrdre = filtresDeclares().stream()
                .filter(f -> f.ordre() == null)
                .map(Filtre::nom)
                .toList();

        assertThat(sansOrdre)
                .withFailMessage("""
                        Ces filtres n'ont pas d'ordre déclaré : %s

                        Spring les trie en dernier, à LOWEST_PRECEDENCE, et entre
                        eux dans l'ordre du balayage du classpath. La séquence
                        devient donc implicite — et l'invariant « la provenance
                        avant ceux qui font confiance à X-Client-Ip » ne tient
                        plus que par accident.

                        Ajouter une constante dans OrdreDesFiltres et l'utiliser.""",
                        sansOrdre)
                .isEmpty();
    }

    @Test
    @DisplayName("le contrôle de provenance précède ceux qui font confiance à X-Client-Ip")
    void provenanceAvantLesDependants() throws IOException {
        List<Filtre> filtres = filtresDeclares();

        int ordreProvenance = filtres.stream()
                .filter(f -> "GatewayAccessFilter".equals(f.nom()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("GatewayAccessFilter absent"))
                .ordreEffectif();

        for (String nom : DEPENDENT_DE_LA_PROVENANCE) {
            Filtre filtre = filtres.stream()
                    .filter(f -> nom.equals(f.nom()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(nom + " absent"));

            assertThat(filtre.ordreEffectif())
                    .withFailMessage("""
                            %s (ordre %s) ne s'exécute pas APRÈS
                            GatewayAccessFilter (ordre %s).

                            Il décide à partir de X-Client-Ip, un en-tête que
                            l'appelant fournit. Sans le contrôle de provenance en
                            amont, un appel direct choisit son adresse : liste
                            noire contournée en changeant de valeur, plafond de
                            débit contourné en la faisant tourner.""",
                            nom,
                            filtre.ordre() == null ? "non déclaré" : filtre.ordre(),
                            ordreProvenance)
                    .isGreaterThan(ordreProvenance);
        }
    }

    @Test
    @DisplayName("deux filtres ne partagent pas le même ordre")
    void aucunOrdreEnDouble() throws IOException {
        List<Filtre> filtres = filtresDeclares();
        List<Integer> ordres = filtres.stream().map(Filtre::ordreEffectif).sorted().toList();

        assertThat(ordres)
                .withFailMessage("deux filtres partagent un ordre : leur séquence "
                        + "relative redevient celle du balayage du classpath, donc "
                        + "implicite")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("la séquence déclarée est celle attendue, de bout en bout")
    void sequenceComplete() throws IOException {
        // Fige la sequence entiere. Un filtre insere au mauvais endroit fait
        // echouer ce test avec la liste reelle, ce qui rend la decision visible
        // plutot que silencieuse.
        List<String> attendue = List.of(
                "GatewayAccessFilter",   // provenance : tout le reste en depend
                "IpBlacklistFilter",     // refuser l'hostile avant tout travail
                "RateLimitFilter",       // plafonner avant de lire un corps
                "ZipBombFilter",         // refuser la charge disproportionnee
                "XssProtectionFilter",   // detecter, signaler
                "MaintenanceJwtFilter",  // autoriser la maintenance
                "AdminSecretFilter",     // autoriser l'administration
                "CspFilter");            // en-tetes de reponse

        List<String> reelle = filtresDeclares().stream()
                .sorted(Comparator.comparingInt(Filtre::ordreEffectif))
                .map(Filtre::nom)
                .toList();

        assertThat(reelle)
                .withFailMessage("séquence réelle : %s%nséquence attendue : %s",
                        reelle, attendue)
                .isEqualTo(attendue);
    }

    @Test
    @DisplayName("tous les filtres sont bien des beans de la chaîne globale")
    void tousBeans() throws IOException, ClassNotFoundException {
        // interface-service n'ajoute aucun filtre a la chaine de Spring Security
        // (verifie : SecurityConfig ne contient aucun addFilterAt). Les huit
        // vivent donc dans la chaine globale, et @Component est correct ici —
        // contrairement a gateway-service et auth-service, ou le cumul
        // @Component + addFilterAt faisait s'executer les filtres deux fois.
        for (Filtre f : filtresDeclares()) {
            Class<?> classe = Class.forName(
                    "com.abdatytch.interface_service.filter." + f.nom());
            assertThat(AnnotationUtils.findAnnotation(classe, Component.class))
                    .withFailMessage("%s n'est pas un bean : il ne s'exécutera "
                            + "jamais", f.nom())
                    .isNotNull();
        }
    }
}
