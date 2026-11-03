package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.service.IDisjoncteurSortant;
import com.abdatytch.auth_service.service.impl.DisjoncteurSortantServiceImpl;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Fabrique de fournisseurs Apple pour les tests.
 *
 * <h2>Pourquoi une cle EC generee a chaque appel</h2>
 *
 * {@link SecretClientApple} valide la cle privee <b>a la construction</b> : un
 * fournisseur Apple « configure » ne peut donc pas etre fabrique avec une chaine
 * quelconque. La fabrique produit une vraie paire P-256, ce qui permet de tester
 * le fournisseur sans qu'aucun secret reel n'entre dans le depot, et sans qu'un
 * compte Apple existe.
 *
 * <h2>Ce que teste l'autre moitie</h2>
 *
 * {@link #nonConfigure()} rend le cas qui compte le plus pour la consigne tenue
 * ici : un fournisseur Apple <b>sans</b> configuration reste un objet valide. Il
 * ne leve pas, ne fait pas echouer le demarrage, et se contente de repondre que
 * les parcours Apple ne sont pas disponibles.
 */
final class AppleDEssai {

    static final String SERVICES_ID = "com.exemple.restart.web";
    static final String REDIRECT_URI = "https://exemple.test/auth/apple/rappel";

    private AppleDEssai() {
    }

    static IDisjoncteurSortant disjoncteur() {
        return new DisjoncteurSortantServiceImpl(
                CircuitBreakerRegistry.ofDefaults(), 50f, 100, 100, 30L);
    }

    /** Fournisseur Apple sans aucun identifiant : l'etat normal hors activation. */
    static FournisseurApple nonConfigure() {
        return construire(null, WebClient.builder(), null);
    }

    /** Fournisseur Apple avec une configuration complete et une cle d'essai. */
    static FournisseurApple configure() {
        return configure(WebClient.builder());
    }

    static FournisseurApple configure(WebClient.Builder constructeur) {
        return construire(secretDEssai(), constructeur, null);
    }

    /**
     * Fournisseur configure, avec une autorite de cles fournie.
     *
     * <p>Necessaire pour tester l'echange de bout en bout : l'identite vient du
     * {@code id_token}, donc le test doit pouvoir signer ce jeton et publier la
     * cle publique correspondante.
     */
    static FournisseurApple configure(WebClient.Builder constructeur,
                                      ClesPubliquesApple cles) {
        return construire(secretDEssai(), constructeur, cles);
    }

    private static FournisseurApple construire(SecretClientApple secret,
                                               WebClient.Builder constructeur,
                                               ClesPubliquesApple cles) {
        IDisjoncteurSortant disjoncteur = disjoncteur();
        ClesPubliquesApple autorite = cles != null ? cles : new ClesPubliquesApple(
                WebClient.builder(), disjoncteur, SERVICES_ID, 5);
        return new FournisseurApple(constructeur, autorite, disjoncteur, secret,
                SERVICES_ID, REDIRECT_URI, 5);
    }

    static SecretClientApple secretDEssai() {
        try {
            KeyPairGenerator generateur = KeyPairGenerator.getInstance("EC");
            generateur.initialize(new ECGenParameterSpec("secp256r1"));
            String pkcs8 = Base64.getEncoder()
                    .encodeToString(generateur.generateKeyPair().getPrivate().getEncoded());
            return new SecretClientApple("EQUIPE1234", SERVICES_ID, "CLE1234567", pkcs8);
        } catch (Exception e) {
            throw new IllegalStateException("cle d'essai non generable", e);
        }
    }
}
