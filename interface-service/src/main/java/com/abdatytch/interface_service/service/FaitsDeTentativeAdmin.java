package com.abdatytch.interface_service.service;

import com.abdatytch.interface_service.dto.GeoLocationInfo;

/**
 * Ce qu'on sait d'une tentative de connexion administrateur.
 *
 * <h2>Pourquoi ce type existe</h2>
 *
 * {@code AdminController} composait lui-meme le corps des deux alertes, en
 * {@code String.format}, et le passait a une methode d'envoi generique. Il y
 * avait donc deux endroits qui decidaient de la forme d'un courriel : le
 * controleur, et le modele de transit qui le transportait.
 *
 * <p>Ce record coupe cela en deux responsabilites nettes. Le controleur produit
 * des FAITS ; {@link EmailService} les traduit en variables de modele. Le
 * controleur ne connait donc plus aucun nom de variable de gabarit, et un
 * gabarit qui change de vocabulaire ne le touche pas.
 *
 * <h2>{@code geo} peut etre nul, et c'est le point</h2>
 *
 * La geolocalisation est un appel a un tiers, qui echoue. L'ancien code avait
 * pour cela une SECONDE copie du corps du message, plus courte, dans la branche
 * d'erreur — soixante lignes recopiees que rien n'obligeait a rester d'accord
 * avec la premiere.
 *
 * <p>Ici l'echec est un {@code null}, et l'adaptateur remplit « non disponible »
 * pour chaque champ absent. Un seul gabarit, un seul texte a relire, et une
 * ligne vide ne peut plus passer pour une information.
 *
 * @param courriel                     l'adresse du compte vise
 * @param adresseIp                    l'adresse IP d'ou vient la tentative
 * @param userAgent                    l'en-tete {@code User-Agent} de la
 *                                     requete — pose par l'attaquant, donc
 *                                     echappe par le rendu du corps HTML
 * @param geo                          la geolocalisation, ou {@code null} si
 *                                     elle n'a pas abouti
 * @param tentatives                   le nombre total d'echecs sur ce compte,
 *                                     {@code null} tant qu'aucun n'a ete compte
 * @param tentativesApresConfirmation  les echecs survenus APRES que la
 *                                     confirmation a ete donnee ; {@code null}
 *                                     comme ci-dessus. Les deux compteurs sont
 *                                     des {@code Integer} en base, donc nuls
 *                                     sur un compte jamais mis en defaut :
 *                                     les declarer {@code int} ici ferait une
 *                                     NullPointerException a l'instant precis
 *                                     ou l'alerte doit partir.
 */
public record FaitsDeTentativeAdmin(
        String courriel,
        String adresseIp,
        String userAgent,
        GeoLocationInfo geo,
        Integer tentatives,
        Integer tentativesApresConfirmation) {

    /** Les memes faits sans geolocalisation, pour la branche d'echec. */
    public FaitsDeTentativeAdmin sansGeolocalisation() {
        return new FaitsDeTentativeAdmin(courriel, adresseIp, userAgent, null,
                tentatives, tentativesApresConfirmation);
    }
}
