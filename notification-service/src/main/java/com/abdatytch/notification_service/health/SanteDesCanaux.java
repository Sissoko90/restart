package com.abdatytch.notification_service.health;

import com.abdatytch.notification_service.service.CanalDeRemise;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L'etat de chaque canal de remise, dans {@code /actuator/health}.
 *
 * <h2>DOWN seulement si AUCUN canal ne peut remettre</h2>
 *
 * C'est la decision de conception de cette classe, et elle n'est pas
 * cosmetique.
 *
 * <p>Un canal inactif est le plus souvent un CHOIX : le SMS est coupe en
 * developpement, le push attend son fichier d'identifiants Firebase. Rendre le
 * service DOWN pour cela serait faux — il remet tres bien par courriel et en
 * boite de reception.
 *
 * <p>En revanche, zero canal actif signifie qu'aucune notification ne partira,
 * quoi qu'on demande. Ce n'est plus une degradation, c'est une panne complete,
 * et elle doit se voir.
 *
 * <h2>Pourquoi cet indicateur ne doit PAS garder la decouverte</h2>
 *
 * Consul interroge {@code /actuator/health/readiness} pour ce service, et non
 * {@code /actuator/health}. C'est deliberé, et le depot a paye pour
 * l'apprendre.
 *
 * <p>Avec {@code query-passing: true}, une instance dont le controle echoue
 * cesse d'etre annoncee a l'equilibreur. Si cet indicateur-ci gardait la
 * decouverte, un canal SMS coupe par choix retirerait le service du routage —
 * et sa boite de reception, ses endpoints d'appareil et son administration de
 * modeles deviendraient injoignables. La sante d'un canal SORTANT ne doit pas
 * decider du trafic ENTRANT.
 *
 * <p>Le cas s'est produit a l'envers, et il a coute : une sonde de groupe qui
 * rendait 401 a fait disparaitre interface-service du catalogue, service
 * vivant, autorite JWT, et 503 a la connexion.
 *
 * <h2>Non bloquant</h2>
 *
 * {@code estActif()} lit un champ ou une reference ; aucun canal n'y fait
 * d'entree-sortie. Un {@code ReactiveHealthIndicator} evite en plus
 * l'adaptateur que Spring interpose devant un {@code HealthIndicator}
 * synchrone, lequel souscrit sur {@code boundedElastic}.
 */
@Component("canauxDeRemise")
public class SanteDesCanaux implements ReactiveHealthIndicator {

    private final List<CanalDeRemise> canaux;

    public SanteDesCanaux(List<CanalDeRemise> canaux) {
        this.canaux = List.copyOf(canaux);
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromSupplier(() -> {
            Map<String, Object> details = new LinkedHashMap<>();
            int actifs = 0;

            for (CanalDeRemise canal : canaux) {
                boolean actif = canal.estActif();
                if (actif) {
                    actifs++;
                }
                details.put(canal.canal().name(), actif ? "ACTIF" : "INACTIF");
            }

            details.put("actifs", actifs);
            details.put("declares", canaux.size());

            Health.Builder etat = actifs > 0 ? Health.up() : Health.down();
            if (actifs == 0) {
                // Le motif est dans les details et non dans une exception : une
                // exception ferait apparaitre une trace dans la reponse de
                // sante, et ce depot ne met rien d'inutile dans une surface
                // lisible de l'exterieur.
                details.put("consequence",
                        "aucune notification ne peut partir, quel que soit le canal demande");
            }
            return etat.withDetails(details).build();
        });
    }
}
