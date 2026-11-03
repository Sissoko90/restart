package com.abdatytch.interface_service.dto.response;

public class TotpConfigResponse {
    private String email;
    private String phoneNumber;
    private String totpEnabled;
    private String qrCodeUrl;
    private String secret;

    public TotpConfigResponse() {}

    public TotpConfigResponse(String email, String phoneNumber, Boolean totpEnabled, String qrCodeUrl, String secret) {
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.totpEnabled = totpEnabled.toString();
        this.qrCodeUrl = qrCodeUrl;
        this.secret = secret;
    }

    public String getEmail() {return email;}

    public void setEmail(String email) {this.email = email;}

    public String getPhoneNumber() {return phoneNumber;}

    public void setPhoneNumber(String phoneNumber) {this.phoneNumber = phoneNumber;}

    public String getTotpEnabled() {return totpEnabled;}

    public void setTotpEnabled(String totpEnabled) {this.totpEnabled = totpEnabled;}

    public String getQrCodeUrl() {return qrCodeUrl;}

    public void setQrCodeUrl(String qrCodeUrl) {this.qrCodeUrl = qrCodeUrl;}

    public String getSecret() {return secret;}

    public void setSecret(String secret) {this.secret = secret;}
}
