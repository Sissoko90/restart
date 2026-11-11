#!/usr/bin/env python3
"""Extrait les endpoints REST des contrôleurs Spring, en résolvant les constantes."""
import json
import os
import re
import sys

ROOT = "/Users/abdatytechnologie/Documents/Restart"

SERVICES = {
    "interface-service": ("interface_service", 8088),
    "auth-service": ("auth_service", 8082),
    "user-service": ("user_service", 8083),
    "media-service": ("media_service", 8087),
    "quotas-service": ("quotas_service", 8089),
    "setting-service": ("setting_service", 8090),
    "security-service": ("security_service", 8091),
    # notification-service : la boite de reception du porteur.
    #
    # Le flux temps reel (/notifications-flux) n'apparait PAS dans la
    # collection : c'est une montee en protocole WebSocket, et Postman ne la
    # construit pas depuis une annotation @GetMapping — il n'y en a pas, le
    # point d'entree est un SimpleUrlHandlerMapping.
    "notification-service": ("notification_service", 8086),
    # La place de marche. Inscrite ici DES sa creation : ModuleLivrableTest
    # refuse le build tant qu'un service exposant des endpoints n'y figure pas,
    # et c'est exactement son role — des endpoints qui existent sans qu'aucune
    # collection ne permette de les appeler sont des endpoints que personne
    # n'essaiera.
    "market-service": ("market_service", 8092),
}

METHOD_ANNOTATIONS = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
}


def load_constants(service_dir):
    """Collecte toutes les constantes String du service : NOM -> valeur."""
    constants = {}
    for dirpath, _, filenames in os.walk(service_dir):
        if "/target/" in dirpath:
            continue
        for name in filenames:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8", errors="ignore") as handle:
                content = handle.read()
            cls = name[:-5]
            for match in re.finditer(
                r'static final String (\w+)\s*=\s*"([^"]*)"', content
            ):
                constants[match.group(1)] = match.group(2)
                constants[f"{cls}.{match.group(1)}"] = match.group(2)
    return constants


def resolve(expr, constants):
    """Résout une expression de chemin : littéral, constante, ou concaténation.

    Les contraintes regex des variables de chemin (`{filename:.+}`) sont retirées :
    Postman ne les comprend pas et elles cassent le découpage en segments.
    """
    expr = re.sub(r"\{(\w+):[^}]*\}", r"{\1}", expr.strip())
    parts = [p.strip() for p in expr.split("+")]
    out = []
    for part in parts:
        if part.startswith('"') and part.endswith('"'):
            out.append(part[1:-1])
        elif part in constants:
            out.append(constants[part])
        else:
            short = part.split(".")[-1]
            if short in constants:
                out.append(constants[short])
            else:
                out.append(f"{{?{part}}}")
    return "".join(out)


def extract_path(annotation_args, constants):
    """Extrait le chemin d'un contenu d'annotation."""
    if not annotation_args:
        return ""
    args = annotation_args.strip()
    m = re.search(r'(?:value|path)\s*=\s*([^,)]+)', args)
    if m:
        return resolve(m.group(1), constants)
    # Premier argument positionnel, si ce n'est pas un autre attribut
    first = args.split(",")[0].strip()
    if "=" in first and not first.startswith('"'):
        return ""
    return resolve(first, constants)


