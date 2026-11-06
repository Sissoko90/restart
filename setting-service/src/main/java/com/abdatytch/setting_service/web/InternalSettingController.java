package com.abdatytch.setting_service.web;

import com.abdatytch.setting_service.dto.response.UserSettingResponse;
import com.abdatytch.setting_service.service.ISettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Les preferences d'une personne, lues par un autre service.
 *
 * <h2>Pourquoi cette surface existe</h2>
 *
 * notification-service doit savoir si une personne accepte les SMS avant de lui
 * en envoyer. Les preferences vivent ici, et les endpoints publics
 * {@code /settings/users/{userId}} sont proteges par
 * {@code ResourceOwnershipGuard} : ils exigent le jeton du <b>proprietaire</b>.
 *
 * <p>Un service qui verifie un consentement n'agit pour personne en particulier
 * et ne porte donc aucun jeton. Il lui fallait une surface a lui.
 *
 * <h2>Tout le jeu de preferences, et non une cle a la fois</h2>
 *
 * Le meme choix que pour la resolution des coordonnees : une notification
 * critique vise quatre canaux, donc quatre consentements. Un endpoint par cle
 * aurait produit quatre appels par notification — et quatre fois plus
 * d'occasions qu'une panne de ce service fasse perdre une alerte de securite.
 *
 * <h2>Ce que ce controleur ne verifie PAS, et qui le verifie</h2>
 *
 * Il ne compare aucun secret, contrairement a {@code InternalAuthController} et
 * {@code InternalJwtController} qui le font chacun dans leurs methodes.
 * {@code AutorisationInterne} s'en charge dans la chaine de securite, pour
 * {@code /internal/**} en entier.
 *
 * <p>La difference compte : un endpoint ajoute demain sous {@code /internal/}
 * est protege d'office. Ecrit dans chaque methode, le controle est quelque chose
 * qu'on peut oublier — et rien ne le signalerait.
 *
 * <h2>Aucune route de passerelle ne mene ici</h2>
 *
 * C'est ce qui rend cette surface injoignable depuis l'exterieur. Deux controles
 * la protegent par ailleurs : le pair TCP doit etre une adresse privee, et le
 * secret partage doit concorder.
 */
@RestController
@RequestMapping("/internal/settings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Interne", description = "Appels de service à service. Non routé par la passerelle.")
public class InternalSettingController {

    private final ISettingService settingService;

    /**
     * Toutes les preferences d'une personne.
     *
     * <p>Rend un flux vide quand la personne n'a rien regle — ce qui est le cas
     * de la quasi-totalite des comptes. Ce n'est pas une erreur : c'est a
     * l'appelant d'appliquer sa politique de defaut, canal par canal.
     *
     * <p>L'identifiant vient du chemin, et c'est sans danger ici : contrairement
     * aux endpoints publics, cet appel n'est pas fait au nom d'une personne mais
     * par un service deja authentifie par le secret partage. Il n'y a donc pas
     * de propriete a verifier — ce service a le droit de lire les preferences de
     * n'importe qui, et c'est precisement son travail.
     */
    @GetMapping("/users/{userId}")
    @Operation(summary = "Préférences d'une personne, pour un service",
            description = "Appel de service à service. Exige l'en-tête "
                    + "X-Internal-Secret, vérifié par la chaîne de sécurité.")
    public Flux<UserSettingResponse> preferences(@PathVariable String userId) {
        // L'identifiant N'EST PAS journalise : c'est le `sub` du porteur, donc
        // une donnee personnelle, et ce journal part vers Loki.
        return settingService.getUserSettings(userId)
                .doOnComplete(() -> log.debug("Preferences lues pour un service appelant"));
    }
}
