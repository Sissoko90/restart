package com.abdatytch.commons.security;

import java.time.Instant;

/**
 * La règle « ce jeton est-il frappé par une révocation globale », en un seul
 * endroit.
 *
 * <h2>Le défaut que cette classe supprime</h2>
 *
 * La clé Redis {@code dev:revoked:<sub>} porte l'<b>instant</b> de la
 * révocation — {@code System.currentTimeMillis()}, durée de vie sept jours.
 * Elle a <b>deux lecteurs</b>, dans deux modules :
 *
 * <ul>
 *   <li>{@code interface-service/DevTokenBlacklistServiceImpl}, consulté par
 *       la validation de l'autorité JWT ;</li>
 *   <li>{@code gateway-service/TokenRevocationChecker}, consulté par
 *       {@code JwtWebFilter} — donc sur le chemin de <b>chaque</b> requête de
 *       la plateforme.</li>
 * </ul>
 *
 * <p>Les deux faisaient un simple {@code hasKey} et <b>ignoraient la
 * valeur</b>. Conséquence : révoquer les jetons d'un développeur l'aurait
 * verrouillé pendant les sept jours de vie de la clé, <b>reconnexion
 * comprise</b> — tout jeton neuf était rejeté. L'horodatage était écrit pour
 * éviter exactement cela, et n'était relu nulle part.
 *
 * <p>Le défaut n'était pas actif : l'écrivain,
 * {@code revokeAllDeveloperTokens}, n'avait aucun appelant hors de sa propre
 * façade. Mais les deux lecteurs tournaient, et brancher une fonction
 * d'administration aussi évidente que « révoquer tous les jetons de ce
 * développeur » aurait provoqué la panne — sur la passerelle, donc partout à la
 * fois.
 *
 * <h2>Pourquoi ici et non dans chaque module</h2>
 *
 * Parce que c'est précisément le critère de ce module : il ne contient que ce
 * dont une <b>divergence entre services créerait une faille</b>. Deux lecteurs
 * d'une même clé qui n'appliquent pas la même règle, c'est la définition du
 * problème — et ce dépôt l'a déjà payé sur les chemins publics, sur la lecture
 * d'adresse IP et sur la lecture des rôles.
 *
 * <p>Un lecteur plus permissif que l'autre laisserait passer au Gateway ce que
 * l'autorité refuse, ou l'inverse. Et la correction se ferait deux fois, ou une
 * seule.
 *
 * <h2>Les deux replis sont FERMÉS</h2>
 *
 * Ne pas savoir si un jeton est révoqué, c'est ne pas savoir si son porteur a
 * encore le droit. Même politique que {@code TokenRevocationChecker} et que la
 * validation de l'autorité : on refuse.
 */
public final class RevocationGlobale {

    private RevocationGlobale() {
    }

    /**
     * Ce jeton est-il frappé par la révocation enregistrée ?
     *
     * @param horodatageStocke la valeur de {@code dev:revoked:<sub>} :
     *                         millisecondes depuis l'époque. {@code null} ou
     *                         vide signifie « aucune révocation » et rend
     *                         {@code false} — c'est le cas normal, et une clé
     *                         absente ne doit pas fermer l'accès
     * @param emisLe           le claim {@code iat} du jeton. {@code null}
     *                         signifie « inconnu » et rend {@code true} : un
     *                         jeton dont on ne peut pas prouver qu'il postdate
     *                         la révocation doit être refusé
     * @return {@code true} si le jeton est antérieur à la révocation, donc
     *         frappé
     */
    public static boolean frappe(String horodatageStocke, Instant emisLe) {
        // Aucune revocation enregistree : le cas normal, et le seul ou l'on
        // ouvre. Une cle absente n'est pas une incertitude, c'est une absence.
        if (horodatageStocke == null || horodatageStocke.isBlank()) {
            return false;
        }

        // Jeton sans date d'emission : on ne peut pas etablir qu'il postdate la
        // revocation. Refuser.
        if (emisLe == null) {
            return true;
        }

        try {
            Instant revoqueLe = Instant.ofEpochMilli(Long.parseLong(horodatageStocke.trim()));
            // Un jeton emis a l'instant EXACT de la revocation est refuse :
            // les horloges de l'emetteur et du revocateur ne sont pas la meme,
            // et la milliseconde ne tranche rien. Le doute ferme.
            return !emisLe.isAfter(revoqueLe);
        } catch (NumberFormatException illisible) {
            // Une valeur qu'on ne comprend pas ne doit pas devenir une
            // ouverture. Elle n'est pas rendue a l'appelant : il n'a pas a la
            // recopier dans un journal.
            return true;
        }
    }
}
