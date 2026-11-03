package com.abdatytch.security_service.service;

import com.abdatytch.security_service.dto.response.ParametreResponse;
import com.abdatytch.security_service.enums.ParametreDeSecurite;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Lecture et ecriture de la politique de securite.
 *
 * <h2>Ce que ce service reprend de setting-service</h2>
 *
 * {@code SettingCategory.SECURITY} portait quatre parametres en cle/valeur libre
 * : {@code max-login-attempts}, {@code lockout-duration-minutes},
 * {@code password-expiry-days}, {@code force-password-change}. Ils sont reprises
 * sous les memes cles, et retires de {@code setting-service} : deux magasins
 * pour une meme decision est le defaut qui a fait qu'un verrouillage
 * administrateur ne verrouillait personne dans ce depot.
 *
 * <h2>Ce que le typage apporte, concretement</h2>
 *
 * <ul>
 *   <li>une cle inconnue est <b>refusee</b> a l'ecriture, au lieu de creer un
 *       parametre fantome que rien ne lit ;</li>
 *   <li>une valeur du mauvais type est refusee a l'ecriture, et non decouverte
 *       a la lecture, loin de l'ecriture qui l'a causee ;</li>
 *   <li>les bornes sont verifiees : un seuil de tentatives a zero verrouillerait
 *       tout le monde a la premiere requete, et c'est un entier valide ;</li>
 *   <li>chaque changement porte son auteur et sa date, donc une revue de
 *       securite peut discuter une decision et pas seulement constater un etat.</li>
 * </ul>
 */
public interface IPolitiqueDeSecuriteService {

    /**
     * Politique complete : defauts du catalogue, surcharges appliquees.
     *
     * <p>C'est cette vue qui est publiee dans Redis. Publier les seules
     * surcharges obligerait chaque applicateur a connaitre les defauts, donc a
     * les porter en double.
     */
    Mono<Map<String, String>> politiqueComplete();

    /** Politique detaillee, pour l'administration : valeur, defaut, bornes, auteur. */
    Flux<ParametreResponse> politiqueDetaillee();

    /** Un parametre et son etat. */
    Mono<ParametreResponse> parametre(String cle);

    /**
     * Change la valeur d'un parametre.
     *
     * @param cle cle du catalogue ; une cle inconnue est refusee
     * @param valeur valeur, validee contre le type et les bornes
     * @param auteur identifiant de l'administrateur, journalise et conserve
     * @throws com.abdatytch.security_service.exception.SecurityPolicyException si
     *         la cle est inconnue, le type ne correspond pas, ou la valeur sort
     *         des bornes
     */
    Mono<ParametreResponse> changer(String cle, String valeur, String auteur);

    /**
     * Remet un parametre a son defaut.
     *
     * <p><b>Supprime</b> la surcharge au lieu d'y ecrire le defaut. Ecrire le
     * defaut figerait la valeur : un changement de defaut dans le code ne
     * s'appliquerait plus jamais a cette installation, alors qu'elle se croit
     * « remise a zero ».
     */
    Mono<ParametreResponse> remettreAuDefaut(String cle, String auteur);

    /**
     * Valeur typee d'un parametre, pour un usage interne.
     *
     * <p>Rend la valeur effective — surcharge si elle existe, defaut sinon.
     * Jamais {@code null} : le catalogue garantit un defaut pour chaque
     * parametre, ce qui supprime la question « et si le parametre n'est pas
     * configure ».
     */
    Mono<String> valeurEffective(ParametreDeSecurite parametre);
}
