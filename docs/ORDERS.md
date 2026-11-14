# order-service

Panier, commandes et retours de la place de marché Sankadi.

> **Sankadi est l'intermédiaire.** L'acheteur n'a pas de relation directe avec
> la boutique : il commande auprès de Sankadi, et c'est Sankadi qui arbitre les
> retours. Une boutique ne peut pas refuser un retour que Sankadi a accepté.

---

## 1. Ce que ce service détient, et lui seul

- le **panier** de chaque acheteur ;
- la **commande** et ses lignes, figées à l'achat ;
- le **retour** et son arbitrage ;
- le **registre des transitions**, qui est ce qui rend l'arbitrage possible ;
- la **machine à états** de la commande.

## 2. Ce qu'il ne fait pas, et pourquoi

| Il ne fait pas | Qui le fait |
|---|---|
| Connaître le catalogue, les prix, le **stock** | market-service |
| Savoir à qui appartient une boutique | market-service |
| Connaître le pays d'une personne | user-service |
| Déplacer de l'argent | payment-service, **à venir** |
| Organiser une livraison | logistics-service, **à venir** |

La commande porte les **montants** et les **dates constatées** — pas les
transactions, pas les tournées. Celles-ci auront leur propre registre dans leur
propre service, et il serait faux de les commencer ici pour les y déplacer
ensuite.

### Aucun endpoint n'est écrit en avance pour les deux services à venir

Les transitions « payée », « expédiée » et « livrée » sont pilotées
**aujourd'hui** par l'administration Sankadi, sous `/admin/orders/commandes`.
Elles ont donc un appelant réel, et la place de marché est exploitable à la
main dès le premier jour.

Le jour où payment-service et logistics-service existeront, ils appelleront les
mêmes méthodes de service. Le rôle inscrit au registre passera de `ADMIN` à
`SYSTEME`, et l'historique gardera la trace de ce qui a été décidé par un
humain et de ce qui a été constaté par une machine.

Ce dépôt a plusieurs fois payé du code écrit pour un appelant qui n'existe pas.

---

## 3. Une commande par boutique

Un panier contenant des articles de trois boutiques produit **trois commandes**.

Ce n'est pas une commodité d'implémentation. C'est la seule découpe qui tienne :
chaque boutique expédie séparément, est payée séparément, et un retour ne
concerne qu'elle. Une commande unique forcerait à redescendre au niveau de la
ligne pour chacune de ces trois questions, et un remboursement partiel
deviendrait impossible à rattacher à un versement.

L'acheteur, lui, voit un seul passage en caisse. C'est l'affaire de la
présentation.

---

## 4. La ligne de commande est un instantané

Le nom, la couleur, la taille, le SKU et le prix sont **copiés** au moment de
l'achat. La boutique peut ensuite renommer son article, changer son prix, le
retirer ou le supprimer : la commande continue de dire ce qui a été acheté et à
quel prix.

Une ligne qui ne porterait que `variante_id` laisserait une facture s'invalider
toute seule, et un litige arbitré par Sankadi n'aurait plus d'objet à examiner.

`produit_id` et `variante_id` sont conservés **en plus**, pour rattacher un
retour au stock — mais ce ne sont pas des clés étrangères : la cible vit dans
une autre base, et une contrainte entre deux bases n'existe pas.

### Le panier, à l'inverse, ne stocke aucun prix

Il est relu chez market-service à chaque affichage. Un prix copié dans le
panier devient faux dès que la boutique le change, et l'acheteur verrait un
montant qui n'est pas celui qu'on lui demandera.

La contrepartie est assumée : un panier peut changer de total entre deux
visites. C'est la vérité, et la cacher serait pire.

---

## 5. Le stock : réservé chez market-service, jamais dupliqué

Le stock vit chez market-service, qui détient le catalogue. Le dupliquer ferait
deux vérités qui divergent au premier incident.

### La base arbitre, pas le Java

```sql
UPDATE produit_variante
   SET stock = stock - :quantite
 WHERE id = :varianteId AND actif = TRUE AND stock >= :quantite
```

La condition est **dans** l'instruction. Lire le stock, vérifier en Java, puis
écrire laisserait une fenêtre : deux acheteurs du dernier article y liraient
tous deux « stock 1 » et le prendraient tous deux.

**Mesuré sur la base réelle**, deux processus simultanés sur un article en stock
1 : l'un obtient `UPDATE 1`, l'autre `UPDATE 0`, stock final **0 et jamais −1**.

