package com.abdatytch.auth_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Critère de recherche des journaux d'authentification par numéro de téléphone.
 *
 * <h2>Pourquoi le numéro passe par le CORPS de la requête</h2>
 *
 * L'endpoint était {@code GET /admin/audit/logs/phone/{phoneNumber}} : le numéro
 * figurait dans le <b>chemin de l'URL</b>. Une URL n'est pas un canal
 * confidentiel, même en HTTPS — elle est écrite en clair dans :
 *
 * <ul>
 *   <li>les journaux d'accès du Gateway et de tout proxy traversé ;</li>
 *   <li>les traces de supervision et les outils de performance, qui
 *       regroupent par URL ;</li>
 *   <li>l'historique du navigateur et l'en-tête {@code Referer} ;</li>
 *   <li>les journaux d'erreurs, qui citent l'URL appelée.</li>
 * </ul>
 *
 * <p>Le problème n'est pas seulement la divulgation. Ces copies sont <b>hors de
 * portée de l'effacement RGPD</b> : {@code AccountErasureService} sait écraser
 * une colonne de base de données, il ne sait pas retirer un numéro des journaux
 * d'accès d'un proxy. Une donnée personnelle versée dans une URL devient
 * ineffaçable.
 *
 * <p>Un paramètre de requête ({@code ?phone=...}) ne réglerait rien : la chaîne
 * de requête est journalisée avec le chemin. Seul le <b>corps</b> échappe aux
 * journaux d'accès, ce qui impose un {@code POST} — d'où
 * {@code POST /admin/audit/logs/search}. Ce n'est pas une création de ressource,
 * mais c'est la seule méthode dont le corps est conventionnellement transmis.
 */
@Data
public class AuditPhoneSearchRequest {

    /**
     * Le numéro recherché, au format E.164.
     *
     * <p>Le motif est celui employé ailleurs dans le service. Attention, il
     * accepte <b>deux caractères</b> : tout traitement en aval doit s'en
     * souvenir — un {@code substring(0, 4)} posé ici avait déjà produit une
     * erreur 500 sur {@code /auth/resend-code}.
     */
    @NotBlank(message = "Le numéro de téléphone est obligatoire")
    @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$",
            message = "Le numéro doit être au format E.164")
    private String phoneNumber;
}
