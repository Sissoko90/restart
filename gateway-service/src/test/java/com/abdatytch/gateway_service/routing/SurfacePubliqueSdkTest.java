package com.abdatytch.gateway_service.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou : le Gateway et auth-service doivent s'accorder sur ce qui est
 * public.
 *
 * <h2>Le défaut que ce test existe pour empêcher</h2>
 *
 * La surface SDK d'auth-service — connexion, inscription, OTP, mot de passe
 * oublié, OAuth2 — est <b>publique par nécessité</b> : l'utilisateur final
 * appelle {@code /auth/login} pour <b>obtenir</b> un jeton, lui en exiger un est
 * impossible par construction.
 *
 * <p>Mais <b>deux</b> services décident de cet accès, et dans cet ordre :
 *
 * <ol>
 *   <li>le Gateway, par {@code JwtWebFilter.isPublicPath} et son
 *       {@code SecurityConfig} ;</li>
 *   <li>auth-service, par les {@code permitAll} de son
 *       {@code SecurityConfig}.</li>
 * </ol>
 *
 * Le Gateway tranche <b>le premier</b>. Un chemin public côté auth-service mais
 * absent de la liste du Gateway répond donc 401 <b>sans jamais atteindre le
 * service</b> — et le journal dit « Aucun jeton présenté », ce qui est exact et
 * ne désigne pas le désaccord de configuration. C'est précisément le symptôme
 * qui a été observé sur {@code /api/v1/auth-sdk/login}.
 *
 * <h2>Pourquoi un contrôle de source</h2>
 *
 * Les deux listes vivent dans deux modules : aucune constante partagée ne peut
 * les unifier sans faire dépendre le Gateway d'auth-service. Ce qu'un test peut
 * faire, c'est lire les deux fichiers et vérifier qu'ils disent la même chose.
 *
 * <p>Le contrôle est faible — il compare des chaînes, pas des comportements —
 * et c'est assumé : il ne prouve pas que l'accès fonctionne, seulement que les
 * deux moitiés ne divergent pas en silence.
 */
@DisplayName("Le Gateway et auth-service s'accordent sur la surface publique du SDK")
class SurfacePubliqueSdkTest {

    /** Préfixe public que le Gateway réécrit en {@code /auth} pour auth-service. */
    private static final String PREFIXE_PUBLIC = "/api/v1/auth-sdk";

    private static final Pattern PERMIT_ALL =
            Pattern.compile("pathMatchers\\(([^()]*)\\)\\s*\\.permitAll\\(\\)");

