package com.abdatytch.interface_service.exception;

/**
 * Refus portant sur une ressource que l'appelant designe par un identifiant
 * qu'il ne peut pas deviner.
 *
 * <h2>Le defaut corrige</h2>
 *
 * {@code ProjectServiceImpl} levait {@code new RuntimeException("Projet non
 * trouve")} en trois endroits — lecture, mise a jour et suppression. Aucun
 * gestionnaire ne connait {@code RuntimeException}, donc
 * {@link GlobalExceptionHandler#handleGenericException} rendait un <b>500</b>.
 *
 * <p>Deux consequences, et la seconde est la plus couteuse :
 *
 * <ul>
 *   <li>un client ne peut pas distinguer « ce projet n'existe pas » d'une panne
 *       du service, donc il reessaie une requete qui ne reussira jamais ;</li>
 *   <li>le trafic normal — demander un projet supprime, suivre un lien perime —
 *       alimente le taux d'erreurs 5xx. C'est exactement le signal sur lequel
 *       reposent les alertes de la plateforme, et le noyer sous des 404
 *       deguises les rend muettes.</li>
 * </ul>
 *
 * <h2>Pourquoi une classe distincte plutot qu'un message</h2>
 *
 * Pour que le statut ne depende pas d'un libelle. Comparer des chaines de
 * messages lierait le code HTTP au texte : reformuler le message changerait le
 * statut, et rien ne l'aurait signale.
 *
 * <h2>Pourquoi 404 et non 403</h2>
 *
 * {@code findByIdAndUserId} ne rend rien dans <b>deux</b> cas qu'il ne distingue
 * pas : le projet n'existe pas, ou il appartient a quelqu'un d'autre. C'est la
 * bonne forme — repondre 403 sur un projet existant et 404 sur un identifiant
 * inconnu permettrait d'enumerer les projets des autres en lisant le seul code
 * de statut.
 *
 * <p>Meme regle que {@code security-service}. Elle ne vaut que pour les
 * identifiants qu'on peut parcourir : un code pays, public et connu de tous,
 * rend 400.
 */
public class RessourceIntrouvableException extends BusinessException {

    public RessourceIntrouvableException(String message) {
        super(message, "RESSOURCE_INTROUVABLE");
    }
}
