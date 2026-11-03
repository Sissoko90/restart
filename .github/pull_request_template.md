## Ce que change cette PR

<!-- En deux ou trois phrases : le problème, puis la solution retenue. -->

## Pourquoi

<!-- Le contexte que la lecture du diff ne donne pas. Si un choix
     surprenant a été fait, c'est ici qu'il s'explique. -->

---

## Vérifications

- [ ] `mvn verify` passe en local
- [ ] Les nouveaux chemins d'**erreur** sont testés, pas seulement le chemin nominal
- [ ] La couverture ne baisse pas (`./scripts/coverage-gate.sh`)

### Si des endpoints ont changé

- [ ] Collections Postman régénérées (`./scripts/postman/generate.sh`)
- [ ] Documentation d'API à jour

### Si la sécurité est touchée

<!-- Filtres, JWT, autorisations, chiffrement, requêtes SQL, en-têtes. -->

- [ ] Un **identifiant venant du client** ne sert jamais seul à décider d'un accès
      — l'appartenance de la ressource est vérifiée (cf. `docs/GATEWAY-SECURITY.md` §2)
- [ ] Un refus répond **404 et non 403** quand un 403 confirmerait l'existence de la ressource
- [ ] Aucun nouveau `WebFilter` annoté `@Component` s'il est ajouté à la chaîne Spring Security
      (il s'exécuterait deux fois — `FilterWiringTest` le vérifie)
- [ ] L'IP client passe par `ClientIpResolver`, jamais par `X-Forwarded-For` directement
- [ ] Les rôles JWT sont lus via `JwtClaimsReader`, jamais par `claims.get("roles", String.class)`
- [ ] Aucun fragment SQL construit par concaténation

### Si des données personnelles sont touchées

- [ ] La finalité et la durée de conservation sont documentées
- [ ] La suppression **efface** réellement, ou l'écart est assumé et documenté
      (cf. `docs/COMPLIANCE.md`)

### Si une dépendance est ajoutée

- [ ] Sa licence est compatible avec une distribution propriétaire
      (ni GPL, ni AGPL, ni SSPL — cf. `scripts/license-check.sh`)
- [ ] La version est gérée dans le **POM parent**, pas dans le module

---

## Points d'attention pour la relecture

<!-- Ce sur quoi vous voulez vraiment un deuxième regard. -->
