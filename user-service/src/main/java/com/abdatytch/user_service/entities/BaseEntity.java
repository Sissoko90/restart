package com.abdatytch.user_service.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Socle commun des entités R2DBC : identifiant et horodatages d'audit.
 *
 * <h2>L'identifiant est produit par la BASE</h2>
 *
 * {@code id} reste nul jusqu'à l'insertion, et {@code gen_random_uuid()} le
 * produit — voir la migration {@code V1__init_user_schema.sql}. C'est ce qui
 * dispense ce service de {@code Persistable} : Spring Data R2DBC décide qu'une
 * ligne est neuve à l'identifiant nul, et il l'est tant que personne ne
 * l'assigne.
 *
 * <p>La contrepartie est une règle à tenir : <b>ne jamais appeler
 * {@code setId()} avant une écriture</b>. Un identifiant déjà posé ferait
 * émettre un {@code UPDATE} pour une ligne jamais insérée, et le {@code save()}
 * serait silencieusement sans effet — exactement le défaut qu'auth-service a
 * payé, et qu'il a dû résoudre par {@code Persistable} parce que ses
 * identifiants, eux, sont assignés par l'application.
 * {@code IdentifiantLaisseALaBaseTest} surveille cette règle.
 *
 * <h2>Les horodatages ne sont pas annotés</h2>
 *
 * {@code @CreatedDate} et {@code @LastModifiedDate} ont été retirés : ils
 * exigent {@code @EnableR2dbcAuditing}, donc le contexte de mapping R2DBC, donc
 * l'auto-configuration complète — que le profil de test exclut. Le contexte ne
 * pouvait pas démarrer en test, et ce service n'avait aucun test de contexte
 * pour le révéler.
 *
 * <p>{@link #preparerAvantEcriture()} les pose désormais, appelée par le rappel
 * de {@code R2dbcAuditingConfig}. Même mécanisme qu'auth-service.
 */
public abstract class BaseEntity {

    @Id
    private UUID id;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    /**
     * Met à jour les horodatages d'audit avant écriture.
     *
     * <p>N'assigne <b>pas</b> l'identifiant : voir la javadoc de la classe.
     */
    public void preparerAvantEcriture() {
        LocalDateTime maintenant = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = maintenant;
        }
        updatedAt = maintenant;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
