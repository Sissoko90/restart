package com.abdatytch.security_service.service;

import com.abdatytch.security_service.dto.request.OuvertureDeMaintenanceRequest;
import com.abdatytch.security_service.dto.response.MaintenanceResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Fenetres de maintenance : couper, annoncer, lever.
 *
 * <h2>Le defaut que ce service corrige</h2>
 *
 * {@code setting-service} declarait {@code system.maintenance-mode}, le
 * journalisait en {@code WARN} avec un message dedie — et <b>rien ne le
 * lisait</b>. Verifie : la cle n'apparait que dans {@code setting-service}
 * lui-meme, et le gateway ne l'interroge jamais. Un administrateur pouvait donc
 * « mettre le site en maintenance », voir la confirmation dans l'interface et
 * dans les journaux, et le trafic continuait de passer.
 *
 * <p>C'est le motif le plus frequent de ce depot : une protection ecrite,
 * confirmee a son auteur, et jamais atteinte.
 *
 * <h2>Ce que ce service ne fait pas</h2>
 *
 * Il ne refuse aucune requete. La coupure est appliquee par
 * {@code MaintenanceWebFilter}, dans le gateway, qui lit la fenetre dans Redis.
 * Faire appeler ce service a chaque requete mettrait un saut reseau sur le
 * chemin critique — et son indisponibilite arreterait la plateforme, ce qui
 * serait une panne causee par l'outil censé l'annoncer.
 */
public interface IMaintenanceService {

    /** Fenetres non levees, a venir comprises. */
    Flux<MaintenanceResponse> fenetres();

    /**
     * Ouvre une fenetre.
     *
     * <p>Une fenetre dont le debut est dans le futur <b>annonce</b> sans couper :
     * c'est ce qui permet de prevenir avant d'interrompre.
     *
     * @throws com.abdatytch.security_service.exception.SecurityPolicyException si
     *         le message est absent — un refus sans message n'est pas
     *         interpretable — ou s'il contient le separateur du cache
     */
    Mono<MaintenanceResponse> ouvrir(OuvertureDeMaintenanceRequest requete, String auteur);

    /**
     * Leve une fenetre avant son terme.
     *
     * <p>La ligne n'est pas supprimee : savoir qu'un service a ete coupe, par
     * qui, et pour combien de temps est exactement ce qu'un journal d'audit doit
     * montrer.
     */
    Mono<MaintenanceResponse> lever(String id, String auteur);
}
