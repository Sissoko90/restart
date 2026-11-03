package com.abdatytch.auth_service.entities;

import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table("refresh_tokens")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken extends BaseEntity {

    /**
     * R2DBC ne gère pas les relations : l'ancien @ManyToOne vers User devient la
     * clé étrangère nue. Les appelants qui faisaient {@code token.getUser().getId()}
     * utilisent désormais {@code token.getUserId()}.
     */
    @Column("user_id")
    private String userId;

    /**
     * SHA-256 du secret porteur, en hexadecimal.
     *
     * <p>Et non le secret lui-meme, comme c'etait le cas. Un jeton de
     * rafraichissement suffit a obtenir un jeton d'acces neuf, indefiniment,
     * sans mot de passe ni second facteur : cette colonne etait une liste de
     * sessions pretes a l'emploi. Le service masquait pourtant deja ces jetons
     * dans les journaux — la precaution la plus visible etait prise, la plus
     * importante ne l'etait pas.
     *
     * <p>Voir {@code EmpreinteDeSecret} dans platform-commons pour la raison du
     * choix de SHA-256 plutot que d'une fonction a sel, et la migration
     * {@code V8} pour le sort des lignes existantes.
     */
    @Column("empreinte")
    private String empreinte;

    /**
     * Le secret en clair, le temps d'un appel.
     *
     * <p>{@link Transient} : jamais ecrit, jamais relu. Il n'est renseigne que
     * par {@code createRefreshToken}, parce que l'appelant doit le transmettre
     * au client — c'est la seule et derniere occasion de le connaitre.
     *
     * <p>Une entite chargee depuis la base le porte donc a {@code null}. C'est
     * pourquoi on y accede par {@link #valeurAEnvoyerAuClient()}, qui leve,
     * plutot que par le lecteur engendre, qui rendrait {@code null} en silence
     * et ferait parvenir au client un jeton vide.
     */
    @Transient
    private String valeurEnClair;

    /**
     * @return le secret a transmettre au client
     * @throws IllegalStateException si cette entite ne vient pas d'etre creee.
     *         Lever ici plutot que rendre {@code null} : un jeton de
     *         rafraichissement absent de la reponse ne se verrait qu'au
     *         rafraichissement suivant, cote client, loin de la cause.
     */
    public String valeurAEnvoyerAuClient() {
        if (valeurEnClair == null) {
            throw new IllegalStateException(
                    "Le secret en clair n'existe qu'a la creation du jeton : une entite "
                            + "chargee depuis la base ne le porte pas, et la base ne garde "
                            + "que son empreinte");
        }
        return valeurEnClair;
    }


    @Column("is_revoked")
    @Builder.Default
    private Boolean isRevoked = false;
    
    @Column("expires_at")
    private LocalDateTime expiresAt;
    
    @Column("ip_address")
    private String ipAddress;
    
    @Column("user_agent")
    private String userAgent;
    
    @Column("revoked_at")
    private LocalDateTime revokedAt;
    
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    public boolean isValid() {
        return !isRevoked && !isExpired();
    }
}
