package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.response.TotpEnrolementResponse;
import com.abdatytch.auth_service.entities.User;
import reactor.core.publisher.Mono;

/**
 * Detenteur unique de la regle du second facteur (TOTP).
 *
 * <h2>Pourquoi ce service existe</h2>
 *
 * La regle etait ecrite dans {@code AdminAuthServiceImpl} et nulle part
 * ailleurs. Tant que la connexion administrateur avait son propre chemin, cela
 * suffisait. Des lors que {@code /auth/login} doit accepter les comptes
 * d'administration, deux chemins ont besoin de la meme regle — et une regle de
 * securite recopiee est une regle qui divergera.
 *
 * <p>Ce depot en a deja fait les frais sur le verrouillage de compte :
 * {@code AuthServiceImpl} portait son propre seuil tandis que
 * {@code AccountLockoutServiceImpl} lisait la configuration. Porter le seuil de
 * 5 a 10 ne changeait donc rien sur l'un des deux chemins, sans aucune erreur.
 *
 * <h2>Ce que la regle dit</h2>
 *
 * <ol>
 *   <li>second facteur non enrole : la connexion passe, pour que
 *       l'administrateur puisse justement l'enroler ;</li>
 *   <li>second facteur enrole, code absent : {@code AUTH_1009}, qui est un refus
 *       <b>nomme</b> — l'appelant a deja prouve le mot de passe, lui dire qu'un
 *       code manque ne lui apprend rien ;</li>
 *   <li>second facteur enrole, code faux : l'echec est <b>compte</b>, puis refus
 *       uniforme {@code AUTH_1001}.</li>
 * </ol>
 *
 * Le point 3 n'est pas un detail : six chiffres font un million de
 * possibilites, ce qui est epuisable en quelques heures par qui detient deja le
 * mot de passe. Sans comptage, le second facteur ne ralentirait rien.
 */
public interface ISecondFacteurService {

    /**
     * Oppose le second facteur a une connexion deja authentifiee par mot de
     * passe.
     *
     * <p>A n'appeler qu'<b>apres</b> la verification du mot de passe : avant,
     * un code errone ferait monter le compteur d'echecs d'un compte dont
     * l'appelant ne connait meme pas le mot de passe, ce qui transforme ce
     * controle en moyen de verrouiller autrui.
     *
     * <h2>Deux preuves acceptees</h2>
     *
     * Un code TOTP, ou l'un des codes de secours du compte. Voir
     * {@link #confirmerPourUneOperation} pour le pourquoi : c'est la meme regle,
     * et elle est enoncee une seule fois.
     *
     * @param user compte dont le mot de passe vient d'etre verifie
     * @param code code TOTP ou code de secours, possiblement nul
     * @param ipAddress adresse de l'appelant, conservee si un code de secours
     *        est consomme pour que la personne reconnaisse — ou non — l'usage.
     *        Peut etre nulle quand l'appelant n'a pas d'echange HTTP sous la
     *        main.
     * @return le compte si le second facteur est satisfait ou absent
     */
    Mono<User> verifier(User user, String code, String ipAddress);

    /**
     * Premier enrolement : remet le secret une seule fois.
     *
     * <p>Refuse avec {@code 2FA_9002} si le second facteur est deja actif. Le
     * reservir rouvrirait la faille corrigee dans interface-service, ou il
     * etait recuperable a volonte.
     */
    Mono<TotpEnrolementResponse> enroler(User user);

    /**
     * Remplace le second facteur par un neuf, et le remet une seule fois.
     *
     * <p>Le secret precedent devient inutilisable immediatement : c'est le point
     * de l'operation. L'ancien appareil cesse donc de fonctionner des cet
     * appel, meme si le nouveau n'est jamais scanne — auquel cas la sortie est
     * {@link #retirer}, par un {@code SUPER_ADMIN}.
     *
     * <p>Refuse avec {@code 2FA_9001} s'il n'y a rien a remplacer : la demande
     * relevait alors de {@link #enroler}, et repondre « reinitialise » a qui n'a
     * pas de second facteur lui ferait croire qu'il en a un.
     */
    Mono<TotpEnrolementResponse> reinitialiser(User user);

    /**
     * Libre-service, etape 1 : produit un secret neuf <b>sans</b> activer.
     *
     * <h2>Pourquoi deux etapes, et non une</h2>
     *
     * Activer le second facteur des la premiere requete enfermerait la personne
     * hors de son propre compte des que quelque chose se passe mal entre les
     * deux : un code de temps decale sur son telephone, un secret mal recopie,
     * une application d'authentification fermee avant d'enregistrer. Elle se
     * retrouverait avec un compte exigeant un code qu'elle ne peut pas produire,
     * et la seule sortie serait {@link #retirer} par un {@code SUPER_ADMIN}.
     *
     * <p>Le secret est donc <b>pose</b> ici et le second facteur reste inactif.
     * La connexion continue de fonctionner sans code — c'est la regle du point 1
     * de la javadoc de cette interface — jusqu'a ce que
     * {@link #confirmerEnrolement} prouve que la personne sait produire un code
     * valide.
     *
     * <h2>Difference avec {@link #enroler}</h2>
     *
     * {@link #enroler} <b>reprend</b> un secret deja pose, ce qui est le cas des
     * comptes d'administration : le semis le depose a la creation, et le service
     * refuse volontairement d'en fabriquer un pour qu'une anomalie du semis
     * reste visible. Ici le secret n'existe pas encore et doit etre produit.
     *
     * @param user compte authentifie demandant son second facteur
     */
    Mono<TotpEnrolementResponse> demarrerEnrolement(User user);

