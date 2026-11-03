package com.abdatytch.media_service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tout contrôleur d'administration est-il réellement couvert par le filtre ?
 *
 * <h2>Pourquoi ce test, et pas un {@code @PreAuthorize}</h2>
 *
 * L'autorisation des chemins d'administration repose sur deux couches :
 * la règle déclarative {@code .pathMatchers("/admin/**").hasRole("ADMIN")} de
 * {@code SecurityConfig}, qui en est le détenteur principal, et
 * {@link RoleAuthorizationWebFilter} en seconde couche.
 *
 * <p>Ajouter une troisième couche par {@code @PreAuthorize} a été écarté
 * délibérément, pour deux raisons mesurées :
 * <ul>
 *   <li>ce module n'active <b>pas</b> {@code @EnableReactiveMethodSecurity} —
 *       seulement {@code @EnableWebFluxSecurity}. Une annotation
 *       {@code @PreAuthorize} y serait <b>inerte</b>, sans aucune erreur au
 *       démarrage. C'est précisément le défaut qui a rendu décoratives
 *       trente-trois annotations dans auth-service, dont celle qui protégeait
 *       l'effacement irréversible des données ;</li>
 *   <li>le défaut dominant de ce dépôt est « deux mécanismes pour une même
 *       préoccupation, dont l'un masque silencieusement l'autre ». Un troisième
 *       mécanisme augmente ce risque au lieu de le réduire.</li>
 * </ul>
 *
 * <p>Ce test est donc un <b>garde-fou</b>, pas une couche d'autorisation : il
 * n'applique rien à l'exécution, il fait échouer la construction si un
 * contrôleur d'administration cesse d'être couvert.
 *
 * <h2>Le défaut qu'il empêche de revenir</h2>
 *
 * {@code ADMIN_PATHS} ne contenait que {@code /admin/documents}. Le second
 * contrôleur d'administration, {@code /admin/document-types} et ses douze
 * endpoints — création, suppression et attribution de rôles aux types de
 * documents — n'était pas couvert par ce filtre. Une liste de préfixes tenue à
 * la main se désynchronise le jour où un contrôleur s'ajoute : c'est arrivé.
 */
@DisplayName("Couverture des chemins d'administration")
class CouvertureDesCheminsAdminTest {

    private static final String PAQUET = "com.abdatytch.media_service.web";

    /** Les chemins de base de tous les contrôleurs du module. */
    private static List<Controleur> controleurs() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Controleur> trouves = new ArrayList<>();
        Set<BeanDefinition> definitions = scanner.findCandidateComponents(PAQUET);