    /**
     * Racine du dépôt, trouvée en remontant l'arborescence.
     *
     * <p>Le répertoire de travail d'un test Maven est celui du module : atteindre
     * auth-service demande de remonter au POM parent.
     */
    private static Path racineDuDepot() {
        Path courant = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && courant != null; i++) {
            if (Files.exists(courant.resolve("docker-compose-minimal.yml"))) {
                return courant;
            }
            courant = courant.getParent();
        }
        throw new AssertionError("Racine du dépôt introuvable depuis "
                + Path.of("").toAbsolutePath());
    }

    private String source(String chemin) throws IOException {
        Path fichier = racineDuDepot().resolve(chemin);
        assertThat(fichier)
                .withFailMessage("fichier introuvable : %s — ce garde-fou serait vert "
                        + "sans rien vérifier", fichier)
                .exists();
        return Files.readString(fichier);
    }

    /** Chemins {@code /auth/...} déclarés publics par auth-service. */
    private Set<String> publicsDAuthService() throws IOException {
        String contenu = source("auth-service/src/main/java/com/abdatytch/"
                + "auth_service/config/SecurityConfig.java");
        Set<String> chemins = new LinkedHashSet<>();
        Matcher m = PERMIT_ALL.matcher(contenu);
        while (m.find()) {
            Matcher litteral = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
            while (litteral.find()) {
                String chemin = litteral.group(1);
                if (chemin.startsWith("/auth/")) {
                    chemins.add(chemin);
                }
            }
        }
        return chemins;
    }

    /**
     * Chemins {@code /api/v1/auth-sdk/...} déclarés publics par le Gateway.
     *
     * <p>Extraction CIBLEE, et non « tout littéral portant le préfixe » : la
     * première version de ce test ramassait aussi le
     * {@code /api/v1/auth-sdk/**} de la règle {@code .authenticated()}, et
     * concluait que le Gateway ouvrait toute la surface. Un garde-fou qui lit
     * trop large accuse à tort, et un détecteur qui crie à tort finit
     * désactivé.
     */
    private Set<String> publicsDuGateway() throws IOException {
        Set<String> chemins = new LinkedHashSet<>();

        // SecurityConfig : uniquement ce qui est dans un pathMatchers(...)
        // immediatement suivi de .permitAll().
        Matcher regles = PERMIT_ALL.matcher(source(
                "gateway-service/src/main/java/com/abdatytch/gateway_service/"
                        + "security/SecurityConfig.java"));
        while (regles.find()) {
            Matcher litteral = Pattern.compile("\"([^\"]+)\"").matcher(regles.group(1));
            while (litteral.find()) {
                if (litteral.group(1).startsWith(PREFIXE_PUBLIC)) {
                    chemins.add(litteral.group(1));
                }
            }
        }

        // JwtWebFilter : l'ensemble CHEMINS_PUBLICS_SDK et les prefixes
        // explicitement acceptes par isPublicPath.
        String filtre = source("gateway-service/src/main/java/com/abdatytch/"
                + "gateway_service/security/JwtWebFilter.java");
        int debut = filtre.indexOf("CHEMINS_PUBLICS_SDK = ");
        int fin = debut < 0 ? -1 : filtre.indexOf(";", debut);
        if (debut >= 0) {
            Matcher litteral = Pattern.compile("\"([^\"]+)\"")
                    .matcher(filtre.substring(debut, fin));
            while (litteral.find()) {
                chemins.add(litteral.group(1));
            }
        }
        Matcher prefixes = Pattern.compile(
                "path\\.startsWith\\(\"(" + Pattern.quote(PREFIXE_PUBLIC) + "[^\"]*)\"\\)")
                .matcher(filtre);
        while (prefixes.find()) {
            chemins.add(prefixes.group(1));
        }
        return chemins;
    }

    /** {@code /api/v1/auth-sdk/login} -> {@code /auth/login}. */
    private static String versCheminDuService(String cheminPublic) {
        return "/auth" + cheminPublic.substring(PREFIXE_PUBLIC.length());
    }

    @Test
    @DisplayName("tout chemin public d'auth-service est public au Gateway")
    void aucunCheminPublicNEstBloqueParLeGateway() throws IOException {
        Set<String> duGateway = publicsDuGateway();
        Set<String> attendus = new LinkedHashSet<>();
        for (String cheminPublic : duGateway) {
            attendus.add(versCheminDuService(cheminPublic.replace("/**", "")));
        }

        for (String cheminDuService : publicsDAuthService()) {
            String nu = cheminDuService.replace("/**", "");
            assertThat(attendus)
                    .withFailMessage("""
                            %s est public dans auth-service mais le Gateway ne le
                            laisse pas passer.

                            Le Gateway tranche le PREMIER : la requête répondra 401
                            sans jamais atteindre auth-service, et le journal dira
                            « Aucun jeton présenté » — exact, mais cela ne désigne
                            pas le désaccord de configuration.

                            Ajouter %s%s à :
                              - JwtWebFilter.CHEMINS_PUBLICS_SDK
                              - SecurityConfig, avant la règle générale
                                /api/v1/auth-sdk/**""",
                            cheminDuService, PREFIXE_PUBLIC,
                            nu.substring("/auth".length()))
                    .anySatisfy(attendu -> assertThat(nu).startsWith(attendu));
        }
    }

    @Test
    @DisplayName("le Gateway n'ouvre rien qu'auth-service garde fermé")
    void leGatewayNOuvrePasPlusQueLeService() throws IOException {
        Set<String> publicsDuService = new LinkedHashSet<>();
        for (String c : publicsDAuthService()) {
            publicsDuService.add(c.replace("/**", ""));
        }

        for (String cheminPublic : publicsDuGateway()) {
            String attendu = versCheminDuService(cheminPublic).replace("/**", "");
            // L'inverse du test precedent. Un chemin ouvert au Gateway mais
            // ferme dans le service repond 401 au bout de la chaine : moins
            // grave, mais le Gateway annonce alors une surface publique qui
            // n'en est pas une, ce qui trompe quiconque lit sa configuration.
            assertThat(publicsDuService)
                    .withFailMessage("le Gateway ouvre %s, qu'auth-service garde fermé",
                            cheminPublic)
                    .anySatisfy(p -> assertThat(attendu).startsWith(p));
        }
    }

    @Test
    @DisplayName("la connexion de l'utilisateur final est publique des DEUX côtés")
    void laConnexionUtilisateurEstPublique() throws IOException {
        // Le chemin qui a motive tout ceci : POST /api/v1/auth-sdk/login
        // repondait 401 au Gateway. Nomme explicitement, pour qu'une
        // regression soit designee et non deduite.
        assertThat(publicsDAuthService())
                .as("auth-service")
                .contains("/auth/login");
        assertThat(publicsDuGateway())
                .as("Gateway")
                .contains(PREFIXE_PUBLIC + "/login");
    }

    /**
     * L'exception assumee : {@code complete-profile}.
     *
     * <p>Il n'est public ni au Gateway ni dans auth-service. Son justificatif
     * est un jeton de type {@code oauth2_temp}, qui voyage dans
     * {@code Authorization: Bearer} et n'ouvre <b>que</b> ce chemin : le Gateway
     * le valide en bornant son exception de type a ce chemin, et auth-service
     * exige en outre l'autorite {@code ROLE_OAUTH2_TEMP}.
     *
     * <p>Ce test verrouille le fait que les deux cotes connaissent le MEME
     * chemin. Une divergence rendrait 401 au Gateway, sans jamais atteindre le
     * service.
     */
    @Test
    @DisplayName("complete-profile n'est public nulle part, et le chemin concorde")
    void completeProfileNEstPublicNullePart() throws IOException {
        assertThat(publicsDAuthService())
                .as("auth-service")
                .doesNotContain("/auth/oauth2/complete-profile");
        assertThat(publicsDuGateway())
                .as("Gateway")
                .doesNotContain(PREFIXE_PUBLIC + "/oauth2/complete-profile");

        String filtre = source("gateway-service/src/main/java/com/abdatytch/"
                + "gateway_service/security/JwtWebFilter.java");
        String service = source("auth-service/src/main/java/com/abdatytch/"
                + "auth_service/security/JwtAuthenticationWebFilter.java");

        assertThat(filtre)
                .withFailMessage("le Gateway doit borner son exception de type au "
                        + "chemin de complete-profile, sinon il refuse le jeton "
                        + "temporaire en 401")
                .contains(PREFIXE_PUBLIC + "/oauth2/complete-profile");
        assertThat(service)
                .withFailMessage("auth-service doit borner l'autorite temporaire au "
                        + "meme chemin, sinon ce jeton satisferait les regles "
                        + ".authenticated() ailleurs — dont /auth/erasure/me")
                .contains("/auth/oauth2/complete-profile");
    }

    /**
     * La connexion biometrique est une METHODE DE CONNEXION.
     *
     * <p>{@code authenticate/initiate} lisait l'identite dans le jeton : il
     * fallait deja etre authentifie pour s'authentifier. L'identifiant arrive
     * desormais dans le corps, et le parcours est public des deux cotes.
     * {@code register/**} reste ferme : on y ajoute un authentificateur a un
     * compte existant.
     */
    @Test
    @DisplayName("la connexion biométrique est publique, l'enrôlement reste fermé")
    void connexionBiometriquePubliqueEnrolementFerme() throws IOException {
        assertThat(publicsDAuthService())
                .contains("/auth/biometric/authenticate/initiate",
                        "/auth/biometric/authenticate/verify")
                .noneSatisfy(c -> assertThat(c).startsWith("/auth/biometric/register"));
        assertThat(publicsDuGateway())
                .contains(PREFIXE_PUBLIC + "/biometric/authenticate/initiate",
                        PREFIXE_PUBLIC + "/biometric/authenticate/verify");
    }

    @Test
    @DisplayName("ce qui agit sur une identité établie reste fermé")
    void leProfilEtLaDeconnexionRestentFermes() throws IOException {
        // La frontiere est celle de la SESSION. Profil, deconnexion et
        // biometrie designent un utilisateur precis : sans jeton, le controleur
        // n'aurait aucune identite a laquelle se rapporter.
        assertThat(publicsDAuthService())
                .doesNotContain("/auth/profile", "/auth/logout")
                // Seul l'ENROLEMENT reste ferme : l'authentification
                // biometrique est une methode de connexion, donc publique.
                .noneSatisfy(c -> assertThat(c).startsWith("/auth/biometric/register"))
                .noneSatisfy(c -> assertThat(c).startsWith("/auth/erasure"));
    }
}
