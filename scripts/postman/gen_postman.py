#!/usr/bin/env python3
"""Génère une collection Postman par service à partir des endpoints extraits."""
import json
import os
import pathlib
import re
import sys

ROOT = "/Users/abdatytechnologie/Documents/Restart"
OUT = os.path.join(ROOT, "postman")

with open(sys.argv[1], encoding="utf-8") as handle:
    DATA = json.load(handle)

# Corps de requête réalistes pour les endpoints les plus utilisés.
BODIES = {
    # La connexion biometrique prend desormais un IDENTIFIANT, et non plus
    # l'identite lue dans le jeton : c'est une methode de CONNEXION, donc
    # l'appelant n'a pas encore de jeton.
    ("auth-service", "POST", "/auth/biometric/authenticate/initiate"): {
        "identifier": "{{phoneNumber}}"},

    # ------------------------------------------------------------------
    # Corps derives des DTO REELS, champ par champ.
    #
    # Les quatorze entrees manquantes rendaient la collection inutilisable
    # pour un essai : la PREMIERE requete dont on a besoin,
    # POST /auth/admin/login, portait {"TODO": "completer le corps"}.
    #
    # Chaque corps ci-dessous a ete releve sur sa classe de requete, et non
    # devine : AdminLoginRequest, OAuth2CallbackRequest,
    # ProfileCompletionRequest, BiometricRegistrationRequest,
    # BiometricVerificationRequest, MessageTemplateRequest, LogFilterRequest,
    # AuditPhoneSearchRequest, AuditIpSearchRequest.
    # ------------------------------------------------------------------

    # Connexion administrateur : le point d'entree de tout essai.
    # `identifier` accepte le telephone ou l'adresse. `totpCode` n'est exige
    # qu'une fois le second facteur enrole — le laisser vide au premier essai.
    ("auth-service", "POST", "/auth/admin/login"): {
        "identifier": "{{adminPhone}}", "password": "{{adminPassword}}",
        "totpCode": ""},
    # Enrolement du second facteur : meme corps, et le secret TOTP n'est rendu
    # QU'UNE FOIS dans la reponse.
    ("auth-service", "POST", "/auth/admin/totp/enroll"): {
        "identifier": "{{adminPhone}}", "password": "{{adminPassword}}",
        "totpCode": ""},
    # Remplacement du second facteur par l'administrateur lui-meme. Le code
    # COURANT est obligatoire : sans lui, un mot de passe seul suffirait a
    # substituer son appareil a celui de l'administrateur.
    ("auth-service", "POST", "/auth/admin/totp/reset"): {
        "identifier": "{{adminPhone}}", "password": "{{adminPassword}}",
        "totpCode": "{{totpCode}}"},

    # Renvoi de code : meme DTO que l'oubli de mot de passe.
    ("auth-service", "POST", "/auth/resend-code"): {
        "phoneNumber": "{{phoneNumber}}"},

    # OAuth2. `state` protege de la falsification inter-site.
    ("auth-service", "POST", "/auth/oauth2/callback"): {
        "code": "code-autorisation-du-fournisseur", "provider": "google",
        "state": "etat-anti-csrf", "redirectUri": "{{baseUrl}}/auth/oauth2/callback"},
    ("auth-service", "POST", "/auth/oauth2/complete-profile"): {
        "phoneNumber": "{{phoneNumber}}", "countryCode": "+223",
        "email": "{{email}}"},

    # Recherches d'audit : le critere est dans le CORPS, jamais dans l'URL —
    # une URL est journalisee par le Gateway et les proxys, hors de portee de
    # l'effacement RGPD.
    ("auth-service", "POST", "/admin/audit/logs/search"): {
        "phoneNumber": "{{phoneNumber}}"},
    ("auth-service", "POST", "/admin/logs/search/by-phone"): {
        "phoneNumber": "{{phoneNumber}}"},
    ("auth-service", "POST", "/admin/logs/search/by-ip"): {
        "ipAddress": "203.0.113.7"},
    ("auth-service", "POST", "/admin/logs/filter"): {
        "eventType": "LOGIN", "status": "FAILED", "page": 0, "size": 20},

    # Biometrie WebAuthn. Ces valeurs ne passeront PAS la verification : elle
    # est reelle depuis webauthn4j, et exige une vraie attestation signee par
    # un authentificateur. Le corps sert a connaitre la forme attendue.
    ("auth-service", "POST", "/auth/biometric/register/complete"): {
        "type": "FINGERPRINT", "credentialId": "identifiant-de-la-credential",
        "publicKey": "", "attestationObject": "attestation-base64url",
        "clientDataJSON": "donnees-client-base64url",
        "deviceName": "iPhone de test", "challenge": "defi-de-register/initiate"},
    ("auth-service", "POST", "/auth/biometric/authenticate/verify"): {
        "credentialId": "identifiant-de-la-credential",
        "authenticatorData": "donnees-authentificateur-base64url",
        "clientDataJSON": "donnees-client-base64url",
        "signature": "signature-base64url",
        "challenge": "defi-de-authenticate/initiate", "userHandle": ""},

    # Gabarits de message.
    ("auth-service", "POST", "/admin/templates"): {
        "templateType": "SMS_VERIFICATION", "subject": "Code de verification",
        "content": "Votre code est {code}", "description": "Gabarit d'essai",
        "isActive": True, "variables": "code"},
    ("auth-service", "PUT", "/admin/templates/{templateId}"): {
        "templateType": "SMS_VERIFICATION", "subject": "Code de verification",
        "content": "Votre code est {code}", "description": "Gabarit modifie",
        "isActive": True, "variables": "code"},

    # Connexion unique, administrateurs compris.
    #
    # `identifier` accepte le telephone ou l'adresse et prime sur `phoneNumber`,
    # conserve pour les integrations existantes du SDK. `totpCode` n'est exige
    # que si le compte vise a un second facteur enrole : le laisser vide pour un
    # utilisateur final, le renseigner pour un administrateur. Le script de
    # pre-requete recalcule {{totpCode}} a partir de {{totpSecret}}.
    ("auth-service", "POST", "/auth/login"): {
        "phoneNumber": "{{phoneNumber}}", "identifier": "",
        "password": "{{password}}", "totpCode": ""},
    ("auth-service", "POST", "/auth/logout"): {"refreshToken": "{{refreshToken}}"},
    ("auth-service", "POST", "/auth/refresh"): {"refreshToken": "{{refreshToken}}"},
    ("auth-service", "POST", "/auth/forgot-password"): {"phoneNumber": "{{phoneNumber}}"},
    ("auth-service", "POST", "/auth/reset-password"): {
        "phoneNumber": "{{phoneNumber}}", "code": "123456",
        "newPassword": "{{password}}", "confirmPassword": "{{password}}"},
    ("auth-service", "POST", "/auth/phone/register-request"): {
        "phoneNumber": "{{phoneNumber}}", "countryCode": "+223"},
    ("auth-service", "POST", "/auth/phone/register-verify"): {
        "phoneNumber": "{{phoneNumber}}", "otp": "123456",
        "password": "{{password}}", "desiredRole": "USER"},
    ("auth-service", "POST", "/auth/phone/auth-request"): {
        "phoneNumber": "{{phoneNumber}}", "countryCode": "+223"},
    ("auth-service", "POST", "/auth/phone/auth-verify"): {
        "phoneNumber": "{{phoneNumber}}", "otp": "123456", "password": "{{password}}"},
    ("auth-service", "POST", "/auth/email/register"): {"email": "{{email}}"},
    ("auth-service", "POST", "/auth/email/register-verify"): {
        "email": "{{email}}", "otp": "123456", "firstName": "Jane", "lastName": "Doe",
        "phoneNumber": "{{phoneNumber}}", "countryCode": "+223",
        "password": "{{password}}", "desiredRole": "USER"},
    ("auth-service", "POST", "/auth/email/auth-request"): {"email": "{{email}}"},
    ("auth-service", "POST", "/auth/email/auth-verify"): {
        "email": "{{email}}", "otp": "123456", "password": "{{password}}"},
    ("auth-service", "PUT", "/auth/profile"): {
        "firstName": "Jane", "lastName": "Doe", "email": "{{email}}"},

    ("interface-service", "POST", "/auth/register"): {
        "email": "{{email}}", "password": "{{password}}",
        "username": "dev", "phoneNumber": "{{phoneNumber}}"},
    ("interface-service", "POST", "/auth/login"): {
        "identifier": "{{email}}", "password": "{{password}}"},
    ("interface-service", "POST", "/auth/verify-email"): {
        "email": "{{email}}", "otp": "123456"},
    ("interface-service", "POST", "/auth/verify-phone"): {
        "phoneNumber": "{{phoneNumber}}", "otp": "123456"},
    ("interface-service", "POST", "/auth/request-email-otp"): {"email": "{{email}}"},
    ("interface-service", "POST", "/auth/request-phone-otp"): {"phoneNumber": "{{phoneNumber}}"},
    ("interface-service", "POST", "/projects"): {
        "projectName": "mon-projet", "description": "Projet de démonstration"},
    ("interface-service", "POST", "/admin/login"): {
        "phoneNumber": "{{adminPhone}}", "password": "{{adminPassword}}", "totpCode": "123456"},
    ("interface-service", "POST", "/maintenance/auth"): {
        "phoneNumber": "{{adminPhone}}", "password": "{{adminPassword}}", "totpCode": "123456"},
    ("interface-service", "POST", "/maintenance/totp-config"): {"password": "{{adminPassword}}"},
    ("interface-service", "POST", "/maintenance/regenerate-totp"): {"password": "{{adminPassword}}"},

    ("quotas-service", "POST", "/quotas/{userId}/consume"): {"resource": "PROJECTS", "amount": 1},
    ("quotas-service", "POST", "/quotas/{userId}/release"): {"resource": "PROJECTS", "amount": 1},
    ("quotas-service", "PUT", "/admin/quotas/{userId}/tier"): {"tier": "PREMIUM"},

    ("setting-service", "PUT", "/admin/settings/system/{key}"): {"value": "true"},
    ("setting-service", "PUT", "/settings/users/{userId}/{key}"): {"value": "dark"},
}

