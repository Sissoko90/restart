package com.abdatytch.commons.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.abdatytch.commons.totp.TotpService;
import com.abdatytch.commons.totp.TotpServiceImpl;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Auto-configuration du socle sécurité partagé.
 *
 * <p>Déclare {@link JwksKeyProvider} et {@link HMACSignatureUtil} dès que
 * {@code platform-commons} est au classpath, sans que chaque service ait à les
 * redéclarer. {@code @ConditionalOnMissingBean} laisse un service fournir sa propre
 * implémentation s'il en a besoin.
 *
 * <p>{@code @EnableScheduling} est activé ici parce que {@link JwksKeyProvider} porte
 * un {@code @Scheduled} de rafraîchissement : sans cela, les clés ne sont chargées
 * qu'au démarrage et la rotation de clés casse silencieusement l'authentification
 * (c'était le cas de gateway-service et user-service, qui n'activaient pas
 * l'ordonnancement).
 *
 * <p>Le {@code WebClient.Builder} est récupéré via {@link ObjectProvider} avec repli
 * sur {@code WebClient.builder()} : une auto-configuration ne peut pas supposer que
 * {@code WebClientAutoConfiguration} a déjà été traitée au moment où elle s'exécute.
 *
 * <p>Un service qui est lui-même l'autorité de signature (interface-service) n'a pas
 * à consommer de JWKS : il pose {@code jwt.jwks.enabled: false}.
 *
 * <p>Les valeurs par défaut sont chargées depuis
 * {@code platform-commons-defaults.properties} plutôt qu'écrites dans des
 * annotations {@code @Value} : aucune adresse ne figure ainsi en dur dans le code
 * Java. Un {@code @PropertySource} a une précédence inférieure à
 * {@code application.yml}, donc la configuration d'un service les remplace
 * toujours.
 */
@AutoConfiguration
@EnableScheduling
@ConditionalOnClass(WebClient.class)
@PropertySource("classpath:platform-commons-defaults.properties")
public class PlatformSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "jwt.jwks.enabled", havingValue = "true", matchIfMissing = true)
    public JwksKeyProvider jwksKeyProvider(ObjectProvider<WebClient.Builder> webClientBuilder) {
        return new JwksKeyProvider(webClientBuilder.getIfAvailable(WebClient::builder));
    }

    @Bean
    @ConditionalOnMissingBean
    public HMACSignatureUtil hmacSignatureUtil() {
        return new HMACSignatureUtil();
    }

    /**
     * Second facteur TOTP, disponible pour tout service protegeant un acces
     * privilegie.
     */
    @Bean
    @ConditionalOnMissingBean
    public TotpService totpService() {
        return new TotpServiceImpl();
    }
}
