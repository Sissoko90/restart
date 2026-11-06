package com.abdatytch.notification_service.repositories;

import com.abdatytch.notification_service.entities.SurchargeDeModele;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/** Les surcharges de modeles posees a chaud. */
@Repository
public interface SurchargeDeModeleRepository
        extends ReactiveCrudRepository<SurchargeDeModele, String> {

    /**
     * Pose ou remplace la surcharge d'une cle, en une seule instruction.
     *
     * <h2>Pourquoi {@code ON CONFLICT} et non {@code save()}</h2>
     *
     * Avec une cle primaire NATURELLE fournie par l'appelant, Spring Data ne
     * peut pas deviner s'il s'agit d'une creation ou d'une mise a jour : il
     * faudrait le lui dire par {@code Persistable.isNew()}, donc porter le
     * drapeau sur l'objet et le positionner correctement a chaque chemin
     * d'ecriture. Ce depot a deja paye ce piege, en JPA comme en R2DBC.
     *
     * <p>Le faire en lisant d'abord — « existe-t-elle ? » puis insert ou update
     * — introduirait en plus une fenetre entre les deux requetes : deux
     * corrections simultanees de la meme cle se verraient toutes deux absentes,
     * et la seconde echouerait sur la contrainte d'unicite.
     *
     * <p>{@code ON CONFLICT DO UPDATE} repond aux deux : une instruction, une
     * decision prise par PostgreSQL sous le verrou de la cle primaire.
     *
     * @return le nombre de lignes touchees, toujours 1
     */
    @Query("""
            INSERT INTO surcharge_de_modele (cle, valeur, modifiee_par, modifiee_le)
            VALUES (:cle, :valeur, :modifieePar, CURRENT_TIMESTAMP)
            ON CONFLICT (cle) DO UPDATE
               SET valeur = EXCLUDED.valeur,
                   modifiee_par = EXCLUDED.modifiee_par,
                   modifiee_le = EXCLUDED.modifiee_le
            """)
    Mono<Long> enregistrer(String cle, String valeur, String modifieePar);
}
