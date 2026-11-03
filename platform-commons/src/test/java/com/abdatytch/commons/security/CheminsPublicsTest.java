package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Les trois ouvertures non voulues, devenues impossibles.
 *
 * <h2>Ce que ce test garde</h2>
 *
 * {@link CheminsPublics} remplace cinq listes de chemins publics tenues à la
 * main, d'où sont sortis trois défauts de la même famille : <b>le contrôle
 * désigne plus large que son intention</b>. Chacun est ici un cas de test
 * nommé, parce qu'un défaut corrigé sans test revient.
 *
 * <p>Les cas négatifs comptent plus que les positifs : un chemin public qui
 * cesse de l'être produit une panne visible, qu'on corrige dans l'heure. Un
 * chemin privé qui devient public ne produit <b>rien</b> — jusqu'à ce que
 * quelqu'un le trouve.
 */
@DisplayName("CheminsPublics - ce qui est exempte du controle d'acces")
class CheminsPublicsTest {

    // ==================================================================
    @Nested
    @DisplayName("Les sondes, et seulement elles")
    class LesSondes {

        private final CheminsPublics sondes = CheminsPublics.sondes().construire();

        @ParameterizedTest
        @ValueSource(strings = {
                "/actuator/health",
                "/actuator/info",
                "/actuator/prometheus",
                "/actuator/health/readiness",
                "/actuator/health/liveness"
        })
        @DisplayName("Consul et Prometheus atteignent ce dont ils ont besoin")
        void sondesAccessibles(String chemin) {
            assertThat(sondes.contient(chemin)).isTrue();
        }

