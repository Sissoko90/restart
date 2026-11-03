#!/usr/bin/env bash
# ==========================================================
# Tableau de bord : quels modules sont finis et bien cables.
# ==========================================================
# POURQUOI UN SCRIPT ET NON UN TABLEAU DANS UN FICHIER
# ----------------------------------------------------
# Un tableau ecrit a la main divergerait du code a la premiere modification, et
# personne ne le saurait. Ce depot a paye exactement cela plusieurs fois : deux
# sources pour un meme etat, dont l'une n'est jamais relue. Le tableau est donc
# RECALCULE a chaque appel, depuis le code lui-meme.
#
# CE QUE « BIEN CABLE » VEUT DIRE ICI
# -----------------------------------
# Chaque critere correspond a un defaut que ce depot a REELLEMENT connu, et non
# a une bonne pratique generale :
#
#   BUILD    le module est declare dans le POM parent.
#            geo, payment et notification ne le sont pas : ils ne sont meme pas
#            compiles.
#
#   ROUTE    le service est nomme dans CheminCible, donc joignable par la
#            passerelle. Une route absente rend un service invisible, et un
#            chemin qui ne mene nulle part est indistinguable d'un chemin
#            inexistant.
#
#   ACCES    un filtre verifie la signature HMAC de la passerelle. C'est le seul
#            mecanisme qui distingue un appel passe par la porte d'un appel
#            direct sur le port du service.
#
#   SEC      une chaine Spring Security est declaree. security-service n'en avait
#            AUCUNE alors qu'il decide de la politique de toute la plateforme :
#            l'auto-configuration s'appliquait, avec un utilisateur « user » au
#            mot de passe journalise au demarrage.
#
#   CHAINE   un ChaineReelleTest prouve que les endpoints repondent quand on les
#            appelle COMME LA PASSERELLE LES APPELLE. C'est le critere le plus
#            important : media-service etait entierement injoignable — deux
#            defauts independants — et aucun test ne le disait, parce qu'aucun
#            test ne faisait cet appel. La lecon ecrite alors : « pas de test de
#            la chaine HTTP » est un symptome plus grave que n'importe quelle
#            regle suspecte.
#
#   BLOC     un NoBlockingCallsTest arme BlockHound. La plateforme est reactive
#            de bout en bout ; un appel bloquant sur une EventLoop ne se voit pas
#            autrement. Un appel DNS de 113 ms a ete trouve ainsi dans le
#            gateway, declenchable par un en-tete forge.
#
#   POSTMAN  une collection existe, engendree depuis les controleurs.
#
# Un critere qui ne s'applique pas rend « s.o. » et ne penalise pas la note :
# noter platform-commons sur l'absence d'endpoints n'apprendrait rien.
#
#   ./scripts/etat-des-modules.sh
# ==========================================================
set -euo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RACINE"

CHEMIN_CIBLE="gateway-service/src/main/java/com/abdatytch/gateway_service/routing/CheminCible.java"

# Les modules declares au build, lus dans le POM parent.
# Deux substitutions explicites, et non `s|</\?module>||g` : le quantificateur
# `\?` n'existe pas dans le sed de BSD, livre avec macOS. La substitution ne
# faisait rien, silencieusement, et la colonne BUILD rendait NON pour TOUS les
# modules — y compris ceux qui sont bel et bien declares. Un tableau de bord qui
# se trompe est pire qu'une absence de tableau de bord.
MODULES_DECLARES="$(sed -n '/<modules>/,/<\/modules>/p' pom.xml \
    | grep -oE '<module>[^<]+</module>' \
    | sed -e 's|<module>||' -e 's|</module>||')"

# Tous les repertoires de service presents sur le disque, declares ou non :
# c'est la difference entre les deux qui est interessante.
REPERTOIRES="$(find . -maxdepth 2 -name pom.xml -not -path './pom.xml' \
    | sed 's|^\./||;s|/pom.xml$||' | sort)"

existe() { [ -n "$(find "$1" -name "$2" -not -path '*/target/*' 2>/dev/null | head -1)" ]; }

marque() { if [ "$1" = "1" ]; then printf 'oui'; else printf 'NON'; fi; }

aff() { if [ "$1" = "non" ]; then printf 's.o.'; else marque "$2"; fi; }

printf '%-22s %6s %6s %6s %5s %7s %5s %8s %7s %6s  %s\n' \
    MODULE BUILD ROUTE ACCES SEC CHAINE BLOC POSTMAN ENDPTS NOTE VERDICT
printf '%s\n' "----------------------------------------------------------------------------------------------------"

total_acquis=0
total_applicables=0

