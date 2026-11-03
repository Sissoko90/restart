package com.abdatytch.auth_service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le {@code PasswordEncoder} n'a qu'un seul detenteur, et le hachage ne tourne
 * jamais sur un EventLoop.
 *
 * <h2>Pourquoi ce garde-fou est de SOURCE et non de comportement</h2>
 *
 * {@code NoBlockingCallsTest} installe BlockHound, qui instrumente la JVM et
 * fait echouer tout appel <b>bloquant</b> execute sur un thread non bloquant :
 * entrees-sorties, {@code Thread.sleep}, attentes de verrou.
 *
 * <p>Une boucle de calcul n'est pas un appel bloquant. BlockHound ne voit donc
 * <b>pas</b> BCrypt, et ne pouvait pas voir le defaut que ce test ferme : la
 * garantie « zero blocage » du depot avait un angle mort exactement la ou se
 * trouve le travail CPU le plus lourd.
 *
 * <h2>La mesure qui motive la regle</h2>
 *
 * Relevee sur la machine de developpement, avec
 * {@code BCryptPasswordEncoder(12)} tel que configure dans
 * {@code PasswordEncoderConfig} :
 *
 * <pre>
 * verification BCrypt(12)        : 241 ms par appel
 * EventLoops Netty alloues       : 10 (NettyConfig = nombre de coeurs)
 * latence moyenne /auth/login    : 703 ms
 * </pre>
 *
 * Dix tentatives de connexion simultanees immobilisaient toute la capacite de
 * traitement du service pendant un quart de seconde. Une requete HTTP qui coute
 * 241 ms de CPU est un amplificateur : le levier de deni de service le moins
 * cher de la plateforme.
 *
 * <h2>Ce que le test refuse</h2>
 *
 * Toute mention des <b>types</b> de hachage hors des fichiers autorises (voir
 * {@code JETONS_INTERDITS}). C'est volontairement plus strict que « hors d'un
 * {@code subscribeOn} » : verifier qu'un appel est bien enveloppe demanderait
 * d'analyser la structure du code, et un tel analyseur se trompe. Interdire la
 * dependance est verifiable sans ambiguite, et c'est la meme forme que le
 * detenteur unique applique ailleurs dans ce depot.
 */
@DisplayName("Hachage de mot de passe — un seul detenteur, hors EventLoop")
class HachageHorsEventLoopTest {

    /**
     * Fichiers autorises a toucher le {@code PasswordEncoder}.
     *
     * <ul>
     *   <li>{@code MotDePasseServiceImpl} : le detenteur, qui porte tout sur
     *       {@code boundedElastic} ;</li>
     *   <li>{@code PasswordEncoderConfig} : la declaration du bean ;</li>
     *   <li>{@code SuperAdminSeeder} : s'execute au <b>demarrage</b>, jamais sur
     *       une requete. Il n'y a pas d'EventLoop a immobiliser, et rendre le
     *       semis asynchrone le rendrait plus difficile a ordonner avec les
     *       migrations.</li>
     * </ul>
     *
     * <p>Liste <b>fermee</b> : y ajouter un fichier demande de justifier
     * pourquoi son hachage ne tourne pas sur une requete.
     */
    private static final Set<String> FICHIERS_AUTORISES = Set.of(
            "MotDePasseServiceImpl.java",
            "PasswordEncoderConfig.java",
            "SuperAdminSeeder.java");

    /**
     * Ce qui est interdit hors des fichiers autorises.
     *
     * <h3>Pourquoi des TYPES et pas un nom de champ</h3>
     *
     * La premiere version cherchait {@code passwordEncoder.} — le nom du champ
     * injecte. Verifiee par mutation : reintroduire le hachage sous la forme
     * {@code new BCryptPasswordEncoder(12).matches(...)} en ligne, sans champ,
     * <b>survivait</b> au garde-fou. La detection par nom d'identifiant se
     * contourne en n'utilisant pas l'identifiant.
     *
     * <p>Interdire les types et le paquet ferme cette porte : il n'existe pas
     * de façon de hacher un mot de passe avec Spring Security sans nommer l'un
     * d'eux. Verifie : ces jetons n'apparaissent dans aucun autre fichier de
     * production, et ceux qui les citent en commentaire sont neutralises.
     */
    private static final Set<String> JETONS_INTERDITS = Set.of(
            "org.springframework.security.crypto",
            "PasswordEncoder",
            "BCrypt",
            "Argon2",
            "SCrypt",
            "Pbkdf2");

