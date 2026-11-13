# market-service

La place de marché : des boutiques professionnelles, un catalogue, une
modération, des avis.

Port 8092. Dans le réacteur Maven depuis sa création.

---

## 1. Ce que ce service détient, et lui seul

**La règle de visibilité.** C'est sa raison d'être : qui voit quel article,
selon son pays et sa qualité de professionnel.

Tout le reste appartient à d'autres :

| Ce qu'il lui faut | Qui le détient |
|---|---|
| L'identité et les rôles | auth-service, par le jeton |
| Le **pays** d'une personne | user-service, `/internal/users/auth/{id}` |
| Les photos et pièces justificatives | media-service |

Il ne gère ni panier, ni commande, ni paiement : ce sont d'autres bornes.

---

## 2. La règle de visibilité

Quatre conditions décident qu'un article est visible :

1. la boutique qui le vend est **approuvée** ;
2. l'article est **approuvé** par la modération et **actif** ;
3. un **professionnel** voit tous les pays et les deux visibilités ;
4. un **particulier** ne voit que **son** pays, et seulement ce qui est public.

### Mesuré sur la plateforme

| Profil | Voit |
|---|---|
| propriétaire de la boutique | ses articles, quel que soit leur état |
| particulier ML | les articles publics **de ML** |
| particulier SN | rien, des articles de ML |
| professionnel ML | **tous** les pays, **les deux** visibilités |
| administrateur | tout |

### Un seul détenteur

`PorteeDuCatalogue` porte **une** clause SQL et **une** décision unitaire, qui
doivent dire la même chose. Recopier ces conditions dans chaque requête serait
la façon la plus sûre d'en oublier une, un jour, dans une seule — et il suffit
d'une pour montrer à quelqu'un ce qu'il n'a pas le droit de voir.

`RegleDeVisibiliteUniqueTest` refuse qu'une requête ramène des produits sans
citer la clause. Deux exceptions sont nommées et **pincées en nombre** : la
vitrine du vendeur et la file de modération, toutes deux autorisées par leur
appelant.

### Le pays ne vient jamais de la requête

Il est lu chez user-service et recopié sur la boutique à sa création. Un
paramètre laisserait un particulier élargir sa portée en se déclarant
d'ailleurs — c'est précisément la frontière que ce service défend.

Si user-service est injoignable, le pays est inconnu et le particulier voit
**moins**, jamais plus.

### Un refus rend 404

Jamais 403. Un article réservé aux professionnels ne doit pas être
distinguable d'un article inexistant : un 403 confirmerait la référence, et une
référence confirmée se revend.

---

## 3. Être professionnel, c'est avoir une boutique approuvée

Pas porter un rôle. `MERCHANT` existe dans auth-service, et il ne sait pas
exprimer ce qui décide ici — approuvée quand, par qui, sur quel RCCM — ni être
retiré sans toucher au jeton de la personne.

Ce service tranche donc lui-même et **n'écrit jamais dans auth-service**.

### Le cycle

```
  dépôt du dossier  ──▶  EN_ATTENTE  ──▶  APPROUVEE  ──▶  SUSPENDUE
                              │                              │
                              └──▶  REJETEE                  └──▶  APPROUVEE
```

- Le dossier porte RCCM, NIF, pays, et des **pièces** déposées chez
  media-service — seuls leurs identifiants arrivent ici.
- Changer le RCCM ou le NIF d'une boutique approuvée la **renvoie en
  modération** : ce sont les pièces sur lesquelles la validation a porté. Sans
  cette règle, il suffirait de faire valider un dossier anodin puis de le
  remplacer.
- **Suspendre n'est pas rejeter.** Une boutique suspendue a déjà été approuvée :
  ses articles sortent du catalogue à l'instant même, par la jointure de la
  clause de visibilité, sans qu'aucun article ne soit touché. Lever la
  suspension les rend donc tels qu'ils étaient — y compris ceux que le vendeur
  avait lui-même désactivés.

---

## 4. Rien n'est publié sans modération

Trois files, un seul mécanisme : les boutiques, les articles, les avis.