def arguments_annotation(lines, index, apres_nom):
    """
    Contenu entre parentheses d'une annotation, meme sur PLUSIEURS lignes.

    La lecture etait faite ligne par ligne avec `\\(([^)]*)\\)`, qui exige la
    parenthese fermante sur la meme ligne. Une annotation repartie sur deux
    lignes — forme courante des que `consumes` ou `produces` s'ajoute au
    `value` — ne livrait donc aucun argument, et le chemin retombait
    silencieusement sur la base du controleur.

    Le resultat n'etait pas un endpoint manquant mais un endpoint **faux** :
    `@PostMapping(value = ...CALLBACK_PROVIDER_ENDPOINT,\\n consumes = ...)`
    produisait `POST /auth`. Une collection Postman qui pointe un mauvais
    chemin est pire qu'une collection incomplete, parce que rien ne la
    contredit.

    Rend `None` si l'annotation n'a pas de parentheses — `@GetMapping` nu, qui
    est valide et designe la base.
    """
    reste = lines[index][apres_nom:].lstrip()
    if not reste.startswith("("):
        return None

    fragments = [lines[index][apres_nom:]]
    for suivante in lines[index + 1:index + 8]:
        if equilibre("".join(fragments)):
            break
        fragments.append(suivante)

    texte = "".join(fragments)
    debut = texte.index("(")
    profondeur = 0
    for position, caractere in enumerate(texte[debut:], start=debut):
        if caractere == "(":
            profondeur += 1
        elif caractere == ")":
            profondeur -= 1
            if profondeur == 0:
                return texte[debut + 1:position]
    # Parenthese jamais fermee sur la fenetre lue : mieux vaut ne rien rendre
    # que rendre un fragment, qui donnerait un chemin tronque.
    return None


def equilibre(texte):
    return texte.count("(") > 0 and texte.count("(") == texte.count(")")


def parse_controller(path, constants):
    with open(path, encoding="utf-8", errors="ignore") as handle:
        content = handle.read()

    if "@RestController" not in content and "@Controller" not in content:
        return None

    base = ""
    m = re.search(r'@RequestMapping\(([^)]*)\)', content)
    if m:
        base = extract_path(m.group(1), constants)

    endpoints = []
    lines = content.split("\n")
    for i, line in enumerate(lines):
        m = re.search(r'@(\w+Mapping)\b', line)
        if not m or m.group(1) not in METHOD_ANNOTATIONS:
            continue

        args = arguments_annotation(lines, i, m.end())

        # Annotation qui PORTE des parentheses mais dont le contenu n'a pas pu
        # etre lu : c'est le symptome exact du defaut corrige ci-dessus, et il
        # est silencieux — le chemin retombe sur la base du controleur et la
        # collection pointe un mauvais endpoint. On le signale au lieu de
        # produire une requete fausse.
        illisible = args is None and lines[i][m.end():].lstrip().startswith("(")

        sub = extract_path(args, constants) if args else ""
        full = (base + sub) or "/"
        full = re.sub(r"/+", "/", full)

        # Nom et signature de la méthode : les ~12 lignes suivantes
        window = "\n".join(lines[i:i + 12])
        name_match = re.search(r'public\s+[\w<>,\[\]\s\.\?]+?\s+(\w+)\s*\(', window)
        method_name = name_match.group(1) if name_match else "?"

        endpoints.append({
            "method": METHOD_ANNOTATIONS[m.group(1)],
            "path": full,
            "handler": method_name,
            "admin": "hasRole('ADMIN')" in window or "/admin" in full,
            "hasBody": "@RequestBody" in window,
            "multipart": "@RequestPart" in window or "MULTIPART" in window,
            "annotationIllisible": illisible,
        })

    return {"controller": os.path.basename(path)[:-5], "base": base, "endpoints": endpoints}


def main():
    result = {}
    for service, (pkg, port) in SERVICES.items():
        service_dir = os.path.join(ROOT, service, "src", "main", "java")
        if not os.path.isdir(service_dir):
            continue
        constants = load_constants(service_dir)

        controllers = []
        for dirpath, _, filenames in os.walk(service_dir):
            if "/target/" in dirpath:
                continue
            for name in sorted(filenames):
                if not name.endswith("Controller.java"):
                    continue
                parsed = parse_controller(os.path.join(dirpath, name), constants)
                if parsed and parsed["endpoints"]:
                    controllers.append(parsed)

        result[service] = {"port": port, "controllers": controllers}

    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