# Endpoints publics : pas de jeton attendu.
CAPTURE_TOKEN = """// L'analyse JSON est GARDEE.
//
// `pm.response.json()` LEVE quand la reponse n'est pas du JSON — un 403 du
// filtre d'acces Gateway, un corps vide, une page d'erreur. Le script
// s'interrompait alors avant les assertions, et Postman affichait une erreur de
// script au lieu du statut reel : sur un echec de connexion, on ne voyait pas
// POURQUOI.
let json = {};
try {
    json = pm.response.json();
} catch (e) {
    console.log("Reponse non JSON (" + pm.response.code + ") : jeton non capture");
}
if (json.accessToken) {
    pm.collectionVariables.set("accessToken", json.accessToken);
    console.log("accessToken enregistré");
}
if (json.refreshToken) {
    pm.collectionVariables.set("refreshToken", json.refreshToken);
}
if (json.token) {
    pm.collectionVariables.set("maintenanceToken", json.token);
}"""


BASE_TEST = """pm.test("Statut non 5xx", function () {
    pm.expect(pm.response.code).to.be.below(500);
});
pm.test("Réponse sous 2s", function () {
    pm.expect(pm.response.responseTime).to.be.below(2000);
});"""


# Prefixe public au Gateway, par service : l'INVERSE de CheminCible
# (gateway-service/.../routing/CheminCible.java).
#
# auth-service et interface-service exposent tous les deux /admin/** et
# /auth/**. Le Gateway reserve /api/v1/admin/** et /api/v1/auth/** a
# interface-service, et donne a auth-service des prefixes distincts qu'il
# reecrit ensuite.
#
# Sans cette table, la collection d'auth-service pointait sur
# {{baseUrl}}/admin/accounts/... — qui part chez interface-service, lequel n'a
# pas ces endpoints.
PREFIXES_GATEWAY = {
    "auth-service": [("/admin", "/auth-admin"), ("/auth", "/auth-sdk")],
}


