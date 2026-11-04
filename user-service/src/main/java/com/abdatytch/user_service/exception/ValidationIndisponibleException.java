package com.abdatytch.user_service.exception;

/**
 * La validation d'un jeton n'a pas pu avoir lieu — le jeton n'est pas en cause.
 *
 * <h2>Pourquoi ce type existe, et ce qu'il sépare</h2>
 *
 * {@link InvalidTokenException} servait aux deux situations : le jeton est
 * mauvais, et la plateforme est incapable d'en juger. Les deux méritent des
 * réponses opposées :
 *
 * <ul>
 *   <li><b>jeton mauvais</b> — expiré, révoqué, mal signé : <b>401</b>. Le
 *       client doit renouveler son jeton, et c'est sur ce code qu'il déclenche
 *       son rafraîchissement ;</li>
 *   <li><b>validation impossible</b> — aucune clé publique disponible, donc
 *       JWKS injoignable : <b>500</b>. Le jeton de l'appelant est peut-être
 *       parfaitement valide. Répondre 401 ferait renouveler un jeton qui n'a
 *       rien et, le renouvellement échouant pour la même raison, déconnecterait
 *       <b>tous</b> les utilisateurs pendant la panne.</li>
 * </ul>
 *
 * <p>Un type, et non un message : le code de réponse ne doit pas dépendre d'une
 * chaîne de caractères. C'est la même décision que
 * {@code security-service/RessourceIntrouvableException}, prise après avoir
 * constaté qu'un statut lié au texte d'un message change dès qu'on reformule
 * le message.
 *
 * <p>Elle n'est volontairement pas traitée par {@code GlobalExceptionHandler} :
 * une indisponibilité doit remonter en erreur serveur, être vue des journaux et
 * des alertes, et non être habillée en réponse métier.
 */
public class ValidationIndisponibleException extends RuntimeException {

    public ValidationIndisponibleException(String message) {
        super(message);
    }
}