        /**
         * Le premier défaut : {@code startsWith("/actuator")}.
         *
         * <p>Il exemptait tout le sous-arbre. {@code /actuator/env} rend la
         * configuration, donc les URL de base et les noms d'utilisateur ;
         * {@code /actuator/loggers} permet d'<b>écrire</b> les niveaux de
         * journalisation ; {@code /actuator/httptrace} rend les dernières
         * requêtes, en-têtes compris. Sans jeton ni signature.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/actuator/env",
                "/actuator/loggers",
                "/actuator/httptrace",
                "/actuator/beans",
                "/actuator/configprops",
                "/actuator/heapdump",
                "/actuator/threaddump",
                "/actuator",
                "/actuator/"
        })
        @DisplayName("le reste d'actuator N'EST PAS public")
        void resteDActuatorProtege(String chemin) {
            assertThat(sondes.contient(chemin))
                    .withFailMessage("'%s' est exempte du controle d'acces. C'est le defaut "
                            + "startsWith(\"/actuator\") qui revient : env rend la "
                            + "configuration, loggers permet d'ecrire les niveaux, httptrace "
                            + "rend les dernieres requetes avec leurs en-tetes.", chemin)
                    .isFalse();
        }

        @Test
        @DisplayName("sans appel explicite, la documentation OpenAPI reste fermee")
        void documentationFermeeParDefaut() {
            assertThat(sondes.contient("/v3/api-docs")).isFalse();
            assertThat(sondes.contient("/swagger-ui/index.html")).isFalse();
            assertThat(sondes.contient("/webjars/swagger-ui/favicon.png")).isFalse();
        }

        @Test
        @DisplayName("la page d'erreur n'est pas publique sans appel explicite")
        void pageDErreurFermeeParDefaut() {
            assertThat(sondes.contient("/error")).isFalse();
            assertThat(CheminsPublics.sondes().pageDErreur().construire()
                    .contient("/error")).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La documentation OpenAPI : signature exigee, jeton non")
    class Documentation {

        private final CheminsPublics ouverte =
                CheminsPublics.sondes().documentationViaPasserelle().construire();

        /**
         * <b>La propriété centrale du dispositif.</b>
         *
         * <p>La documentation n'est <b>pas</b> exemptée du contrôle d'accès :
         * l'attaquer directement sur le port du service échoue, faute de
         * signature HMAC. Elle est seulement exemptée du <b>jeton</b>, donc
         * servie à une requête que la passerelle a signée.
         *
         * <p>C'est ce qui fait de la passerelle la seule porte. Si cette
         * assertion tombait, la documentation — qui décrit toute la surface
         * d'API, chemins d'administration compris — redeviendrait lisible en
         * contournant la passerelle, et <b>rien ne le signalerait</b>.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/swagger-ui/index.html",
                "/webjars/swagger-ui/favicon.png"
        })
        @DisplayName("elle n'est JAMAIS exemptee de la signature de passerelle")
        void jamaisExempteeDeSignature(String chemin) {
            assertThat(ouverte.contient(chemin))
                    .withFailMessage("'%s' est exempte du controle d'acces : la "
                            + "documentation redevient lisible en attaquant le port du "
                            + "service en direct, et elle decrit toute la surface d'API.",
                            chemin)
                    .isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/swagger-ui/index.html",
                "/webjars/swagger-ui/favicon.png"
        })
        @DisplayName("mais elle est bien reconnue comme accessible via la passerelle")
        void reconnueViaPasserelle(String chemin) {
            assertThat(ouverte.estViaPasserelleSansJeton(chemin)).isTrue();
        }

        @Test
        @DisplayName("les sondes ne sont pas classees 'via passerelle' : elles sont ouvertes")
        void sondesNeSontPasViaPasserelle() {
            assertThat(ouverte.estViaPasserelleSansJeton("/actuator/health")).isFalse();
            assertThat(ouverte.contient("/actuator/health")).isTrue();
        }

        /**
         * {@code /v3/api-docs} <b>sans suffixe</b> doit correspondre.
         *
         * <p>C'est l'URL principale du document OpenAPI. Un motif
         * {@code /v3/api-docs/**} converti en regex {@code /v3/api-docs/.*} ne
         * la couvrait pas : la documentation paraissait exposée et son point
         * d'entrée était refusé.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/v3/api-docs.yaml",
                "/swagger-ui",
                "/swagger-ui/index.html",
                "/swagger-ui.html",
                "/webjars/swagger-ui/favicon.png"
        })
        @DisplayName("l'interface et ses ressources sont permises dans la chaine Security")
        void documentationPermise(String chemin) {
            // `/v3/api-docs` SANS suffixe doit figurer parmi les motifs : c'est
            // l'URL principale du document, et `/v3/api-docs/**` ne la couvre
            // pas. Le defaut d'origine convertissait le motif en regex
            // `/v3/api-docs/.*`, qui la manquait.
            assertThat(estPermisParUnMotif(ouverte, chemin))
                    .withFailMessage("'%s' n'est permis par aucun motif : la passerelle "
                            + "signerait la requete pour rien, la chaine Security la "
                            + "refuserait, et la documentation serait annoncee lisible "
                            + "tout en restant injoignable.", chemin)
                    .isTrue();
        }

        /**
         * Le deuxième défaut : {@code contains("/api-docs")}.
         *
         * <p>Il exemptait n'importe quel chemin comportant cette chaîne. Ces
         * cas-ci traversaient donc le contrôle d'accès sans signature, et ne
         * doivent être reconnus par <b>aucune</b> des deux catégories.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/admin/secret/api-docs/vol",
                "/media/api-docs",
                "/auth/v3/api-docs/secret",
                "/admin/swagger-ui/x",
                "/users/webjars/x"
        })
        @DisplayName("un chemin qui CONTIENT api-docs sans commencer par lui est refuse")
        void contientMaisNeCommencePasPar(String chemin) {
            assertThat(ouverte.contient(chemin))
                    .withFailMessage("'%s' est exempte de signature : c'est le defaut "
                            + "contains(\"/api-docs\") qui revient.", chemin)
                    .isFalse();
            assertThat(ouverte.estViaPasserelleSansJeton(chemin))
                    .withFailMessage("'%s' est exempte de jeton : un chemin metier "
                            + "deviendrait lisible sans authentification des lors qu'il "
                            + "passe par la passerelle.", chemin)
                    .isFalse();
        }

        @Test
        @DisplayName("springfox n'est pas servi : /swagger-resources reste ferme")
        void swaggerResourcesNonExpose() {
            assertThat(ouverte.contient("/swagger-resources")).isFalse();
            assertThat(ouverte.estViaPasserelleSansJeton("/swagger-resources")).isFalse();
        }
    }

    /** Un chemin est-il couvert par au moins un motif {@code pathMatchers} ? */
    private static boolean estPermisParUnMotif(CheminsPublics p, String chemin) {
        for (String motif : p.motifsPermitAll()) {
            if (motif.endsWith("/**")) {
                if (chemin.startsWith(motif.substring(0, motif.length() - 3))) {
                    return true;
                }
            } else if (motif.equals(chemin)) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    @Nested
    @DisplayName("Le constructeur refuse ce qui depasse")
    class ConstructeurStrict {

        /**
         * Le troisième défaut : des entrées traitées comme des regex.
         *
         * <p>Un avertissement en commentaire le signalait. Il est désormais
         * refusé au démarrage, ce qu'un commentaire ne pouvait pas faire.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/v3/api-docs/**",
                "/swagger-ui/*",
                "/api/.*",
                "/auth/(login|register)",
                "/media/[0-9]+",
                "/users/?",
                "/x$"
        })
        @DisplayName("un motif est refuse, et le message dit quoi faire")
        void motifRefuse(String motif) {
            assertThatThrownBy(() -> CheminsPublics.sondes().prefixe(motif))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ressemble à un motif");
        }

        /**
         * Un chemin non ancré par {@code /} est refusé.
         *
         * <p>{@code ^/y} y figure plutôt qu'avec les motifs : le contrôle
         * d'ancrage le rejette d'abord. Les deux règles refusent, mais le
         * message doit rester celui du contrôle qui a réellement tranché —
         * sinon le test attesterait une raison que le code ne donne pas.
         */
        @ParameterizedTest
        @ValueSource(strings = {"actuator/health", "error", "v3/api-docs", "^/y"})
        @DisplayName("un chemin non ancre par '/' est refuse")
        void cheminNonAncreRefuse(String chemin) {
            assertThatThrownBy(() -> CheminsPublics.sondes().cheminExact(chemin))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ancré");
        }

        @Test
        @DisplayName("un chemin vide est refuse")
        void cheminVideRefuse() {
            assertThatThrownBy(() -> CheminsPublics.sondes().cheminExact("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("un chemin nul ou vide n'est jamais public")
        void entreeDegradeeJamaisPublique() {
            CheminsPublics p = CheminsPublics.sondes().construire();
            assertThat(p.contient(null)).isFalse();
            assertThat(p.contient("")).isFalse();
            assertThat(p.contient("   ")).isFalse();
        }

        /**
         * L'objet construit est <b>réellement</b> immuable.
         *
         * <p>Le constructeur enveloppait ses collections par
         * {@code Collections.unmodifiableList}, qui est une <b>vue</b> et non
         * une copie. Tout appel supplémentaire sur le même {@code Constructeur}
         * après {@code construire()} modifiait donc l'objet « immuable » — y
         * compris pendant que des threads de requête le lisent, sur des
         * {@code ArrayList} qui ne le supportent pas.
         *
         * <p>Ce test réutilise volontairement le constructeur après coup, ce
         * qu'aucun appelant ne fait aujourd'hui. C'est précisément pour cela
         * qu'il faut un test : le défaut est latent, donc invisible.
         */
        @Test
        @DisplayName("reutiliser le constructeur ne modifie pas un objet deja construit")
        void objetConstruitEstFige() {
            var constructeur = CheminsPublics.sondes();
            CheminsPublics avant = constructeur.construire();

            assertThat(avant.contient("/ajoute-apres")).isFalse();

            constructeur.prefixe("/ajoute-apres");

            assertThat(avant.contient("/ajoute-apres"))
                    .withFailMessage("l'objet deja construit a change : ses collections "
                            + "sont une VUE sur celles du constructeur, pas une copie. "
                            + "Un chemin peut donc devenir public apres coup, pendant "
                            + "que des requetes le lisent.")
                    .isFalse();

            assertThat(constructeur.construire().contient("/ajoute-apres"))
                    .withFailMessage("le nouvel objet devrait, lui, porter le prefixe ajoute")
                    .isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La correspondance de prefixe ne depasse pas le segment")
    class BornageDuPrefixe {

        private final CheminsPublics avecDoc =
                CheminsPublics.sondes().documentationViaPasserelle().construire();

        /**
         * Le défaut corrigé : {@code startsWith} brut.
         *
         * <p>Un préfixe {@code /webjars} exemptait aussi {@code /webjars-admin},
         * et {@code /swagger-ui} exemptait {@code /swagger-ui-private}. C'est
         * exactement « le contrôle désigne plus large que son intention », dans
         * la classe qui prétend rendre ce défaut impossible.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/webjars-admin/secret",
                "/swagger-ui-private/x",
                "/v3/api-docs-internal",
                "/swagger-uix"
        })
        @DisplayName("un chemin qui PROLONGE le segment du prefixe n'est pas couvert")
        void prolongementDeSegmentRefuse(String chemin) {
            assertThat(avecDoc.estViaPasserelleSansJeton(chemin))
                    .withFailMessage("'%s' est couvert par un prefixe de documentation : "
                            + "le bornage au segment a disparu, et un chemin voisin "
                            + "devient exempt de jeton.", chemin)
                    .isFalse();
        }

        /**
         * Les trois formes légitimes restent couvertes.
         *
         * <p>Dont la troisième, le point : springdoc sert réellement
         * {@code /v3/api-docs.yaml} et {@code /swagger-ui.html}. Un bornage
         * strict au séparateur les refuserait, et la documentation serait
         * annoncée ouverte tout en étant partiellement injoignable.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/v3/api-docs.yaml",
                "/swagger-ui",
                "/swagger-ui/index.html",
                "/swagger-ui.html",
                "/webjars/swagger-ui/favicon.png"
        })
        @DisplayName("exact, suivi d'un separateur, ou suivi d'un point : couverts")
        void formesLegitimesCouvertes(String chemin) {
            assertThat(avecDoc.estViaPasserelleSansJeton(chemin)).isTrue();
        }

        /**
         * Même bornage pour les sondes, qui passent par la même méthode.
         *
         * <p>Le préfixe en place est {@code /actuator/health/}, avec sa barre
         * finale, donc déjà borné de fait. Ce test le fixe pour que le retrait
         * de cette barre ne passe pas inaperçu.
         */
        @Test
        @DisplayName("les sondes de groupe restent couvertes, et pas leurs voisins")
        void sondesDeGroupe() {
            CheminsPublics sondes = CheminsPublics.sondes().construire();

            assertThat(sondes.contient("/actuator/health/readiness")).isTrue();
            assertThat(sondes.contient("/actuator/healthcheck")).isFalse();
            assertThat(sondes.contient("/actuator/health-detail")).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les deux couches, et leur asymetrie voulue")
    class DeuxCouches {

        private final CheminsPublics complet = CheminsPublics.sondes()
                .pageDErreur()
                .documentationViaPasserelle()
                .construire();

        /**
         * Tout chemin exempté de <b>signature</b> doit aussi être permis par la
         * chaîne Security.
         *
         * <p>Ce sens de l'implication est obligatoire : une sonde exemptée du
         * contrôle d'accès mais refusée par Spring Security serait annoncée
         * ouverte et resterait injoignable — Consul croirait le service mort.
         *
         * <p>L'implication <b>inverse est fausse, et volontairement</b> : la
         * documentation est permise par Spring Security sans être exemptée de
         * signature. C'est tout le dispositif.
         */
        @Test
        @DisplayName("tout chemin exempte de signature est permis par la chaine Security")
        void exemptDeSignatureImpliquePermis() {
            assertThat(complet.motifsPermitAll()).isNotEmpty();

            for (String chemin : List.of(
                    "/actuator/health", "/actuator/health/readiness",
                    "/actuator/info", "/actuator/prometheus", "/error")) {

                assertThat(complet.contient(chemin)).isTrue();
                assertThat(estPermisParUnMotif(complet, chemin))
                        .withFailMessage("'%s' est exempte du controle d'acces mais refuse "
                                + "par la chaine Security : il est annonce ouvert et reste "
                                + "injoignable. Pour une sonde, Consul croira le service "
                                + "mort.", chemin)
                        .isTrue();
            }
        }

        /**
         * L'asymétrie, énoncée positivement.
         *
         * <p>Si elle disparaissait dans un sens, la documentation deviendrait
         * lisible en direct sur le port ; dans l'autre, elle serait injoignable
         * même via la passerelle. Les deux erreurs sont silencieuses, d'où ce
         * test.
         */
        @Test
        @DisplayName("la documentation est permise par Security SANS etre exemptee de signature")
        void asymetrieDeLaDocumentation() {
            for (String chemin : List.of("/v3/api-docs", "/swagger-ui/index.html")) {
                assertThat(complet.contient(chemin))
                        .withFailMessage("'%s' est exempte de signature : lisible en "
                                + "contournant la passerelle", chemin)
                        .isFalse();
                assertThat(estPermisParUnMotif(complet, chemin))
                        .withFailMessage("'%s' n'est pas permis par la chaine Security : "
                                + "injoignable meme via la passerelle", chemin)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("sans appel explicite, aucun motif ne concerne la documentation")
        void documentationAbsenteParDefaut() {
            String[] motifs = CheminsPublics.sondes().construire().motifsPermitAll();

            assertThat(motifs).noneMatch(m -> m.contains("api-docs"))
                    .noneMatch(m -> m.contains("swagger"))
                    .noneMatch(m -> m.contains("webjars"));
        }
    }
}
