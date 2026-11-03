package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.dto.request.AdminLoginRequest;
import com.abdatytch.auth_service.dto.request.TotpResetRequest;
import com.abdatytch.auth_service.dto.response.AuthResponse;
import com.abdatytch.auth_service.dto.response.TotpEnrolementResponse;
import com.abdatytch.auth_service.service.IAdminAuthService;
import com.abdatytch.auth_service.util.ReactiveRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Second facteur des administrateurs, et entree de connexion restreinte.
 *
 * <h2>Pourquoi ces endpoints sont publics</h2>
 *
 * Ce sont des endpoints d'<b>authentification</b> : exiger un jeton pour les
 * atteindre reviendrait a exiger d'etre deja connecte pour se connecter. Chacun
 * porte donc sa propre verification, et non celle de la couche de securite :
 *
 * <ul>
 *   <li>{@code /login} : role d'administration, mot de passe, second facteur des
 *       lors qu'il est enrole, verrouillage de compte ;</li>
 *   <li>{@code /totp/enroll} : role, mot de passe, verrouillage — et ne remet le
 *       secret que tant que le second facteur n'est pas actif ;</li>
 *   <li>{@code /totp/reset} : role, mot de passe, verrouillage, <b>et le code de
 *       l'appareil actuel</b>.</li>
 * </ul>
 *
 * <h2>La connexion passe aussi par /auth/login</h2>
 *
 * {@code /auth/login} accepte desormais les comptes d'administration, second
 * facteur compris. {@code /auth/admin/login} reste une entree <b>restreinte</b>
 * — elle refuse tout compte sans role d'administration — et delegue la connexion
 * elle-meme, sans en reecrire aucun controle.
 *
 * <h2>Appareil perdu</h2>
 *
 * {@code /totp/reset} exige le code courant, donc il est ferme a qui a perdu son
 * appareil. La sortie est
 * {@code POST /admin/accounts/{identifier}/totp/reset}, reserve au
 * {@code SUPER_ADMIN} et authentifie, qui retire le second facteur sans jamais
 * lire le secret.
 */
@RestController
@RequestMapping(AppConstants.API_BASE_PATH)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Administration - connexion et second facteur",
        description = "Connexion des administrateurs et gestion de leur second facteur")
public class AdminAuthController {

    private final IAdminAuthService adminAuthService;

    @PostMapping(AppConstants.ADMIN_LOGIN_ENDPOINT)
    @Operation(summary = "Connexion administrateur (entree restreinte)",
            description = "Identifiant, mot de passe et code TOTP. Un compte sans role "
                    + "d'administration est refuse avec la meme erreur qu'un mot de passe "
                    + "invalide. /auth/login accepte egalement ces comptes.")
    public Mono<ResponseEntity<AuthResponse>> login(
            @Valid @RequestBody AdminLoginRequest request,
            ServerWebExchange exchange) {
        return Mono.defer(() -> adminAuthService.login(
                        request,
                        ReactiveRequestUtil.getClientIp(exchange),
                        ReactiveRequestUtil.getUserAgent(exchange)))
                .map(ResponseEntity::ok);
    }

    @PostMapping(AppConstants.ADMIN_TOTP_ENROLL_ENDPOINT)
    @Operation(summary = "Enroler le second facteur (une seule fois)",
            description = "Exige le mot de passe. Ne remet le secret que tant que le "
                    + "second facteur n'est pas actif.")
    public Mono<ResponseEntity<TotpEnrolementResponse>> enrollTotp(
            @Valid @RequestBody AdminLoginRequest request) {
        return adminAuthService.enrollTotp(request).map(ResponseEntity::ok);
    }

    @PostMapping(AppConstants.ADMIN_TOTP_RESET_ENDPOINT)
    @Operation(summary = "Remplacer le second facteur",
            description = "Exige le mot de passe ET le code de l'appareil actuellement "
                    + "enrole. L'ancien appareil cesse de fonctionner immediatement. "
                    + "Si l'appareil est perdu, passer par un SUPER_ADMIN.")
    public Mono<ResponseEntity<TotpEnrolementResponse>> reinitialiserTotp(
            @Valid @RequestBody TotpResetRequest request) {
        return adminAuthService.reinitialiserTotp(request).map(ResponseEntity::ok);
    }
}
