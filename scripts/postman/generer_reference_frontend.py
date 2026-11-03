#!/usr/bin/env python3
"""Produit docs/API-FRONTEND.md depuis les contrôleurs et la passerelle.

Pourquoi généré, et non écrit à la main
---------------------------------------
Une liste de deux cents endpoints recopiée à la main divergera du code à la
première modification, et une référence fausse coûte plus cher qu'une référence
absente : l'intégrateur la croit.

Trois sources sont lues, et aucune n'est paraphrasée :
  - les contrôleurs, via extract_endpoints.py (méthode, chemin interne) ;
  - CheminCible, pour le préfixe public que le frontend doit appeler et la
    réécriture appliquée par la passerelle ;
  - les SecurityConfig, pour savoir ce qui est public, authentifié ou réservé.

Le texte d'introduction, lui, est tenu à la main dans docs/API-FRONTEND-INTRO.md :
il porte des décisions que personne ne peut deduire du code.
"""
import json
import pathlib
import re
import subprocess
import sys

RACINE = pathlib.Path(__file__).resolve().parents[2]
SORTIE = RACINE / "docs" / "API-FRONTEND.md"
INTRO = RACINE / "docs" / "API-FRONTEND-INTRO.md"

# Préfixe public -> (service, réécriture appliquée par la passerelle).
# Lu depuis CheminCible pour que l'écart soit impossible.
CHEMIN_CIBLE = (RACINE / "gateway-service/src/main/java/com/abdatytch/"
                "gateway_service/routing/CheminCible.java")

SECURITY_CONFIGS = {
    "auth-service": RACINE / "auth-service/src/main/java/com/abdatytch/"
                             "auth_service/config/SecurityConfig.java",
}

# La passerelle tranche l'accès pour le chemin PUBLIC.
#
# C'est elle que le frontend atteint : un endpoint que le service déclare
# `permitAll` reste inaccessible si la passerelle exige un jeton. L'inverse est
# vrai aussi, d'où la lecture des deux.
SECURITY_GATEWAY = (RACINE / "gateway-service/src/main/java/com/abdatytch/"
                             "gateway_service/security/SecurityConfig.java")

ORDRE_DES_SERVICES = [
    "auth-service", "interface-service", "user-service", "media-service",
    "quotas-service", "setting-service", "security-service",
]


ROUTES_YAML = RACINE / "config-repo/dev/gateway-service-dev.yml"


def lire_le_routage():
    """Rend ({prefixe: service}, {depuis: vers}) tels que la passerelle les tient.

    Les DEUX mécanismes sont lus. Ce dépôt en porte deux — `CheminCible` plus le
    `RouterFunction` d'un côté, les routes Spring Cloud Gateway en YAML de
    l'autre — et n'en lire qu'un marquerait « non routé » des endpoints
    parfaitement joignables. Une référence qui se trompe coûte plus cher qu'une
    référence absente : l'intégrateur la croit.
    """
    source = CHEMIN_CIBLE.read_text(encoding="utf-8")
    prefixes = {}
    for cle, valeur in re.findall(
            r'SERVICES_PAR_PREFIXE\.put\("([^"]+)",\s*"([^"]+)"\)', source):
        prefixes[cle] = valeur
    reecritures = dict(re.findall(
        r'REECRITURES\.put\("([^"]+)",\s*"([^"]+)"\)', source))

    # Routes YAML : `uri: lb://<service>` puis `- Path=<motifs>`.
    #
    # Elles appliquent StripPrefix=2, donc le chemin interne est le chemin
    # public prive de /api/v1 — la même convention que CheminCible sans
    # réécriture. Elles sont ajoutées APRÈS, pour que CheminCible garde la
    # priorité : c'est l'ordre réel, le RouterFunctionMapping de Spring passant
    # avant le RoutePredicateHandlerMapping.
    yaml = ROUTES_YAML.read_text(encoding="utf-8")
    for service, motifs in re.findall(
            r"uri:\s*lb://([a-z0-9-]+)\s*\n\s*predicates:\s*\n\s*-\s*Path=([^\n]+)",
            yaml):
        for motif in motifs.split(","):
            prefixe = motif.strip().removesuffix("/**").removesuffix("/*")
            if prefixe and prefixe not in prefixes:
                prefixes[prefixe] = service
    return prefixes, reecritures


def chemins_pour(source, verdict):
    """Chemins d'une matrice d'autorisation portant un verdict donné.

    Le groupe capturé interdit la parenthèse fermante — `[^)]*` et non `.*?`
    avec DOTALL. Un motif paresseux mais traversant les lignes partait d'un
    `pathMatchers(...)` suivi d'`authenticated()` et s'étendait jusqu'au
    `.permitAll()` suivant, dix lignes plus bas : tous les chemins intermédiaires
    étaient alors déclarés publics. C'est le genre d'erreur qui rend une
    référence confiante et fausse.
    """
    trouves = set()
    for bloc in re.findall(
            r"pathMatchers\(([^)]*)\)\s*\." + verdict, source):
        trouves.update(re.findall(r'"([^"]+)"', bloc))
    return trouves


def chemins_publics(service):
    """Chemins internes déclarés permitAll par le service, tels quels."""
    fichier = SECURITY_CONFIGS.get(service)
    if fichier is None or not fichier.exists():
        return None
    return chemins_pour(fichier.read_text(encoding="utf-8"), r"permitAll\(\)")


