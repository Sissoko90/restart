package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.entities.BaseEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.mapping.event.AfterConvertCallback;
import org.springframework.data.r2dbc.mapping.event.AfterSaveCallback;
import org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback;
import reactor.core.publisher.Mono;

/**

 * Remplace, pour R2DBC, ce qu'Hibernate faisait automatiquement en JPA.
 *
 * <p>Deux mécanismes n'ont pas d'équivalent natif :
 * <ul>
 * <li>{@code @PrePersist} / {@code @CreationTimestamp} / {@code @UpdateTimestamp} :
 * assurés par {@link #auditingCallback()} ;</li>
 * <li>la détection « entité neuve ou existante » : avec un identifiant assigné par
 * l'application, Spring Data considérerait toute entité relue comme neuve et
 * tenterait un {@code INSERT} en double. {@link #markNotNewCallback()} remet
 * le drapeau à jour après chaque lecture.</li>
 * </ul>
 */
@Configuration
public class R2dbcAuditingConfig {

    /** Génère l'identifiant et pose les horodatages avant toute écriture. */
    @Bean
    public BeforeConvertCallback<BaseEntity> auditingCallback() {
        return (entity, table) -> {
            entity.prepareForSave();
            return Mono.just(entity);
        };
    }

    /**

     * Marque comme « déjà persistée » toute entité relue depuis la base,
     * pour que le prochain {@code save()} produise un UPDATE et non un INSERT.
     */
    @Bean
    public AfterConvertCallback<BaseEntity> markNotNewCallback() {
        return (entity, table) -> {
            entity.markNotNew();
            return Mono.just(entity);
        };
    }

    /**

     * Même remise à jour du drapeau, mais après une <b>écriture</b>.
     *
     * <p>Sans ce rappel, seule la lecture faisait basculer {@code newEntity}. Une
     * entité conservée en mémoire après un {@code save()} restait donc marquée
     * « neuve », et la sauvegarde suivante émettait un second {@code INSERT} :
     *
     * <pre>{@code
     * User enregistre = repository.save(nouvelUtilisateur).block();
     * enregistre.setLastLogin(now);
     * repository.save(enregistre);   // INSERT  violation de clé primaire
     * }</pre>
     *
     * Le défaut ne touchait pas le trajet le plus courant (relire, modifier,
     * sauvegarder), ce qui explique qu'il ait pu passer inaperçu — mais il
     * atteignait toute séquence enregistrant puis mettant à jour la même
     * instance, comme la création d'un compte suivie de la mise à jour de sa
     * date de connexion.
     */
    @Bean
    public AfterSaveCallback<BaseEntity> markNotNewAfterSaveCallback() {
        return (entity, outboundRow, table) -> {
            entity.markNotNew();
            return Mono.just(entity);
        };
    }
}
