package com.abdatytch.auth_service.exception;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException ex) {
        // Log different levels based on error type
        if (isBusinessLogicError(ex.getErrorCode())) {
            log.warn("Business logic error: {} - {}", ex.getErrorCode().getCode(), ex.getMessage());
        } else {
            log.error("Auth exception: {} - {}", ex.getErrorCode().getCode(), ex.getMessage());
        }
        
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(ex.getErrorCode().getCode())
                .message(ex.getMessage())
                .build();
        
        HttpStatus status = determineHttpStatus(ex.getErrorCode());
        return ResponseEntity.status(status).body(response);
    }
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        log.error(AppConstants.LOG_VALIDATION_ERROR, ex.getMessage());
        
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(ErrorCode.VALIDATION_ERROR.getCode())
                .message(ErrorCode.VALIDATION_ERROR.getMessage())
                .details(errors)
                .build();
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
    
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleWebExchangeBindException(WebExchangeBindException ex) {
        log.error(AppConstants.LOG_VALIDATION_ERROR, ex.getMessage());
        
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(ErrorCode.VALIDATION_ERROR.getCode())
                .message(ErrorCode.VALIDATION_ERROR.getMessage())
                .details(errors)
                .build();
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
    
    /**
     * Ressource absente : <b>404</b>, et non 500.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * {@code ResourceNotFoundException} n'avait aucun gestionnaire et tombait
     * donc dans {@link #handleGenericException}. Mesuré sur l'environnement de
     * développement :
     *
     * <pre>
     * GET /auth-users/00000000-0000-0000-0000-000000000000
     *   -> 500 {"code":"SYS_5001","message":"Erreur interne du serveur"}
     * </pre>
     *
     * Un identifiant inexistant — cas d'usage parfaitement normal — était
     * annoncé comme une défaillance du serveur. Deux conséquences : l'appelant
     * ne peut pas distinguer « cette ressource n'existe pas » de « le service
     * est en panne », et la supervision compte de fausses erreurs 5xx.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Ressource absente : {}", ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(ErrorCode.USER_NOT_FOUND.getCode())
                .message(ex.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Argument invalide : <b>400</b>, et le message est conservé.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * Les règles métier de {@code UserRoleManagementServiceImpl} lèvent un
     * {@code IllegalArgumentException} porteur d'une explication :
     *
     * <ul>
     *   <li>« Impossible de supprimer le dernier rôle de l'utilisateur » ;</li>
     *   <li>« Impossible de supprimer le rôle principal. Changez d'abord le
     *       rôle principal. »</li>
     * </ul>
     *
     * Sans gestionnaire, les deux rendaient <b>500 « Erreur interne du
     * serveur »</b> et <b>le message était perdu</b> : l'administrateur
     * apprenait que le serveur avait un problème, au lieu d'apprendre ce qu'il
     * devait faire.
     *
     * <h3>Pourquoi cette correspondance est légitime, et où est sa limite</h3>
     *
     * {@code IllegalArgumentException} dit, par contrat, que
     * l'<b>appelant</b> a fourni une valeur invalide : c'est un 400. Les sept
     * sites du service y correspondent tous — fournisseur OAuth inconnu, jeton
     * absent, règle de rôle violée.
     *
     * <p>Sa limite est qu'un défaut de programmation levant le même type
     * deviendrait un 400 et cesserait d'être visible comme un bug. La
     * distinction à tenir est celle que la bibliothèque standard pose déjà :
     * {@code IllegalArgumentException} pour un argument de l'appelant,
     * {@code IllegalStateException} ou {@code NullPointerException} pour un
     * état interne incohérent — et ces deux-là restent en 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Requête invalide : {}", ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(ErrorCode.VALIDATION_ERROR.getCode())
                .message(ex.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Entrée de requête invalide : <b>400</b>, et non 500.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * {@code ServerWebInputException} est la classe de base de Spring pour tout
     * ce que l'appelant a mal fourni en entrée : paramètre obligatoire absent,
     * valeur inconvertible vers le type attendu, corps illisible. Seule sa
     * sous-classe {@code WebExchangeBindException} avait un gestionnaire ; tout
     * le reste tombait dans {@link #handleGenericException}.
     *
     * <p>Mesuré sur l'environnement de développement, avec
     * {@code POST /auth/biometric/register/initiate} dont le paramètre
     * {@code type} est obligatoire :
     *
     * <pre>
     * ?type=FINGERPRINT   -> 200
     * ?type=INEXISTANT    -> 500   (valeur hors de l'énumération)
     * (sans parametre)    -> 500   (paramètre obligatoire absent)
     * </pre>
     *
     * <p>Ce n'est pas propre à la biométrie : <b>tout</b> endpoint prenant un
     * {@code @RequestParam} obligatoire ou une énumération répondait 500 à une
     * requête simplement mal formée. L'appelant ne pouvait pas distinguer sa
     * propre erreur d'une panne, et la supervision comptait de fausses
     * défaillances serveur.
     *
     * <p>La raison de Spring est reprise telle quelle — « Required query
     * parameter 'type' is not present » — parce qu'elle décrit ce que
     * l'appelant doit corriger. Elle ne contient que des noms de paramètres et
     * la valeur qu'il a lui-même envoyée.
     */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> handleServerWebInput(ServerWebInputException ex) {
        log.warn("Entrée de requête invalide : {}", ex.getReason());

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(ErrorCode.VALIDATION_ERROR.getCode())
                .message(ex.getReason() == null
                        ? ErrorCode.VALIDATION_ERROR.getMessage()
                        : ex.getReason())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error(AppConstants.AUTH_UNEXPECTED_EXCEPTION, ex);
        
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(ErrorCode.INTERNAL_SERVER_ERROR.getCode())
                .message(ErrorCode.INTERNAL_SERVER_ERROR.getMessage())
                .build();
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
    
    /**
     * Distingue une erreur attendue d'une defaillance du systeme.
     *
     * <p>Le test porte sur le <b>nom</b> de la constante et non sur sa chaine de
     * code. {@code getCode()} rend {@code "AUTH_1001"} : y chercher
     * {@code "INVALID_CREDENTIALS"} ne pouvait jamais aboutir. Le prefixe, lui,
     * est bien dans la chaine — c'est ce qui masquait le defaut, puisque les
     * regles par prefixe fonctionnaient.
     */
    private boolean isBusinessLogicError(ErrorCode errorCode) {
        String code = errorCode.getCode();
        String nom = errorCode.name();

        return code.startsWith("VER_")
                || code.startsWith("VAL_")
                || (code.startsWith("AUTH_") && (nom.contains("INVALID_CREDENTIALS")
                        || nom.contains("ACCOUNT_LOCKED")
                        || nom.contains("NOT_VERIFIED")))
                || (code.startsWith("REG_") && (nom.contains("ALREADY_EXISTS")
                        || nom.contains("INVALID_FORMAT")))
                || (code.startsWith("2FA_") && nom.contains("INVALID"));
    }

    /**
     * Statut HTTP associe a un code d'erreur.
     *
     * <h2>Le defaut corrige</h2>
     *
     * Les deux branches {@code NOT_FOUND} et {@code EXISTS} testaient
     * {@code code.contains(...)} sur la chaine de code, qui vaut
     * {@code "USER_4001"} et ne contient ni l'un ni l'autre. Elles ne pouvaient
     * donc <b>jamais</b> s'appliquer : <b>17 des 42 codes</b> tombaient dans le
     * {@code else} final et rendaient <b>500</b>, dont {@code USER_NOT_FOUND},
     * {@code TEMPLATE_NOT_FOUND} et {@code TWO_FA_SETUP_NOT_FOUND}. Aucun code
     * ne rendait jamais 404 ni 409.
     *
     * <p>Rendre 500 sur « utilisateur non trouve » n'est pas un detail : le
     * client ne peut pas distinguer une absence d'une panne, la supervision
     * compte des defaillances serveur sur un fonctionnement normal, et les
     * alertes qui en decoulent finissent par etre ignorees.
     *
     * <p>Le test porte desormais sur {@code name()}. Les regles par prefixe sont
     * conservees telles quelles : elles portent sur la chaine de code, ou le
     * prefixe est effectivement present.
     *
     * <h2>404 et non 403 sur un refus</h2>
     *
     * La politique du depot veut qu'un acces refuse sur une ressource dont on
     * n'est pas proprietaire rende 404. Un 403 confirmerait l'existence de la
     * ressource, ce qui permet de l'enumerer.
     */
    private HttpStatus determineHttpStatus(ErrorCode errorCode) {
        String code = errorCode.getCode();
        String nom = errorCode.name();

        if (nom.contains("NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        } else if (nom.contains("ALREADY") || nom.contains("EXISTS")) {
            // « ALREADY » et non seulement « ALREADY_EXISTS ».
            //
            // TWO_FA_ALREADY_ENABLED dit « ENABLED », pas « EXISTS » : il ne
            // satisfaisait aucune branche et retombait sur le `else` final,
            // donc 500. Un conflit d'etat annonce comme une defaillance
            // serveur — le meme defaut que celui corrige plus haut pour
            // USER_NOT_FOUND et TEMPLATE_NOT_FOUND, qui testaient « NOT_FOUND »
            // dans la CHAINE de code au lieu du NOM de la constante.
            return HttpStatus.CONFLICT;
        } else if (nom.contains("UNAVAILABLE")) {
            // 503 et non 500, et la difference n'est pas cosmetique.
            //
            // 500 dit « ce service est defaillant » ; 503 dit « une dependance
            // ne repond pas, reessaie plus tard ». C'est ce que lit un client
            // pour decider s'il reessaie, et ce que lit une sonde pour decider
            // si l'instance doit sortir du pool.
            //
            // SERVICE_UNAVAILABLE porte le prefixe SYS_, qui ne satisfaisait
            // aucune branche : il retombait sur le `else` final. Le delai
            // maximal pose sur l'autorite JWT rendait donc 500 — accusant
            // auth-service d'une panne d'interface-service.
            //
            // Teste sur le NOM et non sur le prefixe du code, comme la branche
            // « ALREADY » ci-dessus, et pour la meme raison : le prefixe SYS_
            // regroupe des causes de gravites differentes.
            return HttpStatus.SERVICE_UNAVAILABLE;
        } else if (code.startsWith("AUTH_")) {
            return HttpStatus.UNAUTHORIZED;
        } else if (code.startsWith("REG_") || code.startsWith("VER_") || code.startsWith("VAL_")) {
            return HttpStatus.BAD_REQUEST;
        } else {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }
}
