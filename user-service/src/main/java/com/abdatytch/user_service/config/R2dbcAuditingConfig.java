package com.abdatytch.user_service.config;

import com.abdatytch.user_service.entities.BaseEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback;
import reactor.core.publisher.Mono;

/**
 * Horodatages d'audit, posés par un rappel avant conversion.
 *
 * <h2>Pourquoi un rappel, et non {@code @EnableR2dbcAuditing}</h2>
 *
 * L'annotation faisait le travail, mais elle exige le bean
 * {@code r2dbcMappingContext}, donc l'auto-configuration R2DBC complète. Or le
 * profil de test l'exclut — les tests ne touchent pas la base et remplacent les
 * dépôts par des simulacres. Résultat : le contexte ne pouvait pas démarrer en
 * test, et c'est l'une des raisons pour lesquelles ce service <b>n'avait aucun
 * test de contexte</b>. L'absence de test rendait l'absence de démarrage
 * invisible.
 *
 * <p>Le rappel, lui, ne dépend que de l'entité. Il fonctionne à l'identique en
 * production et laisse le contexte se charger en test.
 *
 * <h2>Pourquoi c'est aussi la fin d'un doublon</h2>
 *
 * auth-service pose ses horodatages exactement de cette façon, pour la même
 * raison. Deux mécanismes d'audit différents dans deux services du même dépôt
 * obligeaient à se demander lequel s'applique où — et seul le plus fragile des
 * deux était ici.
 *
 * <h2>Ce que ce rappel ne fait PAS, et pourquoi</h2>
 *
 * Il ne génère <b>pas</b> l'identifiant. Les entités de ce service laissent leur
 * {@code id} nul et la base le produit — {@code gen_random_uuid()} dans la
 * migration. C'est la différence avec auth-service, dont les identifiants sont
 * assignés par l'application et qui a donc besoin de {@code Persistable} pour
 * dire à Spring Data si la ligne est neuve : sans ce drapeau, un identifiant
 * déjà posé fait émettre un {@code UPDATE} pour une ligne jamais insérée, et le
 * {@code save()} est silencieusement sans effet.
 *
 * <p>Ici le problème ne se pose pas, tant que personne n'assigne d'identifiant
 * avant l'écriture. {@code IdentifiantLaisseALaBaseTest} le vérifie.
 */
@Configuration
public class R2dbcAuditingConfig {

    /**
     * Pose {@code createdAt} à la création et {@code updatedAt} à chaque
     * écriture.
     *
     * <p>{@code @CreationTimestamp} et {@code @UpdateTimestamp} d'Hibernate
     * n'existent pas en R2DBC, et {@code @CreatedDate} demande le contexte de
     * mapping : d'où ce rappel, qui n'a besoin ni de l'un ni de l'autre.
     */
    @Bean
    public BeforeConvertCallback<BaseEntity> horodatagesDaudit() {
        return (entite, table) -> {
            entite.preparerAvantEcriture();
            return Mono.just(entite);
        };
    }
}
