package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

import java.time.Duration;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;

/**
 * Interface pour le service de stockage (MinIO/S3)
 */
public interface StorageService {
    
    Mono<String> uploadFile(String fileName, Flux<DataBuffer> fileData, String contentType, long fileSize);
    
    Mono<String> uploadFileFromBytes(String path, byte[] content, String contentType);
    
    Mono<byte[]> downloadFile(String fileName);
    
    Mono<Void> deleteFile(String fileName);
    
    Mono<Boolean> fileExists(String fileName);

    /**
     * Un lien temporaire vers un objet du stockage, signe par MinIO.
     *
     * <h3>Pourquoi signer plutot que servir</h3>
     *
     * Servir les octets fait transiter chaque image par ce service : memoire,
     * processeur et bande passante, sur le chemin le plus frequente d'un
     * catalogue. Un lien signe laisse le navigateur telecharger DIRECTEMENT
     * depuis MinIO, et ce service ne voit plus que la demande d'autorisation.
     *
     * <h3>Le seau reste PRIVE</h3>
     *
     * Rien n'est rendu public : l'URL porte une signature et une echeance. Au
     * dela, elle ne vaut plus rien. C'est l'inverse d'une politique de seau
     * ouverte, qui serait irrevocable sans redeploiement.
     *
     * <h3>Le repli local chiffre ne peut PAS etre signe</h3>
     *
     * Quand MinIO est indisponible, les fichiers partent dans un stockage local
     * chiffre, qu'aucune URL ne designe. L'implementation rend alors un
     * {@code Mono} vide, et l'appelant retombe sur le telechargement ordinaire.
     * Pretendre l'inverse donnerait un lien mort.
     *
     * @param chemin le {@code storagePath} de l'objet
     * @param duree  la validite du lien, bornee par l'implementation
     * @return l'URL signee, ou vide si l'objet n'est pas servi par MinIO
     */
    Mono<String> genererLienSigne(String chemin, Duration duree);
    
    // `generatePresignedUrl` a ete RETIRE.
    //
    // Il fabriquait une URL pointant DIRECTEMENT sur MinIO, donc contournant la
    // passerelle — seule porte de cette plateforme —, le controle de propriete,
    // le controle de statut et le dechiffrement. Son unique appelant lui passait
    // 3600 en croyant des secondes, alors que le parametre etait en MINUTES :
    // 60 heures de validite pour un lien rendant des octets chiffres, donc
    // illisibles.
    //
    // Un risque sans benefice. `getDocumentDetails` rend desormais le chemin
    // public de telechargement, qui passe par la passerelle et par
    // `downloadDocument`.
    //
    // Si un acces direct au stockage redevient necessaire un jour, il faudra le
    // reintroduire avec une duree en SECONDES, un nom de parametre qui le dit,
    // et une decision explicite sur les trois controles qu'il contourne.
}
