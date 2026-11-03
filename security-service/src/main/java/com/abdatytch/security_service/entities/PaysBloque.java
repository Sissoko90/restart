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
 * Un pays dont le trafic est refuse, par code ISO 3166-1 alpha-2.
 *
 * <h2>Ce que remplace cette table</h2>
 *
 * {@code GeoAdminController}, dans le gateway, tenait cette liste dans Redis et
 * exposait block, unblock, block-multiple, check, info et status. La surface
 * d'administration demenage ici, et le gateway ne garde que
 * {@code GeoLocationWebFilter}, qui applique.
 *
 * <p>Le deplacement n'est pas cosmetique : un endpoint d'administration dans le
 * gateway melange deux roles. Le gateway achemine et applique ; decider quels
 * pays sont refuses est une politique, et la politique a desormais un detenteur.
 *
 * <h2>Pourquoi un motif, comme pour les adresses</h2>
 *
 * La liste Redis ne portait qu'un code pays. Un pays bloque sans motif se
 * retrouve bloque indefiniment, parce que personne n'ose lever une decision dont
 * il ignore la raison.
 */
@Table("pays_bloque")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaysBloque implements Persistable<String> {

    /**
     * Code ISO 3166-1 alpha-2, en majuscules.
     *
     * <p>Normalise par le service avant ecriture : {@code ml} et {@code ML}
     * designent le Mali, et deux lignes dont une seule est consultee est
     * exactement le genre de divergence que ce depot a deja payee.
     */
    @Id
    @Column("code_iso")
    private String codeIso;

    @Column("motif")
    private String motif;

    @Column("bloque_par")
    private String bloquePar;

    @Column("cree_le")
    private Instant creeLe;

    /** Levee sans suppression : voir {@link RegleDIp}. */
    @Column("levee_le")
    private Instant leveeLe;

    @Column("levee_par")
    private String leveePar;

    @Transient
    @Builder.Default
    private boolean nouvelle = true;

    @Override
    public String getId() {
        return codeIso;
    }

    @Override
    public boolean isNew() {
        return nouvelle;
    }

    public PaysBloque dejaEnBase() {
        this.nouvelle = false;
        return this;
    }

    public boolean estActif() {
        return leveeLe == null;
    }
}