def chemin_gateway(service, path):
    """Chemin a appeler a travers le Gateway, pour un chemin de controleur."""
    for interne, public in PREFIXES_GATEWAY.get(service, []):
        # Segment ENTIER, et non startswith nu : sinon /auth-users deviendrait
        # /auth-sdk-users. Meme raisonnement que CheminCible.estLeSegment.
        if path == interne or path.startswith(interne + "/"):
            return public + path[len(interne):]
    return path


def path_postman_brut(segments):
    """`["a", ":id"]` -> `/a/:id`, ou chaine vide s'il n'y a rien."""
    return ("/" + "/".join(segments)) if segments else ""


def path_to_postman(path):
    """`/a/{id}/b` -> (["a", ":id", "b"], ["id"])."""
    segments, variables = [], []
    for seg in [s for s in path.split("/") if s]:
        if seg.startswith("{") and seg.endswith("}"):
            nom = seg[1:-1]
            variables.append(nom)
            segments.append(":" + nom)
        else:
            segments.append(seg)
    return segments, variables


# Chemins publics, LUS dans le SecurityConfig de chaque service.
#
# Une seule liste codee en dur servait pour les six services. Elle decrivait
# interface-service, ou la connexion developpeur est publique. Dans auth-service,
# en revanche, SEULS /auth/admin/login et /auth/admin/totp/enroll le sont :
# `.pathMatchers("/auth/**").authenticated()` couvre tout le reste, parce que le
# SDK s'adresse a l'application du developpeur, qui doit d'abord s'authentifier.
#
# Consequence mesuree : 12 des 14 requetes sans en-tete Authorization de la
# collection auth-service repondaient 401, et le journal du Gateway disait
# « Aucun jeton presente » — exact, mais cela laisse croire a une erreur de
# l'appelant.
#
# La liste est donc DERIVEE du code, au meme titre que les endpoints : une regle
# de securite modifiee se repercute a la regeneration, sans que personne ait a y
# penser.
#
# Deux pieges rencontres en l'ecrivant, tous deux trouves par la mesure :
#
#  1. `pathMatchers\(([^;]*?)\)\s*\.permitAll\(\)` sur-capturait. Le bloc
#     authorizeExchange entier est UNE SEULE instruction, donc borner au
#     point-virgule ne borne rien : le motif franchissait un
#     `.hasRole("ADMIN")` pour atteindre un `.permitAll()` plus loin, et
#     recoltait « ADMIN » comme chemin public. Interdire les parentheses dans
#     les arguments ferme ce passage.
#
#  2. interface-service declare `pathMatchers(HttpMethod.OPTIONS, "/**")
#     .permitAll()` — une regle de prevol CORS. Prise sans sa methode, elle
#     rendait TOUS ses chemins publics, donc ses 35 requetes depourvues de
#     jeton. La methode est donc retenue avec le chemin.
MOTIF_PERMIT_ALL = re.compile(r'pathMatchers\(([^()]*)\)\s*\.permitAll\(\)')

