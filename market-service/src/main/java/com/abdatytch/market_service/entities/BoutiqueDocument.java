package com.abdatytch.market_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import com.abdatytch.market_service.enums.TypeDocumentBoutique;

import java.util.UUID;

/**
 * Une piece justificative du dossier d'une boutique.
 *
 * <p>{@code documentId} designe un document de media-service. Aucun binaire ne
 * transite ni ne reside ici.
 */
@Table("boutique_document")
public class BoutiqueDocument extends BaseEntity {

    @Column("boutique_id")
    private UUID boutiqueId;

    @Column("type")
    private TypeDocumentBoutique type;

    @Column("document_id")
    private String documentId;

    public UUID getBoutiqueId() { return boutiqueId; }
    public void setBoutiqueId(UUID boutiqueId) { this.boutiqueId = boutiqueId; }

    public TypeDocumentBoutique getType() { return type; }
    public void setType(TypeDocumentBoutique type) { this.type = type; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
}
