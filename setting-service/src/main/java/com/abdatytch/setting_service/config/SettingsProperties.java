package com.abdatytch.setting_service.config;

import com.abdatytch.setting_service.enums.SettingCategory;
import com.abdatytch.setting_service.enums.SettingType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catalogue des paramètres système : valeurs par défaut, catégorie et type.
 *
 * <p>Les défauts sont lus depuis {@code config-repo/dev/setting-service-dev.yml}.
 * La base ne stocke que les surcharges : un paramètre absent de la base prend la
 * valeur définie ici. Modifier une valeur par défaut est donc un changement de
 * configuration, pas une migration.
 *
 * <p>Le catalogue est aussi la <b>liste blanche</b> des clés modifiables : une clé
 * inconnue est rejetée, ce qui évite qu'un appelant n'injecte des paramètres
 * arbitraires dans la configuration de la plateforme.
 */
@Component
@ConfigurationProperties(prefix = "settings")
@Data
public class SettingsProperties {

    private System system = new System();
    private Cache cache = new Cache();

    /**
     * Comportement général de la plateforme.
     *
     * <p>Six champs l'ont quittée pour security-service : ils décidaient de la
     * sécurité sous l'étiquette « system ». Voir {@link #catalog()} pour la
     * correspondance exacte.
     *
     * <p>Ne reste que l'ouverture des inscriptions, qui est une décision
     * commerciale — « acceptons-nous de nouveaux clients » — et non une règle
     * de sécurité.
     */
    @Data
    public static class System {
        private boolean registrationEnabled = true;
    }

    @Data
    public static class Cache {
        private int userSettingsTtlMinutes = 60;
        private int systemSettingsTtlMinutes = 1440;
        private boolean refreshOnUpdate = true;
    }

    /**
     * Définition d'un paramètre : sa valeur par défaut, sa catégorie et son type.
     */
    public record Definition(String key, String defaultValue, SettingCategory category, SettingType type) {
    }

    /**
     * Catalogue complet, indexé par clé. L'ordre d'insertion est conservé pour que
     * la réponse de l'API reste stable et lisible.
     */
    public Map<String, Definition> catalog() {
        Map<String, Definition> catalog = new LinkedHashMap<>();

        put(catalog, "system.registration-enabled", system.isRegistrationEnabled(), SettingCategory.SYSTEM);

        // ------------------------------------------------------------------
        // CE QUI A QUITTE CE CATALOGUE, et pourquoi
        // ------------------------------------------------------------------
        // Toute la politique de sécurité est partie dans security-service, qui
        // la porte TYPÉE : chaque paramètre y a un type, un défaut, des bornes
        // et un auteur conservé. Ici, une clé inconnue était acceptée — donc
        // une faute de frappe créait un paramètre fantôme que rien ne lisait —
        // et « oui » comme booléen devenait silencieusement « false ».
        //
        // Clés déplacées, sous le même nom :
        //   security.max-login-attempts
        //   security.lockout-duration-minutes
        //   security.password-expiry-days
        //   security.force-password-change
        //
        // Clés déplacées en changeant de nom, parce qu'elles décidaient de la
        // sécurité sous l'étiquette « system » :
        //   system.email-verification-required  -> security.email-confirme-obligatoire
        //   system.phone-verification-required  -> security.telephone-confirme-obligatoire
        //   system.password-complexity-enabled  -> security.complexite-mot-de-passe
        //   system.two-factor-auth-enabled      -> security.second-facteur-obligatoire-{utilisateur,admin}
        //                                          (dédoublé : imposer le second
        //                                          facteur aux clients est une
        //                                          décision commerciale, l'imposer
        //                                          à l'administration une décision
        //                                          de sécurité)
        //   system.session-timeout-minutes      -> security.duree-session-{utilisateur,admin}-minutes
        //                                          (dédoublé pour la même raison :
        //                                          une session d'administration
        //                                          doit être courte)
        //
        // system.maintenance-mode n'a PAS d'équivalent en clé : il est remplacé
        // par les fenêtres de maintenance de security-service, qui portent un
        // message, une durée et un service. Ce booléen était de toute façon
        // écrit, journalisé en WARN, et lu par PERSONNE : aucune requête n'était
        // coupée. La table remplace donc un paramètre qui n'avait aucun effet.
        //
        // Les lignes de surcharge correspondantes sont archivées puis retirées
        // par V2__retrait_politique_securite.sql : une clé absente du catalogue
        // fait lever requireDefinition, donc sa ligne devient invisible et non
        // supprimable par l'API, tout en restant en base.

        // Les trois interrupteurs de notification ont ete RETIRES.
        //
        // Ils etaient ecrits ici, exposes par l'API d'administration,
        // modifiables — et lus par PERSONNE. Un administrateur qui desactivait
        // les SMS depuis l'interface ne changeait rien : ils continuaient de
        // partir, et de se payer.
        //
        // L'interrupteur vit desormais la ou il est applique, dans
        // notification-service et son config-repo — devenu modifiable a chaud.
        // Voir V3__retrait_interrupteurs_notifications.sql pour l'archive des
        // decisions d'administrateur.
        //
        // Ce qui RESTE dans ce service : le consentement PAR PERSONNE, cles
        // `notifications.canal.<canal>` du magasin par utilisateur. « La
        // plateforme autorise ce canal » et « ma personne accepte ce canal »
        // sont deux questions differentes.

        return catalog;
    }

    public Definition definitionOf(String key) {
        return catalog().get(key);
    }

    private void put(Map<String, Definition> catalog, String key, boolean value, SettingCategory category) {
        catalog.put(key, new Definition(key, String.valueOf(value), category, SettingType.BOOLEAN));
    }

    private void put(Map<String, Definition> catalog, String key, int value, SettingCategory category) {
        catalog.put(key, new Definition(key, String.valueOf(value), category, SettingType.INTEGER));
    }
}
