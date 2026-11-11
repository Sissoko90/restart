# notification-service

Le seul service de la plateforme qui sache parler SMTP, SMS ou push. Les
autres lui décrivent **ce qu'il faut dire** ; lui décide **comment le dire**.

Port 8086. Entré dans le réacteur Maven — il en était absent, et aucun build ne
le compilait.

---

## 1. Pourquoi un seul expéditeur

Trois modules composaient et envoyaient leurs courriels eux-mêmes, avec leurs
propres comptes SMTP. Cela coûtait trois fois :

- **Deux moteurs de modèles.** « Votre code de vérification est… » existait
  dans auth-service et ici, avec deux textes qui n'étaient déjà plus
  identiques : l'un disait « Ne le communiquez à personne », l'autre non.
- **Une autoconfiguration silencieusement inerte.**
  `MailSenderAutoConfiguration` porte `@ConditionalOnMissingBean(MailSender)` :
  dès qu'un module déclare son propre `JavaMailSender`, Spring recule et
  `spring.mail.*` ne sert plus à rien — **sans aucun signal**.
- **Une non-remise perdue.** Quatre des six appels d'alerte étaient des
  `.subscribe()` sans gestionnaire : un courriel qui ne partait pas ne laissait
  aucune trace.

`UnSeulExpediteurDeCourrielTest` (platform-commons) refuse qu'un second module
redéclare le starter mail ou manipule un `JavaMailSender`.

---

## 2. Les cinq canaux

| Canal | Nature | Remarque |
|---|---|---|
| `COURRIEL` | SMTP | Deux comptes : ordinaire et critique |
| `SMS` | Opérateur tiers | Coûte de l'argent — garde-fous dédiés, §6 |
| `IN_APP` | **Persistant** | Écrit en base, lu par la boîte de réception |
| `PUSH` | FCM | Suppose un appareil enregistré |
| `WEBSOCKET` | **Immédiat et volatil** | Si personne n'écoute, rien n'est gardé |

`IN_APP` et `WEBSOCKET` se confondent facilement, et c'est l'erreur classique :
demander `WEBSOCKET` seul pour une information qui doit survivre à la fermeture
de l'onglet la fait disparaître.

---

## 3. Le moteur de modèles

### Deux couches, pas trois

1. **Le socle** — `modeles-notification.properties` et `modeles-html/*.html`,
   chargés une fois au démarrage depuis le classpath. Versionnés, donc relus en
   revue de code.
2. **Les surcharges** — la table `surcharge_de_modele`, rechargée
   périodiquement, qui prend le pas sur le socle.

Les fichiers de `modeles-html/` ne sont **pas** une troisième couche : ils
*peuplent* le socle. `modeles-html/x.html` devient la clef `x.corps-html`, et
tout ce qui suit — résolution par canal, repli, surcharge, API
d'administration, substitution — fonctionne sans rien savoir de leur origine.

Une table vide se comporte donc exactement comme si elle n'existait pas : aucune
migration n'a à recopier correctement les textes sous peine de notifications
muettes, et une panne de base ne coupe pas les codes de vérification.

### Résolution : la spécificité avant la couche

Pour chaque clef candidate — celle du canal d'abord, puis celle du repli — la
surcharge est consultée avant le socle. **Et non l'inverse.** Corriger le texte
générique ne doit pas écraser la variante SMS courte : sinon le SMS partirait
avec un corps écrit pour le courriel, tronqué par l'opérateur ou facturé en
plusieurs messages.

### Le SMS ne replie pas

Sans variante `.sms`, le modèle est déclaré **introuvable** et rien ne part.
C'est un garde-fou de dépense, pas une limitation.

### Les variables d'un corps HTML sont échappées

L'échappement est porté par le **rendu**, jamais par l'émetteur : demander à
chaque appelant d'y penser est la façon la plus sûre de l'oublier une fois.

La variante texte, elle, n'est pas échappée — y écrire `&amp;` ferait lire
« &amp; » à la personne.

