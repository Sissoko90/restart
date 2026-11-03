package com.abdatytch.user_service.dto.request;

import com.abdatytch.user_service.constants.ValidationMessages;
import com.abdatytch.user_service.enums.UserRole;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.Set;

/**
 * Création d'un compte local, à partir de l'identifiant publié par auth-service.
 *
 * <h2>Deux groupes de champs, et un seul compte vraiment</h2>
 *
 * <ul>
 *   <li>{@code authUserId} et les quatre champs <b>locaux</b>
 *       — {@code dateOfBirth}, {@code address}, {@code bio},
 *       {@code profilePicture} — sont ce que ce service détient et
 *       enregistre ;</li>
 *   <li>{@code email}, {@code phoneNumber}, {@code countryCode},
 *       {@code firstName}, {@code lastName}, {@code role}, {@code roles} et
 *       {@code primaryRole} appartiennent à <b>auth-service</b>, qui en est la
 *       source de vérité. Ils sont acceptés pour ne pas rompre le contrat
 *       d'appel existant, et <b>délibérément ignorés</b> : les enregistrer ici
 *       créerait un second magasin pour un même état, qui divergerait à la
 *       première modification faite dans auth-service. {@code UserMapper} les
 *       lit à la demande.</li>
 * </ul>
 *
 * <h2>Les champs locaux étaient ignorés aussi</h2>
 *
 * {@code UserMapper.toEntity} ne reprenait que {@code authUserId} : les quatre
 * champs locaux étaient acceptés par {@code POST /internal/users} et
 * <b>jetés</b>. Un appelant qui fournissait une date de naissance recevait un
 * 201 et un profil vide. Ils sont désormais appliqués.
 */
public record UserCreationDTO(
    @NotBlank(message = ValidationMessages.AUTH_USER_ID_REQUIRED)
    String authUserId,

    // --- Détenus par auth-service : acceptés, non persistés ---
    String email,
    String phoneNumber,
    String countryCode,
    String firstName,
    String lastName,
    UserRole role,
    Set<UserRole> roles,
    UserRole primaryRole,

    // --- Détenus par ce service ---
    LocalDate dateOfBirth,
    String address,
    String bio,
    String profilePicture
) {

    /**
     * Création minimale : l'identifiant seul.
     *
     * <p>C'est le cas du listener Kafka, qui n'a que l'identifiant à enregistrer.
     * La forme littérale — douze {@code null} alignés — était illisible et
     * fragile : ajouter un champ au milieu du record décalait silencieusement
     * tous les autres, sans erreur de compilation puisqu'ils sont presque tous
     * des {@code String}.
     */
    public static UserCreationDTO pourAuthUserId(String authUserId) {
        return new UserCreationDTO(authUserId,
                null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    /** Vrai si l'appelant a fourni au moins un champ de profil déclaratif. */
    public boolean aDesChampsDeProfil() {
        return address != null || bio != null;
    }
}
