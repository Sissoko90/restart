package com.abdatytch.notification_service.config;

import com.abdatytch.commons.resilience.DisjoncteurSortant;
import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import com.abdatytch.notification_service.constants.ErrorCode;
import com.abdatytch.notification_service.exception.NotificationException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Le disjoncteur des appels sortants de ce module.
 *
 * <h2>Le mecanisme est partage, le vocabulaire d'erreurs est local</h2>
 *
 * {@link DisjoncteurSortant} vit dans platform-commons et ne connait aucune
 * exception de service : les deux regles qui parlent des types de CE module lui
 * sont injectees. auth-service a la meme classe de configuration avec ses
 * propres regles, et les deux partagent une seule implementation.
 *
 * <p>Recopier le disjoncteur ici aurait produit deux implementations d'une meme
 * regle, qui divergent en silence — ce que ce depot a deja paye avec les chemins
 * publics, la lecture d'adresse IP et deux filtres de liste noire inertes.
 *
 * <h2>Ce qui est protege</h2>
 *
 * Un seul appelant : {@code AfrikSmsProvider}. Twilio n'est pas protege, et
 * c'est volontaire : son SDK est bloquant et deja isole sur
 * {@code boundedElastic}, ou une lenteur du fournisseur n'immobilise pas la
 * boucle d'evenements. Poser un disjoncteur dessus demanderait de reecrire son
 * appel, pour un fournisseur de secours.
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
     * <p>Fabrique publique, et non un simple corps de {@code @Bean} : le test
     * d'{@code AfrikSmsProvider} construit un disjoncteur <b>reel</b> — non
     * simule — pour verifier qu'il s'ouvre vraiment. Il doit donc obtenir
     * exactement celui de production, faute de quoi il pourrait passer au vert
     * sur une regle d'echec que personne n'execute.
     */
    public static IDisjoncteurSortant pour(CircuitBreakerRegistry registre,
                                           float tauxDEchec,
                                           int fenetre,
                                           int appelsMinimum,
                                           long attenteOuvert) {
        return new DisjoncteurSortant(registre,
                DisjoncteurSortantConfig::estUneIndisponibilite,
                nom -> new NotificationException(ErrorCode.SERVICE_UNAVAILABLE),
                tauxDEchec, fenetre, appelsMinimum, attenteOuvert);
    }

    /**
     * Seule une indisponibilite du fournisseur compte comme un echec.
     *
     * <p>Sur un chemin SMS la distinction se chiffre. Un refus du fournisseur —
     * numero mal forme, message rejete, credit epuise — prouve qu'il
     * <b>fonctionne</b> : il a repondu. Le compter ouvrirait le disjoncteur sur
     * du trafic sain, et une rafale de mauvais numeros couperait l'envoi de SMS
     * a tous les autres destinataires.
     *
     * <p>Dans l'autre sens, une panne de transport non comptee laisserait
     * chaque requete attendre le delai maximal pour un echec connu d'avance.
     *
     * <p>La regle porte sur le NOM du code et non sur une constante precise :
     * c'est la meme convention que {@code GlobalExceptionHandler}, qui classe
     * tout code nomme {@code UNAVAILABLE} en 503. Une seule regle, deux
     * endroits qui s'y conforment — et un code ajoute plus tard la suit sans
     * qu'on ait a y penser.
     */
    static boolean estUneIndisponibilite(Throwable erreur) {
        if (erreur instanceof NotificationException notification) {
            // Pas de garde sur null : le code d'erreur de NotificationException
            // est obligatoire, pose par son unique constructeur.
            return notification.getErrorCode().name().contains("UNAVAILABLE");
        }
        // Ce que l'appelant n'a pas traduit : panne de transport, delai, erreur
        // inattendue. A compter, faute de quoi un oubli de traduction rendrait
        // le disjoncteur aveugle — exactement le defaut de la configuration
        // d'origine, dont la liste `recordExceptions` ne nommait que des types
        // de RestTemplate sur une pile WebClient.
        return !(erreur instanceof IllegalArgumentException);
    }
}
