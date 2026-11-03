package com.abdatytch.user_service.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Profil complété après une inscription OAuth2, publié par auth-service.
 *
 * <h2>Cet événement n'était pas consommé</h2>
 *
 * La classe n'existait pas ici, et le listener désérialisait <b>tout</b> le
 * topic {@code user-events} en {@code UserCreatedEvent}. Une complétion de
 * profil OAuth2 était donc lue comme une création : le compte existait déjà, le
 * traitement journalisait « déjà présent » et s'arrêtait.
 *
 * <p>Conséquence concrète : quand une personne s'inscrit par Google ou Facebook
 * puis complète son profil — numéro, indicatif, date de naissance, adresse,
 * biographie, photo —, auth-service publiait bien l'information et
 * <b>user-service ne l'enregistrait jamais</b>. Le profil restait vide alors que
 * la personne venait de le remplir.
 *
 * <h2>Les champs que ce service retient</h2>
 *
 * {@code phoneNumber} et {@code countryCode} appartiennent à auth-service
 * (source de vérité de l'identité) et ne sont pas recopiés ici — ce serait un
 * second magasin pour un même état. Ce service garde ce qui lui est propre : la
 * date de naissance, l'URL de photo, et les champs déclaratifs du profil.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuth2ProfileCompletedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String authUserId;

    /** Détenu par auth-service ; reçu pour information, non persisté ici. */
    private String phoneNumber;

    /** Détenu par auth-service ; reçu pour information, non persisté ici. */
    private String countryCode;

    private LocalDate dateOfBirth;
    private String address;
    private String bio;
    private String profilePicture;

    private LocalDateTime completedAt;
    private String eventId;
}
