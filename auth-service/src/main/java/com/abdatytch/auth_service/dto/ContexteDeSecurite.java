package com.abdatytch.auth_service.dto;

import com.abdatytch.auth_service.dto.request.GeoLocationData;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Les circonstances d'un événement de sécurité, telles qu'on les raconte à la
 * personne concernée.
 *
 * <h2>Pourquoi ces champs, et pas l'événement seul</h2>
 *
 * « Votre mot de passe a été modifié » ne permet aucune décision : la personne
 * ne sait pas si c'est elle. « Votre mot de passe a été modifié le 14 octobre à
 * 16h12, depuis Bamako, Mali, avec Chrome sur macOS » lui permet de reconnaître
 * — ou non — sa propre action.
 *
 * <p>C'est toute la valeur d'une alerte de sécurité : elle ne bloque rien, elle
 * rend <b>visible</b> ce qui serait passé inaperçu. Sans circonstances, elle
 * n'informe pas, elle inquiète.
 *
 * <h2>Pourquoi la géolocalisation est facultative</h2>
 *
 * {@code null} quand la recherche a échoué ou que l'adresse est privée. L'alerte
 * part quand même : savoir que son mot de passe a changé reste utile sans savoir
 * d'où. L'inverse — retenir l'alerte parce que la géolocalisation est
 * indisponible — ferait dépendre une notification de sécurité de la
 * disponibilité d'un service tiers.
 */
@Getter
@Builder
public class ContexteDeSecurite {

    /** Adresse de l'appelant. Peut être nulle quand l'opération n'a pas d'échange HTTP. */
    private final String ipAddress;

    /** En-tête {@code User-Agent} brut, tel que reçu. */
    private final String userAgent;

    /** Localisation déduite de l'adresse. Nulle si indisponible. */
    private final GeoLocationData geo;

    /** Instant de l'événement. */
    private final LocalDateTime quand;

    /**
     * Localisation lisible, ou {@code null} si elle n'apporte rien.
     *
     * <p>{@code getFormattedLocation()} rend « Unknown » quand il ne sait rien,
     * ce qui est un bon défaut dans un journal et un mauvais dans un courriel :
     * « depuis Unknown » se lit comme une anomalie du système plutôt que comme
     * une information manquante. Ici, l'absence est rendue par {@code null}, et
     * la phrase est construite sans le lieu.
     */
    public String lieu() {
        if (geo == null) {
            return null;
        }
        String formate = geo.getFormattedLocation();
        return formate == null || formate.isBlank() || "Unknown".equals(formate)
                ? null
                : formate;
    }

    /** Contexte minimal, sans géolocalisation. */
    public static ContexteDeSecurite de(String ipAddress, String userAgent) {
        return ContexteDeSecurite.builder()
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .quand(LocalDateTime.now())
                .build();
    }

    /** Contexte enrichi de la localisation déjà obtenue par l'appelant. */
    public static ContexteDeSecurite de(String ipAddress, String userAgent,
                                        GeoLocationData geo) {
        return ContexteDeSecurite.builder()
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .geo(geo)
                .quand(LocalDateTime.now())
                .build();
    }
}
