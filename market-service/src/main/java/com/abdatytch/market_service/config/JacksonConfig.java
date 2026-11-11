package com.abdatytch.market_service.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Le serialiseur JSON de ce service.
 *
 * <h2>Pourquoi il est declare et non herite</h2>
 *
 * Le contexte refusait de demarrer : {@code CacheDePortee} exige un
 * {@code ObjectMapper}, et aucun n'etait expose. Le service ne tirait que
 * {@code jackson-datatype-jsr310}, pas l'auto-configuration qui en publie un.
 *
 * <h2>Les trois reglages, et ce que chacun evite</h2>
 *
 * <ul>
 *   <li>{@code JavaTimeModule} : sans lui, un {@code LocalDateTime} ne se
 *       serialise pas du tout — et ces reponses en portent toutes un ;</li>
 *   <li>dates en ISO et non en horodatage numerique : un nombre de
 *       millisecondes oblige chaque client a connaitre le fuseau suppose ;</li>
 *   <li>un champ inconnu est IGNORE a la lecture. Les reponses des autres
 *       services portent plus de champs que ce module n'en veut —
 *       {@code UserDetailDTO} en a une vingtaine pour deux utilises — et
 *       echouer dessus ferait d'un ajout anodin chez le voisin une panne ici.
 *       </li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
