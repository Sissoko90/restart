package com.abdatytch.notification_service.dto.request;

/**
 * Pose ou remplace la surcharge d'un modele.
 *
 * <h2>La cle est dans le CORPS, pas dans le chemin</h2>
 *
 * {@code PUT /admin/modeles/{cle}} aurait ete la forme attendue, mais une cle
 * de modele est <b>pointee</b> — {@code securite.code-de-verification.sms.corps}
 * — et ce qu'un {@code @PathVariable} conserve d'une valeur pointee depend de la
 * configuration de resolution de chemin du framework.
 *
 * <p>Plutot que de faire reposer sur ce comportement une surface qui modifie le
 * texte des alertes de securite, la cle voyage dans le corps : il n'y a alors
 * plus de question a se poser. Le chemin reste {@code /admin/modeles}, donc bien
 * couvert par la regle {@code /admin/**}.
 *
 * @param cle    la cle complete. Sa forme est controlee par {@code CleDeModele} :
 *               une cle que le moteur ne lirait jamais est refusee a l'ecriture,
 *               et non enregistree pour rester sans effet
 * @param valeur le texte. Les variables s'y ecrivent {@code {nomDeLaVariable}}
 *               et sont substituees litteralement, en un seul passage
 */
public record SurchargeDeModeleRequest(String cle, String valeur) {
}
