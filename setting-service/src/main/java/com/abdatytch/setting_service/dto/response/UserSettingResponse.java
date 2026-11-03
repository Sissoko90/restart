package com.abdatytch.setting_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Préférence d'un utilisateur.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSettingResponse {

    private String userId;

    private String key;

    private String value;

    private Instant updatedAt;
}
