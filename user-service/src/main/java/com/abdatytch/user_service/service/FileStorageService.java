package com.abdatytch.user_service.service;

import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

/**
 * Stockage des photos de profil.
 *
 * <h2>Les deux methodes du monde servlet ont ete retirees</h2>
 *
 * {@code storeProfilePicture(MultipartFile, String)} et
 * {@code isValidImage(MultipartFile)} prenaient un {@code MultipartFile}, le
 * type bloquant du monde servlet. Ce service est en WebFlux : aucun
 * {@code MultipartFile} n'y est jamais produit, donc ni l'une ni l'autre
 * n'etait atteignable.
 *
 * <p>Le cout n'etait pas seulement du code mort : {@code isValidImage} portait
 * la validation — liste blanche d'extensions, plafond de taille, type de
 * contenu — et le chemin reactif a du la reimplementer. La meme regle existait
 * donc en deux exemplaires, l'un vivant et l'autre inatteignable, avec le risque
 * habituel de corriger celui qui ne tourne pas.
 */
public interface FileStorageService {

    /**
     * Stocke un fichier de manière réactive et retourne l'URL d'accès
     * @param filePart Le fichier à stocker (FilePart réactif)
     * @param userId L'ID de l'utilisateur (pour organiser les fichiers)
     * @return Mono contenant l'URL d'accès au fichier
     */
    Mono<String> storeFile(FilePart filePart, String userId);
    
    /**
     * Supprime un fichier de profil
     * @param fileUrl L'URL du fichier à supprimer
     */
    void deleteProfilePicture(String fileUrl);
}
