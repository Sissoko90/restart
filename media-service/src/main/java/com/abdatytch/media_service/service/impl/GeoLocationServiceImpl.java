package com.abdatytch.media_service.service.impl;

import com.abdatytch.commons.geo.LocalisationIp;
import com.abdatytch.media_service.service.GeoLocationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Géolocalisation : délègue à {@link LocalisationIp}.
 *
 * <h2>Ce qui a changé, et pourquoi</h2>
 *
 * Cette classe portait sa <b>propre</b> lecture MaxMind — chargement du fichier,
 * {@code DatabaseReader}, extraction des champs — en parallèle de celle de
 * {@code gateway-service}. Deux implémentations pour le même travail, et avec
 * deux <b>versions différentes</b> de la bibliothèque : {@code 4.1.0} ici,
 * {@code 5.0.2} dans la passerelle. Un correctif devait donc être appliqué deux
 * fois, sur deux API qui ne sont pas les mêmes.
 *
 * <p>La lecture vit désormais dans {@code platform-commons}, en un seul
 * exemplaire et sur une version gérée par le POM parent. Le DTO de ce service
 * est conservé : c'est son contrat d'API, et il n'a pas de raison d'être le même
 * que celui de la passerelle.
 *
 * <h2>Ce que GeoLite2 ne peut PAS remplir</h2>
 *
 * Le DTO porte {@code isp}, {@code organization}, {@code isProxy},
 * {@code isVpn}, {@code isTor} et {@code connectionType}. Ces champs viennent
 * des bases <b>payantes</b> GeoIP2 Anonymous IP, GeoIP2 ISP et Enterprise. Avec
 * GeoLite2, qui est la base gratuite utilisée ici, MaxMind les rend toujours
 * nuls ou faux.
 *
 * <p>La conséquence dépasse ce service : {@code UserActivityLogServiceImpl}
 * écrit {@code isSuspicious} dans les données de sécurité de chaque activité, et
 * cette valeur vaut donc <b>toujours faux</b>. Un lecteur du journal en
 * conclurait qu'aucun trafic anonymisé n'a jamais été observé — une absence
 * présentée comme un constat. Ces champs sont laissés tels quels, et documentés
 * ici, parce que les remplir demanderait un abonnement MaxMind : c'est une
 * décision, pas un correctif.
 */
@Service
@Slf4j
public class GeoLocationServiceImpl implements GeoLocationService {

    /** Valeurs rendues quand la base ne répond pas. */
    private static final String PAYS_INCONNU = "Unknown";
    private static final String PAYS_LOCAL = "Local";
    private static final String RESEAU_LOCAL = "Local Network";

    @Value("${geolocation.database.path:/opt/geoip/GeoLite2-City.mmdb}")
    private String cheminBase;

    @Value("${geolocation.enabled:false}")
    private boolean geoLocationActive;

    private LocalisationIp localisation;

    @PostConstruct
    public void init() {
        if (!geoLocationActive) {
            // Le lien de téléchargement n'est plus journalisé : la consigne du
            // dépôt est qu'aucun lien ne figure dans le code. Le nom du fichier
            // attendu suffit à agir.
            log.info("Géolocalisation désactivée (geolocation.enabled=false)");
            localisation = LocalisationIp.inactive();
            return;
        }
        // Ce service n'utilise que la base Ville : elle porte aussi le pays,
        // donc elle est passée aux deux emplacements. Sans base ASN, les champs
        // d'organisation restent vides — ils le seraient de toute façon avec
        // GeoLite2.
        localisation = LocalisationIp.charger(cheminBase, cheminBase, null);
    }

    @Override
    public Mono<GeoLocationInfo> getLocationFromIp(String ipAddress) {
        if (localisation == null || !localisation.estActive()) {
            return Mono.just(infoMinimale(ipAddress));
        }
        if (LocalisationIp.estUneAdressePrivee(ipAddress)) {
            return Mono.just(infoLocale(ipAddress));
        }
        // Le portage hors EventLoop est fait par LocalisationIp.details :
        // la base Ville est projetée en mémoire, donc sa lecture peut toucher
        // le disque. Le refaire ici ajouterait un second saut de fil.
        return localisation.details(ipAddress)
                .map(details -> versDto(ipAddress, details));
    }

    private static GeoLocationInfo versDto(String ipAddress, LocalisationIp.Details details) {
        return new GeoLocationInfo(
                ipAddress,
                details.pays() != null ? details.pays() : PAYS_INCONNU,
                details.codePays(),
                details.ville(),
                details.region(),
                details.codePostal(),
                details.latitude(),
                details.longitude(),
                details.fuseauHoraire(),
                // isp : exige GeoIP2 ISP. Toujours nul avec GeoLite2.
                null,
                details.organisation(),
                details.numeroAsn(),
                // proxy, vpn, tor : exigent GeoIP2 Anonymous IP. Toujours faux
                // avec GeoLite2 — voir la javadoc de la classe.
                false,
                false,
                false,
                // connectionType : exige Enterprise. Toujours nul.
                null);
    }

    /**
     * Information rendue sans base : le strict nécessaire, et pas une erreur.
     *
     * <p>Refuser ferait échouer la journalisation d'activité sur une
     * installation sans fichiers MaxMind, qui ne sont pas versionnés.
     */
    private static GeoLocationInfo infoMinimale(String ipAddress) {
        return new GeoLocationInfo(ipAddress, PAYS_INCONNU, null, null, null, null,
                null, null, null, null, null, null, false, false, false, null);
    }

    private static GeoLocationInfo infoLocale(String ipAddress) {
        return new GeoLocationInfo(ipAddress, PAYS_LOCAL, LocalisationIp.LOCAL,
                "Localhost", RESEAU_LOCAL, null, null, null, null,
                "Local ISP", RESEAU_LOCAL, null, false, false, false, PAYS_LOCAL);
    }
}