### Trois décisions de conception

**Réserver rend l'instantané** — nom, prix, couleur, taille, boutique, devise —
dans le même aller-retour. Lire ensuite laisserait le prix changer entre la
réservation et l'enregistrement de la commande.

**La visibilité est vérifiée par market-service.** order-service ne transmet
qu'un **identifiant de compte** : ni le pays, ni la boutique, ni la qualité de
professionnel. market-service les résout lui-même.

> Ce n'est pas une économie de champs, c'est une question de sécurité. Si
> order-service annonçait la portée, un défaut chez lui — ou un appel forgé —
> suffirait à se déclarer professionnel et à acheter un article réservé.

**Les lignes sont verrouillées dans l'ordre de leur identifiant de variante**,
et non dans celui du panier. Deux acheteurs dont les paniers se recouvrent
prendraient sinon A puis B et B puis A, et s'attendraient mutuellement.
PostgreSQL en tue un, et le symptôme est un passage en caisse qui échoue au
hasard sous la charge.

### Il n'y a volontairement aucun endpoint de lecture du stock

Il inviterait à « vérifier d'abord, réserver ensuite » — exactement la fenêtre
que la réservation conditionnelle existe pour supprimer. Le stock affichable
fait partie de la description d'article, que `/internal/market/stock/decrire`
sert sans rien réserver.

---

## 6. La limite connue : deux bases, pas de transaction commune

La réservation vit chez market-service, la commande ici. Si l'écriture de la
commande échoue après une réservation réussie, le stock est rendu par
**compensation**.

**Si cette compensation échoue à son tour, du stock reste retenu sans
commande** : invisible, et introuvable sans inventaire.

Ce trou n'est pas caché :

- il est **compté** par `order.stock.compensation.echouee.total` ;
- l'alerte `CompensationDeStockEchouee` est **critique dès la première
  occurrence**, sans `for` — chaque occurrence est un article qu'une boutique ne
  peut plus vendre ;
- le **détail des lignes** est journalisé en `ERROR`, pour pouvoir rendre le
  stock à la main.

Le fermer complètement demanderait une réservation **idempotente** portant
l'identifiant de la commande, que market-service pourrait rejouer sans double
effet. C'est la bonne suite ; ce n'est pas fait, et le dire vaut mieux que le
laisser croire.

---

## 7. La machine à états

```
A_PAYER  ──►  PAYEE  ──►  PREPAREE  ──►  EXPEDIEE  ──►  LIVREE  ──►  CLOTUREE
   │            │             │              │             │
   ▼            ▼             ▼              │             │
ANNULEE      ANNULEE       ANNULEE           │             │
                                             ▼             ▼
                                        RETOUR_EN_COURS ──►  REMBOURSEE
                                             │
                                             └──►  LIVREE   (retour refuse)
```

Les transitions légales sont déclarées **dans l'énumération**, à côté des
états — pas dispersées en `if` dans les services. Un état ajouté sans sa règle
devient alors visible : il n'a ni prédécesseur ni successeur.

### Trois décisions lisibles dans ce schéma

**Une fois expédiée, plus d'annulation.** Le colis est parti. Le recours est le
retour, pas l'annulation : les deux n'ont pas les mêmes conséquences sur le
stock ni sur l'argent.

**Un retour refusé renvoie à `LIVREE`.** La commande reprend son cours, sa
fenêtre de retour court toujours, et l'acheteur peut redemander avec un meilleur
motif. C'est pourquoi l'index unique ne bloque que les retours **ouverts**.

**`CLOTUREE`, `REMBOURSEE` et `ANNULEE` sont terminaux.** Une commande clôturée
ne rouvre pas : la somme est payable, et un état qui revient en arrière après un
versement produit un versement en double.

### Un seul applicateur, trois gestes inséparables

`MachineDeCommande` vérifie la légalité, écrit, et inscrit au registre — dans la
même transaction. Dispersées, ces trois opérations finissent par se désaccorder :
un chemin oublie le registre, un autre oublie la vérification.

Le verrou est **optimiste**, dans le `WHERE` :

```sql
UPDATE commande SET statut = :vers WHERE id = :id AND statut = :attendu
```

Deux appels concurrents se présentent avec le même état attendu, un seul le
trouve encore. Le second reçoit zéro ligne modifiée et échoue franchement. Sur
une commande, la fenêtre entre une lecture et une écriture suffit à expédier
deux fois ou à rembourser deux fois.

