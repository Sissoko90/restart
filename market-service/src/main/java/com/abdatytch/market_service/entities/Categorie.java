package com.abdatytch.market_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Un noeud de l'arbre du catalogue.
 *
 * <p>{@code parentId} nul designe une racine. Le {@code slug} est unique sur
 * tout l'arbre et non par niveau : une URL publique ne porte pas le chemin de
 * ses parents.
 */
@Table("categorie")
public class Categorie extends BaseEntity {

    @Column("parent_id")
    private UUID parentId;

    @Column("nom")
    private String nom;

    @Column("slug")
    private String slug;

    @Column("position")
    private Integer position;

    @Column("actif")
    private Boolean actif;

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public String getNom() { return nom; }
    public void setNom(String nom) { this.nom = nom; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }

    public Boolean getActif() { return actif; }
    public void setActif(Boolean actif) { this.actif = actif; }
}