        for (BeanDefinition definition : definitions) {
            try {
                Class<?> classe = Class.forName(definition.getBeanClassName());
                RequestMapping mapping = classe.getAnnotation(RequestMapping.class);
                String chemin = (mapping != null && mapping.value().length > 0)
                        ? mapping.value()[0]
                        : "";
                trouves.add(new Controleur(classe.getSimpleName(), chemin));
            } catch (ClassNotFoundException introuvable) {
                throw new AssertionError("Controleur scanne mais non chargeable : "
                        + definition.getBeanClassName(), introuvable);
            }
        }
        return trouves;
    }

    private record Controleur(String nom, String cheminDeBase) {
        boolean seDitAdmin() {
            return nom.contains("Admin");
        }

        boolean estSousAdmin() {
            return cheminDeBase.startsWith("/admin");
        }
    }

    /**
     * Le filtre est-il déclenché sur ce chemin ?
     *
     * <p>La requête est envoyée <b>sans</b> attribut {@code claims}, ce qui est
     * le cas d'un appelant non authentifié. Un chemin couvert doit alors être
     * refusé sans que la chaîne aval soit sollicitée ; un chemin non couvert
     * doit passer.
     */
    private boolean filtreDeclenche(String chemin) {
        MockServerWebExchange echange = MockServerWebExchange.from(
                MockServerHttpRequest.get(chemin).build());

        AtomicBoolean chaineAppelee = new AtomicBoolean(false);
        WebFilterChain chaine = e -> {
            chaineAppelee.set(true);
            return Mono.empty();
        };

        new RoleAuthorizationWebFilter().filter(echange, chaine).block();

        if (chaineAppelee.get()) {
            return false;
        }
        assertThat(echange.getResponse().getStatusCode())
                .withFailMessage("le chemin '%s' est intercepte mais ne rend pas 403", chemin)
                .isEqualTo(HttpStatus.FORBIDDEN);
        return true;
    }

    @Test
    @DisplayName("le module expose bien plusieurs controleurs, dont des controleurs d'administration")
    void leScanTrouveQuelqueChose() {
        List<Controleur> trouves = controleurs();

        assertThat(trouves)
                .withFailMessage("aucun controleur trouve dans %s : le scan est casse, "
                        + "et tous les tests suivants seraient vacuement verts", PAQUET)
                .isNotEmpty();

        assertThat(trouves).filteredOn(Controleur::seDitAdmin)
                .withFailMessage("aucun controleur d'administration trouve : le critere de "
                        + "nommage a change, ce test ne garde plus rien")
                .isNotEmpty();
    }

    /**
     * Un contrôleur nommé « Admin » doit être monté sous {@code /admin}.
     *
     * <p>Sinon il échappe à la fois à la règle déclarative et au filtre, sans
     * qu'aucune erreur ne le signale : il serait servi à tout porteur de jeton.
     */
    @Test
    @DisplayName("tout controleur d'administration est monte sous /admin")
    void controleursAdminSousPrefixeAdmin() {
        for (Controleur controleur : controleurs()) {
            if (controleur.seDitAdmin()) {
                assertThat(controleur.estSousAdmin())
                        .withFailMessage("%s se presente comme un controleur d'administration "
                                + "mais est monte sur '%s'. Il echappe donc a la regle "
                                + "/admin/** de SecurityConfig ET au filtre de role : il "
                                + "serait servi a tout porteur de jeton.",
                                controleur.nom(), controleur.cheminDeBase())
                        .isTrue();
            }
        }
    }

    /**
     * Et réciproquement : chaque chemin d'administration est réellement arrêté.
     *
     * <p>C'est l'assertion qui ferme la désynchronisation. Elle ne lit pas
     * {@code ADMIN_PATHS} — elle exerce le filtre et constate le refus. Un
     * préfixe retiré de la liste fait donc échouer ce test, pas seulement une
     * relecture attentive.
     */
    @Test
    @DisplayName("chaque chemin d'administration est effectivement refuse sans jeton")
    void chaqueCheminAdminEstRefuse() {
        List<Controleur> admins = controleurs().stream().filter(Controleur::estSousAdmin).toList();

        assertThat(admins)
                .withFailMessage("aucun controleur sous /admin : ce test ne garde rien")
                .isNotEmpty();

        for (Controleur controleur : admins) {
            assertThat(filtreDeclenche(controleur.cheminDeBase()))
                    .withFailMessage("le chemin '%s' de %s N'EST PAS couvert par "
                            + "RoleAuthorizationWebFilter : la requete a traverse la chaine "
                            + "sans aucun controle de role.",
                            controleur.cheminDeBase(), controleur.nom())
                    .isTrue();
        }
    }

    /**
     * Le préfixe ne doit pas non plus être trop large.
     *
     * <p>Un filtre qui intercepterait les chemins métier exigerait le rôle
     * ADMIN de tout le monde : l'envoi de documents cesserait de fonctionner.
     * Le même soin qu'ailleurs dans ce dépôt sur {@code contains} contre
     * {@code startsWith} — désigner exactement ce qu'on veut, ni plus ni moins.
     */
    @Test
    @DisplayName("les chemins metier ne sont pas interceptes par le filtre de role")
    void cheminsMetierNonInterceptes() {
        List<Controleur> metier = controleurs().stream()
                .filter(c -> !c.estSousAdmin() && !c.cheminDeBase().isEmpty())
                .toList();

        assertThat(metier)
                .withFailMessage("aucun controleur metier trouve : ce test ne garde rien")
                .isNotEmpty();

        for (Controleur controleur : metier) {
            assertThat(filtreDeclenche(controleur.cheminDeBase()))
                    .withFailMessage("le chemin metier '%s' de %s est intercepte par le "
                            + "filtre de role : il exigerait donc ADMIN de tout le monde.",
                            controleur.cheminDeBase(), controleur.nom())
                    .isFalse();
        }
    }

    /**
     * Le refus ne renseigne pas l'appelant sur le modèle d'autorisation.
     *
     * <p>Le message rendait « Votre rôle actuel: X », ce qui confirmait le rôle
     * reconnu dans le jeton <b>et</b> le rôle attendu par le chemin. Le rôle
     * est désormais journalisé, jamais renvoyé — même raison qui a fait retirer
     * {@code X-Error-Reason} du filtre d'accès de ce module.
     */
    @Test
    @DisplayName("le refus ne divulgue ni le role attendu ni celui de l'appelant")
    void refusNeDivulgueRien() {
        MockServerWebExchange echange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/documents/pending").build());

        new RoleAuthorizationWebFilter()
                .filter(echange, e -> Mono.empty())
                .block();

        String corps = echange.getResponse().getBodyAsString().block();

        assertThat(corps).isNotNull();
        assertThat(corps)
                .withFailMessage("le corps du refus nomme un role : %s", corps)
                .doesNotContain("ADMIN")
                .doesNotContain("rôle")
                .doesNotContain("role");
    }
}
