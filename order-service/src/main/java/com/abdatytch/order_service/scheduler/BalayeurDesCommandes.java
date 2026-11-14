package com.abdatytch.order_service.scheduler;

import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.metrics.OrderMetrics;
import com.abdatytch.order_service.repository.CommandeRepository;
import com.abdatytch.order_service.repository.RetourRepository;
import com.abdatytch.order_service.service.ICommandeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Les deux balayages, et le releve des files.
 *
 * <h2>Chacun protege de quelque chose de concret</h2>
 *
 * <ul>
 *   <li><b>Les impayees</b> : une commande non payee a l'echeance est annulee et
 *       son stock RENDU. Sans ce passage, un seul acheteur mal intentionne
 *       viderait une boutique sans rien debourser, et le stock resterait bloque
 *       indefiniment.</li>
 *   <li><b>Les clotures</b> : une commande livree dont la fenetre de retour est
 *       passee devient {@code CLOTUREE}. C'est l'etat que payment-service
 *       attendra pour verser a la boutique : sans ce passage, AUCUNE BOUTIQUE
 *       NE SERAIT JAMAIS PAYEE.</li>
 * </ul>
 *
 * <h2>Pourquoi un plafond par passage</h2>
 *
 * Sans lui, le premier passage apres une panne prolongee chargerait toutes les
 * commandes en retard d'un coup. Un balayeur qui tombe au milieu laisse la
 * moitie du travail fait — et comme chaque commande est traitee
 * independamment, le passage suivant reprend la ou il s'etait arrete.
 *
 * <h2>Les erreurs sont absorbees PAR COMMANDE</h2>
 *
 * Une commande qui echoue ne doit pas arreter le balayage des suivantes. C'est
 * la difference entre « une commande en retard » et « plus aucune commande
 * cloturee », et la seconde se solde par des boutiques impayees.
 */
@Component
public class BalayeurDesCommandes {

    private static final Logger log = LoggerFactory.getLogger(BalayeurDesCommandes.class);

    /** L'auteur inscrit au registre pour les transitions automatiques. */
    static final String AUTEUR = "balayeur";

    private final CommandeRepository commandes;
    private final RetourRepository retours;
    private final ICommandeService service;
    private final OrderMetrics metriques;
    private final int plafond;

    public BalayeurDesCommandes(CommandeRepository commandes, RetourRepository retours,
                                ICommandeService service, OrderMetrics metriques,
                                @Value("${order.balayage.plafond:200}") int plafond) {
        this.commandes = commandes;
        this.retours = retours;
        this.service = service;
        this.metriques = metriques;
        this.plafond = plafond;
    }

    /**
     * Annule les commandes impayees echues et rend leur stock.
     *
     * <p>{@code fixedDelayString} et non {@code fixedRateString} : le delai
     * court APRES la fin du passage precedent. A taux fixe, un passage plus
     * long que la periode en declencherait un second en parallele, et les deux
     * traiteraient les memes commandes.
     */
    @Scheduled(fixedDelayString = "${order.balayage.impayees-ms:300000}",
            initialDelayString = "${order.balayage.impayees-ms:300000}")
    public void annulerLesImpayees() {
        commandes.impayeesEchues(LocalDateTime.now(), plafond)
                .concatMap(commande -> service.fairePasserA(commande.getId(),
                                StatutCommande.ANNULEE, AUTEUR, RoleDeTransition.SYSTEME,
                                "Delai de paiement depasse")
                        .doOnNext(annulee -> log.info(
                                "Commande {} annulee : paiement non recu dans le delai",
                                commande.getNumero()))
                        // Absorbee PAR COMMANDE : une commande qui resiste ne
                        // doit pas empecher d'annuler les suivantes et de
                        // rendre leur stock.
                        .onErrorResume(erreur -> {
                            log.warn("Annulation impossible pour {} : {}",
                                    commande.getNumero(), erreur.getMessage());
                            return Mono.empty();
                        }))
                .count()
                .subscribe(traitees -> {
                    if (traitees > 0) {
                        log.info("Balayage des impayees : {} commande(s) annulee(s)", traitees);
                    }
                }, erreur -> log.error("Balayage des impayees interrompu", erreur));
    }

    /**
     * Cloture les commandes livrees dont la fenetre de retour est passee.
     *
     * <p>C'est le passage qui rend une commande PAYABLE. Qu'il s'arrete sans
     * bruit serait le pire defaut de ce module : le service repondrait
     * normalement, les acheteurs commanderaient, et aucune boutique ne serait
     * payee. L'alerte {@code AucuneClotureDeCommande} existe pour cela.
     */
    @Scheduled(fixedDelayString = "${order.balayage.clotures-ms:3600000}",
            initialDelayString = "${order.balayage.clotures-ms:3600000}")
    public void cloturerLesLivrees() {
        commandes.aCloturer(LocalDateTime.now(), plafond)
                .concatMap(commande -> service.fairePasserA(commande.getId(),
                                StatutCommande.CLOTUREE, AUTEUR, RoleDeTransition.SYSTEME,
                                "Fenetre de retour ecoulee")
                        .onErrorResume(erreur -> {
                            log.warn("Cloture impossible pour {} : {}",
                                    commande.getNumero(), erreur.getMessage());
                            return Mono.empty();
                        }))
                .count()
                .subscribe(traitees -> {
                    if (traitees > 0) {
                        log.info("Balayage des clotures : {} commande(s) cloturee(s)", traitees);
                    }
                }, erreur -> log.error("Balayage des clotures interrompu", erreur));
    }

    /**
     * Releve les files que Sankadi doit traiter.
     *
     * <h3>Un recomptage periodique, et non une jauge a fonction de lecture</h3>
     *
     * Une jauge Micrometer appelle sa fonction depuis le fil du relevé
     * Prometheus, et cette fonction est SYNCHRONE : elle bloquerait sur du SQL.
     * La politique de ce depot est zero blocage, verifiee par BlockHound.
     *
     * <p>La contrepartie est une fraicheur d'une minute, ce qui est sans
     * consequence pour une file d'arbitrage.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void releverLesFiles() {
        Mono.zip(
                        commandes.compterParStatut(StatutCommande.A_PAYER),
                        retours.compterEnAttenteDArbitrage(),
                        commandes.compterParStatut(StatutCommande.LIVREE))
                .subscribe(compte -> metriques.files(
                                compte.getT1().intValue(),
                                compte.getT2().intValue(),
                                compte.getT3().intValue()),
                        erreur -> log.warn("Releve des files impossible : {}",
                                erreur.getMessage()));
    }
}
