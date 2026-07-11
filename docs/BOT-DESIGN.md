# Bot — Document de conception

> Conception du bot « joue comme un humain » de Red Dawn Raid. Chaque règle citée ici a été
> vérifiée dans le code (`backend/.../game/domain/`) et dans [REGLES.md](REGLES.md).
> Ce document sert de référence pour l'implémentation du package `bot/`.

---

## 1. Objectif et principes

Le bot doit remplacer un joueur humain (chasseur **ou** vampire) et jouer de manière
**contextuelle** : garder ses cartes pour le bon moment, monter des combos, adapter sa
stratégie à l'état de la partie. Trois principes non négociables :

1. **Utility AI** — chaque action possible reçoit un **score** calculé à partir de l'état
   de la partie ; le bot joue la mieux notée si elle dépasse un seuil, sinon il attend.
   Pas de machine learning, pas d'arbre de recherche : des heuristiques lisibles et
   réglables à la main.
2. **Équité (pas de triche)** — le bot vit côté serveur et pourrait lire tout le `Game`
   (mains adverses, ordre des pioches, lieux face cachée). Il ne doit raisonner **que sur
   ce qu'un humain verrait** : l'équivalent du snapshot masqué produit par
   `SnapshotService`. Concrètement, le bot construit son état de décision via une classe
   `BotView` qui n'expose que l'information publique + sa propre main. Toute heuristique
   qui aurait besoin d'une info cachée doit se contenter d'une **estimation** (voir §5,
   modèle d'adversaire).
3. **Humanisation** — délais de réaction aléatoires (2 à 8 s via le `raidTaskScheduler`),
   léger bruit sur les scores (via `Dice`) pour ne pas être prévisible, et respect des
   fenêtres temporisées existantes (~30 s en PREPHASE3, ~60 s en PHASE4).

Le bot appelle **directement la façade `GameService`** (jamais le contrôleur REST) : il
bénéficie ainsi des mêmes validations que les joueurs humains et n'a besoin ni de compte
ni de token. Un bot est un `Player` ordinaire dans le blob `Game`, avec un id synthétique
(ex. `bot:<uuid>`) et un flag `bot=true`.

---

## 2. Squelette du package `bot/`

```
backend/src/main/java/org/castello/bot/
├── BotManager.java          # cycle de vie : créer un bot (lobby), adopter le Player
│                            # d'un humain qui abandonne, retirer un bot ; nommage
├── BotOrchestrator.java     # écoute les événements de partie (même source que LiveEvents)
│                            # + tick de sécurité périodique ; décide QUEL bot doit agir
│                            # MAINTENANT et déclenche son cerveau avec un délai humanisé
├── BotView.java             # vue « équitable » de la partie pour un bot : info publique
│                            # + sa main ; AUCUN accès aux mains/pioches adverses
├── BotProfile.java          # personnalité : poids (agressivité, économie, prise de
│                            # risque, focalisation corruption) + archétype de départ
├── brain/
│   ├── HunterBrain.java     # décisions du bot chasseur, phase par phase
│   ├── VampireBrain.java    # décisions du bot vampire, phase par phase
│   └── ServantBrain.java    # chasseur devenu serviteur (corruption 3)
├── eval/
│   ├── CardEvaluator.java   # interface : score(BotView, BotProfile) -> double [0..1]
│   ├── hunter/…             # 1 évaluateur par carte chasseur (16)
│   └── vampire/…            # 1 évaluateur par carte vampire (13)
└── model/
    ├── ThreatAssessment.java  # rapport de force sur un lieu (voir §5)
    └── OpponentModel.java     # stats simples sur les habitudes adverses (voir §5)
```

Points d'intégration avec l'existant :
- **Déclenchement** : `BotOrchestrator` s'abonne aux mêmes points d'émission que
  `LiveEvents` (après commit). Un tick de secours (toutes les ~5 s) rattrape tout état
  où un bot doit agir et ne l'a pas fait — c'est la garantie « ne bloque jamais la partie ».
- **Lobby** : bouton « Ajouter un bot » → `BotManager` crée le joueur bot dans la partie
  (sans passer par `PlayerService.joinGame`, qui exige un compte).
- **Présence** : les bots doivent être exemptés du suivi `lastSeenTs`/`leftGame`
  (ou `BotOrchestrator` rafraîchit leur présence).
- **Serviteur** : si un bot chasseur atteint corruption 3, son cerveau bascule sur
  `ServantBrain` (il joue alors pour le vampire — comme un humain converti).

---

## 3. Les profils de personnalité

Un `BotProfile` est un petit jeu de **poids** consulté par tous les évaluateurs. Au
démarrage, tous les bots partent sur le préréglage **économique** (vampire Bâtisseur,
chasseur Prudent) — l'archétype n'est pas sélectionnable au lobby ; ce sont les **pivots
de situation** ci-dessous qui font émerger les autres comportements. Les archétypes
restent néanmoins définis comme préréglages : en activer d'autres plus tard (au hasard
ou via le lobby) sera trivial.

