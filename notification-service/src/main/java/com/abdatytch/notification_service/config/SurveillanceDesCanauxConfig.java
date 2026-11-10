package com.abdatytch.notification_service.config;

import com.abdatytch.notification_service.metrics.NotificationMetrics;
import com.abdatytch.notification_service.service.CanalDeRemise;
import com.abdatytch.notification_service.service.impl.MoteurDeModeles;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Cable les jauges d'etat sur les canaux et le moteur de modeles.
 *
 * <h2>Pourquoi cette classe existe, plutot qu'une injection directe</h2>
 *
 * {@link NotificationMetrics} ne peut pas injecter les canaux : un canal a
 * besoin des metriques pour compter ses remises, et Spring refuserait le cycle.
 * Cette classe detient les deux cotes et ne fait que les presenter l'un a
 * l'autre.
 *
 * <p>Le travail se fait dans le CONSTRUCTEUR et non dans un {@code @Bean} : il
 * n'y a aucun objet a rendre, seulement un enregistrement a effectuer une fois.
 * Un {@code @Bean} rendant {@code void} n'existe pas, et un
 * {@code @PostConstruct} sur une classe sans etat n'ajouterait qu'une
 * indirection.
 *
 * <h2>Pourquoi des jauges et non des compteurs</h2>
 *
 * « Le canal PUSH est-il en mesure d'envoyer ? » est un etat, pas un
 * evenement. Un compteur ne saurait que croitre et decrirait « combien de fois
 * le canal est devenu actif », ce qui ne repond pas a la question posee a 3 h
 * du matin.
 *
 * <p>Une jauge Micrometer est tiree a la collecte : elle appelle
 * {@code estActif()} au moment ou Prometheus racle, donc elle ne peut pas
 * devenir perimee. C'est aussi ce qui la rend sans cout quand personne ne
 * racle.
 *
 * <h2>Ce que cela ferme</h2>
 *
 * Les sept metriques de ce service etaient toutes alimentees, et AUCUN tableau
 * de bord ne les affichait — sept autres services en avaient un. L'etat des
 * canaux, lui, n'existait nulle part : quand le canal PUSH s'est declare ACTIF
 * sans pouvoir envoyer, seul le journal de demarrage le disait.
 */
@Configuration
public class SurveillanceDesCanauxConfig {

    public SurveillanceDesCanauxConfig(NotificationMetrics metriques,
                                       List<CanalDeRemise> canaux,
                                       MoteurDeModeles moteur) {
        metriques.suivreCanaux(canaux);
        metriques.suivreMoteurDeModeles(moteur);
    }
}
