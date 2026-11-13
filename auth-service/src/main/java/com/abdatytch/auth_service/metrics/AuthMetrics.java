package com.abdatytch.auth_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Metriques metier de l'authentification, exposees a Prometheus.
 *
 * <h2>Ou ces methodes doivent etre appelees</h2>
 *
 * <b>Au detenteur de la regle, et nulle part ailleurs.</b> Ce n'est pas une
 * preference de style : {@code recordAccountLockout} etait appele depuis
 * {@code AuthServiceImpl}, alors que la regle de verrouillage appartient a
 * {@code AccountLockoutServiceImpl}. Consequence mesurable — un verrouillage
 * declenche par la connexion administrateur, par l'enrolement du second facteur
 * ou par un code TOTP errone n'etait <b>compte par personne</b>. Le tableau de
 * bord affichait donc zero verrouillage pendant qu'un compte se verrouillait.
 *
 * <p>Corollaire a surveiller : une fois l'appel pose chez le detenteur, il doit
 * etre <b>retire</b> de l'appelant, sinon le chemin d'origine compte deux fois
 * et les deux autres zero — un ecart plus trompeur que l'absence de mesure.
 *
 * <h2>Pas d'etiquette a cardinalite libre</h2>
 *
 * Aucune methode ici ne prend un identifiant, un telephone ou une adresse IP en
 * etiquette. Chaque valeur distincte cree une serie temporelle dans Prometheus :
 * une etiquette d'identifiant ferait croitre la memoire sans borne. Ce sont
 * aussi des donnees personnelles, et une serie Prometheus n'est pas effaçable
 * par {@code AccountErasureService}.
 */
@Component
@RequiredArgsConstructor
public class AuthMetrics {
    
    private final MeterRegistry registry;
    
    // Compteurs de succès/échec
    public void recordLoginSuccess(String method) {
        Counter.builder("auth.login.success")
            .tag("method", method)
            .description("Nombre de connexions réussies")
            .register(registry)
            .increment();
    }
    
    public void recordLoginFailure(String method, String reason) {
        Counter.builder("auth.login.failure")
            .tag("method", method)
            .tag("reason", reason)
            .description("Nombre de connexions échouées")
            .register(registry)
            .increment();
    }
    
    public void recordRegistration(String method) {
        Counter.builder("auth.registration")
            .tag("method", method)
            .description("Nombre d'inscriptions")
            .register(registry)
            .increment();
    }
    
    /**
     * Duree de generation d'un jeton JWT.
     *
     * <p>{@code publishPercentileHistogram} est indispensable ici : le tableau
     * de bord d'auth-service trace p50, p95 et p99 de cette duree, et
     * {@code histogram_quantile} ne travaille que sur des {@code _bucket}. Un
     * {@code Timer} ordinaire n'en publie aucun — le panneau affichait
     * « No data » depuis sa creation. Mesure du 2026-11-13.
     */
    public void recordTokenGeneration(Duration duration) {
        Timer.builder("auth.token.generation")
            .description("Durée de génération des tokens JWT")
            .publishPercentileHistogram()
            .register(registry)
            .record(duration);
    }
    
    /**
     * Revocation de jeton, etiquetee par portee.
     *
     * @param portee {@code jeton} pour une revocation unitaire,
     *        {@code tous_les_jetons_du_compte} pour une revocation globale —
     *        les deux ont des causes et des gravites differentes, et un
     *        compteur unique les melangerait
     */
    public void recordTokenRevocation(String portee) {
        Counter.builder("auth.token.revocation")
            .tag("portee", portee)
            .description("Revocations de jeton")
            .register(registry)
            .increment();
    }
    
    
    public void recordPasswordReset() {
        Counter.builder("auth.password.reset")
            .description("Nombre de réinitialisations de mot de passe")
            .register(registry)
            .increment();
    }
    
    public void recordOAuth2Login(String provider) {
        Counter.builder("auth.oauth2.login")
            .tag("provider", provider)
            .description("Nombre de connexions OAuth2")
            .register(registry)
            .increment();
    }
    
    public void recordOtpSent(String channel) {
        Counter.builder("auth.otp.sent")
            .tag("channel", channel)
            .description("Nombre d'OTP envoyés")
            .register(registry)
            .increment();
    }
    
    public void recordOtpVerification(boolean success) {
        Counter.builder("auth.otp.verification")
            .tag("success", String.valueOf(success))
            .description("Nombre de vérifications OTP")
            .register(registry)
            .increment();
    }

