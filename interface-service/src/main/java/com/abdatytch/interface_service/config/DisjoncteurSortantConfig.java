package com.abdatytch.interface_service.config;

import com.abdatytch.commons.resilience.DisjoncteurSortant;
import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import com.abdatytch.interface_service.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Le disjoncteur des appels sortants de ce service.
 *
 * <h2>Pourquoi un par service, et non un dans platform-commons</h2>
 *
 * La MECANIQUE est partagee : {@code DisjoncteurSortant} vit dans
 * platform-commons, et les quatre reglages viennent des memes clefs
 * {@code resilience.sortant.*}. Ce qui change d'un service a l'autre, ce sont
 * les deux decisions que le constructeur reclame :
 *
 * <ul>
 *   <li><b>ce qui compte comme une indisponibilite</b>. Un refus metier ne doit
 *       PAS ouvrir le disjoncteur : il prouve justement que le service d'en
 *       face repond. Reconnaitre un refus demande de connaitre le type
 *       d'exception du service, et chacun a le sien ;</li>
 *   <li><b>l'exception levee a l'ouverture</b>. Elle doit traverser la chaine
 *       d'erreurs du service et ressortir avec le bon statut. Ici
 *       {@code BusinessException}, que {@code GlobalExceptionHandler} de ce
 *       module sait rendre.</li>
 * </ul>
 *
 * <p>Les declarer dans platform-commons obligerait a y connaitre les
 * exceptions des onze modules, ce qui inverserait la dependance.
 *
 * <h2>Ce qu'il protege aujourd'hui</h2>
 *
 * Le client de la messagerie sortante — les deux courriels de code a usage
 * unique, qui partent desormais par notification-service. Sans ce bean, le
 * contexte ne demarre pas : mesure faite au premier chargement apres la
 * migration, et c'est le test de contexte qui l'a dit.
 *
 * <h2>Pourquoi `!(erreur instanceof IllegalArgumentException)`</h2>
 *
 * Le repli par defaut compte toute autre erreur comme une indisponibilite :
 * delai depasse, connexion refusee, reponse illisible. Un argument invalide,
 * lui, se reproduira a l'identique au prochain appel — ouvrir le disjoncteur
 * dessus punirait le service d'en face pour une faute locale.
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
        return new DisjoncteurSortant(registre,
                DisjoncteurSortantConfig::estUneIndisponibilite,
                nom -> new BusinessException(
                        "Service momentanement indisponible", "SERVICE_UNAVAILABLE"),
                tauxDEchec, fenetre, appelsMinimum, attenteOuvert);
    }

    static boolean estUneIndisponibilite(Throwable erreur) {
        if (erreur instanceof BusinessException metier) {
            // Un refus metier PROUVE que le service repond : il ne doit pas
            // ouvrir le disjoncteur. Seul le refus que ce service emet
            // lui-meme a l'ouverture compte comme une indisponibilite.
            return "SERVICE_UNAVAILABLE".equals(metier.getErrorCode());
        }
        return !(erreur instanceof IllegalArgumentException);
    }
}
