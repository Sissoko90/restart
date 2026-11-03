package com.abdatytch.commons.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Les chemins exemptés du contrôle d'accès de la passerelle, en un seul endroit.
 *
 * <h2>Pourquoi cette classe existe</h2>
 *
 * Cinq services tenaient chacun leur propre liste de chemins publics, dans le
 * corps de leur filtre d'accès. Les cinq listes ont divergé, et <b>trois
 * défauts</b> en sont sortis — le même défaut, découvert trois fois, corrigé
 * trois fois séparément :
 *
 * <ol>
 *   <li>{@code startsWith("/actuator")} exemptait <b>tout</b> le sous-arbre,
 *       donc {@code /actuator/env}, {@code /actuator/loggers} et
 *       {@code /actuator/httptrace} — la configuration, les niveaux de
 *       journalisation et les dernières requêtes, sans jeton ni signature ;</li>
 *   <li>{@code contains("/api-docs")} exemptait <b>n'importe quel</b> chemin
 *       comportant cette chaîne, donc {@code /admin/secret/api-docs/x} ;</li>
 *   <li>{@code path.matches(motif.replace("**", ".*"))} traitait chaque entrée
 *       comme une <b>expression régulière</b>, où un {@code .} vaut n'importe
 *       quel caractère. Un commentaire en avertissait — un commentaire
 *       n'applique rien.</li>
 * </ol>
 *
 * <p>Ces trois erreurs ont une forme commune : <b>le contrôle désigne plus
 * large que son intention</b>. Elles ne peuvent plus se produire ici, parce que
 * la correspondance n'offre que deux formes — exacte, ou par préfixe ancré — et
 * que le constructeur <b>refuse</b> ce qui ressemble à un motif.
 *
 * <h2>Ce que cette classe ne factorise PAS</h2>
 *
 * Volontairement, elle ne couvre ni la vérification HMAC, ni le contrôle d'IP,
 * ni la forme de la réponse de refus. C'est là que vivent les différences
 * <b>intentionnelles</b> entre services — interface-service, qui détient les
 * clés JWT, n'expose que sa sonde de santé et ses deux chemins JWKS, et doit
 * rester ainsi. Factoriser le filtre entier obligerait à paramétrer ces
 * différences et transformerait une correction en refonte de la chaîne de
 * sécurité de six modules.
 *
 * <p>La couture retenue est donc celle d'où sont sortis les défauts, et elle
 * seule.
 *
 * <h2>Deux exemptions distinctes, et c'est la clé</h2>
 *
 * Un chemin traverse <b>deux</b> contrôles successifs, et il faut pouvoir
 * l'exempter de l'un sans l'autre :
 *
 * <table border="1">
 *   <caption>Les trois catégories réelles</caption>
 *   <tr><th></th><th>signature HMAC</th><th>jeton JWT</th></tr>
 *   <tr><td>sondes Consul et Prometheus</td>
 *       <td>exemptées</td><td>exemptées</td></tr>
 *   <tr><td>documentation OpenAPI</td>
 *       <td><b>EXIGÉE</b></td><td>exemptée</td></tr>
 *   <tr><td>tout le reste</td>
 *       <td>exigée</td><td>exigé</td></tr>
 * </table>
 *
 * <p>La ligne du milieu est celle qui manquait, et elle exprime la règle de la
 * plateforme : <b>la passerelle est la seule porte</b>. Une documentation
 * exemptée de signature serait lisible en attaquant le port du service en
 * direct ; exemptée du seul jeton, elle n'est atteignable que par une requête
 * que la passerelle a signée. Le contrôle d'accès reste donc entier, et c'est
 * la passerelle qui décide qui peut lire.
 *
 * <p>D'où deux sorties <b>volontairement asymétriques</b> :
 * {@link #contient(String)} pour le filtre d'accès, et
 * {@link #motifsPermitAll()} — un <b>sur-ensemble</b> — pour les
 * {@code pathMatchers} de la chaîne Security.
 *
 * <p>Les deux restent issues d'<b>une seule</b> déclaration. C'est ce qui
 * empêche le défaut inverse : deux listes tenues séparément se contredisent, et
 * c'est ainsi que l'interface Swagger se trouvait exemptée de signature dans
 * certains services tout en étant bloquée par leur {@code SecurityConfig} —
 * annoncée ouverte, injoignable.
 */
public final class CheminsPublics {

    /**
     * Les sondes, nommées une par une.
     *
     * <p>Consul et Prometheus ne portent ni jeton ni signature, et n'ont besoin
     * que de celles-ci. Tout le reste d'{@code /actuator} reste protégé.
     */
    private static final Set<String> SONDES = Set.of(
            "/actuator/health",
            "/actuator/info",
            "/actuator/prometheus");

    /** Sondes de groupe : {@code /actuator/health/readiness}, {@code /liveness}. */
    private static final String PREFIXE_SONDES_GROUPEES = "/actuator/health/";

    /**
     * La documentation OpenAPI.
     *
     * <p>{@code /webjars} en fait partie : l'interface Swagger y charge ses
     * ressources. L'omettre laisse la page se charger et tous ses actifs être
     * refusés — une panne qui ressemble à un défaut d'affichage.
     *
     * <p>{@code /swagger-resources} n'y est <b>pas</b> : c'est un chemin
     * springfox, et cette plateforme emploie springdoc. Rien ne le sert, donc
     * l'exempter n'ajouterait qu'une ouverture ayant l'air d'une décision.
     */
    private static final List<String> DOCUMENTATION = List.of(
            "/v3/api-docs",
            "/swagger-ui",
            "/webjars");

    private final Set<String> exacts;
    private final List<String> prefixes;
    private final List<String> prefixesViaPasserelle;
    private final List<String> motifsPermitAll;

    /**
     * Copie, et non enveloppe.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * C'était {@code Collections.unmodifiableSet(exacts)}, soit une <b>vue</b>
     * sur les collections du constructeur et non une copie. Après
     * {@code construire()}, tout appel supplémentaire sur le même
     * {@code Constructeur} modifiait donc l'objet « immuable » — y compris
     * pendant que des threads de requête le lisent, sur des {@code ArrayList}
     * qui ne le supportent pas. Et deux {@code construire()} rendaient deux
     * objets partageant le même état.
     *
     * <p>{@code List.copyOf} et {@code Set.copyOf} rendent l'immuabilité réelle
     * plutôt qu'annoncée.
     */
    private CheminsPublics(Set<String> exacts, List<String> prefixes,
                           List<String> prefixesViaPasserelle, List<String> motifsPermitAll) {
        this.exacts = Set.copyOf(exacts);
        this.prefixes = List.copyOf(prefixes);
        this.prefixesViaPasserelle = List.copyOf(prefixesViaPasserelle);
        this.motifsPermitAll = List.copyOf(motifsPermitAll);
    }

    /**
     * Le préfixe couvre-t-il ce chemin, <b>sans dépasser le segment</b> ?
     *
     * <h3>Le défaut corrigé</h3>
     *
     * C'était {@code chemin.startsWith(prefixe)} brut. Un préfixe
     * {@code /webjars} exemptait donc aussi {@code /webjars-admin}, et
     * {@code /swagger-ui} exemptait {@code /swagger-ui-private} : exactement le
     * défaut « le contrôle désigne plus large que son intention » que cette
     * classe est censée rendre impossible. Il n'était pas exploitable — le seul
     * préfixe en place porte une barre finale — mais la classe doit tenir ce
     * qu'elle promet avant que d'autres s'y appuient.
     *
     * <h3>Les trois formes acceptées, et pourquoi la troisième</h3>
     *
     * <ul>
     *   <li>le chemin <b>est</b> le préfixe : {@code /v3/api-docs} ;</li>
     *   <li>le préfixe est suivi d'un <b>séparateur</b> :
     *       {@code /v3/api-docs/swagger-config} ;</li>
     *   <li>le préfixe est suivi d'un <b>point</b> : {@code /v3/api-docs.yaml},
     *       {@code /swagger-ui.html}. springdoc sert réellement ces formes, et
     *       un bornage strict au segment les refuserait — une documentation
     *       annoncée ouverte et partiellement injoignable.</li>
     * </ul>
     *
     * <p>Aucune des trois ne reconnaît {@code /webjars-admin} : c'est le tiret
     * qui fait la différence, et c'est tout l'objet de ce contrôle.
     */
    private static boolean couvre(String prefixe, String chemin) {
        if (!chemin.startsWith(prefixe)) {
            return false;
        }

        // Un prefixe qui porte DEJA sa barre finale est borne par construction :
        // `/actuator/health/` ne peut pas reconnaitre `/actuator/healthcheck`.
        //
        // Sans ce cas, la suite examinait le premier caractere du segment suivant
        // — le 'r' de `readiness` — et refusait les sondes de groupe de Consul.
        // Attrape par le test au premier lancement.
        if (prefixe.endsWith("/")) {
            return true;
        }

        if (chemin.length() == prefixe.length()) {
            return true;
        }
        char suivant = chemin.charAt(prefixe.length());
        return suivant == '/' || suivant == '.';
    }

    /**
     * Ce chemin est-il exempté du <b>contrôle d'accès de la passerelle</b> ?
     *
     * <p>C'est-à-dire : peut-il être appelé <b>sans signature HMAC</b>, donc en
     * attaquant directement le port du service ? Seules les sondes le peuvent,
     * parce que Consul et Prometheus ne passent pas par la passerelle.
     *
     * <p>La documentation OpenAPI n'en fait <b>pas</b> partie, délibérément :
     * voir {@link Constructeur#documentationViaPasserelle()}.
     *
     * <p>Deux formes de correspondance seulement : égalité, ou début de chemin.
     * Jamais {@code contains}, jamais d'expression régulière.
     */
    public boolean contient(String chemin) {
        if (chemin == null || chemin.isBlank()) {
            return false;
        }
        return exacts.contains(chemin)
                || prefixes.stream().anyMatch(p -> couvre(p, chemin));
    }

    /**
     * Ce chemin est-il atteignable <b>uniquement via la passerelle</b>, sans
     * jeton ?
     *
     * <p>Vrai pour la documentation OpenAPI : signature exigée, jeton non.
     */
    public boolean estViaPasserelleSansJeton(String chemin) {
        if (chemin == null || chemin.isBlank()) {
            return false;
        }
        return prefixesViaPasserelle.stream().anyMatch(p -> couvre(p, chemin));
    }

    /**
     * Les motifs {@code pathMatchers} à passer en {@code permitAll}.
     *
     * <p><b>Sur-ensemble</b> de {@link #contient(String)} : il ajoute les
     * chemins accessibles via la passerelle sans jeton. L'asymétrie est le
     * cœur du dispositif — permettre dans la chaîne Security n'ouvre rien tant
     * que le filtre d'accès exige la signature.
     *
     * <p>Un chemin exact se rend tel quel, un préfixe reçoit {@code /**}.
     */
    public String[] motifsPermitAll() {
        return motifsPermitAll.toArray(new String[0]);
    }

    /** Un ensemble ne contenant que les sondes de santé et de métriques. */
    public static Constructeur sondes() {
        return new Constructeur();
    }

    /**
     * Construit un ensemble de chemins publics, en refusant ce qui dépasse.
     *
     * <p>Les contrôles du constructeur sont le cœur de cette classe : ils
     * transforment en <b>erreur de démarrage</b> ce qui n'était jusqu'ici qu'un
     * avertissement en commentaire.
     */
    public static final class Constructeur {

        private final Set<String> exacts = new LinkedHashSet<>(SONDES);
        private final List<String> prefixes = new ArrayList<>(List.of(PREFIXE_SONDES_GROUPEES));
        private final List<String> viaPasserelle = new ArrayList<>();
        private final List<String> motifs = new ArrayList<>();

        private Constructeur() {
            motifs.addAll(SONDES);
            motifs.add("/actuator/health/**");
        }

        /**
         * La documentation OpenAPI : <b>signature exigée, jeton non</b>.
         *
         * <p>C'est la troisième catégorie, celle qui exprime la règle de la
         * plateforme — <b>la passerelle est la seule porte, en entrée comme en
         * sortie</b>. La documentation est donc :
         * <ul>
         *   <li><b>non</b> exemptée du contrôle d'accès : l'attaquer
         *       directement sur le port du service échoue, faute de signature
         *       HMAC valide ;</li>
         *   <li>exemptée du jeton dans la chaîne Security : une requête que la
         *       passerelle a signée est servie.</li>
         * </ul>
         *
         * <p>Ce qui déplace la décision « qui peut lire la documentation » là
         * où elle doit être : sur la passerelle, qui l'agrège et la protège une
         * seule fois, au lieu de sept surfaces à ouvrir et refermer.
         *
         * <p>L'erreur à ne pas refaire serait d'appeler aussi
         * {@link #prefixe(String)} sur ces mêmes chemins : ils seraient alors
         * exemptés de signature, et la documentation redeviendrait lisible en
         * direct.
         */
        public Constructeur documentationViaPasserelle() {
            for (String chemin : DOCUMENTATION) {
                valider(chemin);
                viaPasserelle.add(chemin);
                motifs.add(chemin + "/**");
                // Le chemin NU doit etre permis aussi : `/v3/api-docs` sans
                // suffixe est l'URL principale du document, et `/v3/api-docs/**`
                // ne la couvre pas.
                motifs.add(chemin);
            }
            return this;
        }

        /**
         * La page d'erreur du conteneur.
         *
         * <p>Sans elle, une erreur réacheminée vers {@code /error} se heurte au
         * contrôle de signature et le client reçoit un refus d'accès au lieu de
         * l'erreur réelle.
         */
        public Constructeur pageDErreur() {
            return cheminExact("/error");
        }

        /** Un chemin autorisé en correspondance <b>exacte</b>. */
        public Constructeur cheminExact(String chemin) {
            valider(chemin);
            exacts.add(chemin);
            motifs.add(chemin);
            return this;
        }

        /** Un préfixe autorisé, en correspondance par <b>début de chemin</b>. */
        public Constructeur prefixe(String prefixe) {
            valider(prefixe);
            prefixes.add(prefixe);
            motifs.add(prefixe.endsWith("/") ? prefixe + "**" : prefixe + "/**");
            return this;
        }

        /**
         * Refuse ce qui ne peut pas être une correspondance sûre.
         *
         * <p>C'est ici que les trois défauts historiques deviennent
         * impossibles : un motif {@code **}, un caractère d'expression
         * régulière ou un chemin non ancré lèvent une exception au démarrage,
         * au lieu de produire une correspondance plus large que l'intention.
         */
        private void valider(String chemin) {
            if (chemin == null || chemin.isBlank()) {
                throw new IllegalArgumentException("Chemin public vide");
            }
            if (!chemin.startsWith("/")) {
                throw new IllegalArgumentException(
                        "Un chemin public doit être ancré par '/' : " + chemin);
            }
            for (String interdit : List.of("*", "?", ".*", "[", "]", "(", ")", "|", "$", "^")) {
                if (chemin.contains(interdit)) {
                    throw new IllegalArgumentException(
                            "'" + chemin + "' ressemble à un motif. Les chemins publics se "
                            + "déclarent littéralement : cheminExact(...) pour une égalité, "
                            + "prefixe(...) pour un début de chemin. Un motif libre a déjà "
                            + "produit trois ouvertures non voulues dans ce dépôt.");
                }
            }
        }

        public CheminsPublics construire() {
            return new CheminsPublics(exacts, prefixes, viaPasserelle, motifs);
        }
    }
}
