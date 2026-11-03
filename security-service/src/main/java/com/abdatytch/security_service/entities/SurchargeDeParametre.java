package com.abdatytch.security_service.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Valeur d'un parametre de securite, lorsqu'elle differe du defaut.
 *
 * <h2>Seules les surcharges sont en base</h2>
 *
 * Les defauts vivent dans
 * {@link com.abdatytch.security_service.enums.ParametreDeSecurite}, en code.
 * Remettre un parametre a son defaut consiste donc a <b>supprimer</b> la ligne,
 * pas a y reecrire la valeur d'origine — sans quoi un changement de defaut dans
 * le code ne s'appliquerait jamais aux installations qui ont « remis a zero ».
 *
 * <p>C'est le meme choix que {@code SystemSetting} dans {@code setting-service},
 * et il est conserve volontairement : la migration des quatre parametres de
 * securite existants devient une copie de lignes.
 *
 * <h2>Qui et quand, pas seulement quoi</h2>
 *
 * Un seuil de verrouillage porte a vingt est une decision de securite. Savoir
 * qu'il vaut vingt ne suffit pas : il faut savoir qui l'a porte la et quand,
 * sinon la revue de securite constate un etat sans pouvoir le discuter. C'est
 * ce que le magasin cle/valeur de {@code setting-service} ne permettait pas.
 */
@Table("surcharge_parametre")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurchargeDeParametre implements Persistable<String> {

    /** Cle du catalogue, ex. {@code security.max-login-attempts}. */
    @Id
    @Column("cle")
    private String cle;

    /**
     * Valeur, toujours stockee en texte.
     *
     * <p>Le type reel est porte par le catalogue, qui valide et convertit. Une
     * colonne par type aurait demande cinq colonnes dont quatre nulles a chaque
     * ligne, et un type ajoute aurait demande une migration.
     */
    @Column("valeur")
    private String valeur;

    @Column("modifie_par")
    private String modifiePar;

    @Column("modifie_le")
    private Instant modifieLe;

    @Transient
    @Builder.Default
    private boolean nouvelle = true;

    @Override
    public String getId() {
        return cle;
    }

    @Override
    public boolean isNew() {
        return nouvelle;
    }

    public SurchargeDeParametre dejaEnBase() {
        this.nouvelle = false;
        return this;
    }
}