**Toute écriture renvoie en attente.** Créer, modifier, changer une photo ou un
prix. Sans cela, il suffirait de faire approuver un article anodin puis de le
remplacer, et la modération ne servirait à rien.

Une exception, et c'est la seule : **retirer un article de la vitrine** ne le
renvoie pas en modération — son contenu n'a pas changé. C'est pourquoi cette
opération a son propre endpoint.

**Le motif est obligatoire au refus**, et il est **rendu** au vendeur. Un refus
dont on ignore la cause se représente à l'identique, et fait deux fois le
travail de l'équipe.

---

## 5. Les photos : signées, jamais transportées

market-service ne détient aucun fichier et ne doit pas en détenir — un second
magasin, c'est un second endroit où un document d'identité peut fuir.

```
navigateur ──▶ market-service   /market/produits/{id}
                (décide de la portée)
           ◀── { photos: [ "https://minio/...?X-Amz-Expires=900" ] }

navigateur ──▶ MinIO   (direct, aucun Java sur le chemin des octets)
```

media-service signe une URL MinIO valable quinze minutes. Le seau reste
**privé** : rien n'est rendu public, l'URL porte une signature et une échéance.

**La confiance est bornée par le type, pas par l'appelant.** `/internal/media/liens`
ne demande pas qui regarde — market-service a déjà décidé. Mais seuls les types
marqués `signableSansProprietaire` sont signés : une demande portant
l'identifiant d'une pièce d'identité ne rend rien, même venue d'ici.

**Une photo non signable est omise**, jamais rendue brute. Mieux vaut une photo
manquante qu'un lien mort. La métrique `market.photos.non_signees.total` et son
alerte rendent cette dégradation visible — sans elles, un catalogue entier
pourrait perdre ses images sans qu'aucune erreur HTTP n'apparaisse.

---

## 6. La surface HTTP

### Catalogue — `/api/v1/market/produits`

| Méthode | Chemin | Accès |
|---|---|---|
| `GET` | `/` | authentifié |
| `GET` | `/{id}` | authentifié |
| `GET` · `POST` | `/{id}/avis` | authentifié |
| `GET` | `/{id}/avis/moi` | authentifié |

**Le catalogue n'est pas public**, et ce n'est pas un oubli. Ce qu'une personne
voit dépend de son pays et de sa qualité : il faut savoir qui elle est pour lui
répondre. Un accès anonyme devrait montrer soit tout, soit rien.

### Espace boutique — `/api/v1/market/boutiques`

`POST /`, `GET` · `PUT /moi`, `POST /moi/pieces`, `GET /{id}` (vitrine), et
`/moi/produits/**` pour les articles, photos, variantes, caractéristiques,
activation et suppression.

**`/moi` et jamais `/{boutiqueId}`.** Accepter un identifiant de boutique en
chemin obligerait à vérifier la propriété à chaque méthode — et c'est l'oubli
d'une seule de ces vérifications qui produit un accès direct à l'objet
d'autrui.

### Modération — `/api/v1/admin/market`

Boutiques, produits, avis, catégories. `ROLE_ADMIN` exigé **deux fois** : par la
chaîne de sécurité et par `@PreAuthorize`. Ce dépôt a déjà trouvé trente-trois
`@PreAuthorize` rendues inertes par l'absence de l'annotation qui les active.

La liste à jour est engendrée dans [API-FRONTEND.md](API-FRONTEND.md).

---

## 7. Performance

**Le N+1 est la question centrale du catalogue.** Une page affiche vingt
articles, chacun avec ses photos. Écrit naïvement : vingt lectures et vingt
appels sortants.

- Les photos de **toute la page** sont lues en une requête, et leurs liens
  demandés en **un** appel à media-service.
- Les variantes et caractéristiques ne sont **pas** chargées pour une liste :
  un écran de catalogue n'en montre aucune.
- `MarketMapper` est fait de **fonctions pures** — `NoBlockingCallsTest` le
  vérifie, ce dépôt ayant déjà payé un mapper qui bloquait sur chaque réponse.
- La **portée** est mise en cache dans Redis : la calculer demande deux appels
  sortants, et il en faut une à chaque requête de catalogue.