| Poids | Effet principal |
|-------|-----------------|
| `aggression` | Chercher/éviter le combat ; jouer les buffs offensifs tôt |
| `economy` | Prioriser récolte, constructions, boutique |
| `risk` | Seuils : attaquer à PV moyens, garder ou claquer les combos |
| `corruptionFocus` (vampire) | Prioriser morsures/marque/autel vs dégâts directs |

Archétypes de départ (des presets de ces poids, pas des bots séparés) :

- **Vampire Bâtisseur** (éco) : Scierie → Mine tôt, revenus auto, monte en puissance,
  évite le combat les premiers raids.
- **Vampire Corrupteur** : Autel tôt + Marque ténébreuse + morsures — transformer un
  chasseur en serviteur le plus vite possible (voir §7).
- **Vampire Prédateur** : Laboratoire → monstres + combos offensifs de toutes sortes :
  Éclipse → Lune de sang, Passage secret pour surgir sur un isolé, Clones d'ombre +
  potion/élixir de Rage… Cherche l'élimination directe des chasseurs.
- **Chasseur Traqueur** : oriente tous ses choix (lieu, Pisteur, cartes, achats) vers
  une seule cible — trouver le vampire et concentrer les attaques de l'équipe sur lui.
- **Chasseur Prudent** : économie, banque, équipement. On ne « choisit » pas vraiment
  ses combats (la révélation surprend) : prudent = minimiser la probabilité de croiser
  le vampire (choix de lieux, jamais de Pisteur) et ne rejoindre un plan offensif
  (embuscade coordonnée) que si l'équipe est en surnombre clair.
- **Chasseur Soutien** : échanges généreux, fumigations, purifications à l'autel.

**Pivot en cours de partie** (le cœur du système) : les poids sont modulés en continu
par l'état de la partie, dans les deux sens :
- pivots **défensifs** — vampire à PV bas → `aggression` baisse, fuite/défense montent ;
  chasseur corruption 2 → priorité absolue à la purification quel que soit le profil ;
  chasseur à PV bas → éviter le contact, potion de Vie ;
- pivots **opportunistes** — un chasseur atteint corruption 1 → le `corruptionFocus` du
  vampire monte (la proie est entamée, le rush serviteur devient rentable) ; un chasseur
  à PV bas repéré → le vampire presse l'attaque ; vampire visiblement blessé → les
  chasseurs basculent en chasse groupée.

---

## 4. Points de décision à couvrir (garantie anti-blocage)

Inventaire par phase, tiré des ~70 endpoints de `GameController`. **[O]** = obligatoire
(la partie attend ce joueur), **[F]** = facultatif (fenêtre d'opportunité).

### Lobby / vie de partie
- [O] rejoindre (`join` — via `BotManager`), se déclarer prêt (`boot-ready`), démarrage.
- [O] ne jamais déclencher `surrender`/`leave` ; présence rafraîchie automatiquement.

### PHASE0 — Météo
- [O] `weather/roll` si c'est au bot de lancer.

### PHASE1 — Planification (chasseurs)
- [F] `FUMIGATION_AIL` (avant de choisir son lieu), `PISTEUR`.
- [O] `select-location` (choix du lieu, voir §5).

### PHASE2 — Vampire
- [F] `CATACLYSME`, `CLONES_OMBRE` (+ `clones/roll`, `clones/confirm`), `IMAGE_MIROIR`.
- [F] `plan-construction` (voir §6) — **joue aussi la carte Lieu du chantier**
  (Forêt/Carrière/Manoir) : le vampire qui construit a donc déjà choisi son lieu.
- [O] `select-location` — seulement s'il n'a pas planifié de construction.

### PREPHASE3 — Révélation
- [O] `corruption/roll` (jet d6 du chasseur instable) ; si le bot est le **vampire** :
  `unstable/assign-target` / `assign-harvest` / `assign-nothing` pour chaque succombé.
- [O] répondre aux **effets de lieu** en file (`location-effect` + `effect-*` : vol,
  prédiction, expérimentation, soin, corruption, forge, explosion, pillage…).
- [O] résolutions de cartes en attente me ciblant ou m'appartenant
  (`actions/*/resolve`, `net/target`, `image-miroir/choose`, `secret-passage/resolve`…).
- [F] cartes d'action de combat (voir §8), `potions/use`, caisses (`crate/roll|resolve`).
- [O] `skip` / laisser expirer le timer 30 s quand rien ne vaut la peine — **le bot doit
  savoir dire « je passe »**, sinon il ralentit chaque raid.

### PHASE3 — Récolte & combat
- [O] `roll` (chaque jet d'attaque/défense qui m'appartient).
- [O] `corruption/roll` — la morsure se déclenche automatiquement côté serveur
  (`currentBite`) ; il reste deux jets à cliquer : le **d20 du vampire**, puis, si la
  cible porte l'armure de plates en argent, son **d4 de parade**. Le Chapelet sacré,
  lui, s'applique et se consume tout seul (à condition d'avoir été posé avant, en
  PREPHASE3).
