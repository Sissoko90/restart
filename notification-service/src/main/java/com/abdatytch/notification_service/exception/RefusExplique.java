package com.abdatytch.notification_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * Un refus dont le MOTIF est destine a l'appelant.
 *
 * <h2>Pourquoi ce type existe</h2>
 *
 * {@link GlobalExceptionHandler} applique une regle juste et deliberee : aucun
 * message interne ne franchit la frontiere. Les refus portent le libelle du
 * statut, et le motif reel va au journal. Deux raisons, toutes deux solides :
 * les causes d'un refus d'authentification ne doivent pas etre distinguables,
 * et un message d'exception nomme des classes, des tables, parfois des
 * adresses.
 *
 * <h2>Le defaut que cette regle produisait</h2>
 *
 * Elle s'appliquait aussi aux motifs ECRITS A LA MAIN pour l'appelant, qui sont
 * d'une autre nature. Mesure sur le code existant :
 *
 * <ul>
 *   <li>{@code AdminModeleController} refuse une clef de modele invalide avec
 *       {@code CleDeModele.motifDInvalidite(cle)} — un texte qui dit
 *       exactement ce qui ne va pas dans la clef, et qui ne quittait jamais le
 *       journal ;</li>
 *   <li>l'exploitant recevait {@code {"message":"Bad Request"}} et devait
 *       deviner, ou demander l'acces aux journaux du service.</li>
 * </ul>
 *
 * <p>Ecrire un motif precis que personne ne lit revient a ne pas l'ecrire. Et
 * ce depot connait le cout de l'inverse : un motif opaque — {@code
 * "NotificationException"} — rendu par {@code /internal/message}, qui
 * n'apprenait rien a l'operateur.
 *
 * <h2>Pourquoi un TYPE et non un drapeau</h2>
 *
 * Le gestionnaire ne peut pas deviner si un {@code getReason()} a ete redige
 * pour l'appelant ou recopie d'une cause interne : Spring lui-meme en pose
 * dans ses propres refus. Un type separe rend l'intention explicite au point
 * ou elle est connue — celui qui leve — et rend impossible la divulgation par
 * inadvertance : un {@code ResponseStatusException} ordinaire garde l'ancien
 * comportement.
 *
 * <h2>Ce qui N'A PAS sa place ici</h2>
 *
 * Un motif d'authentification ou d'autorisation. {@code 401} et {@code 403}
 * doivent rester indistinguables : « mot de passe faux » et « compte inconnu »
 * se repondent tous deux « non ». Le constructeur le REFUSE, plutot que de
 * compter sur la vigilance.
 *
 * <p>Ne doit pas y figurer non plus : un nom de table, une requete, un chemin
 * de fichier, une adresse ou un numero. Le motif decrit la DEMANDE de
 * l'appelant, jamais l'interieur du service.
 */
public class RefusExplique extends ResponseStatusException {

    /**
     * @param statut statut 4xx, ni 401 ni 403
     * @param motif  texte destine a l'appelant, sans detail interne
     * @throws IllegalArgumentException si le statut n'est pas un refus de
     *                                  l'appelant, ou s'il porte sur
     *                                  l'authentification
     */
    public RefusExplique(HttpStatus statut, String motif) {
        super(verifier(statut), motif);
    }

    private static HttpStatusCode verifier(HttpStatus statut) {
        if (!statut.is4xxClientError()) {
            throw new IllegalArgumentException(
                    "RefusExplique ne sert qu'aux refus 4xx : " + statut
                            + " decrit une panne du service, dont le detail ne doit "
                            + "pas sortir");
        }
        if (statut == HttpStatus.UNAUTHORIZED || statut == HttpStatus.FORBIDDEN) {
            throw new IllegalArgumentException(
                    "Un refus " + statut.value() + " ne s'explique pas : les causes "
                            + "d'un refus d'acces doivent rester indistinguables");
        }
        return statut;
    }
}