_cache_publics = {}


def chemins_publics(service):
    """[(methode|None, chemin)] en permitAll, lus dans le SecurityConfig."""
    if service in _cache_publics:
        return _cache_publics[service]
    racine = pathlib.Path(ROOT) / service / "src/main/java"
    regles = []
    for fichier in racine.rglob("SecurityConfig.java"):
        contenu = fichier.read_text(encoding="utf-8", errors="replace")
        for m in MOTIF_PERMIT_ALL.finditer(contenu):
            args = m.group(1)
            methode = re.search(r'HttpMethod\.([A-Z]+)', args)
            for chemin in re.findall(r'"([^"]+)"', args):
                regles.append((methode.group(1) if methode else None, chemin))
    _cache_publics[service] = regles
    return regles


def is_public(path, methode, service):
    for m, motif in chemins_publics(service):
        # Une regle portant une methode ne vaut que pour celle-la : la regle de
        # prevol CORS d'interface-service couvre "/**" en OPTIONS seulement.
        if m is not None and m != methode:
            continue
        if motif.endswith("/**"):
            if path.startswith(motif[:-3]):
                return True
        elif path == motif:
            return True
    return False


# En-tete du secret partage, defini une seule fois dans le code par
# `AutorisationInterne.EN_TETE_SECRET` (platform-commons).
EN_TETE_INTERNE = "X-Internal-Secret"


def est_interne(path):
    """Le chemin appartient-il a la surface inter-services.

    Segment ENTIER, et non `startswith` nu : `/internalement` n'est pas
    `/internal`. Meme raisonnement que `CheminCible.estLeSegment`.
    """
    return path == "/internal" or path.startswith("/internal/")


