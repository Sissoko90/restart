package com.abdatytch.notification_service;

import com.abdatytch.notification_service.repositories.NotificationRepository;
import com.abdatytch.notification_service.repositories.RemiseParCanalRepository;
import com.abdatytch.notification_service.repositories.JetonDAppareilRepository;
import com.abdatytch.notification_service.repositories.SurchargeDeModeleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le contexte de notification-service se charge.
 *
 * <h2>Ce que ce test prouve, et ce qu'il ne prouve pas</h2>
 *
 * Il prouve que le cablage Spring tient : les beans se construisent, aucune
 * auto-configuration ne manque une dependance, aucun {@code @Value} ne reste
 * irresolu. C'est le minimum qu'un module doit franchir avant d'etre dit
 * livrable — et ce module ne le franchissait pas, puisqu'il n'etait meme pas
 * dans le reacteur.
 *
 * <p>Il ne prouve PAS que le service demarre en vrai : {@code application-test}
 * exclut R2DBC, Flyway, le serveur de configuration et Consul. Une colonne
 * manquante ou une migration fautive resteraient invisibles ici. C'est une
 * limite assumee et deja payee ailleurs dans ce depot ; elle se leve avec
 * Testcontainers, le jour ou ce service aura des depots a exercer.
 *
 * <p>{@code @ActiveProfiles("test")} est indispensable : sans lui, le contexte
 * cherche une URL R2DBC qu'aucune configuration ne fournit en test, et echoue
 * sur « Failed to determine a suitable R2DBC Connection URL ».
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("notification-service - le contexte se charge")
class NotificationServiceApplicationTests {

	/**
	 * Les depots sont SIMULES, comme dans setting-service.
	 *
	 * <p>{@code application-test} exclut
	 * {@code R2dbcRepositoriesAutoConfiguration}, donc aucun bean de depot
	 * n'existe — et l'orchestrateur en exige deux. Sans ces deux declarations,
	 * le contexte echoue sur « No qualifying bean of type
	 * NotificationRepository ».
	 *
	 * <p>C'est la contrepartie assumee d'un test de contexte sans base : il
	 * prouve le cablage, pas le schema. Un vrai test des depots demande
	 * Testcontainers.
	 */
	@MockitoBean
	private NotificationRepository notificationRepository;

	@MockitoBean
	private RemiseParCanalRepository remiseParCanalRepository;

	/**
	 * Le depot des surcharges de modeles, exige par {@code MoteurDeModeles}.
	 *
	 * <p>Il n'est pas stubbe, et ce n'est pas un oubli. {@code findAll()} rend
	 * donc {@code null}, ce que {@code Flux.defer} transforme en signal d'erreur
	 * — pris par l'{@code onErrorResume} du moteur, qui conserve son instantane
	 * precedent, c'est-a-dire vide. Le contexte se charge, le fichier sert seul.
	 *
	 * <p>C'est exactement la degradation voulue au demarrage, et ce test la
	 * traverse gratuitement : le service rend des notifications sans attendre sa
	 * base.
	 */
	@MockitoBean
	private SurchargeDeModeleRepository surchargeDeModeleRepository;

	/**
	 * Le depot des jetons d'appareil, exige par {@code CanalPush}.
	 *
	 * <p>Quatrieme depot simule, pour la meme raison que les trois autres :
	 * {@code application-test} exclut {@code R2dbcRepositoriesAutoConfiguration},
	 * donc aucun bean de depot n'existe. Sans cette declaration, le contexte
	 * echoue sur « No qualifying bean of type JetonDAppareilRepository » —
	 * message exact rencontre a l'ajout du canal push.
	 */
	@MockitoBean
	private JetonDAppareilRepository jetonDAppareilRepository;

	@Test
	@DisplayName("le cablage Spring tient")
	void contextLoads() {
	}

	/**
	 * La planification est reellement active.
	 *
	 * <h2>Pourquoi ce test existe</h2>
	 *
	 * Ce module porte un {@code @Scheduled} — la surveillance du solde SMS, seul
	 * moyen de voir venir un credit epuise — et ne declare <b>pas</b>
	 * {@code @EnableScheduling}. Six des sept modules qui utilisent
	 * {@code @Scheduled} le declarent ; celui-ci non, ce qui donne toutes les
	 * apparences d'un oubli.
	 *
	 * <p>Ce n'en est pas un : {@code PlatformSecurityAutoConfiguration} de
	 * platform-commons porte {@code @EnableScheduling}, et s'applique ici parce
	 * que sa seule condition — {@code WebClient} au classpath — est remplie.
	 *
	 * <p>Mais cette chaine est <b>indirecte et fragile</b> : elle tient a une
	 * annotation posee dans un autre module, pour une autre raison (le
	 * rafraichissement du JWKS), sous une condition qui n'a rien a voir avec la
	 * planification. Retirer {@code WebClient}, ou deplacer
	 * {@code @EnableScheduling} de cette auto-configuration, rendrait la
	 * surveillance du solde inerte — <b>sans aucune erreur</b> : Spring ignore
	 * simplement l'annotation, et la tache ne se declenche jamais.
	 *
	 * <p>Ce test verifie donc le fait plutot que le raisonnement. Le bean
	 * recherche est celui que {@code @EnableScheduling} enregistre, et c'est lui
	 * qui traite les {@code @Scheduled}. Il est designe par la constante de
	 * Spring et non par une chaine, pour qu'un renommage se voie a la
	 * compilation.
	 */
	@Test
	@DisplayName("les taches planifiees sont reellement traitees")
	void planificationActive(ApplicationContext contexte) {
		assertThat(contexte.containsBean(
						TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
				.withFailMessage("""
						Aucun traitement des @Scheduled dans ce contexte.

						La surveillance du solde SMS ne se declencherait jamais, en \
						silence : Spring ignore un @Scheduled sans @EnableScheduling \
						sans rien signaler. Declarer @EnableScheduling dans ce module.""")
				.isTrue();
	}

}