def est_public(chemin, publics):
    if publics is None:
        return None
    for motif in publics:
        if motif.endswith("/**"):
            if chemin.startswith(motif[:-3]):
                return True
        elif motif.endswith("/*"):
            if chemin.startswith(motif[:-2]):
                return True
        elif motif == chemin:
            return True
    return False


def chemin_public(interne, service, prefixes, reecritures):
    """Chemin que le FRONTEND appelle, pour un chemin interne donné."""
    candidats = []
    for prefixe, cible in prefixes.items():
        if cible != service:
            continue
        # Le préfixe public, débarrassé de /api/v1, est-il réécrit ?
        court = prefixe[len("/api/v1"):]
        vers = reecritures.get(court, court)
        if interne == vers or interne.startswith(vers + "/"):
            reste = interne[len(vers):]
            candidats.append((len(vers), prefixe + reste))
    if not candidats:
        return None
    # Le préfixe interne le PLUS LONG gagne : /admin/documents avant /admin.
    return max(candidats)[1]


def portee(interne, appelable, publics, passerelle):
    """Accès réel, décidé par la passerelle PUIS par le service.

    Les deux comptent, et dans cet ordre : la passerelle est ce que le frontend
    atteint, donc elle peut refuser ce que le service autorise. Un endpoint
    n'est public que si les deux le laissent passer.
    """
    if interne.startswith("/internal"):
        return "interne"
    if appelable is None:
        return "non routé"

    admins, publics_passerelle = passerelle
    if est_public(appelable, admins):
        return "admin"

    if not est_public(appelable, publics_passerelle):
        # Surface d'administration, même quand la passerelle se contente
        # d'`authenticated()`.
        #
        # Le rôle est alors exigé par le service, pas par la passerelle. Les
        # deux étages comptent, et seul le résultat intéresse l'intégrateur :
        # marquer « authentifié » lui ferait croire qu'un client ordinaire peut
        # appeler ces endpoints, et il construirait un écran qui recevra 403.
        return "admin" if surface_dadministration(interne) else "authentifie"

    # La passerelle laisse passer : le service a le dernier mot.
    if publics is None:
        return "public"
    if est_public(interne, publics):
        return "public"
    return "admin" if surface_dadministration(interne) else "authentifie"


def surface_dadministration(interne):
    """Le chemin interne désigne-t-il une surface d'administration ?

    Lu sur le chemin INTERNE : c'est lui que la matrice du service filtre, et
    `/api/v1/auth-admin/...` comme `/api/v1/security-admin/...` se ramènent tous
    deux à des chemins commençant par `/admin` ou `/security-admin`.
    """
    return interne.startswith("/admin") or interne.startswith("/security-admin")


def main():
    extracteur = pathlib.Path(__file__).with_name("extract_endpoints.py")
    brut = subprocess.run([sys.executable, str(extracteur)],
                          capture_output=True, text=True, check=True).stdout
    services = json.loads(brut)
    prefixes, reecritures = lire_le_routage()

    source_passerelle = SECURITY_GATEWAY.read_text(encoding="utf-8")
    passerelle = (
        chemins_pour(source_passerelle, r'hasRole\("ADMIN"\)'),
        chemins_pour(source_passerelle, r"permitAll\(\)"),
    )

    lignes = []
    if INTRO.exists():
        lignes.append(INTRO.read_text(encoding="utf-8").rstrip())
        lignes.append("")

    lignes.append("## Référence des endpoints")
    lignes.append("")
    lignes.append("Générée depuis les contrôleurs par "
                  "`scripts/postman/generer_reference_frontend.py`. "
                  "Ne pas modifier à la main : la prochaine génération écrase.")
    lignes.append("")

    total = 0
    internes = 0
    for service in ORDRE_DES_SERVICES:
        info = services.get(service)
        if info is None:
            continue
        publics = chemins_publics(service)
        lignes.append(f"### {service} (port {info['port']})")
        lignes.append("")

        for controleur in sorted(info["controllers"],
                                 key=lambda c: c["controller"]):
            rangees = []
            for endpoint in controleur["endpoints"]:
                interne = endpoint["path"]
                appelable = chemin_public(interne, service, prefixes, reecritures)
                acces = portee(interne, appelable, publics, passerelle)
                if acces == "interne":
                    internes += 1
                    continue
                total += 1
                rangees.append((
                    endpoint["method"],
                    appelable or "(non routé par la passerelle)",
                    acces,
                    "oui" if endpoint.get("hasBody") else "—",
                ))
            if not rangees:
                continue
            lignes.append(f"**{controleur['controller']}**")
            lignes.append("")
            lignes.append("| Méthode | Chemin appelé par le frontend | Accès | Corps |")
            lignes.append("|---|---|---|---|")
            for methode, chemin, acces, corps in rangees:
                lignes.append(f"| `{methode}` | `{chemin}` | {acces} | {corps} |")
            lignes.append("")

    lignes.append("---")
    lignes.append("")
    lignes.append(f"**{total} endpoints appelables** depuis le frontend. "
                  f"{internes} endpoints `/internal/**` sont exclus : ils ne "
                  "sont joignables qu'entre services, derrière la signature HMAC "
                  "de la passerelle, et un appel direct est refusé.")
    lignes.append("")

    SORTIE.parent.mkdir(parents=True, exist_ok=True)
    SORTIE.write_text("\n".join(lignes) + "\n", encoding="utf-8")
    print(f"{SORTIE.relative_to(RACINE)} : {total} endpoints, "
          f"{internes} internes exclus")


if __name__ == "__main__":
    main()