for module in $REPERTOIRES; do
    # --- BUILD : declare dans le POM parent ---
    if printf '%s\n' "$MODULES_DECLARES" | grep -qx "$module"; then build=1; else build=0; fi

    # --- Endpoints : ce qui decide si les criteres de surface s'appliquent ---
    #
    # Le `|| true` n'est pas une precaution decorative. `set -o pipefail` est
    # actif, et un grep qui ne trouve RIEN sort en 1 : pour config-service et
    # platform-commons, qui n'ont legitimement aucun endpoint, l'affectation
    # echouait et `set -e` arretait le script apres la premiere ligne. Le
    # tableau paraissait complet alors qu'il ne montrait qu'un module.
    endpoints=$({ grep -rhoE '@(Get|Post|Put|Patch|Delete)Mapping' "$module/src/main" \
        --include='*.java' 2>/dev/null || true; } | wc -l | tr -d ' ')

    # --- ROUTE : nomme dans CheminCible ---
    if grep -q "\"$module\"" "$CHEMIN_CIBLE" 2>/dev/null; then route=1; else route=0; fi

    # --- ACCES : un filtre verifie la signature de la passerelle ---
    # Cherche le COMPORTEMENT et non un nom de classe : la classe s'appelle
    # GatewayAccessWebFilter dans cinq services et GatewayAccessFilter dans deux
    # autres. Chercher un nom aurait declare les deux derniers non proteges —
    # un faux positif qui aurait envoye corriger ce qui marche.
    if grep -rql 'X-Gateway-Signature' "$module/src/main" --include='*.java' 2>/dev/null; then
        acces=1; else acces=0; fi

    # --- SEC, CHAINE, BLOC, POSTMAN ---
    if existe "$module/src/main" 'SecurityConfig.java'; then sec=1; else sec=0; fi
    if existe "$module/src/test" 'ChaineReelleTest.java'; then chaine=1; else chaine=0; fi
    if existe "$module/src/test" 'NoBlockingCallsTest.java'; then bloc=1; else bloc=0; fi
    if [ -d "postman/$module" ]; then postman=1; else postman=0; fi

    # ------------------------------------------------------------------
    # Applicabilite. Un critere hors sujet rend « s.o. » : il ne compte ni
    # comme acquis ni comme attendu.
    # ------------------------------------------------------------------
    a_route="oui"; a_acces="oui"; a_chaine="oui"; a_postman="oui"; a_bloc="oui"; a_sec="oui"

    if [ "$endpoints" = "0" ]; then
        # Sans endpoint, rien a router, rien a atteindre, rien a collectionner.
        a_route="non"; a_acces="non"; a_chaine="non"; a_postman="non"
    fi
    if [ "$module" = "platform-commons" ]; then
        # Une bibliotheque : ni chaine de securite, ni appels a instrumenter.
        a_sec="non"; a_bloc="non"
    fi
    if [ "$module" = "gateway-service" ]; then
        # Elle EST la porte : elle ne franchit pas sa propre signature.
        a_route="non"; a_acces="non"
    fi
    if [ "$module" = "config-service" ]; then
        # Servi avant la passerelle, par les services eux-memes au demarrage.
        a_route="non"; a_acces="non"
    fi

    # --- Note : acquis sur applicables ---
    acquis=0; applicables=0
    for couple in "oui:$build" "$a_route:$route" "$a_acces:$acces" "$a_sec:$sec" \
                  "$a_chaine:$chaine" "$a_bloc:$bloc" "$a_postman:$postman"; do
        applicable="${couple%%:*}"; valeur="${couple##*:}"
        if [ "$applicable" = "oui" ]; then
            applicables=$((applicables + 1))
            acquis=$((acquis + valeur))
        fi
    done

    if [ "$build" = "0" ]; then
        verdict="SQUELETTE (hors build)"
    elif [ "$acquis" = "$applicables" ]; then
        verdict="BOUCLE"
    else
        verdict="PARTIEL"
    fi

    printf '%-22s %6s %6s %6s %5s %7s %5s %8s %7s %4s/%-1s  %s\n' \
        "$module" \
        "$(marque "$build")" \
        "$(aff "$a_route" "$route")" \
        "$(aff "$a_acces" "$acces")" \
        "$(aff "$a_sec" "$sec")" \
        "$(aff "$a_chaine" "$chaine")" \
        "$(aff "$a_bloc" "$bloc")" \
        "$(aff "$a_postman" "$postman")" \
        "$endpoints" \
        "$acquis" "$applicables" \
        "$verdict"

    total_acquis=$((total_acquis + acquis))
    total_applicables=$((total_applicables + applicables))
done

# Les squelettes hors build sont listes separement : ils existent sur le disque
# et n'entrent dans aucune compilation. Les ignorer en silence serait la
# meilleure facon de les oublier jusqu'au jour ou quelqu'un les croit livres.
echo
for repertoire in geo-service payment-service notification-service; do
    if [ -d "$repertoire" ] && ! printf '%s\n' "$MODULES_DECLARES" | grep -qx "$repertoire"; then
        echo "  hors build : $repertoire — present sur le disque, dans aucune compilation"
    fi
done

echo
printf 'Total : %s criteres acquis sur %s applicables.\n' "$total_acquis" "$total_applicables"
echo
echo "Un PARTIEL n'est pas un defaut en soi : c'est une case a regarder."
echo "La colonne qui compte le plus est CHAINE — elle dit si les endpoints"
echo "repondent quand on les appelle comme la passerelle les appelle."