def build_request(service, port, endpoint):
    path = endpoint["path"]
    # Les en-tetes et le choix « public » se decident sur le chemin du
    # CONTROLEUR ; seule l'URL appelee passe par le prefixe du Gateway.
    segments, path_vars = path_to_postman(chemin_gateway(service, path))

    headers = [{"key": "Content-Type", "value": "application/json"}]

    # LA SURFACE INTERNE N'EST ROUTEE PAR AUCUNE PASSERELLE.
    #
    # Sept endpoints /internal/** etaient publies avec `{{baseUrl}}`, qui
    # designe le Gateway. Ils rendaient donc 404 pour quiconque suivait la
    # collection — et, pire, invitaient a « reparer » ce 404 en routant
    # /internal, ce qui detruirait precisement l'isolement qui fait leur
    # securite.
    #
    # Ils pointent desormais le port du service, et portent le secret partage
    # au lieu d'un jeton : ils ne sont pas authentifies par JWT. Deux controles
    # les gardent, et le second est ici — le premier, le pair TCP prive, ne se
    # met pas dans une collection.
    if est_interne(path):
        headers.append({"key": EN_TETE_INTERNE, "value": "{{internalSecret}}"})
        return {
            "method": endpoint["method"],
            "header": headers,
            "url": {
                "raw": "{{directUrl}}" + path_postman_brut(segments),
                "host": ["{{directUrl}}"],
                "path": segments,
            },
            "description": (f"Handler : {endpoint['handler']}\n\n"
                            "Surface INTERNE. Elle n'est routee par aucune "
                            "passerelle : l'appel se fait en direct sur le port "
                            "du service, avec le secret partage. Aucun jeton "
                            "JWT n'y est attendu."),
        }

    if not is_public(path, endpoint["method"], service):
        # complete-profile ne s'authentifie PAS avec le jeton d'acces.
        #
        # Son justificatif est le jeton temporaire d'OAuth2, type oauth2_temp,
        # emis par le callback. Il voyage desormais dans Authorization: Bearer —
        # un seul canal pour tout le service — et n'ouvre que ce chemin : le
        # Gateway et auth-service bornent tous deux son acceptation a cet
        # endpoint.
        jeton = ("{{tempToken}}" if path.endswith("/oauth2/complete-profile")
                 else "{{accessToken}}")
        headers.append({"key": "Authorization", "value": "Bearer " + jeton})
    if endpoint["admin"] and service == "interface-service":
        headers.append({"key": "X-Admin-Secret", "value": "{{adminSecret}}"})
    if path.startswith("/maintenance") and path != "/maintenance/auth":
        headers = [h for h in headers if h["key"] != "Authorization"]
        headers.append({"key": "Authorization", "value": "Bearer {{maintenanceToken}}"})
    if path == "/maintenance/auth":
        headers.append({"key": "X-Admin-Secret", "value": "{{adminSecret}}"})
        headers.append({"key": "X-Maintenance-Identifier", "value": "{{maintenanceSecret}}"})

    request = {
        "method": endpoint["method"],
        "header": headers,
        "url": {
            "raw": "{{baseUrl}}/" + "/".join(segments),
            "host": ["{{baseUrl}}"],
            "path": segments,
        },
        "description": f"Handler : {endpoint['handler']}"
                       + ("\n\n⚠️ Réservé au rôle ADMIN." if endpoint["admin"] else ""),
    }

    # Les accuses de reception AfrikSMS s'authentifient par un secret dans la
    # CHAINE DE REQUETE : c'est le seul moyen possible, AfrikSMS ne signe rien
    # et n'envoie aucun en-tete de notre choix. Sans ce parametre, la reponse est
    # 404 — un refus se presente comme une absence, et dans une collection de
    # test c'est deroutant.
    if path.startswith("/sms/accuses"):
        request["url"]["raw"] += "?cle={{cleAccusesSms}}"
        request["url"]["query"] = [{
            "key": "cle",
            "value": "{{cleAccusesSms}}",
            "description": "SMS_AFRIKSMS_NOTIFY_SECRET. Absent ou faux : 404.",
        }]
        # Ce point d'entree est appele par AfrikSMS, qui n'a aucun jeton.
        request["header"] = [h for h in request["header"]
                             if h["key"] != "Authorization"]

    if path_vars:
        request["url"]["variable"] = [
            {"key": v, "value": f"{{{{{v}}}}}", "description": f"Valeur de {v}"}
            for v in path_vars
        ]

    if endpoint["multipart"]:
        request["header"] = [h for h in headers if h["key"] != "Content-Type"]
        request["body"] = {
            "mode": "formdata",
            "formdata": [
                {"key": "file", "type": "file", "src": []},
                {"key": "documentTypeCode", "value": "CNI", "type": "text"},
            ],
        }
    elif endpoint["hasBody"] and endpoint["method"] not in ("GET", "DELETE", "HEAD"):
        # Un GET ne porte jamais de corps.
        #
        # `hasBody` vient d'une recherche de « @RequestBody » dans une FENETRE
        # de texte autour du mapping : sur un controleur ou un @GetMapping
        # precede un @PostMapping, la fenetre attrape l'annotation du voisin.
        # C'est ainsi que GET /admin/templates se voyait attribuer un corps
        # alors que sa methode n'en prend aucun — un corps sur un GET est au
        # mieux ignore, au pire refuse par le serveur.
        body = BODIES.get((service, endpoint["method"], path), {"TODO": "compléter le corps"})
        request["body"] = {
            "mode": "raw",
            "raw": json.dumps(body, indent=2, ensure_ascii=False),
            "options": {"raw": {"language": "json"}},
        }

    events = [{"listen": "test", "script": {"type": "text/javascript",
                                            "exec": BASE_TEST.split("\n")}}]
    if "login" in path or path.endswith("auth-verify") or path.endswith("register-verify") \
            or path == "/maintenance/auth":
        events[0]["script"]["exec"] = (CAPTURE_TOKEN + "\n\n" + BASE_TEST).split("\n")

    name = f"{endpoint['method']} {path}"
    return {"name": name, "request": request, "event": events, "response": []}


