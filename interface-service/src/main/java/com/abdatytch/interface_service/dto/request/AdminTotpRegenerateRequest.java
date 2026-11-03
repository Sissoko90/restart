package com.abdatytch.interface_service.dto.request;

import jakarta.validation.constraints.NotBlank;

public class AdminTotpRegenerateRequest {

    @NotBlank(message = "Le mot de passe est obligatoire")
    private String password;

    public String getPassword() {return password;}

    public void setPassword(String password) {this.password = password;}
}