- La note moyenne est un **agrégat** tenu à l'écriture. La recalculer à la
  lecture ferait une agrégation par article affiché.

Ce qui n'est **pas** mis en cache : les articles. Un cache de produits
montrerait des prix périmés, ce qui se paie en litiges.

---

## 8. Observabilité

### Métriques

```
market.moderation.file                 jauge, par nature
market.moderation.decisions.total      par nature et par sens
market.catalogue.hors_portee.total     la règle de visibilité au travail
market.catalogue.duree                 rendu d'une page, bout en bout
market.photos.non_signees.total        dégradation silencieuse rendue visible
```

Les compteurs sont **créés à zéro au démarrage** : « zéro refus » et « la
métrique n'existe pas » se ressemblent beaucoup trop, et c'est précisément sur
zéro qu'on veut pouvoir alerter.

Aucune étiquette ne porte ce qui a été demandé. Une métrique est conservée
longtemps et exportée largement ; y inscrire ce que quelqu'un a tenté de voir en
ferait un journal d'activité personnel.

### Alertes

`FileDeModerationFigee` · `FileDeModerationSaturee` ·
`PhotosDeProduitNonSignees` · `AucuneDecisionDeModeration` · `CatalogueRalenti`

La première est la plus importante : une file qui monte sans redescendre
signifie que **plus personne ne modère**, et rien d'autre ne le signale. Le
service répond normalement, les vendeurs déposent, et leurs articles ne sortent
jamais.

Le seuil porte sur la **durée** et non sur la taille — une file de trente
articles un lundi matin est saine, la même file inchangée depuis six heures ne
l'est pas.

### Un chronomètre ne publie pas de centiles tout seul

`market.catalogue.duree` est déclaré avec `publishPercentileHistogram()`, et
c'est **nécessaire**, pas décoratif. Un `Timer` Micrometer ordinaire publie
`_count`, `_sum` et `_max` — aucun `_bucket`. Or le panneau « Rendu d'une page
de catalogue (centiles) » et l'alerte `CatalogueRalenti` interrogent tous deux
`market_catalogue_duree_seconds_bucket`.

Sans l'histogramme, le panneau affiche « No data » — ce qui finit par se
remarquer. L'alerte, elle, **ne se déclenche jamais** : une expression PromQL
qui ne rend aucune série ne passe pas à `firing`, et son vert permanent se lit
comme une absence d'incident.

Le chronomètre n'est enregistré **qu'une fois**, dans le constructeur. Micrometer
rend le compteur déjà enregistré pour un identifiant donné et ignore la
configuration du second appel : deux `Timer.builder` dans la même classe
faisaient gagner le premier, et poser `publishPercentileHistogram()` sur le
second n'avait aucun effet — sans la moindre erreur pour le dire.

`MetriquesDesAlertesTest` refuse désormais toute métrique interrogée en
`_bucket` qui n'en déclare pas. Il lit les sources **dépouillées de leurs
commentaires** : sans cela, la javadoc qui explique pourquoi un histogramme est
là suffisait à faire croire qu'il y était.

### Journaux

`logback-spring.xml` pose `traceId` et `spanId` sur chaque ligne — sans eux, un
journal ne se recoupe pas avec une trace, et il faut relire la requête à la main
pour savoir de laquelle on parle.

L'appender Loki n'est actif que sous les profils `prod`, `staging` et
`observability` : en développement, il n'y a pas de collecteur à joindre et
chaque ligne coûterait une tentative de connexion.

### Résilience

Deux disjoncteurs, un par dépendance : `user-service-profil` et
`media-service-document`. **Jamais un partagé** — deux dépendances sous le même
nom tombent ensemble, donc une panne de user-service fermerait le chemin de
media-service.

Ils sont appliqués par **opérateur Reactor**, dans `ClientProfilUtilisateur` et
`ClientMedia`, et non par annotation : ce dépôt n'embarque pas `aspectjweaver`,
donc `@CircuitBreaker` n'y tisse rien. `DisjoncteurSortantTest` prouve qu'un
disjoncteur ouvert **n'exécute plus l'appel**, et pas seulement qu'il change
d'état.