# Calcul du code TOTP, execute avant CHAQUE requete de la collection.
#
# Le second facteur de l'administrateur change toutes les 30 secondes : le
# recopier a la main entre deux requetes est intenable, et c'est la premiere
# chose qui fait abandonner une collection de test. Postman embarque CryptoJS,
# donc le code se calcule sur place a partir de {{totpSecret}}.
#
# Le secret reste dans l'ENVIRONNEMENT, jamais dans la collection : celle-ci est
# suivie par git.
PRE_REQUEST_TOTP = """// Calcule {{totpCode}} a partir de totpSecret, avant chaque requete.
//
// AUCUN `return` au niveau superieur : le bac a sable de Postman n'encadre pas
// toujours le script dans une fonction, et un `return` hors fonction y est une
// erreur de syntaxe. Le script s'interrompait alors AVANT de poser totpCode,
// la connexion partait avec un code vide, et le serveur repondait
// « Identifiants invalides » — un message qui ne desigpas le script.
//
// Tout est donc dans une fonction, appelee a la fin, et le resultat est
// journalise : sans trace dans la console, un script de pre-requete qui echoue
// est indistinguable d'un mauvais mot de passe.
function codeTotp(secretBrut) {
    const secret = (secretBrut || "").replace(/[^A-Za-z2-7]/g, "").toUpperCase();
    if (!secret) { return ""; }

    // Base32 -> octets. CryptoJS ne sait pas lire le base32.
    const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    let bits = "";
    for (let i = 0; i < secret.length; i++) {
        const j = ALPHABET.indexOf(secret.charAt(i));
        if (j >= 0) { bits += j.toString(2).padStart(5, "0"); }
    }
    let hex = "";
    for (let i = 0; i + 8 <= bits.length; i += 8) {
        hex += parseInt(bits.substring(i, i + 8), 2).toString(16).padStart(2, "0");
    }
    if (!hex) { return ""; }

    // Compteur : pas de 30 secondes depuis l'epoque, sur 8 octets.
    const pas = Math.floor(Date.now() / 1000 / 30);
    const compteur = pas.toString(16).padStart(16, "0");

    const hmac = CryptoJS.HmacSHA1(
        CryptoJS.enc.Hex.parse(compteur),
        CryptoJS.enc.Hex.parse(hex)).toString();

    // Troncature dynamique, RFC 4226.
    const decalage = parseInt(hmac.substring(hmac.length - 1), 16) * 2;
    const tronque = parseInt(hmac.substring(decalage, decalage + 8), 16) & 0x7fffffff;
    return String(tronque % 1000000).padStart(6, "0");
}

// Le secret peut etre dans l'environnement OU dans la collection : chercher
// dans les deux evite le piege du mauvais environnement selectionne.
const brut = pm.environment.get("totpSecret") || pm.collectionVariables.get("totpSecret") || "";
const code = codeTotp(brut);
pm.collectionVariables.set("totpCode", code);

if (!brut) {
    console.log("totpSecret VIDE : totpCode laisse vide. Normal si le second "
        + "facteur n'est pas encore enrole ; sinon, renseignez-le dans "
        + "l'environnement.");
} else if (!code) {
    console.log("totpSecret present mais illisible (base32 attendu) : " + brut.length
        + " caracteres.");
} else {
    console.log("totpCode = " + code);
}"""


