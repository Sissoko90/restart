package com.abdatytch.security_service.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Métriques de la politique : elles suivent la DÉCISION, pas son application.
 *
 * <h2>Pourquoi elles sont ici et plus dans le gateway</h2>
 *
 * {@code geo_blocked_countries_count} et {@code geo_blocked_country_list}
 * étaient émises par {@code GeoLocationService}, dans la passerelle, qui tenait
 * alors la liste. La liste a déménagé ici ; les métriques la suivent, sinon
 * elles deviennent muettes.
 *
 * <p>C'est exactement ce que {@code MetriquesDesAlertesTest} a signalé pendant
 * ce déplacement : une alerte {@code TooManyBlockedCountries} et deux panneaux
 * Grafana interrogeaient des métriques que plus aucun code n'émettait. Une
 * alerte sur une métrique absente ne se déclenche <b>jamais</b>, et Prometheus
 * ne le signale pas : l'expression rend un ensemble vide, la condition est
 * fausse.
 *
 * <h2>Pourquoi un AtomicLong et non un gauge qui lit la base</h2>
 *
 * Micrometer évalue un gauge <b>au moment du scrape Prometheus</b>, sur le fil
 * qui sert {@code /actuator/prometheus} — une EventLoop Netty. Y appeler la base
 * ou Redis bloquerait ce fil. La valeur est donc rafraîchie par la publication,
 * et le gauge se contente de lire cet entier.
 *
 * <p>La précaution venait du code d'origine, et elle est conservée : son auteur
 * l'avait documentée, et la perdre lors du déplacement aurait réintroduit un
 * blocage sur le chemin du scrape.
 */
@Component
@RequiredArgsConstructor
public class MetriquesDeSecurite {

    /** Gauge du nombre de pays refusés. Lu au scrape, écrit à la publication. */
    private final AtomicLong paysRefuses = new AtomicLong(0);

    private final MeterRegistry registre;

    @PostConstruct
    public void init() {
        registre.gauge("geo_blocked_countries_count", paysRefuses, AtomicLong::get);
    }

    /**
     * Met à jour les métriques des pays refusés.
     *
     * <p>Appelée par la publication, qui connaît déjà la liste : la recalculer
     * ici demanderait une seconde lecture de la base pour une valeur qu'on vient
     * d'avoir en main.
     *
     * <h2>L'étiquette par pays est bornée, et c'est ce qui la rend acceptable</h2>
     *
     * Un code ISO : deux cent cinquante valeurs possibles au plus. Le code
     * d'origine émettait aussi {@code geo_requests_by_city_total} étiquetée par
     * <b>nom de ville</b> — des milliers de valeurs, donc une cardinalité qui
     * rend la métrique inexploitable et alourdit Prometheus durablement. Cette
     * métrique n'a pas été reprise.
     */
    public void majPaysRefuses(List<String> codesIso) {
        paysRefuses.set(codesIso.size());
        for (String iso : codesIso) {
            // Une jauge à 1 par pays : c'est ce que le panneau « Pays Bloqués
            // (Liste) » interroge. Un compteur ne conviendrait pas — la question
            // est « lesquels », pas « combien de fois ».
            registre.gauge("geo_blocked_country_list", Tags.of("country", iso), 1);
        }
    }
}