---

## 8. Le registre des transitions

Table `evenement_commande`, en **append seul**.

Sankadi se tient entre un acheteur et une boutique qui n'ont pas de relation
directe. Quand l'un affirme avoir expédié et l'autre n'avoir rien reçu, le seul
recours est l'historique : **qui** a déclaré **quoi**, **quand**, et sous quel
**rôle**.

Le rôle importe autant que l'identité : la même personne peut être acheteuse sur
une commande et arbitre sur une autre, et c'est le rôle exercé qui légitime la
transition.

Ce n'est pas un journal applicatif. Un journal se purge, se perd, et ne répond
pas à une requête SQL.

---

## 9. Les retours : Sankadi arbitre

```
DEMANDE  ──►  ACCEPTE  ──►  RECU  ──►  REMBOURSE
   │
   └──────►  REFUSE
```

### Quatre étapes, et aucune n'est sautable

**`RECU` est un état distinct d'`ACCEPTE`**, et c'est la décision la plus
importante de ce module. Accepter un retour et avoir reçu l'article sont deux
faits différents : les confondre fait rembourser un colis qui n'est jamais
revenu.

C'est l'erreur la plus coûteuse d'une place de marché, parce qu'elle est
**industrialisable** : on demande, on obtient l'accord, on n'envoie rien.

La contrainte de base `retour_remboursement_apres_reception` l'interdit aussi,
en plus du service.

### Le stock est rendu à la réception

Et non à l'acceptation. À l'acceptation, l'article n'est pas encore reparti :
recréditer la boutique la ferait vendre deux fois le même exemplaire.

### La boutique n'a aucune route sur les retours

C'est tout l'intérêt de l'intermédiaire. Elle en est **informée, pas
consultée**. Sa seule transition est « c'est prêt ».

### Un retour ouvert bloque la clôture

Une commande en `RETOUR_EN_COURS` n'est pas clôturable, donc la boutique n'est
pas payée pendant l'arbitrage. Sans ce couplage, un retour serait accepté sur
une commande déjà soldée, et Sankadi devrait reprendre l'argent à la boutique.

### `JAMAIS_RECU` est le seul motif acceptable avant la livraison

Les autres supposent qu'on a vu l'article. Un colis qui n'arrive pas se signale
depuis `EXPEDIEE`, et **sans fenêtre** : il n'y a pas de date de livraison pour
la faire courir, et exiger une livraison avant de signaler une non-livraison
serait absurde.

---

## 10. L'argent

### En unités mineures, dans des entiers

Comme `produit.prix` chez market-service. Jamais de flottant : `0.1 + 0.2` n'est
pas `0.3`, et une place de marché additionne des milliers de lignes.

### Le taux de commission est figé sur chaque commande

Le taux de Sankadi changera. Une commande passée sous l'ancien taux garde **ses**
conditions : recalculer depuis le taux courant réécrirait le passé, et un
versement ne correspondrait plus à ce qui avait été annoncé à la boutique.

### L'arrondi va dans le sens de la boutique

La commission est arrondie **vers le bas**, et la somme due est obtenue par
soustraction. Deux conséquences, toutes deux voulues :

1. la décomposition **boucle toujours** : `articles = commission + dû`. La
   contrainte `commande_decomposition_boucle` l'exige, et calculer les deux
   parts séparément laisserait un centime orphelin que personne ne saurait
   attribuer ;
2. un centime d'arrondi va à la boutique et non à Sankadi. L'intermédiaire ne
   doit jamais gagner sur un arrondi, sinon chaque commande lui donne un
   avantage systématique — et un vendeur qui recompte le remarque.

**16 tests** le vérifient, dont le bouclage sur neuf couples montant/taux. Deux
mutations tuées : passer en `HALF_UP` casse un test, calculer les deux parts
séparément en casse six.

### La commission n'est pas rendue à l'acheteur

`commissionMontant` et `montantDuALaBoutique` sont nuls dans la réponse envoyée
à l'acheteur, renseignés pour la boutique et l'administration. Ce que Sankadi
prend sur une vente est une affaire entre Sankadi et la boutique.

Le masquage est fait **dans le service**. Envoyer le champ et compter sur le
client pour ne pas l'afficher n'est pas une protection.

---

## 11. Les deux échéances

### Le délai de paiement — `order.paiement.delai-minutes`, 30 par défaut