- `combat/continue` — simple clic de progression (le front l'envoie après les jets),
  pas une décision : le tick de sécurité de l'orchestrateur l'émet si la PHASE3 stagne
  (indispensable quand tous les participants d'un combat sont des bots).
- [O] `actions/pit/resolve`, `blessed-stake/resolve`… quand le serveur attend mon jet.

### PHASE4 — Maintenance
- [F] boutique (`shop/buy-*`, `shop/sell`, `shop-upgrade-weapon`, `buy-upgrade-armor`),
  banque (`bank-contribute`), transmutation (`transmutation/do`), échanges
  (`trade/offer|accept|decline` — répondre aux offres reçues !), cartes de phase 4
  (`CHARISMATIQUE`, `MARCHAND_ITINERANT`, `AVIDITE_NOCTURNE`, `ADVANCED_TRANSMUTATION`).
- [O] `phase4/finish` quand le bot a terminé (ne pas faire attendre les humains).

> **Règle d'or de l'étape 1** : implémenter d'abord tous les **[O]** avec des choix
> naïfs (lieu aléatoire pondéré, toujours lancer les dés, toujours finir la phase).
> Un bot bête mais qui ne bloque jamais est immédiatement jouable.

---

## 5. Choix du lieu (la décision la plus importante)

Les lieux sont choisis **face cachée** (chasseurs en PHASE1, vampire en PHASE2) : personne
ne voit les choix adverses avant la révélation. Le bot raisonne donc en probabilités.

**`ThreatAssessment` (rapport de force sur un lieu)** — utilisé partout :
`force(camp, lieu) = Σ joueurs [PV × poids] + tiers d'équipement + mods de raid visibles
(météo, corruption, buffs annoncés) + monstres présents`. Le ratio
`force(alliés)/force(ennemis)` alimente presque toutes les heuristiques de combat.

**`OpponentModel` (estimation de l'info cachée)** — fréquences simples observées :
où chaque adversaire est allé les derniers raids, combien de cartes il a achetées
(info publique via l'historique), s'il a une eau bénite probable (achat vu en boutique).
Rien d'autre — pas de lecture des mains.

**Bot chasseur** choisit son lieu selon :
1. **Besoins de ressources** (manque pour potion/équipement/banque → lieu correspondant,
   cf. récoltes §9 de REGLES.md ; Manoir = or mais = territoire du vampire).
2. **Chasse** : si profil agressif et rapport de force favorable estimé → lieu probable
   du vampire (ou `PISTEUR` pour le suivre à coup sûr).
3. **Fuite/corruption** : corruption ≥ 2 ou PV bas → éviter le vampire, viser l'Autel
   purifié (retirer 1 corruption) ou un lieu calme.
4. **Coordination** : si d'autres bots chasseurs existent, converger vers la même cible
   quand une embuscade se prépare (les bots d'un même camp peuvent partager leurs
   *intentions*, jamais l'info cachée).

**Bot vampire** choisit son lieu selon :
1. **Âmes** : Manoir/bâtiments = récolte d'âmes (carburant du Labo, de l'Autel, des clones).
2. **Chasse** : lieu où un chasseur isolé/affaibli est probable (OpponentModel).
3. **Défense du domaine** : si un chasseur rôde avec Incendiaire probable, défendre.
4. **Setup de fuite** : rester au Manoir permet `PASSAGE_SECRET` (voir §8) —
   le vampire Corrupteur/Prédateur y gagne une flexibilité énorme après révélation.
5. **Fumigation** : lieux fumigés interdits (le bot doit filtrer ses options légales).

---

## 6. Stratégie de construction (vampire)

Vérifié dans `ConstructionService` : 1 construction/raid (planifiée en PHASE2, finalisée
fin de PHASE3 si les ressources sont encore là), chaque infra une seule fois, impossible
sous Cyclone. La carte Lieu construite va dans **la main de tous les joueurs** — construire
ouvre donc aussi des options aux chasseurs (à intégrer au score !).

| Infra | Coût | Intérêt stratégique |
|-------|------|---------------------|
| Scierie | 2 pierre, 2 fer | Éco : pas d'inauguration ; +3 bois/récolte de lieu, +1 bois auto/raid (vampire) |
| Mine | 3 bois, 1 fer | Éco : pas d'inauguration ; +3 fer/récolte de lieu, +1 fer auto/raid (vampire) |
| Bibliothèque | 4 bois, 2 pierre, 1 fer | Contrôle : pioche, sabotage de cartes, prédiction |
| Laboratoire | 2 eau, 2 herbes, 3 pierre, 50 âmes | Agression : monstres (100–600 âmes), alchimie |
| Salle de bal | 5 pierre, 2 fer, 50 âmes | Agression/corruption : danse macabre, valse, vols |
| Autel | 4 pierre, 1 bois, 2 fer, 50 âmes | Corruption : naît **corrompu** → +1 corruption/raid sur un chasseur |
| Forge | 5 fer, 4 pierre, 2 bois | Équipement tier 3 — à double tranchant, voir note |

> **Forge, un timing délicat** : elle profite aussi aux chasseurs (craft du tier 3 moins
> cher qu'en boutique, dont l'armure de plates en argent anti-morsure). Trop tôt, elle
> équipe l'ennemi ; trop tard, le vampire — qui n'a guère que la Transmutation avancée
> pour s'équiper sans elle — se prive d'options alors que les chasseurs auront déjà
> monté leur équipement en boutique. Le score doit viser le milieu de partie.

Ordres de construction par archétype (le bot suit l'ordre tant que les ressources et la
menace le permettent, sinon il décale d'un raid) :

- **Bâtisseur** : Scierie → Mine → Bibliothèque/Forge. Les +1 auto s'accumulent ; les
  récoltes améliorées (+3) financent le reste. Risque assumé : Incendiaire adverse —
  d'où défense du domaine si la menace monte.

  > ⚠️ **Défaut connu du Bâtisseur naïf (à corriger à l'étape 3, dit par le game
  > designer le 2026-07-10)** : construire Scierie/Mine **sans défense** offre ces lieux
  > (et leurs cartes, distribuées à *tous*) aux chasseurs, qui s'y équipent trop vite.
  > Correctif visé : le Bâtisseur doit **défendre ses lieux de production avec un
  > monstre** avant de les exposer. Deux voies :
  > 1. **Laboratoire d'abord** puis, le raid suivant, jouer Laboratoire →
  >    *Expérimentation* → monstre gardien envoyé sur la Scierie/Mine.
  > 2. **Cartes « Portail »** ✅ *(livrées le 2026-07-11)* — `PORTAL_INVOCATION_REVENANT`
  >    et `PORTAL_INVOCATION_BAT` : en PHASE2, invoquent un monstre gardien (Revenant
  >    5pv D6/D4, Chauve-souris 5pv D4/D6) sur un lieu choisi, **gratuitement** (l'effet
  >    Expérimentation du Labo sans construire le Labo), 4 copies de chaque dans le deck
  >    vampire. Monstre **persistant**. Permet le start Scierie → carte Portail →
  >    défendre. **Reste à faire (étape 3)** : que le bot Bâtisseur JOUE ces cartes pour
  >    protéger ses lieux de production — le câblage backend/UI est fait, l'évaluateur
  >    bot arrive avec la grille §8.
- **Corrupteur** : l'Autel est finançable dès le raid 1 si besoin (coût : 4 pierre,
  1 bois, 2 fer, 50 âmes — le vampire démarre avec 10 pierre et 100 âmes × nb de
  chasseurs). Le déclencheur n'est donc pas la ressource mais la **stratégie** : le
  score monte quand la victime est choisie et la Marque posée/en main, pas forcément
  en début de partie. Une fois construit, l'Autel est très « collant » : tant que le
  vampire s'y rend, le combat y bloque la purification à l'eau bénite — il ne reste
  aux chasseurs que la purification par le combat (≥ 1 dégât au vampire et aucune
  morsure réussie sur place ce raid). Ensuite Salle de bal (danse macabre = corruption
  sur attaque réussie).
- **Prédateur** : Laboratoire (eau/herbes à récolter au Lac/Forêt d'abord, ou
  transmutation) → monstres pour harceler pendant que le vampire chasse. Ensuite
  Salle de bal.

Entrées du score de construction : ressources actuelles vs coût, revenu attendu par raid
restant estimé, pression chasseur (Incendiaire déjà vu ?), météo (Cyclone), et le
`corruptionFocus`/`economy` du profil.

---

## 7. Stratégie de corruption (vampire) et contre-jeu (chasseur)

Mécanique vérifiée (`CorruptionService`, `CombatService`, `LocationEffectService`) :
- Niveaux 0→3 ; L1 = −1 ATK/DEF ; L2 = instable (d6 en préphase : 1–3 → succombe ce raid,
  le vampire peut l'assigner à attaquer un allié ou récolter pour lui) ; L3 = **serviteur
  définitif** (perd ses cartes, son or devient des âmes, change de camp).
- Sources de +1 corruption : **morsure** (d20 > 8 après un combat ; +50 âmes ; clone :
  d20 > 12, +30 âmes), **Autel corrompu** (action du vampire sur place), **Marque
  ténébreuse** (1×/raid quand le marqué croise le vampire — combat ou assignation
  d'instable), **Danse macabre** (Salle de bal : attaque réussie → +1).
- Contres chasseur : **Eau bénite** mode REDUCE (−1 corruption) et mode CLEANSE (dissipe
  la Marque), **Chapelet sacré** (annule une morsure réussie), **armure de plates argent**
  (jet de d4 pour repousser), **Autel purifié** (−1 corruption sur place).

**Plan du bot Corrupteur — « rush serviteur »** (exactement le scénario décrit par le
game designer) :

1. **Choisir une victime** et s'y tenir : déjà corrompue > faible (PV, équipement) >
   sans eau bénite estimée (aucun achat d'eau bénite observé) > isolée souvent.
2. **Empiler les sources** sur la même cible et le même raid quand possible :
   Marque ténébreuse (PREPHASE3) + aller à son contact (combat → morsure, la Marque
   déclenche en plus son +1 au contact) + Autel corrompu si construit + Faim
   irrépressible si l'attaque risque d'échouer (morsure quand même possible).
3. **Vitesse avant tout** : chaque raid où la cible reste à corruption 1–2 est une
   fenêtre pour une Eau bénite adverse. D'où le combo « 2 corruptions dans le même
   raid » (Autel + morsure, ou Marque + morsure) pour passer de 1 à 3 sans fenêtre de
   purification.
4. **À corruption 2, exploiter l'instable** : sur un jet raté (1–3), assigner la cible
   contre le chasseur le plus dangereux, ou en récolteur pour affamer l'équipe.

**Contre-jeu du bot chasseur** (symétrique, toute stratégie confondue) :
- corruption 1 + carte Eau bénite en main → REDUCE **avant** d'atteindre 2 si le vampire
  est en stratégie corruption (Marque vue, Autel construit) ;
- corruption 2 → priorité absolue : REDUCE immédiat, sinon Autel purifié, sinon éviter
  tout contact avec le vampire (le d6 d'instable ne se déclenche qu'en préphase, mais la
  morsure ne peut arriver qu'au contact) ;
- marqué → CLEANSE tôt ; Chapelet sacré prophylactique si on prévoit de croiser le
  vampire ; acheter de l'eau bénite en boutique (150 or + 4 eau) dès que le vampire
  révèle une stratégie corruption.

---

## 8. Grille des 29 cartes Action

Format : **Fenêtre** (vérifiée dans `ActionCardService`) · **Jouer quand** (heuristique,
facteurs de score) · **Retenir/Combo** (pourquoi attendre).

### Chasseurs (16)

| Carte | Fenêtre & conditions (code) | Jouer quand | Retenir / Combo |
|-------|------------------------------|-------------|------------------|
| **EAU_BENITE** | modale 3 modes : REDUCE (−1 corruption), CLEANSE (dissipe Marque), ATTACK (+4 dégâts sacrés ce raid) | REDUCE si corruption ≥ 2 (urgence max) ou ≥ 1 face à un vampire Corrupteur ; CLEANSE si marqué ; ATTACK si combat vs vampire imminent et corruption saine | La carte la plus polyvalente : en garder **toujours une** si le vampire a l'Autel ou la Marque. ATTACK + Embuscade = burst maximal |
| **FUMIGATION_AIL** | PHASE1, avant de choisir son lieu | Protéger une grosse récolte (Manoir) ou un allié faible ; bloquer l'accès du vampire à son propre Autel corrompu | Peu utile si le vampire est loin ; à jouer sur la base de l'OpponentModel |
| **PISTEUR** | PHASE1 (aussi achetable 100 or) | Profil Traqueur + rapport de force favorable (équipe boostée, vampire blessé) | Combo naturel : plusieurs chasseurs pistent → Embuscade quasi garantie au raid suivant |
| **FEU_DE_CAMP** | PREPHASE3, météo Crépuscule/Nuit obscure/Nuit claire, sur son lieu | Annuler un malus météo qui gêne MON combat ; **indispensable sous Nuit obscure pour ré-autoriser Filet/Fosse** | Prérequis de combo pièges sous Nuit obscure |
| **NET** (Filet) | PREPHASE3, adversaire sur mon lieu, interdit Nuit obscure sans feu | Combat certain sur mon lieu : d20 > 12 → −1 DEF cible (monstre : −1 ATK et DEF) | S'empile avec Fosse + Embuscade sur la même cible avant le burst |
| **PIT** (Fosse) | PREPHASE3, ennemi/monstre sur mon lieu, interdit Nuit obscure sans feu | Comme Filet, en plus fort (−2 DEF si la victime rate d20 < 8) | Même combo ; jouer Fosse avant Filet si on n'a qu'un tour |
| **PROVOCATION** | PREPHASE3, ≥ 1 ennemi sur mon lieu ; **peut interrompre Passage secret / Image miroir** | Tank (PV hauts, armure) protège un allié fragile ; ou **contre-fuite** : le vampire tente de s'échapper → le provoquer l'en empêche | L'anti-PASSAGE_SECRET officiel : à garder si le vampire campe au Manoir |
| **INCENDIAIRE** | PREPHASE3, sur le lieu à brûler ; détruit l'infra en fin de raid | Score = valeur de l'infra pour le vampire : Autel corrompu/Labo avec monstres > Salle de bal > Mine/Scierie | L'arme anti-Bâtisseur ; anticiper la défense du lieu |
| **AMBUSH** (Embuscade) | PREPHASE3, ≥ 2 chasseurs vivants + ≥ 1 ennemi sur mon lieu, 1×/lieu/raid | Score ∝ nb de chasseurs présents et leurs buffs ; **sauf** si PV vampire très bas (une attaque normale suffit, garder la carte) | Le finisher d'équipe : synchroniser avec potions/élixirs/Eau bénite ATTACK/Filet/Fosse le même raid |
| **LONELY** (Solitaire) | PREPHASE3, sur un lieu | +1 ATK/DEF **par chasseur absent** : excellent en duel seul contre le vampire | Anti-synergie avec Embuscade (qui veut du monde) ; parfait pour le tank provocateur isolé |
| **BLESSED_STAKE** (Épieu béni) | PREPHASE3, persistant, un seul actif | Combat vs vampire probable ; se déclenche contre un ennemi présent | Se pose à l'avance (persistant) : à jouer dès qu'on part en chasse, pas au dernier moment |
| **SACRED_ROSARY** (Chapelet) | PREPHASE3, persistant | Annule une morsure réussie : corruption 1–2 + contact vampire probable = score max ; réponse à Faim irrépressible | Assurance-vie du rush corruption adverse ; le poser AVANT le combat risqué |
| **CHARISMATIQUE** | PHASE4, 1×/raid, bloqué par Présence écrasante | Juste avant une grosse séance d'achats (prix réduits, revente améliorée) | À combiner avec la revente massive de ressources excédentaires |
| **MARCHAND_ITINERANT** | PHASE4, 1×/raid | Or disponible et rien d'urgent : bonus aléatoire à bon rapport | Après Charismatique si les deux sont en main |
| **CRATE_LAKE / CRATE_MANOR** | PREPHASE3, être au Lac / au Manoir, 1 caisse/raid | Quasi toujours quand on est au bon endroit et pas en danger | Influence le choix de lieu en PHASE1 (« j'ai la caisse du Lac → Lac ») |

### Vampire (13)

| Carte | Fenêtre & conditions (code) | Jouer quand | Retenir / Combo |
|-------|------------------------------|-------------|------------------|
| **CATACLYSME** | PHASE2 | Raid offensif planifié : ajoute 2 météos choisies cumulées | Setup du raid d'assaut : cumuler avec Éclipse/Lune de sang pour un pic de puissance |
| **CLONES_OMBRE** | PHASE2 | Chasseurs dispersés + stock d'âmes (option morsure payée en âmes par clone, réussite d20 > 12, +30 âmes, +1 corruption) | Corrupteur : clones mordeurs = corruption en parallèle sur plusieurs cibles |
| **IMAGE_MIROIR** | PHASE2, interdit si provoqué | Chasse groupée sur moi probable : je pose un leurre et choisis mon vrai lieu après révélation | Vulnérable à Provocation ; **contre Pisteur** (le traqueur suit la carte posée en PHASE2, la matérialisation se décide après révélation) ; alterner avec Passage secret pour rester illisible |
| **PRESENCE_ECRASANTE** | PREPHASE3 | Les chasseurs empilent des prépas (Filet/Fosse/Embuscade) sur MON lieu → tout annuler + bloquer leurs cartes ce raid | Le contre direct de l'Embuscade : à garder tant que les chasseurs jouent groupé |
| **ECLIPSE** | PREPHASE3 | Je veux combattre ce raid : météo → Pleine lune (+2 ATK) | **Combo signature : Éclipse → Lune de sang** le même raid (+4 ATK au total) |
| **BLOOD_MOON** (Lune de sang) | PREPHASE3, requiert Pleine lune | Après Éclipse, ou météo naturelle 12 | Le pic d'attaque à synchroniser avec Faim irrépressible / Valse sanguinaire |
| **VOILE_DE_BRUME** | PREPHASE3, cible un lieu | Récolte ÷2 + −1 DEF chasseurs sur le lieu : harcèlement éco (lieu riche et peuplé) ou débuff pré-combat | Bâtisseur : ralentir l'éco adverse sans combattre |
| **FAIM_IRREPRESSIBLE** | PREPHASE3 | Morsure possible même sans dégâts ce raid : cible à corruption 1–2 présente, ou chasseur trop coriace pour être blessé | Cœur du rush corruption ; combo Marque + Autel le même raid |
| **MARQUE_TENEBREUSE** | PREPHASE3, cible un chasseur | Poser tôt sur LA victime choisie (+1 corruption à chaque contact, 1×/raid) | Se dissipe à l'Eau bénite CLEANSE → forcer le tempo après la pose ; la Liche peut aussi marquer |
| **AFFAIBLISSEMENT_OCCULTE** | PREPHASE3, cible un chasseur vivant | −2 ATK ce raid sur le meilleur attaquant adverse, juste avant un combat que je ne peux pas éviter | Défausse défensive de dernier recours quand l'Embuscade est inévitable |
| **PASSAGE_SECRET** | PREPHASE3, **être au Manoir**, interdit si provoqué ; destination choisie après révélation | Fuir un rapport de force défavorable (plusieurs chasseurs boostés sur moi) **ou** frapper par surprise un isolé | Justifie de camper au Manoir ; **contre Pisteur** (le traqueur est déjà déplacé quand la fuite se résout) ; contré par Provocation → varier avec Image miroir |
| **AVIDITE_NOCTURNE** | PHASE4, 1×/raid | Les chasseurs sont riches (grosse récolte d'or au Manoir observée) et vont acheter | Anti-boutique ; synergie avec Voile de brume (éco totale) |
| **ADVANCED_TRANSMUTATION** | PHASE4, 1×/raid | Excédent de ressources brutes à convertir (offre améliorée type marchand) | Outil du Bâtisseur pour financer âmes/constructions |

---

## 9. Potions, élixirs, boutique (heuristiques rapides)

- **Potions** (PREPHASE3, participant à un combat imminent, gelées sous Blizzard) :
  Vie si PV < ~50 % ; Force/Endurance selon rôle attaquant/défenseur ; Focalisation
  gardée pour les jets critiques (embuscade, morsure défensive).
- **Élixirs** (1/raid) : à **claquer avec le combo** — Rage sur le raid d'Embuscade,
  Résilience/Invulnérabilité quand le vampire Prédateur fond sur moi, Invisibilité pour
  frapper sans riposte. Ne jamais les boire « parce qu'on les a ».
- **Boutique chasseur** : priorité équipement (D4→D6→D8 : 2 bois+2 fer puis 3+3) >
  eau bénite si menace corruption > potions. **Argent** : ne sert qu'au craft du
  tier 3 à la Forge (3–4 argent par pièce, dont l'armure de plates anti-morsure :
  4 fer + 4 argent) — n'en acheter que si la Forge est construite ou imminente.
  Achats de cartes : coût croissant (n+1)×50 → 1 à 2 par raid max.
- **Banque** (chasseurs) : le Prudent y met ses pierres tôt (10/15/20 → +50 à +100 or/raid
  pour toute l'équipe) ; les autres profils y contribuent l'excédent.
- **Vampire PHASE4** : transmutation pour compléter un coût de construction ;
  acheter des cartes Action quand les âmes débordent.

---

## 10. Plan d'implémentation incrémental

1. **Bot légal** ✅ *(implémenté le 2026-07-09)* — `BotManager` + `BotOrchestrator` +
   tous les points **[O]** du §4 avec des choix naïfs. Livrable : une partie complète
   humain vs bot sans blocage. *Réalisation : un unique `BotBrain` naïf (les cerveaux
   par rôle arrivent à l'étape 3) + `BotController` (endpoints lobby) ; validé par une
   partie bots-seuls de 16+ raids (combats, morsures, instables) sans blocage.
   Découverte au passage : `combatsQueue` ne reflète jamais les jets (copies JSON,
   les jets vivent sur le miroir `currentCombat`) → le bot avance en PHASE4 via
   `advance` explicite, comme le front. Après les premiers retours de jeu réel :
   ajout d'un **rythme par stabilité d'état** (l'orchestrateur mesure depuis quand
   l'état n'a pas changé, le cerveau exige un délai par type d'action, doublé d'un
   mode « humains présents » où le bot laisse le front faire les progressions
   automatiques et ne sert que de filet de sécurité).*
2. **Économie** ✅ *(implémentée le 2026-07-10)* — choix de lieu pondéré (§5),
   constructions par archétype (§6), boutique, banque, transmutation, potions basiques.
   *Réalisation : `BotProfile` (presets économiques Bâtisseur/Prudent — les archétypes
   agressifs et les pivots viennent à l'étape 3) ; dans `BotBrain` : `pickHunterLocation`
   (score par déficit de ressources vers des cibles TARGET_*), `chooseConstruction`
   (ordre Scierie→Mine→Bibliothèque→Forge, garde cyclone/fumigation/ressources ;
   planConstruction joue aussi le lieu), `pickVampSideLocation` (Manoir pour les âmes),
   `maybeUsePotion` en PREPHASE3 (Vie si PV bas, sinon Force/Endurance une fois/raid,
   proxy « ennemi sur mon lieu »), et une PHASE4 économique : chasseur = équipement →
   banque → potion de réserve ; vampire = rééquilibrage bois/fer par transmutation.
   Validé bots-seuls : vampire construit les 4 infras dans l'ordre, banque niveau 2,
   chasseurs qui forgent/achètent/déposent, aucun blocage ni exception sur 6+ raids.
   Non couvert (renvoyé à l'étape 3) : achat de cartes Action, argent/forge tier 3,
   choix d'effet de lieu non triviaux (vol/prédiction/expérimentation).*
3. **Cartes & combos** — les 29 évaluateurs (§8), corruption/contre-corruption (§7),
   élixirs synchronisés, `ThreatAssessment` complet.
4. **Réglage** — `OpponentModel`, bruit/délais humanisés, équilibrage des poids en
   jouant, éventuels niveaux de difficulté (= jeux de poids + bruit plus ou moins fort).

Chaque étape est jouable et testable indépendamment ; on n'aborde l'étape suivante
qu'une fois la précédente validée en partie réelle.

## 11. Décisions produit (tranchées)

- **6 bots max** par partie, vampire compris ; archétype de départ **non sélectionnable** :
  préréglage **économique** par défaut (quand les profils seront implémentés, étape 2+),
  les pivots de situation (§3) faisant émerger les autres comportements.
- Bot **vampire possible dès la v1**.
- **Noms** de bots : oui ; avatars plus tard (pas encore implémentés, même pour les
  humains).
- **Reprise sur abandon** : si un humain quitte la partie, un bot reprend son joueur
  en cours de route (`BotManager` doit savoir adopter un `Player` existant, pas
  seulement en créer un).
