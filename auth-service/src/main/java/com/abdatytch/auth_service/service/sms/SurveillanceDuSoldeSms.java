package com.abdatytch.auth_service.service.sms;

import com.abdatytch.auth_service.constants.ServiceConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Surveille le solde SMS, pour voir venir la panne la plus silencieuse de la
 * chaîne d'inscription.
 *
 * <h2>Pourquoi une tâche périodique, et non seulement un point d'entrée admin</h2>
 *
 * Un crédit épuisé chez AfrikSMS ne produit <b>aucune erreur HTTP</b> : l'envoi
 * répond 200, avec un {@code code} de refus. Rien ne tombe, rien n'alerte. La
 * panne se manifeste uniquement par des utilisateurs qui ne reçoivent plus leur
 * code — et personne ne pense à consulter un solde avant d'avoir compris qu'il
 * est la cause.
 *
 * <p>Le point d'entrée {@code GET /admin/sms/solde} répond à qui regarde. Cette
 * tâche, elle, regarde pour nous.
 *
 * <h2>Deux seuils, pas un</h2>
 *
 * Un solde déjà négatif ou nul signifie que les SMS <b>ne partent plus</b> :
 * c'est un incident, journalisé en {@code ERROR}. Un solde bas mais positif est
 * un avertissement : il reste du temps pour recharger. N'avoir que le premier
 * seuil reviendrait à n'alerter qu'une fois le mal fait.
 *
 * <h2>Le piège du {@code Mono} jeté</h2>
 *
 * {@code ScheduledTasksConfig} a connu, dans ce dépôt, la forme
 * {@code Mono.fromRunnable(() -> service.purge()).subscribe()} : le corps du
 * {@code Runnable} construisait le {@code Mono} de purge puis le jetait, et
 * aucune des trois purges ne supprimait quoi que ce soit — pendant que les
 * journaux annonçaient leur bon déroulement. Le {@code Mono} est ici souscrit
 * directement.
 */
@Component
@Slf4j
public class SurveillanceDuSoldeSms {

    private final IAdministrationSms administration;
    private final boolean surveillanceActive;
    private final boolean smsActif;
    private final String fournisseurRetenu;
    private final int seuilDAlerte;

    public SurveillanceDuSoldeSms(
            IAdministrationSms administration,
            @Value("${sms.solde.surveillance.enabled:true}") boolean surveillanceActive,
            @Value("${sms.enabled:false}") boolean smsActif,
            @Value("${sms.provider:" + AfrikSmsProvider.NOM + "}") String fournisseurRetenu,
            @Value("${sms.solde.surveillance.seuil-alerte:100}") int seuilDAlerte) {
        this.administration = administration;
        this.surveillanceActive = surveillanceActive;
        this.smsActif = smsActif;
        this.fournisseurRetenu = fournisseurRetenu == null ? "" : fournisseurRetenu.trim();
        this.seuilDAlerte = seuilDAlerte;
    }

    /**
     * Relève le solde et alerte s'il le faut.
     *
     * <p>Tous les jours à 8 h par défaut : assez tôt pour qu'une recharge soit
     * possible dans la journée, et une seule fois, parce qu'une alerte répétée
     * toutes les heures cesse d'être lue.
     */
    @Scheduled(cron = "${sms.solde.surveillance.cron:0 0 8 * * *}")
    public void verifierLeSolde() {
        if (!doitSurveiller()) {
            return;
        }
        administration.consulterSolde()
                .doOnNext(this::alerterSiNecessaire)
                // L'echec de la consultation est signale, jamais propage : il ne
                // doit pas interrompre l'ordonnanceur. Mais il ne doit pas non
                // plus passer inapercu — c'est exactement ce qui est arrive aux
                // purges de ce service.
                .doOnError(erreur -> log.error(
                        ServiceConstants.LOG_ERROR_SMS_SOLDE_ILLISIBLE,
                        erreur.getClass().getSimpleName()))
                .onErrorComplete()
                .subscribe();
    }

    /**
     * Le relevé a-t-il un sens dans cette configuration ?
     *
     * <p>Trois raisons de s'abstenir, et aucune n'est une anomalie :
     *
     * <ul>
     *   <li>la surveillance est explicitement coupée ;</li>
     *   <li>{@code sms.enabled} est faux — aucun SMS ne part, donc aucun crédit
     *       ne se consomme ;</li>
     *   <li>le fournisseur retenu n'est pas AfrikSMS. La consultation de solde
     *       est <b>propre à AfrikSMS</b> : l'appeler sous Twilio interrogerait
     *       un service qu'on n'utilise pas, et une alerte en sortirait sur un
     *       crédit qui ne sert à rien.</li>
     * </ul>
     */
    private boolean doitSurveiller() {
        return surveillanceActive
                && smsActif
                && AfrikSmsProvider.NOM.equalsIgnoreCase(fournisseurRetenu);
    }

    /**
     * Journalise au niveau qui correspond à la gravité.
     *
     * <p>Visible pour le test : c'est la décision qu'il faut vérifier, et elle
     * ne se déduit pas de la lecture du {@code cron}.
     */
    void alerterSiNecessaire(List<IAdministrationSms.SoldeParPays> soldes) {
        if (soldes.isEmpty()) {
            log.warn(ServiceConstants.LOG_WARN_SMS_SOLDE_VIDE);
            return;
        }
        for (IAdministrationSms.SoldeParPays parPays : soldes) {
            if (parPays.estCritique()) {
                log.error(ServiceConstants.LOG_ERROR_SMS_SOLDE_EPUISE,
                        parPays.pays(), parPays.solde());
            } else if (parPays.solde() <= seuilDAlerte) {
                log.warn(ServiceConstants.LOG_WARN_SMS_SOLDE_BAS,
                        parPays.pays(), parPays.solde(), seuilDAlerte);
            } else {
                log.info(ServiceConstants.LOG_INFO_SMS_SOLDE, parPays.pays(), parPays.solde());
            }
        }
    }
}
