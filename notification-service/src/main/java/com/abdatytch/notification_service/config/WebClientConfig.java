package com.abdatytch.notification_service.config;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Le constructeur de clients HTTP sortants.
 *
 * <h2>Pourquoi ce bean doit etre declare a la main</h2>
 *
 * Spring Boot 4 n'auto-configure plus {@code WebClient.Builder}. Sans ce bean,
 * le contexte refuse de demarrer :
 *
 * <pre>
 * No qualifying bean of type 'WebClient$Builder' available
 *   -&gt; afrikSmsProvider -&gt; smsServiceImpl
 * </pre>
 *
 * <p>C'est le meme changement que pour {@code ObjectMapper}, declare dans
 * {@code JacksonConfig} pour la meme raison. Les cinq autres modules du depot
 * portent chacun leur propre classe equivalente.
 *
 * <h2>Portee prototype, et pourquoi</h2>
 *
 * {@code WebClient.Builder} est <b>mutable</b> : {@code baseUrl(...)} et
 * {@code defaultHeader(...)} modifient l'instance et rendent {@code this}. Un
 * bean singleton est donc partage entre tous les injecteurs, et les reglages du
 * premier se retrouvent dans le client du second — y compris un en-tete
 * d'autorisation ou une cle d'API posee par defaut.
 *
 * <p>C'est la raison pour laquelle l'auto-configuration de Spring Boot le
 * declarait {@code prototype}. Quatre des cinq classes equivalentes de ce depot
 * l'ont perdu en la reecrivant.
 *
 * <p>Mesure honnete : <b>le defaut ne se paie pas aujourd'hui</b>. Les deux
 * consommateurs de ce module — {@code AfrikSmsProvider} et
 * {@code AfrikSmsAdministration} — appellent {@code build()} sans rien regler,
 * et un releve sur l'ensemble du depot ne trouve que deux appels a
 * {@code baseUrl} hors classe de configuration, chacun seul dans son module. La
 * portee correcte est posee ici parce qu'elle coute une ligne, pas parce qu'un
 * incident l'a revelee : le jour ou un troisieme appelant posera une cle d'API
 * par defaut, la contamination serait silencieuse et tres difficile a lire.
 */
@Configuration
public class WebClientConfig {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