Le stock est réservé dès la création de la commande. **Sans échéance, un seul
acheteur mal intentionné viderait une boutique sans rien débourser**, et le stock
resterait bloqué indéfiniment.

Trente minutes : assez pour finir un paiement mobile, assez peu pour qu'un
article convoité ne reste pas retenu une journée.

### La fenêtre de retour — `order.retour.fenetre-jours`, 7 par défaut

Comptée depuis la **livraison**, et non depuis l'achat : un délai qui courrait
depuis la commande serait déjà entamé, voire épuisé, quand le colis arrive.

La date d'échéance est **stockée** sur la commande au moment de la livraison.
Changer le réglage n'affecte que les commandes livrées **après** : une commande
déjà livrée garde la fenêtre annoncée à son acheteur.

---

## 12. Les deux balayeurs

| Balayeur | Ce qu'il protège |
|---|---|
| Annulation des impayées | sans lui, le stock reste bloqué indéfiniment |
| **Clôture des livrées** | sans lui, **aucune boutique n'est jamais payée** |

Le second est le plus critique du module. `CLOTUREE` est l'état que
payment-service attendra pour verser. S'il s'arrête sans bruit, le service
répond normalement, les acheteurs commandent, les colis arrivent — et l'argent
ne part pas. L'alerte `AucuneClotureDeCommande` existe pour cela.

`fixedDelay` et non `fixedRate` : à taux fixe, un passage plus long que la
période en déclencherait un second en parallèle, et les deux traiteraient les
mêmes commandes.

Les erreurs sont absorbées **par commande**. C'est la différence entre « une
commande en retard » et « plus aucune commande clôturée », et la seconde se
solde par des boutiques impayées.

Un **plafond par passage** (`order.balayage.plafond`, 200) : sans lui, le
premier passage après une panne prolongée chargerait toutes les commandes en
retard d'un coup.

---

## 13. La surface HTTP

### Panier — `/api/v1/orders/panier`

Aucun identifiant d'acheteur dans les chemins : l'acheteur est le porteur du
jeton. Un `/panier/{acheteurId}` laisserait consulter et modifier le panier d'un
autre, et il faudrait vérifier l'appartenance à chaque endpoint — donc l'oublier
une fois.

`PUT .../quantite/{quantite}` **remplace** la quantité, ne l'incrémente pas : un
client qui rejoue sa requête après un délai réseau doublerait la quantité avec
un incrémenteur, et c'est ce qui arrive sur un réseau mobile.

### Commandes — `/api/v1/orders/commandes`

`POST` rend une **liste** : une commande par boutique. Masquer cela derrière un
objet unique obligerait le client à le redécouvrir au premier suivi de
livraison.

Le passage en caisse n'est pas formellement idempotent, mais il est borné : il
convertit le panier, donc un second appel ne trouve plus de panier ouvert. Un
double-clic ne commande pas deux fois.

### Boutique — `/api/v1/orders/boutique`

La boutique de l'appelant n'est **jamais** dans le chemin : elle est résolue
depuis le jeton via market-service. Un `/boutique/{boutiqueId}/commandes`
laisserait un vendeur lire les commandes d'un concurrent — ses prix, ses
volumes, ses clients.

### Administration — sous `/api/v1/admin/orders`

`/admin/orders/commandes` porte le constat d'encaissement, d'expédition, de
livraison et l'annulation. `/admin/orders/retours` porte l'arbitrage.

La règle `hasRole("ADMIN")` est dans la **chaîne de sécurité** et pas seulement
en `@PreAuthorize`. Ce dépôt a déjà trouvé trente-trois `@PreAuthorize` rendues
inertes par l'absence de l'annotation qui les active.

### Un refus rend 404

Jamais 403. Un 403 confirmerait que la ressource existe, et une référence
confirmée s'énumère. C'est la règle du dépôt, appliquée au panier, aux commandes
et aux retours.

---

## 14. Observabilité

### Métriques

```
order.commandes.creees.total              les commandes passees
order.transitions.total                   par etat d'arrivee et par role
order.stock.compensation.echouee.total    LA metrique critique du module
order.caisse.refus_de_stock.total         articles refuses au passage en caisse
order.retours.total                       par sens de decision
order.caisse.duree                        avec histogramme, pour les centiles
order.files                               jauge, par nature
```

