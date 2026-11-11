package com.abdatytch.commons.plateforme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aucun module ne compose de balisage HTML dans du code Java.
 *
 * <h2>Le defaut que ce garde-fou pince</h2>
 *
 * Trois endroits d'interface-service construisaient le corps d'un courriel en
 * concatenant des fragments de HTML, puis le passaient a une methode d'envoi
 * generique qui prenait un corps DEJA COMPOSE. Ce corps partait vers un modele
 * de transit — sujet et corps en variables — declare sans variante
 * {@code corps-html}.
 *
 * <p>Resultat mesure sur un courriel recu le 2026-11-10 a 20:02 UTC : le
 * message est parti en {@code text/plain} avec son balisage dedans, et le
 * destinataire a lu la source.
 *
 * <h2>Pourquoi ce n'etait pas seulement laid</h2>
 *
 * Ces gabarits inseraient par {@code String.format} des valeurs venues de
 * l'exterieur, dont le {@code User-Agent} de la requete — c'est-a-dire une
 * valeur posee par la personne que l'alerte denonce. Du balisage pouvait donc
 * entrer dans le courriel de securite qui la signale.
 *
 * <p>Le rendu de notification-service echappe toute variable d'un
 * {@code corps-html}. Un corps compose en Java contourne cet echappement par
 * construction : il n'y a plus de variable a echapper, tout est deja du
 * balisage.
 *
 * <h2>Ce que le test regarde</h2>
 *
 * Les sources de production, commentaires RETIRES et chaines CONSERVEES. La
 * javadoc de ce depot est ecrite en HTML — {@code <p>}, {@code <h2>},
 * {@code <ul>} — et un garde-fou qui lirait le texte brut echouerait sur sa
 * propre documentation. Ce depot s'est deja fait prendre ainsi.
 *
 * <p>Les motifs cherches sont choisis pour ne pas pouvoir apparaitre dans du
 * Java legitime : {@code <div}, {@code <table} ou {@code <p style} ne sont pas
 * des types generiques.
 */
@DisplayName("Aucun corps HTML compose en Java")
class AucunCorpsHtmlEnJavaTest {

    /**
     * Les marqueurs de balisage.
     *
     * <p>Assembles a l'execution : ecrits en clair, ils figureraient dans ce
     * fichier et un balayage qui inclurait les tests se trouverait lui-meme.
     */
    private static final List<String> BALISES = List.of(
            "<" + "div", "<" + "table", "<" + "html", "<" + "body",
            "<" + "span", "<" + "img ", "<" + "h1", "<" + "h2 style",
            "<" + "p style", "<" + "a href", "<" + "strong>", "<" + "br>");

    /**
     * Le seul module autorise a porter du HTML, et seulement en RESSOURCE.
     *
     * <p>notification-service detient les gabarits, mais dans
     * {@code src/main/resources/modeles-html/} — pas dans son code. Aucune
     * exception n'est donc declaree ici : le test vaut pour tous les modules,
     * y compris lui.
     */
    @Test
    @DisplayName("aucune source de production ne contient de balisage")
    void aucunBalisageDansLesSources() throws IOException {
        List<String> fautifs = new ArrayList<>();

        for (Path module : SourcesDuDepot.modules()) {
            String nom = module.getFileName().toString();
            for (Path source : SourcesDuDepot.sourcesDeProduction(module)) {
                String code = SourcesDuDepot.sansCommentairesJava(Files.readString(source));
                for (String balise : BALISES) {
                    if (code.contains(balise)) {
                        fautifs.add(nom + " : " + source.getFileName() + " porte " + balise);
                        break;
                    }
                }
            }
        }

        assertThat(fautifs)
                .withFailMessage("""
                        Du balisage HTML est compose dans du code Java :

                        %s

                        Les corps de courriel vivent dans \
                        notification-service/src/main/resources/modeles-html/, \
                        et l'emetteur ne fournit que des VARIABLES.

                        Deux raisons, toutes deux mesurees dans ce depot :

                        1. Un corps compose ici part vers un modele qui n'a \
                        peut-etre aucune variante corps-html. Il arrive alors en \
                        text/plain avec son balisage visible, et rien ne le \
                        signale.

                        2. Le rendu echappe les variables d'un corps-html. Un \
                        corps deja compose n'a plus de variable a echapper : la \
                        valeur de l'exterieur est devenue du balisage avant \
                        d'arriver.""", String.join("\\n", fautifs))
                .isEmpty();
    }

    /** Le garde-fou se garde : sans sources balayees, il ne verifie rien. */
    @Test
    @DisplayName("le balayage voit bien les sources de production")
    void leBalayageFonctionne() throws IOException {
        int sources = 0;
        for (Path module : SourcesDuDepot.modules()) {
            sources += SourcesDuDepot.sourcesDeProduction(module).size();
        }

        assertThat(sources)
                .withFailMessage("%d sources balayees : le balayage est casse et "
                        + "ce test ne protege plus rien", sources)
                .isGreaterThan(300);
    }
}
