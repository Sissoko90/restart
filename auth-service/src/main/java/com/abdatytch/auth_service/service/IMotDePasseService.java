package com.abdatytch.auth_service.service;

import reactor.core.publisher.Mono;

/**
 * Hachage et verification de mot de passe, hors de l'EventLoop.
 *
 * <h2>Le defaut que ce service corrige</h2>
 *
 * {@code passwordEncoder.matches(...)} et {@code encode(...)} etaient appeles
 * <b>directement</b> dans les chaines reactives, a huit endroits. Sur une
 * requete WebFlux, cela place le calcul sur un thread d'EventLoop Netty.
 *
 * <p>Mesure sur la machine de developpement : une verification
 * {@code BCryptPasswordEncoder(12)} prend <b>241 ms</b>. {@code NettyConfig}
 * alloue autant d'EventLoops que de coeurs — dix ici. Dix tentatives de
 * connexion simultanees immobilisaient donc toute la capacite de traitement du
 * service pendant un quart de seconde : controles de sante, lectures de profil,
 * OTP et accuses de reception SMS attendaient derriere.
 *
 * <p>Une requete HTTP qui coute 241 ms de CPU est un <b>amplificateur</b> : le
 * levier de deni de service le moins cher de la plateforme.
 *
 * <h2>Pourquoi BlockHound ne pouvait pas le voir</h2>
 *
 * Il instrumente les appels <b>bloquants</b> — entrees-sorties,
 * {@code Thread.sleep}, attentes de verrou. Une boucle de calcul n'en est pas
 * un. La garantie « zero blocage » du depot, pourtant verifiee par
 * {@code NoBlockingCallsTest}, avait donc un angle mort exactement la ou se
 * trouve le travail CPU le plus lourd. Ce n'est pas un defaut du test : c'est
 * la limite de l'outil, et elle se comble par le garde-fou de source
 * {@code HachageHorsEventLoopTest}.
 *
 * <h2>Pourquoi un service et non huit appels enveloppes</h2>
 *
 * Envelopper chaque site dans {@code Mono.fromCallable(...).subscribeOn(...)}
 * aurait fait huit copies de la meme decision, et le neuvieme site l'aurait
 * oubliee. Un detenteur unique rend en outre la regle verifiable : aucun autre
 * fichier n'a le droit de toucher au {@code PasswordEncoder}.
 */
public interface IMotDePasseService {

    /**
     * Verifie un mot de passe contre son empreinte.
     *
     * <p>Rend {@code false} plutot qu'une erreur si l'empreinte est absente :
     * un compte cree par un parcours sans mot de passe — OAuth2, biometrie — ne
     * doit pas faire echouer la comparaison autrement qu'un mot de passe faux,
     * sans quoi la reponse distinguerait les deux.
     */
    Mono<Boolean> correspond(String motDePasseEnClair, String empreinte);

    /** Calcule l'empreinte d'un mot de passe. */
    Mono<String> chiffrer(String motDePasseEnClair);
}