Une substitution se fait **en un seul passage** : une valeur contenant
`{autreClef}` reste littérale. Sans cela, un émetteur pourrait faire lire une
variable qu'il n'a pas fournie. Cela compte doublement depuis que les textes
sont modifiables à chaud.

Une clef absente laisse **le marqueur en place** plutôt que d'écrire « null » :
un texte où l'on voit `{pays}` se diagnostique, un texte où l'on lit « null » se
devine.

---

## 4. Le contrat avec les émetteurs

> L'émetteur nomme un **modèle** et fournit des **variables**.
> Il ne compose jamais un corps.

Une signature qui accepte un corps déjà composé ne peut garantir ni son rendu
ni son échappement : elle ignore si on lui donne du texte ou du balisage, et
quelles variantes le modèle possède.

Ce n'est pas théorique. Trois alertes composaient leur HTML en concaténation
Java et le passaient à un modèle de transit **sans variante `corps-html`** : le
courriel partait en `text/plain` avec son balisage dedans, et le destinataire
lisait la source. Le `User-Agent` de l'attaquant y entrait de surcroît sans
échappement, dans l'alerte de sécurité qui le dénonçait.

`AucunCorpsHtmlEnJavaTest` (platform-commons) refuse le retour du balisage dans
du code Java, commentaires dépouillés.

Le contrat de variables est tenu en **deux moitiés indépendantes** :
`AlertesDExploitationTest` dérive les marqueurs des gabarits,
`VariablesDesAlertesTest` dérive les clefs produites par l'émetteur. Une seule
liste partagée entre les deux ne verrait ni l'ajout d'un marqueur ni l'oubli
d'une clef.

---

## 5. La surface HTTP

### Appelable par le frontend

