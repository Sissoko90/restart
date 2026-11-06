package com.abdatytch.notification_service.service.sms;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Administration du fournisseur SMS : solde et URL de rappel.
 *
 * <h2>Pourquoi une interface</h2>
 *
 * {@code AfrikSmsAdministration} etait injectee en classe concrete par le
 * controleur d'administration et par la surveillance du solde, alors que tout le
 * reste de la couche SMS passe par {@link ISmsProvider}. Deux consequences :
 *
 * <ul>
 *   <li>le contrat n'etait decrit que dans l'implementation, et le type de
 *       retour du solde — une donnee que le controleur interprete pour decider
 *       si la situation est critique — etait un {@code record} imbrique dans
 *       cette implementation ;</li>
 *   <li>un second fournisseur aurait demande de modifier ses appelants, alors
 *       que la couche d'envoi, elle, en accepte deja deux.</li>
 * </ul>
 *
 * <h2>Pourquoi le solde merite un point d'entree</h2>
 *
 * Un credit epuise chez AfrikSMS ne produit <b>aucune erreur HTTP</b> : les
 * envois repondent 200 avec un code de refus. Sans moyen de regarder le solde,
 * la panne se manifeste uniquement par des utilisateurs qui ne reçoivent plus
 * leur code — en masse, et sans que rien n'indique pourquoi.
 */
public interface IAdministrationSms {

    /**
     * Pousse au fournisseur l'URL de rappel <b>de la configuration</b>.
     *
     * <p>Sans parametre, et c'est deliberе : accepter une URL de l'appelant
     * permettrait a qui detient un jeton d'administration de rediriger les
     * accuses de reception vers un serveur de son choix — donc d'observer qui
     * reçoit des SMS et quand.
     *
     * @return le {@code typeNotification} confirme par le fournisseur
     */
    Mono<String> declarerUrlDeRappel();

    /** @return le solde par pays, tel que le fournisseur le rapporte */
    Mono<List<SoldeParPays>> consulterSolde();

    /**
     * Solde disponible pour un pays.
     *
     * <p>Declare sur l'interface et non dans l'implementation : c'est la forme
     * de la reponse, donc une part du contrat.
     *
     * @param solde nombre de SMS restants ; <b>peut etre negatif</b>
     */
    record SoldeParPays(String pays, int solde) {

        /** Au-dessous de quoi il faut recharger avant de manquer de credit. */
        public boolean estCritique() {
            return solde <= 0;
        }
    }
}
