package com.abdatytch.auth_service.service;

import reactor.core.publisher.Mono;

/**
 * Service de gestion du verrouillage de compte après tentatives échouées
 * Utilise Redis réactif pour compteur distribué
 */
public interface IAccountLockoutService {
    
    /**
     * Enregistre une tentative de connexion échouée
     * @param identifier Email ou téléphone de l'utilisateur
     * @return Mono<Boolean> true si le compte est maintenant verrouillé
     */
    Mono<Boolean> recordFailedAttempt(String identifier);

    /**
     * Enregistre un échec sur un compte <b>déjà chargé</b>, et verrouille au
     * seuil.
     *
     * <p>Variante à destination des chemins de connexion, qui tiennent déjà
     * l'entité : elle évite une seconde lecture, et surtout elle évite de
     * réécrire la règle. Les deux chemins de connexion la comptaient chacun de
     * leur côté, avec leur propre seuil — c'est ainsi que le verrouillage
     * administrateur et le verrouillage utilisateur ont cessé de parler du même
     * état.
     *
     * @return {@code true} si le compte vient d'être verrouillé
     */
    Mono<Boolean> enregistrerEchec(com.abdatytch.auth_service.entities.User user);

    /**
     * Laisse passer, ou refuse si le verrou court encore.
     *
     * <p>Rend le compte inchangé s'il n'est pas verrouillé, le libère et le
     * persiste si l'échéance est passée, et <b>échoue</b> avec
     * {@code ACCOUNT_LOCKED} sinon — en indiquant les minutes restantes.
     *
     * <p>À appeler par <b>tout</b> chemin de connexion. Son absence sur la
     * connexion administrateur était la seconde moitié du défaut : cette
     * surface n'avait ni compteur d'échecs, ni vérification de verrou.
     */
    Mono<com.abdatytch.auth_service.entities.User> assurerNonVerrouille(
            com.abdatytch.auth_service.entities.User user);
    
    /**
     * Vérifie si un compte est verrouillé
     * @param identifier Email ou téléphone de l'utilisateur
     * @return Mono<Boolean> true si le compte est verrouillé
     */
    Mono<Boolean> isAccountLocked(String identifier);
    
    /**
     * Réinitialise le compteur de tentatives échouées (après connexion réussie)
     * @param identifier Email ou téléphone de l'utilisateur
     * @return Mono<Void>
     */
    Mono<Void> resetFailedAttempts(String identifier);
    
    /**
     * Déverrouille manuellement un compte (admin)
     * @param identifier Email ou téléphone de l'utilisateur
     * @return Mono<Void>
     */
    Mono<Void> unlockAccount(String identifier);

    /**
     * Verrouille un compte pour une duree donnee, sans passer par des tentatives
     * echouees.
     *
     * <p>Le controleur d'administration simulait un verrouillage en appelant
     * {@link #recordFailedAttempt(String)} <b>cinq fois</b>. Deux defauts :
     *
     * <ul>
     *   <li>le nombre d'appels etait couple a {@code auth.lockout.max-attempts},
     *       qui vaut 5 aujourd'hui mais est configurable. Porte a 10, le compte
     *       n'aurait plus ete verrouille du tout — et la reponse aurait quand
     *       meme annonce un succes ;</li>
     *   <li>la duree demandee etait <b>ignoree</b> : le verrouillage prenait la
     *       duree de configuration, tandis que la reponse rapportait la duree
     *       demandee. L'API affirmait une chose et en faisait une autre.</li>
     * </ul>
     *
     * @param identifier      compte a verrouiller
     * @param durationMinutes duree du verrouillage, en minutes
     */
    Mono<Void> lockAccount(String identifier, int durationMinutes);
    
    /**
     * Récupère le nombre de tentatives échouées restantes avant verrouillage
     * @param identifier Email ou téléphone de l'utilisateur
     * @return Mono<Integer> nombre de tentatives restantes
     */
    Mono<Integer> getRemainingAttempts(String identifier);

    /**
     * Etat complet du verrouillage, en <b>une seule</b> lecture du compte.
     *
     * <p>{@code isAccountLocked} puis {@code getRemainingAttempts} repondaient a
     * la meme question en deux allers-retours vers la base, alors que l'entite
     * porte les deux reponses. Cette methode les remplace pour l'usage
     * d'administration ; les deux autres restent pour les appelants qui n'ont
     * besoin que d'un booleen.
     *
     * <p>Un identifiant inconnu rend un etat « non verrouille, seuil complet »
     * et non une erreur : l'endpoint ne doit pas reveler l'inexistence d'un
     * compte.
     */
    Mono<com.abdatytch.auth_service.dto.response.EtatVerrouillageResponse> etatDuCompte(
            String identifier);
}
