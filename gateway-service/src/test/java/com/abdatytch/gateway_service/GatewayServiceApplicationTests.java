package com.abdatytch.gateway_service;

import com.abdatytch.commons.security.JwksKeyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Vérifie que le contexte Spring se construit : toutes les dépendances sont
 * satisfaites et aucun filtre n'est déclaré deux fois.
 *
 * <h2>Pourquoi ces trois précautions</h2>
 *
 * Sans elles, le test héritait du profil {@code dev} déclaré dans
 * {@code application.yml}. Il allait donc chercher sa configuration sur le
 * config-server, puis tentait cinq fois de joindre l'autorité JWT. Le résultat
 * d'un {@code mvn install} dépendait de ce qui tournait sur le poste, et le build
 * s'allongeait d'une trentaine de secondes de temporisation pour rien.
 *
 * <ul>
 *   <li>{@code @ActiveProfiles("test")} charge {@code application-test.yml} ;</li>
 *   <li>{@code properties} désactive le config-server — cette clé doit être posée
 *       ici et non dans le YAML, car elle est évaluée avant le chargement des
 *       fichiers de configuration ;</li>
 *   <li>{@code @MockitoBean} remplace le provider JWKS : le bean reste présent
 *       pour les filtres qui en dépendent, mais aucune requête réseau n'est
 *       émise.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.consul.enabled=false"
})
@ActiveProfiles("test")
class GatewayServiceApplicationTests {

    @MockitoBean
    private JwksKeyProvider jwksKeyProvider;

    @Test
    void contextLoads() {
    }
}
