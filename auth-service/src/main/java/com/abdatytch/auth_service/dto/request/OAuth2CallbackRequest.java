package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO pour le callback OAuth2 après authentification avec le provider
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuth2CallbackRequest {
    
    @NotBlank(message = AppConstants.OAUTH2_CALLBACK_CODE_REQUIRED)
    private String code;
    
    @NotBlank(message = AppConstants.OAUTH2_CALLBACK_PROVIDER_REQUIRED)
    private String provider;
    
    /**
     * Valeur anti-falsification, verifiee par {@code EtatOAuth2Redis}.
     *
     * <p>Le champ existait deja avec le commentaire « Pour la validation CSRF »,
     * et rien ne le lisait : l'URL d'autorisation ne portait aucun
     * {@code state}, et le rappel ne verifiait rien. Il est desormais consomme
     * au debut du rappel, <b>avant</b> l'echange du code.
     */
    private String state;

    /**
     * Prenom transmis par le rappel, quand le fournisseur ne le donne pas
     * autrement.
     *
     * <p>N'existe que pour Apple, et seulement a la <b>premiere</b>
     * autorisation : Apple n'a aucun point de profil et envoie le nom une seule
     * fois, dans le champ {@code user} du formulaire de rappel. Google et
     * Facebook le fournissent a chaque fois dans leur reponse de profil, et
     * laissent donc ces deux champs vides.
     *
     * <p>Valeur non fiable, venue d'un formulaire poste par un tiers : elle ne
     * sert qu'a renseigner l'etat civil, jamais a identifier. L'identite vient
     * du {@code sub} du {@code id_token} verifie.
     */
    private String prenomDuRappel;

    /** Nom de famille transmis par le rappel. Voir {@link #prenomDuRappel}. */
    private String nomDuRappel;


    @NotBlank(message = AppConstants.OAUTH2_CALLBACK_REDIRECT_URI_REQUIRED)
    private String redirectUri; // L'URI de redirection utilisée
}