# Dossier de mise en route, place EN TETE.
#
# Les 71 requetes sont rangees par controleur, ce qui est juste pour s'y
# retrouver mais inutilisable pour demarrer : sans jeton, tout repond 401. Ce
# dossier donne l'ordre a suivre, et les deux requetes qui le produisent.
def dossier_mise_en_route(service):
    if service != "auth-service":
        return None
    enroll = {
        "name": "1. Enroler le second facteur (une seule fois)",
        "request": {
            "method": "POST",
            "header": [{"key": "Content-Type", "value": "application/json"}],
            "url": {"raw": "{{baseUrl}}/auth-sdk/admin/totp/enroll",
                    "host": ["{{baseUrl}}"], "path": ["auth-sdk", "admin", "totp", "enroll"]},
            "body": {"mode": "raw", "options": {"raw": {"language": "json"}},
                     "raw": '{\n  "identifier": "{{adminIdentifier}}",\n'
                            '  "password": "{{adminPassword}}"\n}'},
            "description": (
                "A executer UNE SEULE FOIS, sur un compte dont le second facteur "
                "n'est pas encore enrole.\n\n"
                "La reponse contient une URL `otpauth://` : scannez-la, puis "
                "recopiez le secret dans la variable d'environnement `totpSecret`. "
                "**Il n'est remis qu'une fois.**\n\n"
                "Tant que `totpSecret` est vide, la connexion fonctionne sans code "
                "— c'est ce qui permet l'enrolement."),
        },
        "event": [{"listen": "test", "script": {"type": "text/javascript", "exec": (
            'let j = {};\n'
            'try { j = pm.response.json(); } catch (e) {}\n'
            'if (j.secret) {\n'
            '    pm.environment.set("totpSecret", j.secret);\n'
            '    console.log("totpSecret enregistre dans l\'environnement");\n'
            '} else if (j.qrCodeUrl) {\n'
            '    const m = /secret=([A-Z2-7=]+)/i.exec(j.qrCodeUrl);\n'
            '    if (m) { pm.environment.set("totpSecret", m[1]); '
            'console.log("totpSecret extrait de l\'URL otpauth"); }\n'
            '}\n\n' + BASE_TEST).split("\n")}}],
        "response": [],
    }
    login = {
        "name": "2. Connexion administrateur  (capture le jeton)",
        "request": {
            "method": "POST",
            "header": [{"key": "Content-Type", "value": "application/json"}],
            "url": {"raw": "{{baseUrl}}/auth-sdk/admin/login",
                    "host": ["{{baseUrl}}"], "path": ["auth-sdk", "admin", "login"]},
            "body": {"mode": "raw", "options": {"raw": {"language": "json"}},
                     "raw": '{\n  "identifier": "{{adminIdentifier}}",\n'
                            '  "password": "{{adminPassword}}",\n'
                            '  "totpCode": "{{totpCode}}"\n}'},
            "description": (
                "`{{totpCode}}` est calcule automatiquement avant l'envoi, a partir "
                "de `totpSecret`. Laissez-le tel quel.\n\n"
                "Le jeton est capture dans `{{accessToken}}` et reinjecte dans "
                "toutes les autres requetes."),
        },
        "event": [{"listen": "test", "script": {"type": "text/javascript",
                                                "exec": (CAPTURE_TOKEN + "\n\n" + BASE_TEST).split("\n")}}],
        "response": [],
    }
    return {
        "name": "0 - MISE EN ROUTE  (commencer ici)",
        "description": (
            "**Ordre a suivre.**\n\n"
            "1. Selectionnez l'environnement `Restart - local`.\n"
            "2. Renseignez `adminPassword` (valeur de `SUPER_ADMIN_PASSWORD` "
            "dans `.env`) — c'est la SEULE valeur a saisir.\n"
            "3. Si le second facteur n'est pas encore enrole : lancez la "
            "requete 1, elle remplit `totpSecret` toute seule.\n"
            "4. Lancez la requete 2 : le jeton est capture.\n"
            "5. Toutes les autres requetes fonctionnent.\n\n"
            "Le mot de passe n'est PAS dans la collection : celle-ci est suivie "
            "par git. Il vit dans l'environnement, qui ne l'est pas."),
        "item": [enroll, login],
    }


