package com.abdatytch.auth_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationResponse {
    
    private String message;
    private Boolean success;
    private Integer expiresInMinutes;
    private String phoneNumber;
    private String email;
    private Integer expiresIn;
}