    /**
     * Libre-service, etape 2 : active apres preuve qu'un code valide est
     * produisible.
     *
     * <p>Le code n'est pas une formalite : c'est la seule chose qui distingue
     * « la personne a enregistre le secret dans son application » de « la
     * personne croit l'avoir fait ». Sans cette preuve, l'activation est un
     * verrouillage a l'aveugle.
     *
     * @param user compte dont l'enrolement a ete demarre
     * @param totpCode code produit par l'application d'authentification
     * @throws com.abdatytch.auth_service.exception.AuthException {@code 2FA_9001}
     *         si aucun enrolement n'a ete demarre, {@code 2FA_9002} s'il est
     *         deja actif, {@code AUTH_1001} si le code est faux
     */
    Mono<Void> confirmerEnrolement(User user, String totpCode);

    /**
     * Libre-service : desactive son propre second facteur.
     *
     * <h2>Un code valide est exige, et ce n'est pas une formalite</h2>
     *
     * L'appelant est deja authentifie — il pourrait sembler suffisant. Ce serait
     * vider le second facteur de son sens : qui vole un jeton d'acces pourrait
     * alors retirer la protection meme qui existe pour que le vol d'un jeton ne
     * suffise pas. Exiger un code prouve que l'appareil est encore detenu.
     *
     * <p>Qui a perdu son appareil passe donc par {@link #retirer}, cote
     * administration. C'est plus lent, et c'est voulu.
     *
     * <p>Accepte un code TOTP <b>ou</b> un code de secours : c'est le chemin de
     * celui qui a perdu son appareil, et le lui fermer ne laisserait que
     * {@link #retirer} par un {@code SUPER_ADMIN}.
     *
     * <p>Le lot de codes de secours est invalide au passage — voir
     * {@link com.abdatytch.auth_service.service.ICodeDeSecoursService#invaliderLeLot}.
     *
     * @param code code TOTP en cours, ou code de secours
     * @param ipAddress adresse de l'appelant, conservee si un code de secours
     *        est consomme. Peut etre nulle.
     */
    Mono<Void> desactiver(User user, String code, String ipAddress);

    /**
     * Exige un code valide pour une operation sensible, sans rien changer a
     * l'enrolement.
     *
     * <h2>Pourquoi cette primitive existe separement</h2>
     *
     * Certaines operations ont besoin d'une preuve <b>a cet instant</b>, et non
     * d'une session ouverte il y a des heures : accorder la confiance a un
     * appareil, autoriser un debit, retirer une protection. C'est ce qu'on
     * appelle une authentification renforcee.
     *
     * <p>{@link #verifier} ne convient pas : il laisse passer quand le second
     * facteur n'est pas enrole, parce que c'est la regle du chemin de connexion.
     * Ici, l'absence d'enrolement doit <b>refuser</b> — une operation sensible
     * ne peut pas etre autorisee par une preuve qui n'existe pas.
     *
     * <h2>L'echec est compte</h2>
     *
     * Comme un echec de connexion. Six chiffres font un million de possibilites,
     * epuisables par qui detient deja une session : sans frein, un jeton d'acces
     * vole suffirait a autoriser une operation sensible par force brute.
     *
     * <h2>Pourquoi un code de secours est accepte ici aussi</h2>
     *
     * Le TOTP est essaye d'abord, puis les codes de secours. Sans cette
     * seconde preuve, quelqu'un qui perd son telephone se retrouve enferme :
     * il peut se connecter avec un code de secours, mais plus rien ensuite —
     * {@link #desactiver} et la reinitialisation exigent toutes deux un code
     * produit par l'appareil perdu. La seule sortie restante serait
     * {@link #retirer} par un {@code SUPER_ADMIN}, c'est-a-dire une
     * intervention humaine jugeant de l'identite de quelqu'un qui ne peut plus
     * la prouver : exactement le chemin des attaques par ingenierie sociale.
     *
     * <p>Un code de secours presente ici est <b>consomme</b> : il ne sert
     * qu'une fois, qu'il ouvre une connexion ou autorise une operation.
     *
     * @param code code TOTP ou code de secours
     * @param ipAddress adresse de l'appelant, conservee si un code de secours
     *        est consomme. Peut etre nulle.
     * @throws com.abdatytch.auth_service.exception.AuthException
     *         {@code 2FA_9001} si le second facteur n'est pas enrole — l'appelant
     *         doit alors l'activer d'abord ; {@code AUTH_1009} si le code est
     *         absent ; {@code AUTH_1001} s'il est faux
     */
    Mono<Void> confirmerPourUneOperation(User user, String code, String ipAddress);

    /**
     * Retire le second facteur d'un compte, sans jamais rendre le secret.
     *
     * <p>Destine au {@code SUPER_ADMIN} pour un administrateur qui a perdu son
     * appareil. Un secret neuf est pose en meme temps, de sorte que
     * l'administrateur re-enrole ensuite lui-meme : sans cela, le
     * {@code SUPER_ADMIN} remettrait en service l'ancien secret, qu'un appareil
     * perdu — ou vole — porte encore.
     *
     * <p>Le secret n'est pas rendu, pour que le {@code SUPER_ADMIN} ne puisse
     * pas se substituer au second facteur d'un autre administrateur. Il peut le
     * retirer, non l'usurper.
     */
    Mono<Void> retirer(User user);
}