def build_collection(service, info):
    folders = []
    demarrage = dossier_mise_en_route(service)
    if demarrage:
        folders.append(demarrage)
    for controller in info["controllers"]:
        items = [build_request(service, info["port"], e) for e in controller["endpoints"]]
        folders.append({
            "name": controller["controller"].replace("Controller", "").replace("Reactive", ""),
            "description": f"Contrôleur `{controller['controller']}` — base `{controller['base'] or '/'}`",
            "item": items,
        })

    total = sum(len(f["item"]) for f in folders)
    return {
        "info": {
            "name": f"Restart — {service}",
            "description": (
                f"Collection générée depuis le code source ({total} endpoints).\n\n"
                f"**Appels via le Gateway** : `baseUrl` = `http://localhost:8081/api/v1`.\n\n"
                f"L'appel en direct sur `http://localhost:{info['port']}` est refusé : "
                f"les services exigent la signature HMAC du Gateway, qu'un client "
                f"ne peut pas produire.\n\n"
                f"**Une seule exception, et elle est voulue** : la surface "
                f"`/internal/**` n'est routée par AUCUNE passerelle — c'est "
                f"précisément ce qui la rend injoignable depuis l'extérieur. Ses "
                f"requêtes utilisent donc `directUrl` "
                f"(`http://localhost:{info['port']}`) et l'en-tête "
                f"`X-Internal-Secret`, jamais un jeton.\n\n"
                + (
                    "**Préfixes propres à auth-service.** interface-service occupe "
                    "`/api/v1/auth/**` et `/api/v1/admin/**` ; auth-service est donc "
                    "joignable sous `/api/v1/auth-sdk/**` (ses endpoints `/auth/**`), "
                    "`/api/v1/auth-admin/**` (ses endpoints `/admin/**`) et "
                    "`/api/v1/auth-users/**`. Le Gateway réécrit le chemin et signe "
                    "le chemin réécrit.\n\n"
                    if service == "auth-service" else ""
                ) +
                f"Le jeton est capturé automatiquement après une connexion réussie "
                f"et réinjecté dans `{{{{accessToken}}}}`."
            ),
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "event": [{"listen": "prerequest",
                   "script": {"type": "text/javascript",
                              "exec": PRE_REQUEST_TOTP.split("\n")}}],
        "item": folders,
        "variable": [
            {"key": "baseUrl", "value": "http://localhost:8081/api/v1"},
            {"key": "accessToken", "value": ""},
            {"key": "refreshToken", "value": ""},
            {"key": "maintenanceToken", "value": ""},
            {"key": "email", "value": "dev@example.com"},
            {"key": "password", "value": "SecurePass123!"},
            # Numero de DOCUMENTATION, jamais un numero reel : la valeur
            # precedente etait un vrai numero, recopie dans les 6 collections et
            # dans le fichier d'environnement, donc committe. Un numero de
            # telephone est une donnee personnelle.
            #
            # +223 0000 0000 satisfait le motif E.164 du service
            # (^\+?[1-9]\d{1,14}$) sans designer personne.
            {"key": "phoneNumber", "value": "+22300000000"},
            {"key": "userId", "value": ""},
            {"key": "adminPhone", "value": ""},
            # VIDES, et elles doivent le rester : cette collection est suivie
            # par git. Les vraies valeurs vivent dans le fichier
            # d'environnement, qui est ignore.
            {"key": "adminIdentifier", "value": ""},
            {"key": "adminPassword", "value": ""},
            # Calcule avant chaque requete par PRE_REQUEST_TOTP.
            {"key": "totpCode", "value": ""},
            # Jeton temporaire OAuth2, rendu par le callback quand le profil
            # est incomplet. Seul justificatif accepte sur complete-profile.
            {"key": "tempToken", "value": ""},
            {"key": "adminSecret", "value": ""},
            {"key": "maintenanceSecret", "value": ""},
            # Appel DIRECT au service, pour la seule surface /internal/**.
            # Le Gateway ne la route pas, et ne doit pas la router.
            {"key": "directUrl", "value": f"http://localhost:{info['port']}"},
            # Vide, comme les autres secrets : cette collection est suivie par
            # git. La vraie valeur vit dans le fichier d'environnement.
            {"key": "internalSecret", "value": ""},
        ],
    }


def main():
    os.makedirs(OUT, exist_ok=True)
    summary = []
    for service, info in DATA.items():
        collection = build_collection(service, info)
        folder = os.path.join(OUT, service)
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, f"{service}.postman_collection.json")
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(collection, handle, indent=2, ensure_ascii=False)
        n = sum(len(f["item"]) for f in collection["item"])
        summary.append((service, n, len(collection["item"])))
        print(f"  ✓ {service:20} {n:3} requêtes, {len(collection['item'])} dossiers")
    print(f"\nTotal : {sum(s[1] for s in summary)} requêtes")


if __name__ == "__main__":
    main()
