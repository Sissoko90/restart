package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.commons.resilience.DisjoncteurSortant;
import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Le disjoncteur des appels sortants de ce module.
 *
 * <h2>Le mecanisme est partage, le vocabulaire d'erreurs reste ici</h2>
 *
 * {@code DisjoncteurSortantServiceImpl} vivait dans ce module et connaissait
 * {@code AuthException} et {@code ErrorCode}. Le sous-systeme SMS, qui rejoint
 * notification-service, en a besoin aussi : le mecanisme est donc passe dans
 * {@code platform-commons}, et les deux regles qui parlent des exceptions
 * d'auth-service sont declarees ici.
 *
 * <p>Recopier le disjoncteur dans notification-service aurait produit deux
 * implementations d'une meme regle — exactement ce que ce depot paie en boucle,
 * des chemins publics a la lecture d'adresse IP.
 *
 * <h2>Le comportement est IDENTIQUE a l'avant</h2>
 *
 * C'est la raison des deux strategies injectees. Les chemins OAuth2 — cles
 * publiques Apple, echange de jeton — et le client interface-service passent par
 * ce disjoncteur : si le type d'exception leve a l'ouverture changeait,
 * {@code GlobalExceptionHandler} classerait le refus en 500 au lieu de 503, et
 * ce service s'accuserait d'une panne alors qu'il vient de se proteger.
 */
@Configuration
public class DisjoncteurSortantConfig {

    @Bean
    public IDisjoncteurSortant disjoncteurSortant(
            CircuitBreakerRegistry registre,
            @Value("${resilience.sortant.taux-echec-pourcent:50}") float tauxDEchec,
            @Value("${resilience.sortant.fenetre-appels:10}") int fenetre,
            @Value("${resilience.sortant.appels-minimum:5}") int appelsMinimum,
            @Value("${resilience.sortant.attente-ouvert-secondes:30}") long attenteOuvert) {

        return pour(registre, tauxDEchec, fenetre, appelsMinimum, attenteOuvert);
    }

    /**
     * Construit le disjoncteur avec les regles de CE module.
     *
     * <p>Fabrique publique, et non un simple corps de {@code @Bean} : les tests
     * d'{@code AfrikSmsProvider}, des cles Apple et du client interface-service
     * construisent un disjoncteur REEL — non simule — pour verifier qu'il
     * s'ouvre vraiment. Ils doivent donc obtenir exactement celui de
     * production.
     *
     * <p>Sans cette fabrique, chaque test recopierait les deux strategies, et
     * un test pourrait alors passer avec une regle d'echec differente de celle
     * qui tourne — un test vert sur un comportement que personne n'execute.
     */
    public static IDisjoncteurSortant pour(CircuitBreakerRegistry registre,
                                           float tauxDEchec,
                                           int fenetre,
                                           int appelsMinimum,
                                           long attenteOuvert) {
        return new DisjoncteurSortant(registre,
                DisjoncteurSortantConfig::estUneIndisponibilite,
                nom -> new AuthException(ErrorCode.SERVICE_UNAVAILABLE),
                tauxDEchec, fenetre, appelsMinimum, attenteOuvert);
    }

    /**
     * Seule une indisponibilite de la dependance compte comme un echec.
     *
     * <p>Un refus metier — identifiants invalides, numero mal forme, credit
     * epuise — signale que la dependance <b>fonctionne</b> : elle a repondu. Le
     * compter ouvrirait le disjoncteur sur du trafic parfaitement sain, et sur
     * un chemin d'authentification cela transformerait une rafale de mauvais
     * mots de passe en panne generale.
     */
    static boolean estUneIndisponibilite(Throwable erreur) {
        if (erreur instanceof AuthException auth) {
            // Regle sur le NOM du code, et non sur une constante precise.
            //
            // La version precedente ne reconnaissait que SERVICE_UNAVAILABLE :
            // elle aurait laisse passer SMS_PROVIDER_UNAVAILABLE, donc le
            // disjoncteur du fournisseur SMS ne se serait jamais ouvert — la
            // meme panne silencieuse sous une autre forme.
            //
            // C'est la MEME convention que GlobalExceptionHandler, qui classe
            // tout code nomme UNAVAILABLE en 503 : une seule regle, deux
            // endroits qui s'y conforment.
            return auth.getErrorCode() != null
                    && auth.getErrorCode().name().contains("UNAVAILABLE");
        }
        // Ce qui n'a pas ete traduit par l'appelant : panne de transport,
        // delai, erreur inattendue. A compter, faute de quoi un oubli de
        // traduction rendrait le disjoncteur aveugle.
        return !(erreur instanceof IllegalArgumentException);
    }
}
