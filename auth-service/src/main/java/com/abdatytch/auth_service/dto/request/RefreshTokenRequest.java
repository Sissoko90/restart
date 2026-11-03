package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenRequest {
    
    @NotBlank(message = AppConstants.REFRESH_TOKEN_REQUIRED_MESSAGE)
    private String refreshToken;
}
