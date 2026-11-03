package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Critères de filtrage des journaux d'authentification.
 *
 * <h2>Pourquoi ce n'est plus une liste de paramètres de requête</h2>
 *
 * L'endpoint était {@code GET /filter?phoneNumber=...&ipAddress=...}. Une chaîne
 * de requête est journalisée <b>avec le chemin</b> par le Gateway, les proxys et
 * les outils de supervision : déplacer une donnée personnelle du chemin vers un
 * paramètre ne la protège pas, cela la déplace de quelques caractères.
 *
 * <p>Le numéro de téléphone et l'adresse IP sont tous deux des données
 * personnelles — {@code AccountErasureService} les efface explicitement à ce
 * titre. Les journaux d'accès étant hors de portée de cet effacement, les y
 * verser les rend ineffaçables.
 *
 * <p>C'était l'endpoint le plus exposé des trois : les deux autres cherchaient
 * sur un seul critère, celui-ci est le point d'entrée généraliste, donc le plus
 * appelé.
 *
 * <h2>Bornes de pagination</h2>
 *
 * {@code size} est borné. Sans borne, {@code size=2000000000} faisait construire
 * une sous-liste de la taille demandée après avoir chargé la totalité de la
 * table en mémoire. Les bornes sont posées comme <b>valeurs par défaut et
 * plafonds</b> ici, mais la vérification effective reste dans le contrôleur :
 * {@code @Min}/{@code @Max} dépendent d'un proxy de validation, et ce dépôt a
 * déjà connu 33 {@code @PreAuthorize} inertes faute de l'activation
 * correspondante.
 */
@Data
public class LogFilterRequest {

    private EventType eventType;
    private Status status;
    private String userId;

    /** Donnée personnelle : c'est la raison d'être de ce DTO. */
    private String phoneNumber;

    /** Donnée personnelle, au même titre que le numéro de téléphone. */
    private String ipAddress;

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    @Min(value = 0, message = "La page ne peut pas être négative")
    private int page = 0;

    @Min(value = 1, message = "La taille doit être d'au moins 1")
    @Max(value = 500, message = "La taille ne peut pas dépasser 500")
    private int size = 50;

    private String sortBy = "timestamp";
    private String sortDirection = "DESC";
}
