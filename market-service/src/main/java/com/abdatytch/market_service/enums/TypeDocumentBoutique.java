package com.abdatytch.market_service.enums;

/**
 * La nature d'une piece justificative de boutique.
 *
 * <p>Le FICHIER vit dans media-service ; ce service ne garde que l'identifiant
 * qu'il rend et le role que ce document joue dans le dossier.
 */
public enum TypeDocumentBoutique {

    /** Registre du commerce et du credit mobilier. */
    RCCM,

    /** Numero d'identification fiscale. */
    NIF,

    /** Piece d'identite du gerant. */
    PIECE_IDENTITE,

    /** Tout justificatif supplementaire exige par un pays. */
    AUTRE
}