| Méthode | Chemin | Accès |
|---|---|---|
| `GET` | `/api/v1/notifications` | authentifié |
| `GET` | `/api/v1/notifications/compteur` | authentifié |
| `POST` | `/api/v1/notifications/{id}/lue` | authentifié |
| `POST` · `GET` · `DELETE` | `/api/v1/notifications/appareils` | authentifié |
| `DELETE` | `/api/v1/notifications/appareils/tous` | authentifié |
| `POST` | `/api/v1/admin/notifications` | admin |
| `POST` | `/api/v1/admin/notifications/groupe` | admin |
| `GET` · `PUT` · `DELETE` | `/api/v1/admin/modeles` | admin |
| `GET` | `/api/v1/admin/sms/solde` | admin |
| `POST` | `/api/v1/admin/sms/url-de-rappel` | admin |
| `GET` · `POST` | `/api/v1/sms/accuses/afriksms` | public (accusés de l'opérateur) |

La liste à jour est engendrée dans
[API-FRONTEND.md](API-FRONTEND.md) — ne pas la recopier à la main.

### `/internal/message` — et pourquoi elle n'est pas routée

`POST /internal/message` est la porte d'entrée des autres services. Elle n'est
routée par **aucune** passerelle, et c'est précisément ce qui la rend
injoignable depuis l'extérieur. Deux contrôles la gardent : le pair TCP privé,
et l'en-tête `X-Internal-Secret` comparé à temps constant.

Un appel s'y fait donc **en direct**, sur le port du service. Les collections
Postman le reflètent (`directUrl`) ; elles pointaient auparavant la passerelle,
donc sept requêtes rendaient 404 — et invitaient à « réparer » ce 404 en
routant `/internal`, ce qui aurait détruit l'isolement.

La réponse est `{remis, motif}`, et elle **doit être lue** : un plafond
journalier atteint n'est pas un succès.

---

## 6. Les garde-fous de dépense SMS

Un SMS se paie. Trois règles, toutes du côté de ce service, seul à tenir le
registre des envois :

- **Aucun réessai.** Réessayer un envoi dont on ne sait pas s'il est parti le
  fait payer deux fois et le destinataire reçoit deux messages.
- **Plafond journalier** tenu dans Redis.
- **Liste blanche** de numéros.

Un client appelant ne doit donc pas réessayer non plus : il multiplierait le
plafond au lieu de le respecter.

---

## 7. Observabilité

### Métriques

```
notification.demandes.total                      notification.remises.total
notification.rejets.total                        notification.traitement.duree
notification.canal.actif                         notification.critiques.perdues.total
notification.courriel.repli.total                notification.websocket.sessions
notification.modeles.surcharges                  notification.modeles.derniere.lecture.secondes
```

Deux méritent un mot.

`notification.courriel.repli.total` compte les courriels **critiques** repartis
par le compte ordinaire parce que le compte dédié a échoué. Ce repli *sauve* le
message, donc il *cache* la panne : sans ce compteur, des identifiants SMTP
invalides resteraient invisibles indéfiniment. L'alerte se déclenche au premier
repli — des identifiants ne se réparent pas seuls.

`notification.modeles.derniere.lecture.secondes` existe parce que le
rechargement des surcharges **dégrade en silence** : si la base est
injoignable, l'instantané précédent reste en vigueur et le nombre de surcharges
ne bouge pas. Rien ne distinguerait « rien n'a changé » de « plus rien ne se
lit », et une correction poussée par `PUT /admin/modeles` n'arriverait jamais.

### Santé

| Indicateur | DOWN quand |
|---|---|
| `canauxDeRemise` | **aucun** canal n'est actif |
| `surchargesDeModeles` | les surcharges ne se lisent plus |

`canauxDeRemise` ne tombe pas parce qu'un canal manque : un service qui se
déclare malade dès qu'un canal sur cinq est inactif serait retiré du
répartiteur alors qu'il remet encore quatre canaux sur cinq.

### Alertes

```
AucunCanalDeNotificationActif      CanalDeNotificationTombe
NotificationCritiquePerdue         CourrielCritiqueReplieSurLOrdinaire
DemandesDeNotificationRejetees     PlafondDepenseSmsAtteint
DisjoncteurSortantOuvert           SurchargesDeModelesJamaisLues
SurchargesDeModelesFigees
```

`MetriquesDesAlertesTest` vérifie que chaque alerte et chaque panneau visent une
métrique qui **existe** : le dépôt a déjà porté des alertes muettes, posées sur
des noms que personne n'émettait.

### Disjoncteurs

`smtp-ordinaire` et `smtp-critique`, déclarés **au démarrage** : sans cela leur
instance n'existerait qu'après le premier appel, donc invisible de la
supervision jusque-là.

---

## 8. Effacement RGPD

`EcouteurDEffacement` consomme l'événement Kafka d'effacement et
`ServiceDEffacementDesNotifications` traite les notifications du sujet.

Effacer n'est pas supprimer la ligne : un registre de preuve demeure.

---

## 9. Ce qui reste ouvert

- **Les identifiants `CRITICAL_MAIL_*` sont invalides** en environnement de
  développement. Le repli couvre, et la métrique le rend visible — mais
  l'alerte critique emprunte donc la réputation d'expédition du trafic
  ordinaire.
- **Les liens d'action sont des `GET`.** L'alerte de connexion administrateur
  porte « c'est moi » et « ce n'est pas moi » en liens cliquables. Un scanner
  de messagerie qui préfiltre les liens peut donc déclencher le blocage sans
  que personne n'ait cliqué. Le corriger demande une page de confirmation côté
  frontend, qui n'existe pas encore.
- `IMessagerieSortante.envoiSmsActif()` ne connaît qu'une des raisons de ne rien
  envoyer — l'interrupteur global. Il ignore le numéro hors liste, le plafond
  atteint et le fournisseur mal configuré. `envoyer()` rend la vérité, et c'est
  elle qu'il faudrait lire ; les deux appelants formulent leur réponse dans une
  méthode synchrone appelée après l'envoi, ce qui demande sa propre passe.
