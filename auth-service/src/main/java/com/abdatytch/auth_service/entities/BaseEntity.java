package com.abdatytch.auth_service.entities;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Socle commun des entités R2DBC : identifiant et horodatages d'audit.
 *
 * <p><b>Pourquoi Persistable.</b> Avec un identifiant assigné par l'application
 * (ici un UUID textuel), Spring Data R2DBC ne peut pas deviner si une entité est
 * nouvelle : il émettrait un {@code UPDATE} pour un objet jamais inséré, et le
 * {@code save()} serait <b>silencieusement sans effet</b>. {@link #isNew()} lève
 * cette ambiguïté à l'aide du drapeau transitoire {@code newEntity}, remis à jour
 * après chaque lecture par {@code R2dbcAuditingConfig}.
 *
 * <p>{@code @CreationTimestamp} / {@code @UpdateTimestamp} d'Hibernate n'existent
 * pas en R2DBC : les horodatages sont posés par {@link #prepareForSave()}, appelée
 * automatiquement avant chaque écriture.
 */
@Getter
@Setter
public abstract class BaseEntity implements Persistable<String>, Serializable {

    @Id
    private String id;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    /**
     * Vrai tant que l'entité n'a pas été écrite en base.
     * Transitoire : jamais persisté, jamais relu depuis la base.
     */
    @Transient
    private boolean newEntity = true;

    @Override
    public String getId() {
        return id;
    }

    /**
     * Évalué par Spring Data <b>avant</b> la conversion, donc avant que
     * l'identifiant ne soit généré : on se fie uniquement au drapeau, jamais à
     * la présence de l'identifiant.
     */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    /** Marque l'entité comme déjà persistée (appelée après lecture). */
    public void markNotNew() {
        this.newEntity = false;
    }

    /**
     * Génère l'identifiant si besoin et met à jour les horodatages d'audit.
     */
    public void prepareForSave() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }
}
