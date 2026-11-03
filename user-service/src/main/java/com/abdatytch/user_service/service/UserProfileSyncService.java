package com.abdatytch.user_service.service;

import com.abdatytch.user_service.event.OAuth2ProfileCompletedEvent;
import reactor.core.publisher.Mono;

/**
 * Applique au profil local ce qu'auth-service publie à son sujet.
 *
 * <h2>Pourquoi un service à part</h2>
 *
 * La synchronisation venue d'un événement n'est pas une modification demandée
 * par une personne : elle ne vérifie pas de droits, ne mesure pas de décision
 * d'administration, et doit être <b>idempotente</b> — Kafka reproposera le même
 * événement si le traitement échoue à mi-chemin.
 *
 * <p>La mettre dans {@code UserService} aurait mélangé ces règles avec celles de
 * la modification par le porteur, et {@code UserAdminService} porte celles de
 * l'administration. Trois provenances, trois jeux de règles, une seule écriture
 * en base.
 */
public interface UserProfileSyncService {

    /**
     * Enregistre les champs d'un profil complété après inscription OAuth2.
     *
     * <p>Ne touche que ce que ce service détient : date de naissance, URL de
     * photo, adresse et biographie. Le numéro et l'indicatif figurent dans
     * l'événement et sont ignorés — auth-service les détient.
     *
     * <p>Les champs nuls de l'événement <b>n'effacent pas</b> l'existant : une
     * complétion partielle complète, elle ne remplace pas. C'est ce qui rend
     * l'opération rejouable sans perte.
     *
     * @return vide ; une erreur signifie que Kafka doit reproposer l'événement
     */
    Mono<Void> appliquerCompletionOAuth2(OAuth2ProfileCompletedEvent evenement);
}
