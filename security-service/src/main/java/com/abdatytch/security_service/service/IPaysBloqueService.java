package com.abdatytch.security_service.service;

import com.abdatytch.security_service.dto.request.BlocageDePaysRequest;
import com.abdatytch.security_service.dto.response.PaysBloqueResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pays refuses : la decision, et son historique.
 *
 * <h2>D'ou vient cette surface</h2>
 *
 * De {@code GeoAdminController}, qui vivait dans le <b>gateway</b> et exposait
 * block, unblock, block-multiple, check, info et status. Le deplacement n'est
 * pas cosmetique : la passerelle achemine et applique, decider quels pays sont
 * refuses est une politique. Un endpoint d'administration dans la passerelle
 * melangeait les deux roles, et obligeait a y porter un magasin de decisions.
 *
 * <h2>Deux defauts corriges au passage</h2>
 *
 * <ol>
 *   <li>la validation exigeait {@code [A-Z]{2}} et etait appelee <b>avant</b> la
 *       mise en majuscules : bloquer {@code ml} etait donc refuse comme « code
 *       invalide », alors que le code suivant le normalisait. Qui saisissait un
 *       code en minuscules obtenait un refus incomprehensible ;</li>
 *   <li>{@code unblockCountry} ne validait <b>rien</b>. Un code fantaisiste
 *       rendait « non trouve », ce qui est juste par accident : la meme reponse
 *       qu'un code valide non bloque, donc aucun moyen de distinguer une faute
 *       de frappe d'un pays deja accepte.</li>
 * </ol>
 *
 * <h2>Ce service n'applique rien</h2>
 *
 * Le refus est applique par {@code GeoLocationWebFilter}, qui lit l'ensemble
 * publie dans Redis. La passerelle n'ecrit plus dans cet ensemble — elle
 * l'ensemençait au demarrage, ce qui aurait fait reapparaitre un pays a chaque
 * redemarrage pendant que la publication le faisait disparaitre.
 */
public interface IPaysBloqueService {

    /** Pays refuses, levees exclues, par code ISO croissant. */
    Flux<PaysBloqueResponse> refuses();

    /**
     * Refuse un ou plusieurs pays.
     *
     * <p>Plusieurs d'un coup parce que {@code GeoAdminController} exposait
     * {@code block-multiple}, et que l'usage reel est de refuser une liste.
     * Traiter un code par appel multiplierait les republications.
     *
     * @throws com.abdatytch.security_service.exception.SecurityPolicyException si
     *         un code n'est pas un ISO 3166-1 alpha-2 — <b>apres</b>
     *         normalisation, pour que {@code ml} soit accepte
     */
    Flux<PaysBloqueResponse> refuser(BlocageDePaysRequest requete, String auteur);

    /**
     * Accepte de nouveau un pays.
     *
     * <p>La ligne n'est pas supprimee : savoir qu'un pays a ete refuse puis
     * accepte, par qui et quand, est ce qu'un journal d'audit doit montrer.
     *
     * @throws com.abdatytch.security_service.exception.SecurityPolicyException si
     *         le code est invalide, ou si ce pays n'est pas refuse — les deux
     *         sont distingues, contrairement a l'implementation remplacee
     */
    Mono<PaysBloqueResponse> accepter(String codeIso, String auteur);
}