    /**
     * Plancher de detection.
     *
     * <p>Si le balayage cesse de trouver des sources — chemin change, module
     * renomme — il rendrait zero fichier et le test passerait sans rien
     * verifier. Ce depot s'est deja fait prendre trois fois par un harnais vert
     * qui ne mesurait plus rien.
     */
    private static final int PLANCHER_FICHIERS_EXAMINES = 80;

    @Test
    @DisplayName("aucun autre fichier n'appelle le PasswordEncoder")
    void detenteurUnique() throws IOException {
        List<Path> sources = sourcesPrincipales();

        assertThat(sources)
                .withFailMessage("aucune source balayee : ce garde-fou serait vert "
                        + "sans rien verifier")
                .hasSizeGreaterThanOrEqualTo(PLANCHER_FICHIERS_EXAMINES);

        List<String> fautifs = new ArrayList<>();
        for (Path source : sources) {
            String nom = source.getFileName().toString();
            if (FICHIERS_AUTORISES.contains(nom)) {
                continue;
            }
            String contenu = neutraliserCommentaires(Files.readString(source));
            for (String interdit : JETONS_INTERDITS) {
                if (contenu.contains(interdit)) {
                    fautifs.add(nom + " (" + interdit + ")");
                    break;
                }
            }
        }

        assertThat(fautifs)
                .withFailMessage("""
                        Ces fichiers touchent le PasswordEncoder : %s

                        BCrypt(12) coute 241 ms de CPU par appel. Appele dans une \
                        chaine reactive, ce calcul s'execute sur un thread d'EventLoop \
                        Netty — il y en a dix — et immobilise la capacite de traitement \
                        du service pour toutes les autres requetes.

                        BlockHound ne le detectera pas : il instrumente les appels \
                        BLOQUANTS (I/O, sleep, verrous), pas les boucles de calcul.

                        Passer par IMotDePasseService, qui porte le calcul sur \
                        boundedElastic.""", fautifs)
                .isEmpty();
    }

    @Test
    @DisplayName("le detenteur porte bien le calcul sur boundedElastic")
    void detenteurPorteSurBoundedElastic() throws IOException {
        // Sans cette verification, le test precedent serait satisfait par un
        // detenteur unique qui appellerait BCrypt en ligne : la regle serait
        // respectee dans la forme et violee dans le fond.
        Path detenteur = racineDuDepot().resolve("auth-service/src/main/java/com/abdatytch/"
                + "auth_service/service/impl/MotDePasseServiceImpl.java");
        assertThat(detenteur)
                .withFailMessage("le detenteur est introuvable : ce garde-fou serait "
                        + "vert sans rien verifier")
                .exists();

        String contenu = Files.readString(detenteur);
        assertThat(contenu)
                .withFailMessage("le detenteur n'utilise pas boundedElastic : le calcul "
                        + "retourne sur l'EventLoop, et le garde-fou de dependance ne "
                        + "sert plus a rien")
                .contains("Schedulers.boundedElastic()");

        // Les deux operations doivent l'etre, pas seulement l'une.
        assertThat(contenu.split("subscribeOn\\(Schedulers\\.boundedElastic\\(\\)\\)", -1).length - 1)
                .withFailMessage("une seule des deux operations (verification, hachage) "
                        + "est portee hors de l'EventLoop")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * Neutralise les commentaires, en preservant les positions de ligne.
     *
     * <p>La javadoc de {@code verifyPassword} cite {@code passwordEncoder.matches}
     * pour expliquer le defaut corrige. Sans cette neutralisation, le test
     * echouerait sur sa propre documentation — erreur que ce depot a deja
     * commise avec un garde-fou de journalisation.
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

    private static List<Path> sourcesPrincipales() throws IOException {
        Path racine = racineDuDepot().resolve("auth-service/src/main/java");
        try (Stream<Path> fichiers = Files.walk(racine)) {
            return fichiers
                    .filter(p -> p.toString().endsWith(".java"))
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
