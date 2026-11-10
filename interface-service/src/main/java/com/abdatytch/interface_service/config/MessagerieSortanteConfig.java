package com.abdatytch.interface_service.config;

import com.abdatytch.commons.notification.ClientMessagerieNotification;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Enregistre le client de la messagerie sortante, partage par platform-commons.
 *
 * <h2>Pourquoi un {@code @Import} et non une auto-configuration</h2>
 *
 * {@link ClientMessagerieNotification} vit dans platform-commons, qu'aucun
 * service ne balaye : chaque {@code @SpringBootApplication} ne scanne que son
 * propre paquet. Il faut donc l'enregistrer explicitement.
 *
 * <p>Une auto-configuration l'aurait fait seule, et c'est precisement ce qu'il
 * ne faut pas : elle se declencherait dans les ONZE modules ayant
 * {@code WebClient} au chemin de classe — gateway, media, quotas, setting,
 * security — et chacun journaliserait au demarrage, en ERREUR, que
 * {@code notification-service.url} n'est pas configure. Onze erreurs par
 * demarrage de plateforme pour un client que deux services emploient. Un
 * journal qui crie sans raison finit par n'etre plus lu.
 *
 * <h2>Pourquoi {@code @Import} d'une classe nue, et non un {@code @Bean}</h2>
 *
 * Le client porte ses six {@code @Value} sur son CONSTRUCTEUR. Spring les
 * honore pour une definition de bean, ce qu'{@code @Import} cree. Passer par un
 * {@code @Bean} obligerait a recopier les six clefs de configuration ici — donc
 * dans chacun des deux services — et deux listes d'une meme valeur finissent
 * par diverger.
 *
 * <h2>Pourquoi le client a quitte auth-service</h2>
 *
 * Il y etait, et interface-service en avait besoin pour ses deux courriels
 * d'OTP. Le recopier aurait fait deux clients d'un meme protocole interne, avec
 * deux manieres de lire la reponse et de traiter le refus. Le contrat est
 * garde par {@code ContratDuMessageInterneTest}, qui compare le client au
 * controleur de notification-service champ par champ.
 */
@Configuration
@Import(ClientMessagerieNotification.class)
public class MessagerieSortanteConfig {
}
