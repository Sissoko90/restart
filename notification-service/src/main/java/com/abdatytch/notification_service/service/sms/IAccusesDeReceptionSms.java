package com.abdatytch.notification_service.service.sms;

import reactor.core.publisher.Mono;

/**
 * Interpretation d'un accuse de reception SMS.
 *
 * <h2>Pourquoi ce service existe</h2>
 *
 * {@code AccuseDeReceptionSmsController} injectait
 * {@link IRegistreDesEnvoisSms} et portait lui-meme la derivation du statut, la
 * consultation du registre, la journalisation et la tolerance a une panne de
 * Redis. Ce sont quatre decisions metier dans un controleur, dont deux ont une
 * consequence directe sur le comportement du fournisseur en cas de panne.
 *
 * <h2>Pourquoi rien n'echoue jamais ici</h2>
 *
 * Un fournisseur qui reçoit une erreur <b>reessaie</b>. Un accuse qu'on ne sait
 * pas interpreter — identifiant inconnu, registre purge, Redis indisponible —
 * declencherait donc des reprises indefinies pour une information deja perdue.
 * Toutes ces situations se journalisent et se terminent normalement.
 */
public interface IAccusesDeReceptionSms {

    /**
     * Enregistre ce qu'on peut tirer d'un accuse, sans jamais echouer.
     *
     * <p>Le champ {@code message} de l'accuse n'est <b>ni lu ni journalise</b> :
     * c'est une chaine libre venue d'un tiers, et rien ne garantit qu'elle ne
     * contienne pas un echo de ce qu'on lui a envoye, donc un code a usage
     * unique.
     *
     * @param resourceId identifiant de l'envoi, possiblement nul ou inconnu
     * @param code code de statut du fournisseur, possiblement nul
     * @return se termine toujours normalement, meme sur un accuse inexploitable
     */
    Mono<Void> enregistrer(String resourceId, String code);
}