Ni `retry` ni `ratelimiter` ne sont déclarés, et c'est délibéré : rien ne les
appliquerait. Une instance déclarée sans appliquant est une affirmation fausse
dans la configuration. Le réessai n'est d'ailleurs pas souhaitable partout —
une lecture de profil peut être rejouée, une décision de modération déjà écrite
ne le doit pas.

---

## 9. Les notifications de décision

Une décision sur une boutique ou sur un article part en courriel au vendeur.
Sans cela il devait revenir voir ; pour un refus, il attendait une réponse déjà
arrivée, puis redéposait le même dossier.

- Les **textes vivent dans notification-service** (`market.boutique-decision`,
  `market.produit-decision`), comme tous les messages de la plateforme.
  market-service ne fournit que des variables.
- Le **sens est déjà traduit** — « approuvée », « refusée » — parce qu'écrire
  `APPROUVEE` dans un courriel ferait lire une valeur d'énumération à quelqu'un
  qui vend du tissu.
- **Une notification ne fait jamais échouer une décision.** Elle est déjà écrite
  en base ; propager son erreur annulerait la réponse d'une opération faite, et
  le modérateur la rendrait deux fois.
- **L'adresse vient de user-service**, jamais d'un champ saisi : prévenir à une
  adresse déclarée par l'intéressé permettrait d'envoyer un courriel au nom de
  la plateforme vers n'importe qui.

Les **avis** ne déclenchent aucune notification, et c'est une décision à
prendre plutôt qu'un oubli : prévenir l'auteur d'un avis refusé ouvre une
discussion avec quelqu'un qui n'est pas client de la plateforme mais d'une
boutique.

---

## 10. Le pays d'une boutique qui déménage

Le pays est recopié depuis user-service à la création. S'il changeait sans être
relu, un vendeur ayant déménagé garderait indéfiniment le pays de son
inscription, et ses articles resteraient visibles du mauvais public.

Il est donc relu **à chaque modification de la boutique**, et propagé à ses
articles. Trois points :

- **Pas à la résolution de portée.** Celle-ci se calcule sur le chemin de
  chaque requête de catalogue ; y détecter une divergence ajouterait une
  comparaison, et le jour où elle diverge, deux écritures — sur l'écran le plus
  consulté.
- **Cela ne renvoie pas en modération.** Contrairement au RCCM, le pays n'est
  pas une pièce à vérifier : il vient de user-service, qui fait foi.
- **Un profil injoignable ne touche à rien.** Écraser le pays par `null`
  rendrait les articles invisibles de tous : une panne de user-service viderait
  les vitrines.

**Limite assumée :** un vendeur qui déménage sans jamais rouvrir son dossier
garde l'ancien pays. Un relevé périodique le rattraperait ; ce n'est pas encore
justifié.

---

## 11. La recherche

`ILIKE` sur le nom et la description. Deux défauts y ont été corrigés le
2026-11-13 :

- **Les jokers n'étaient pas échappés.** Le motif était `"%" + terme + "%"` :
  un terme valant `%` donnait `%%%`, trois jokers, qui correspondent à tout.
  Mesuré sur PostgreSQL, sur quatre articles dont « Solde -50% » : non échappé
  **4 résultats sur 4**, échappé **1**. Ce n'était pas une faille — la clause de
  visibilité s'applique et la page est plafonnée — mais un article nommé
  « Solde -50% » ne se trouvait pas par son nom.
- **Le total ignorait le terme.** `compterLeCatalogue` était appelé même quand
  une recherche était active : une recherche ramenant deux articles annonçait le
  total du catalogue entier, et l'écran affichait des pages vides.

Le volume ne justifie **pas encore** un index textuel : un index GIN sur deux
colonnes se paie à chaque écriture. Le jour où `ILIKE` ne suffira plus, c'est
ici qu'il faudra regarder — pas dans la clause de visibilité, qui ne change pas.

---

## 12. Ce qui reste ouvert

- **Les avis ne sont pas notifiés** — voir §9, c'est une décision à prendre.
- **Pas d'index textuel** — voir §11, c'est un seuil de volume à atteindre.
- **Le pays d'un vendeur qui ne rouvre jamais son dossier** — voir §10.
