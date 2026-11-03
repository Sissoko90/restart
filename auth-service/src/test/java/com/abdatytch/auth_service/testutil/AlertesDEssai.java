package com.abdatytch.auth_service.testutil;

import com.abdatytch.auth_service.dto.ContexteDeSecurite;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.service.IAlerteDeSecuriteService;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Alertes de sécurité muettes, pour les suites qui ne les mesurent pas.
 *
 * <h2>Pourquoi un helper partagé</h2>
 *
 * {@code AuthServiceImpl} a gagné une dépendance à
 * {@link IAlerteDeSecuriteService} : un changement de mot de passe ou d'adresse
 * prévient la personne, et la connexion le fait sur demande. Cinq suites
 * construisent ce service, et aucune ne mesure ces envois — elles portent sur
 * le mot de passe, le verrouillage, le renvoi de code et la connexion.
 *
 * <p>Écrire cinq fois le même simulacre recréerait la duplication que ce dépôt
 * passe son temps à retirer. Même forme que {@link ConfianceDEssai} et
 * {@link CodesDeSecoursDEssai}.
 *
 * <h2>Pourquoi les envois sont ENREGISTRÉS, et pas seulement ignorés</h2>
 *
 * {@link #evenements()} liste ce qui a été demandé. Les suites existantes
 * l'ignorent, mais une alerte est une protection : la propriété « le changement
 * de mot de passe prévient la personne » doit pouvoir s'écrire sans monter un
 * serveur SMTP, et sans que le helper soit à refaire le jour où quelqu'un veut
 * l'affirmer.
 *
 * <p>Un helper qui ne rend aucun compte pousse à l'inverse : on vérifie que
 * l'opération réussit, et personne ne remarque que l'alerte a disparu.
 */
public final class AlertesDEssai {

    private AlertesDEssai() {
    }

    /** Service muet qui retient ce qu'on lui a demandé d'envoyer. */
    public static final class Muettes implements IAlerteDeSecuriteService {

        private final List<String> evenements = new ArrayList<>();

        /** Les alertes demandées, dans l'ordre. Jamais nulle. */
        public List<String> evenements() {
            return List.copyOf(evenements);
        }

        @Override
        public Mono<Void> motDePasseModifie(User user, ContexteDeSecurite contexte) {
            evenements.add("mot_de_passe:" + user.getId());
            return Mono.empty();
        }

        @Override
        public Mono<Void> adresseModifiee(User user, String ancienneAdresse,
                                          boolean ancienneEtaitVerifiee,
                                          ContexteDeSecurite contexte) {
            // L'ANCIENNE adresse est retenue, et c'est tout l'intérêt : une
            // inversion — envoyer à la nouvelle — est le défaut que cette
            // alerte doit justement empêcher, et il s'écrit sans y penser.
            evenements.add("adresse:" + ancienneAdresse
                    + ":verifiee=" + ancienneEtaitVerifiee);
            return Mono.empty();
        }

        @Override
        public Mono<Void> numeroModifie(User user, String ancienNumero,
                                        boolean ancienEtaitVerifie,
                                        ContexteDeSecurite contexte) {
            // L'ANCIEN numéro est retenu, pour la même raison que l'ancienne
            // adresse : l'inversion est le défaut que cette alerte doit
            // empêcher, et l'entité porte déjà le nouveau à cet instant.
            evenements.add("numero:" + ancienNumero
                    + ":verifie=" + ancienEtaitVerifie);
            return Mono.empty();
        }

        @Override
        public Mono<Void> nouvelleConnexion(User user, ContexteDeSecurite contexte) {
            evenements.add("connexion:" + user.getId());
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> changerLalerteDeConnexion(String userId, boolean actif) {
            return Mono.just(actif);
        }
    }

    public static Muettes aucuneAlerte() {
        return new Muettes();
    }
}
