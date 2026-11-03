package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.entities.BiometricCredential;
import com.abdatytch.auth_service.enums.BiometricType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Ce que l'utilisateur a besoin de savoir sur ses authentificateurs
 * biométriques — et rien de plus.
 *
 * <h2>Pourquoi l'entité n'est plus renvoyée directement</h2>
 *
 * {@code GET /auth/biometric/credentials} sérialisait
 * {@link BiometricCredential} en entier, donc trois champs qui n'ont aucune
 * raison de sortir du service :
 *
 * <ul>
 *   <li><b>{@code publicKey}</b> — le matériel cryptographique de
 *       l'authentificateur. Ce n'est pas un secret au sens strict, et sa
 *       divulgation ne permet pas de forger une assertion. Mais elle a déjà
 *       servi : l'ancienne vérification biométrique calculait
 *       {@code Base64(publicKey + authenticatorData + clientDataJSON + challenge)}
 *       et acceptait tout appelant capable de fournir ces valeurs. Cet endpoint
 *       livrait donc la seule des quatre que le client ne possédait pas. La
 *       vérification passe désormais par webauthn4j, mais exposer la clé reste
 *       donner gratuitement un élément d'attaque pour la prochaine erreur ;</li>
 *   <li><b>{@code aaguid}</b> — l'identifiant de <i>modèle</i> de
 *       l'authentificateur. Il désigne la marque et le modèle exacts du matériel
 *       (telle clé de telle marque, tel téléphone), ce qui contribue à
 *       l'empreinte unique de l'utilisateur ;</li>
 *   <li><b>{@code counter}</b> — l'état anti-clonage. Le connaître permet de
 *       choisir une valeur crédible si la vérification du compteur devenait
 *       contournable.</li>
 * </ul>
 *
 * <p>Ce qui reste est ce qui sert à l'usage réel de l'endpoint : reconnaître ses
 * appareils pour en révoquer un.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BiometricCredentialResponse {

    /**
     * Identifiant de l'authentificateur, nécessaire pour le révoquer.
     *
     * <p>Il est conservé parce que {@code DELETE /credentials/{credentialId}}
     * l'exige. Ce n'est pas une fuite : la suppression vérifie la propriété et
     * refuse de façon identique qu'elle soit inconnue ou qu'elle appartienne à
     * quelqu'un d'autre.
     */
    private String credentialId;

    private BiometricType biometricType;
    private String deviceName;
    private String deviceOs;
    private Boolean isActive;
    private LocalDateTime lastUsedAt;
    private LocalDateTime registeredAt;

    /**
     * {@code deviceId} est volontairement absent.
     *
     * <p>C'est un identifiant stable d'appareil, donc un traceur : il permet de
     * relier des sessions entre elles. L'utilisateur reconnaît son appareil par
     * {@code deviceName} et {@code deviceOs}, qui suffisent à l'usage.
     */
    public static BiometricCredentialResponse de(BiometricCredential credential) {
        return BiometricCredentialResponse.builder()
                .credentialId(credential.getCredentialId())
                .biometricType(credential.getBiometricType())
                .deviceName(credential.getDeviceName())
                .deviceOs(credential.getDeviceOs())
                .isActive(credential.getIsActive())
                .lastUsedAt(credential.getLastUsedAt())
                .registeredAt(credential.getRegisteredAt())
                .build();
    }
}
