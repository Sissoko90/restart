package com.abdatytch.auth_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Identifiants du compte SUPER_ADMIN, cree au deploiement par
 * {@link SuperAdminSeeder}.
 *
 * <p>Volontairement <b>distincts</b> des {@code admin.default.*} : partager les
 * identifiants entre le responsable technique et l'administrateur fonctionnel
 * annulerait la separation que ces deux roles etablissent.
 *
 * <p>Aucune valeur par defaut. Si les trois champs obligatoires ne sont pas
 * fournis, le seeder ne cree rien et le signale : un compte administrateur aux
 * identifiants lisibles dans le depot est pire que pas de compte du tout.
 */
@Component
@ConfigurationProperties(prefix = "super-admin")
public class SuperAdminProperties {

    private String email;
    private String phone;
    private String password;
    private String countryCode = "ML";
    private String firstName = "Super";
    private String lastName = "Admin";

    /**
     * @return {@code true} si les trois champs sans lesquels aucun compte
     *         utilisable ne peut exister sont renseignes.
     */
    public boolean estConfigure() {
        return estRenseigne(email) && estRenseigne(phone) && estRenseigne(password);
    }

    private boolean estRenseigne(String valeur) {
        return valeur != null && !valeur.isBlank();
    }

    public String email() { return email; }
    public String phone() { return phone; }
    public String password() { return password; }
    public String countryCode() { return countryCode; }
    public String firstName() { return firstName; }
    public String lastName() { return lastName; }

    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setPassword(String password) { this.password = password; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
}
