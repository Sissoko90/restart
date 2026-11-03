package com.abdatytch.user_service.dto.request;

import com.abdatytch.user_service.constants.ValidationMessages;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Map;

/**
 * Modification du profil par son porteur. Tous les champs sont optionnels.
 *
 * <h2>Ce qui a été retiré, et pourquoi</h2>
 *
 * {@code firstName} et {@code lastName} figuraient ici, validés en taille, et
 * {@code UserServiceReactiveImpl.updateUser} les <b>jetait</b> — avec un
 * commentaire disant qu'ils vivent dans auth-service. Le contrat promettait donc
 * une modification que rien n'effectuait : la personne changeait son prénom,
 * recevait un 200, et rien ne bougeait.
 *
 * <p>Les retirer plutôt que les implémenter : auth-service est la source de
 * vérité de l'identité, et les écrire ici créerait un second magasin qui
 * divergerait. Un frontal qui les envoie encore n'est pas cassé — Jackson ignore
 * les propriétés inconnues — mais le contrat ne ment plus.
 *
 * <h2>Ce qui a été ajouté</h2>
 *
 * {@code preferences}. Elles étaient <b>stockées</b> (colonne
 * {@code user_profiles.preferences}), <b>rendues</b> dans {@code UserDetailDTO},
 * <b>effacées</b> par le RGPD — et <b>jamais modifiables</b>. Le seul appel à
 * {@code setPreferences} de tout le service était celui de l'effacement, qui les
 * vide. Une donnée que l'API montre et que personne ne peut écrire.
 */
public record UserUpdateDTO(

    @Past(message = ValidationMessages.DATE_OF_BIRTH_PAST)
    LocalDate dateOfBirth,

    @Size(max = 1000, message = ValidationMessages.BIO_MAX_SIZE)
    String bio,

    @Size(max = 500, message = ValidationMessages.ADDRESS_MAX_SIZE)
    String address,

    @Size(max = 100, message = ValidationMessages.CITY_MAX_SIZE)
    String city,

    @Size(max = 100, message = ValidationMessages.COUNTRY_MAX_SIZE)
    String country,

    @Pattern(regexp = ValidationMessages.REGEX_POSTAL_CODE,
            message = ValidationMessages.POSTAL_CODE_PATTERN)
    String postalCode,

    /**
     * Préférences libres, remplacées en bloc quand le champ est fourni.
     *
     * <p><b>Remplacées et non fusionnées</b>, et c'est le choix structurant :
     * une fusion rendrait impossible la <i>suppression</i> d'une préférence —
     * l'appelant n'aurait aucun moyen d'exprimer « retire cette clef ». Envoyer
     * une carte vide efface tout ; ne pas envoyer le champ ne touche à rien.
     *
     * <p>Les bornes ne sont pas décoratives. C'est une carte libre venue du
     * client, écrite dans une colonne {@code TEXT} : sans plafond, un seul appel
     * y place des mégaoctets, et chaque lecture du profil les relit ensuite.
     * {@code @Size} porte sur le <b>nombre d'entrées</b> ; la longueur des clefs
     * et des valeurs est vérifiée par {@link #preferencesDansLesBornes()},
     * qu'aucune annotation standard ne sait exprimer.
     */
    @Size(max = MAX_PREFERENCES, message = ValidationMessages.PREFERENCES_MAX_ENTREES)
    Map<String, String> preferences
) {

    /** Au-delà, ce n'est plus une préférence d'affichage mais un stockage. */
    public static final int MAX_PREFERENCES = 50;
    public static final int MAX_LONGUEUR_CLEF = 64;
    public static final int MAX_LONGUEUR_VALEUR = 512;

    /**
     * Vrai si chaque clef et chaque valeur tient dans ses bornes.
     *
     * <p>Vérifié par le service plutôt que par une annotation : {@code @Size}
     * sur une {@code Map} contraint sa taille, pas la longueur de son contenu,
     * et il n'existe pas d'annotation standard pour « chaque valeur fait au plus
     * n caractères ».
     */
    public boolean preferencesDansLesBornes() {
        if (preferences == null) {
            return true;
        }
        return preferences.entrySet().stream().allMatch(entree ->
                entree.getKey() != null
                        && entree.getKey().length() <= MAX_LONGUEUR_CLEF
                        && (entree.getValue() == null
                            || entree.getValue().length() <= MAX_LONGUEUR_VALEUR));
    }
}