    // ==================================================================
    // Ce qui est BLOQUE : le signal le plus important, et il manquait
    // ==================================================================

    /**
     * Un compte vient de se verrouiller.
     *
     * <p>A appeler depuis {@code AccountLockoutServiceImpl} uniquement : c'est
     * le detenteur unique de la regle, et tous les chemins y deleguent.
     *
     * @param reason ce qui a fait franchir le seuil, en valeur bornee
     */
    public void recordAccountLockout(String reason) {
        Counter.builder("auth.account.lockout")
            .tag("reason", reason)
            .description("Verrouillages de compte")
            .register(registry)
            .increment();
    }

    /**
     * Une tentative refusee parce que le compte est <b>deja</b> verrouille.
     *
     * <p>Distinct de {@code recordAccountLockout}, qui compte le franchissement
     * du seuil, et distinct de {@code recordLoginFailure}, qui compte un mauvais
     * identifiant. Les trois ensemble disent si l'on subit une attaque
     * (verrouillages qui montent) ou si un utilisateur legitime est enferme
     * (tentatives bloquees qui montent sans nouveau verrouillage).
     */
    public void recordTentativeBloquee(String surface) {
        Counter.builder("auth.tentative.bloquee")
            .tag("surface", surface)
            .description("Tentatives refusees sur un compte verrouille")
            .register(registry)
            .increment();
    }

    // ==================================================================
    // Second facteur : aucune metrique n'existait
    // ==================================================================

    // ==================================================================
    // Alertes de securite
    // ==================================================================

    /**
     * Envoi d'une alerte de securite, abouti ou non.
     *
     * <h2>Pourquoi cette metrique n'est pas accessoire</h2>
     *
     * Une alerte ne doit JAMAIS faire echouer l'operation qu'elle signale : le
     * mot de passe est deja change quand le courriel part, et une panne de SMTP
     * ne doit pas faire croire a la personne que rien n'a bouge.
     *
     * <p>Mais une erreur avalee et seulement journalisee est une protection qui
     * s'eteint sans bruit — le defaut que ce depot passe son temps a retirer.
     * Ce compteur est ce qui rend l'extinction visible : {@code echec} qui monte
     * signale que les detournements de compte redeviennent silencieux.
     *
     * @param evenement {@code mot_de_passe_modifie}, {@code adresse_modifiee}
     *        ou {@code nouvelle_connexion} — valeurs BORNEES
     * @param resultat {@code envoyee}, {@code sans_destinataire} ou
     *        {@code echec}. « sans_destinataire » est compte a part : ce n'est
     *        pas une panne, c'est un compte sans coordonnee verifiee — et une
     *        courbe qui monte la y signale des comptes qu'aucune alerte ne
     *        protege.
     */
    public void recordAlerteDeSecurite(String evenement, String resultat) {
        Counter.builder("auth.security.alerte")
            .tag("evenement", evenement)
            .tag("resultat", resultat)
            .description("Alertes de securite envoyees a la personne concernee")
            .register(registry)
            .increment();
    }

    /**
     * Resultat de l'opposition du second facteur.
     *
     * @param resultat {@code valide}, {@code code_absent}, {@code code_faux},
     *        {@code non_enrole} ou {@code code_de_secours} — valeurs bornees,
     *        jamais le code lui-meme
     */
    public void recordSecondFacteur(String resultat) {
        Counter.builder("auth.second.facteur")
            .tag("resultat", resultat)
            .description("Verifications du second facteur TOTP")
            .register(registry)
            .increment();
    }

    /**
     * Operation sensible sur le second facteur d'un compte d'administration.
     *
     * <p>Ces trois operations sont rares et lourdes de consequences : un
     * enrolement remet un secret, une reinitialisation invalide l'appareil
     * existant, un retrait desactive le second facteur d'autrui. A ce titre
     * elles doivent declencher une alerte des la premiere occurrence, et non
     * sur un taux.
     *
     * @param operation {@code enrolement}, {@code reinitialisation} ou
     *        {@code retrait}
     */
    public void recordOperationSecondFacteur(String operation) {
        Counter.builder("auth.second.facteur.operation")
            .tag("operation", operation)
            .description("Operations sensibles sur le second facteur des administrateurs")
            .register(registry)
            .increment();
    }
}
