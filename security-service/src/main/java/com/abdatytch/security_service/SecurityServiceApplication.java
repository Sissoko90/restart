package com.abdatytch.security_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * security-service : detenteur de la politique de securite de la plateforme.
 *
 * <h2>Ce que ce service fait, et ce qu'il ne fait pas</h2>
 *
 * Il <b>decide</b> : politique de securite typee, regles d'adresses, pays
 * refuses, fenetres de maintenance, appareils de confiance, codes de secours,
 * contacts de recuperation, parcours d'export des donnees personnelles.
 *
 * <p>Il n'<b>applique</b> rien sur le chemin des requetes. Le gateway et
 * auth-service appliquent, en lisant la politique dans Redis — jamais en
 * appelant ce service. Un appel par requete mettrait un saut reseau sur le
 * chemin critique, ce qui est exactement ce que l'architecture de cette
 * plateforme refuse.
 *
 * <p>Le motif est celui deja en place pour {@code TokenRevocationChecker} :
 * une decision publiee, et des applicateurs qui lisent un cache.
 */
@SpringBootApplication
public class SecurityServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(SecurityServiceApplication.class, args);
	}

}
