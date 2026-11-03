package com.abdatytch.user_service.service;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Interface pour le service de mise à jour de la photo de profil
 */
public interface UserProfileUpdateService {
    
    Mono<String> updateProfilePicture(UUID userId, String profilePictureUrl);

    /**
     * Retire la photo de profil : la ligne, <b>et le fichier sur le disque</b>.
     *
     * <h3>Le manque que cela comble</h3>
     *
     * On pouvait envoyer une photo et jamais la retirer. La seule facon de s'en
     * debarrasser etait d'en envoyer une autre — et l'ancienne restait sur le
     * disque.
     *
     * <h3>Trois emplacements, comme l'effacement RGPD</h3>
     *
     * La colonne {@code users.profile_picture_url}, le fichier, et l'entree de
     * cache. Effacer l'URL sans supprimer le fichier laisse une photographie
     * d'identite accessible a quiconque connait le chemin : c'est l'oubli le
     * plus courant, et {@code ProfileErasureService} le documente deja.
     *
     * <p>Idempotent : un compte sans photo rend un succes sans rien faire. Un
     * refus serait inutilement bavard pour une suppression.
     */
    Mono<Void> supprimerPhotoDeProfil(UUID userId);
}
