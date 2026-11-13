package com.abdatytch.market_service.config;

import com.abdatytch.commons.notification.ClientMessagerieNotification;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * La messagerie sortante, PARTAGEE avec auth-service et interface-service.
 *
 * <p>{@code ClientMessagerieNotification} vit dans platform-commons. Un client
 * propre a ce module aurait fait une seconde implementation d'un meme protocole
 * interne, avec son propre disjoncteur et son propre delai a tenir d'accord.
 *
 * <p>{@code @Import} et non {@code @ComponentScan} : la classe n'est pas
 * annotee {@code @Component}, precisement pour que chaque module qui s'en sert
 * le DECLARE. Un balayage l'enregistrerait partout, y compris la ou personne
 * n'envoie rien.
 */
@Configuration
@Import(ClientMessagerieNotification.class)
public class MessagerieSortanteConfig {
}