Les compteurs sont **créés à zéro au démarrage** : « aucune compensation
échouée » et « la métrique n'existe pas » se ressemblent beaucoup trop, et c'est
précisément sur zéro qu'on veut pouvoir alerter.

Le chronomètre porte `publishPercentileHistogram()`, enregistré **une seule
fois** dans le constructeur. Un `Timer` ordinaire ne publie aucun `_bucket`, et
`histogram_quantile` ne travaille que sur des buckets : sans lui, le panneau
affiche « No data » et l'alerte ne se déclenche jamais. Micrometer rend de plus
le meter déjà enregistré et **ignore** la configuration d'un second appel.

**Aucune étiquette ne porte l'identifiant d'une personne** — ni l'acheteur, ni
la boutique, ni le numéro de commande. Une métrique est conservée longtemps et
exportée largement, et sa cardinalité serait illimitée. Qui a commandé est une
question d'audit, et le registre des transitions existe pour cela.

### Alertes

`FileDArbitrageFigee` · `FileDArbitrageSaturee` · **`AucuneClotureDeCommande`** ·
**`CompensationDeStockEchouee`** · `CaisseRefuseTropSouvent` · `CaisseRalentie` ·
`AucunPaiementConstate`

Les deux en gras sont critiques, et pour la même raison : elles décrivent des
pannes **silencieuses**. Dans les deux cas le service répond normalement.

`AucuneClotureDeCommande` porte `or vector(0)` : `increase()` sur une fenêtre
sans aucun échantillon ne rend pas zéro mais un **vecteur vide**, et la
comparaison serait alors vide — l'alerte ne partirait jamais, précisément sur un
déploiement neuf.

`CaisseRefuseTropSouvent` est un **ratio** et non un compte absolu : cent refus
sur dix mille commandes est sain, dix refus sur douze ne l'est pas. Le
dénominateur passe par `clamp_min` pour qu'une période sans trafic ne produise
pas `NaN`.

### Résilience

Trois disjoncteurs, un par chemin :

| Instance | Chemin |
|---|---|
| `market-service-stock` | la réservation — chemin critique de la caisse |
| `market-service-catalogue` | l'affichage du panier, les lectures |
| `user-service-profil` | le pays de l'acheteur |

`market-service-stock` et `market-service-catalogue` visent le **même service** et
tombent séparément. Sans cette séparation, un affichage de panier qui ralentit
ouvrirait le disjoncteur partagé et fermerait le passage en caisse pour tout le
monde.

**Un 409 de stock n'est pas compté comme une panne.** C'est une réponse normale
d'une place de marché : une rupture sur un article populaire ne doit pas couper
l'accès à un service parfaitement sain.

Ni `retry` ni `ratelimiter` : rien ne les appliquerait — ce dépôt n'embarque pas
`aspectjweaver`. Et le réessai serait **dangereux** ici : rejouer une
réservation la prend deux fois, rejouer une transition peut expédier ou
rembourser deux fois.

### Une panne de user-service empêche de commander, et c'est voulu

C'est la différence avec market-service, qui se contente de montrer moins. Ici
le pays n'est pas un filtre d'affichage : il est inscrit sur la commande, sert au
contrôle de visibilité, et déterminera la livraison.

Une commande passée avec un pays faux devra être annulée après avoir réservé du
stock et peut-être encaissé — et l'acheteur l'aura vue aboutir. **La dégradation
silencieuse est acceptable sur une lecture ; pas sur une écriture qui engage de
l'argent.**

---

## 15. Ce qui reste ouvert

Énoncé comme des décisions, pas comme des oublis.

**La réservation n'est pas idempotente.** C'est la limite de la section 6, et la
seule de cette liste qui mérite d'être fermée.

**Aucun paiement, aucune livraison.** Deux services à venir. La couture est
prête et pilotée à la main en attendant.

**Pas de notification d'avancement.** L'acheteur doit consulter ses commandes
pour voir où elles en sont. Les textes vivraient dans notification-service,
comme ceux de market-service.

**Pas de frais de port ni d'adresse de livraison.** Ils appartiennent à la
logistique. `montant_articles` porte bien son nom : il ne contient que les
articles.

**Pas de facture.** Elle demande une numérotation séquentielle légale et un
format figé, ce qui est un sujet en soi.

**Pas de versement aux boutiques.** `montant_du_a_la_boutique` et l'état
`CLOTUREE` sont ce qu'il faut pour les calculer ; les exécuter appartient à
payment-service.
