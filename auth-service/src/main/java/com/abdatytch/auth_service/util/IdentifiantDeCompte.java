package com.abdatytch.auth_service.util;

import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.repository.UserRepository;
import reactor.core.publisher.Mono;

/**
 * Resolution d'un identifiant libre — adresse electronique ou telephone — en un
 * compte.
 *
 * <h2>Pourquoi un seul detenteur</h2>
 *
 * La regle etait ecrite <b>deux fois</b> : une copie dans {@code AuthServiceImpl}
 * (telephone seulement) et une dans {@code AdminAuthServiceImpl} (adresse ou
 * telephone). Consequence directe : un administrateur joignable par
 * {@code /auth/admin/login} avec son adresse ne l'etait pas par
 * {@code /auth/login}, qui ne savait chercher que par telephone.
 *
 * <h2>Pourquoi une methode statique et non une methode par defaut du repository</h2>
 *
 * La premiere version etait une methode {@code default} sur
 * {@code UserRepository}. Elle etait correcte en production et <b>inerte en
 * test</b> : {@code Mockito.mock} bouchonne aussi les methodes par defaut, donc
 * la repartition ne s'executait jamais et l'appel rendait {@code null}. Les
 * trente-trois tests de connexion tombaient sur une
 * {@code NullPointerException}, et c'est la forme chanceuse du probleme — un
 * stub pose dessus les aurait rendus verts sans que la regle soit jamais
 * exercee.
 *
 * <p>Une methode statique ne se bouchonne pas : elle s'execute toujours, et les
 * tests qui simulent {@code findByEmail} et {@code findByPhoneNumber}
 * continuent de valoir.
 */
public final class IdentifiantDeCompte {

    private IdentifiantDeCompte() {
    }

    /**
     * @param repository depot des comptes
     * @param identifiant adresse si elle contient une arobase, telephone sinon
     * @return le compte, ou un {@code Mono} vide — y compris si l'identifiant
     *         est vide, pour que l'appelant traite ce cas comme un compte
     *         inconnu et non comme une erreur distincte. Les deux colonnes sont
     *         {@code UNIQUE} dans {@code users} (voir
     *         {@code V1__init_auth_schema.sql}), donc aucun identifiant ne peut
     *         designer deux comptes.
     */
    public static Mono<User> resoudre(UserRepository repository, String identifiant) {
        if (identifiant == null || identifiant.isBlank()) {
            return Mono.empty();
        }
        return identifiant.contains("@")
                ? repository.findByEmail(identifiant)
                : repository.findByPhoneNumber(identifiant);
    }
}
