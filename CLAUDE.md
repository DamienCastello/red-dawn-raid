# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Vue d'ensemble

**Red Dawn Raid** est un jeu de société numérique multijoueur en ligne (« Chasseurs vs
Vampire ») — un monorepo avec un **frontend Angular 20** (`frontend/`) et un **backend
Spring Boot 3.3 / Java 21** (`backend/`), adossé à **PostgreSQL 16**. Le jeu en temps réel
repose sur **WebSocket (STOMP)**. La majorité des commentaires et des termes métier sont en
français.

## Commandes

### Backend (`backend/`, nécessite Java 21 + Maven 3.8+)
```bash
mvn -q -DskipTests clean package   # build (jar)
mvn spring-boot:run                # lance sur http://localhost:8080 (profil : dev, requiert Postgres sur localhost:5432)
mvn test                           # lance les tests (note : aucun test n'existe actuellement sous src/test)
```

### Frontend (`frontend/`, nécessite Node 20+ et Angular CLI)
```bash
npm install
npm start                                   # gen:assets + ng serve sur http://localhost:4200
ng serve --proxy-config proxy.conf.json     # dev avec /api et /ws proxifiés vers :8080
npm run build                               # gen:assets + build de production
npm test                                    # Karma + Jasmine (exécution unique : ng test --watch=false)
```
`npm start`/`npm run build` exécutent d'abord `gen:assets` (`gen-assets-manifest.mjs`), qui
parcourt `public/assets/` et écrit un manifeste d'images consommé par `AssetPreloaderService`.
Si les assets changent, ce script doit être ré-exécuté — passez toujours par les scripts npm,
jamais par `ng serve`/`ng build` bruts.

`proxy.conf.json` route `/api/*` et `/ws` vers le backend en dev.

### Stack complète via Docker
```bash
docker compose up -d --build                                        # dev : db + backend + frontend
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build   # prod
```

## Architecture

### Vue d'ensemble backend (après refacto)

```
GameController (REST /api/games)         GET /{id} ─────────────┐
        │ délègue chaque endpoint                               ▼
        ▼                                                 SnapshotService
   GameService  ← FAÇADE (~210 l, pure délégation, aucune règle)   (vue client masquée)
        │
        ▼  aiguille vers le bon domaine
┌───────────────────────── game/domain/ (les règles) ─────────────────────────┐
│ PhaseFlowService · CombatService · CorruptionService · LocationEffectService │
│ ActionCardService · HunterActionService · VampireActionService · ShopService │
│ WeatherService · HarvestService · ConstructionService · EquipmentService     │
│ BankService · TradeService · DeckService · GameLifecycleService              │
└──────────────────────────────────┬───────────────────────────────────────────┘
        │ chaque service : loadForUpdate → mute Game → save → events après commit
        ▼
   game/support/ :  GameStore (JSON + verrou + afterCommit) · Dice (aléatoire)
                    RaidFlow (interface : les domaines rappellent le flux en @Lazy)
        │
        ▼
   PostgreSQL — games(id, state jsonb, version)   +   LiveEvents → WebSocket /topic
```

Deux points de conception clés issus de la refacto :
- **Verrou + transaction** : toute méthode qui mute passe par `GameStore.loadForUpdate`
  (`SELECT … FOR UPDATE`) pour éviter que deux actions simultanées s'écrasent (« dernière
  écriture gagne »). Un verrou exige une transaction → ces méthodes sont `@Transactional`
  (ou dans un `tx.execute{}`). Oublier l'annotation = erreur « Query requires transaction ».
- **Casser la dépendance circulaire** : les domaines qui doivent relancer le flux de phases
  (combat → vérifier victoire, etc.) ne dépendent pas de la classe `PhaseFlowService` mais de
  l'interface `RaidFlow` qu'elle implémente, injectée en `@Lazy` (résolue au 1ᵉʳ appel, pas au
  démarrage). Sans ça, Spring ne saurait pas lequel des deux construire en premier.

### Backend : un unique blob d'état de partie sérialisé
Tout l'état d'exécution d'une partie tient dans **un seul POJO `Game`** (`game/Game.java`,
~1500 lignes) qui est **sérialisé en/depuis une chaîne JSON** et stocké dans la colonne
`state` (`jsonb`) de `GameEntity` (`persistence/`). Il n'y a **aucun modèle relationnel de
l'état de jeu** — la base contient `games(id, state jsonb, version)` plus les lignes
auth/joueur. C'est le point le plus important à comprendre :

- **Les règles du jeu sont découpées en services de domaine sous `game/domain/`.** Chaque
  service porte un chapitre des règles et suit le même patron : charger `Game` via
  `GameStore.loadForUpdate` (verrou pessimiste) → muter le POJO → `GameStore.save` →
  émettre les événements après commit (`GameStore.afterCommit`). Les services :
  `WeatherService`, `HarvestService`, `ShopService`, `EquipmentService`, `ConstructionService`,
  `CorruptionService`, `LocationEffectService`, `ActionCardService` (jouer une carte) +
  `HunterActionService`/`VampireActionService` (résolutions), `CombatService`, `BankService`,
  `TradeService`, `DeckService`, `SnapshotService`, `GameLifecycleService` (lobby/démarrage/victoire)
  et `PhaseFlowService` (transitions de phase + timers).
- **`game/support/`** contient la plomberie transverse : `GameStore` (charger/verrouiller/sauver
  le JSON + `afterCommit`), `Dice` (tous les jets aléatoires) et `RaidFlow` (interface que les
  domaines appellent pour relancer les timers de préphase / vérifier la victoire sans dépendance
  circulaire ; implémentée par `PhaseFlowService`, injectée en `@Lazy`).
- **`GameService.java` (~210 lignes) est une simple façade** : un point d'entrée pour le
  contrôleur, chaque méthode déléguant au service de domaine responsable. Aucune règle n'y vit.
- **`GameController.java` (~800 lignes)** est une fine couche REST sous `/api/games` — ~70
  endpoints, chacun déléguant à une méthode de `GameService` (ex. `advance`, `roll`,
  `select-location`, `shop/buy-*`, `actions/<nom>/resolve`, `effect-*`) — sauf `GET /{id}` qui
  appelle directement `SnapshotService`. Pour ajouter une mécanique on touche : endpoint du
  contrôleur → façade `GameService` → méthode du service de domaine → `LiveEvents`.
- Beaucoup de helpers de lecture sont portés par le **modèle** : `Game` expose `vampire()`,
  `hunters()`, `findPlayer(id)`, `addHistory(...)`, `locationOf(id)`, `playersByLocation()`,
  `addRaidMod(...)`, `entityName(id)`… et `Player` expose `isAlive()`, `isVampSide()`,
  `grant(res, qty)` (tous `@JsonIgnore`, exclus du JSON sauvegardé).
- **`Game.java`** imbrique aussi des records/enums métier ; les enums autonomes incluent `Phase`
  (PHASE0→PHASE4 avec PREPHASE3), `GameStatus` (CREATED/STARTING/ACTIVE/ENDED) et `Action`
  (les cartes action chasseur/vampire).
- **`web/dto/GameSnapshot.java`** est la projection destinée au client de `Game`, renvoyée par
  `GET /api/games/{id}` — pas le `Game` brut. Le garder synchronisé avec le type `GameSnapshot`
  côté frontend dans `api.service.ts`.
- Les phases de raid temporisées utilisent un `TaskScheduler`
  (`@Qualifier("raidTaskScheduler")`, configuré dans `config/SchedulingConfig`) — les tours de
  jeu peuvent avancer sur un timer côté serveur, pas uniquement sur action du joueur.

### Bots (mode solo) — package `bot/`
La conception complète est dans `docs/BOT-DESIGN.md` (grille des 29 cartes, stratégies,
plan en 4 étapes). Étapes 1 (« bot légal qui ne bloque jamais ») **et 2 (économie)**
implémentées ; le profil économique (`BotProfile` : Bâtisseur/Prudent) pilote le choix
de lieu pondéré, la construction du vampire, la boutique/banque/transmutation en PHASE4
et les potions basiques en PREPHASE3.
- **`BotManager`** : ajoute/retire un bot au lobby (endpoints `POST /api/games/{id}/bots`
  et `POST /api/games/{id}/bots/{botId}/remove` dans `BotController`). Un bot est un
  `Player` ordinaire du blob `Game` avec `bot=true` et un id `bot:<uuid>` — pas de compte,
  pas de ligne SQL `players`, jamais d'appel REST. Max 6 bots, vampire possible.
- **`BotOrchestrator`** : tick périodique (1,2 s, scheduler dédié `botTaskScheduler` dans
  `SchedulingConfig`) qui balaie les parties contenant des bots et fait jouer **au plus une
  action par partie et par tick** via `BotBrain`. Les refus serveur (409/403) sont normaux
  (état changé entre-temps) et avalés — le tick suivant repart d'un état frais.
  **Rythme** : l'orchestrateur mesure la « stabilité » de l'état observable (empreinte) et
  `BotBrain` exige un délai minimal par type d'action, plus long quand des humains sont
  présents — le front joue alors ses animations (météo 3 temps, `SPECTATE_HOLD_MS` = 5 s
  sur les résultats de combat) et fait lui-même les progressions (`combat/continue`,
  `advance`) ; le bot n'est qu'un filet de sécurité. Les courses résiduelles sont bénignes :
  le front les ignore via `showErrorUnlessConflict` (409 silencieux, `game.component.ts`).
- **`BotBrain`** : cerveau couvrant tous les points obligatoires (météo, choix de lieu,
  assignation d'instables, effets de lieu — qui n'ont AUCUN timer serveur —, jets de
  combat/morsure/fosse, fin de phase 4) PLUS l'économie (étape 2 : choix de lieu pondéré,
  construction, boutique/banque/transmutation, potions) en appelant la **façade
  `GameService`** : le bot subit les mêmes validations qu'un humain et ne lit que l'info
  publique + sa propre main. `BotProfile` porte l'archétype économique (Bâtisseur/Prudent).
- Exemptions lobby : les bots sont prêts d'office (`requestStart`) et jamais purgés comme
  fantômes (`cleanupStaleLobbyPlayers`).
- **Piège connu** : `combatsQueue` est une copie jamais mise à jour des jets (ils vivent sur
  le miroir `currentCombat` après resérialisation JSON) → l'auto-PHASE4 de `combatContinue`
  ne se déclenche jamais après de vrais combats. Comme le front humain
  (`game.component.ts` → `advancePhase('PHASE4')`), le bot appelle `advance` explicitement.

### Événements temps réel (STOMP sur WebSocket)
- `WebSocketConfig` expose `/ws` (et `/ws-sockjs`), un broker simple sur `/topic`, préfixe
  applicatif `/app`.
- `LiveEvents.java` publie des `GameEvents` (typés par `GameEvents.Type`) vers deux
  destinations : **`/topic/games/{gameId}`** pour les événements par partie et
  **`/topic/lobby`** pour les changements de lobby/liste.
- Le frontend `LiveService` s'abonne à ces mêmes topics. L'union discriminée `GameEvent` en
  tête de `services/live.service.ts` **doit rester synchronisée** avec `GameEvents.Type` et les
  payloads émis dans `LiveEvents`. Ajouter un événement = mettre à jour les deux côtés.
- Flux général : le client POST une action → `GameService` mute + persiste → `LiveEvents`
  diffuse → tous les clients reçoivent l'événement et (généralement) recharger le snapshot.

### Authentification (un seul token : `authToken`)
- **`authToken`** — session de compte. `POST /api/auth/signup|login` le renvoie ; stocké côté
  client dans `sessionStorage`/`localStorage`. `authInterceptor` ajoute
  `Authorization: Bearer <authToken>` à chaque requête. `TokenAuthFilter` le résout en un
  utilisateur et attribue `ROLE_USER`.
- **Appartenance à une partie** — il n'existe **pas** de token par partie. Au join,
  `PlayerService.joinGame` crée une ligne SQL `players(user_id, game_id)` (une seule partie
  par compte) ; les endpoints liés à une partie vérifient l'appartenance via
  `PlayerService.requireInGame(userId, gameId)`.
- `SecurityConfig` : sans état, CSRF désactivé, CORS restreint à localhost:4200 + les deux
  hôtes `castello.ovh`. `GET /api/games/**` et `/api/auth/**` sont publics ; les autres
  `/api/games/**` nécessitent `ROLE_USER` ; le handshake `/ws` n'est pas filtré par
  `TokenAuthFilter`.

### Structure du frontend
- Composants Angular standalone, routes dans `app.routes.ts` : `auth` → `lobby` → `game/:id`
  (les deux derniers derrière `authGuard`).
- **`game.component.ts` (~8k lignes) est le « cerveau » de la partie** : il possède l'état (le
  snapshot), écoute le WebSocket, appelle l'API et orchestre les modales. Il ne contient plus
  le markup du plateau : le template (`game.component.html`, ~140 l) n'est qu'un assemblage de
  composants de board.
- **`components/board/` — les 5 régions du plateau** (composants d'affichage « bêtes ») :
  `hunters-row` (haut), `vampire-panel` (milieu-gauche), `center-board` (milieu : fil live +
  cartes du centre + historique auto-scrollé), `decks-panel` (milieu-droite), `my-board` (bas).
  Plus deux feuilles partagées réutilisées par plusieurs régions : `player-equip-bar` et
  `mod-chips`. Chaque région reçoit ses données via `@Input` (souvent un `vm`/`actions`/`helpers`
  comme les modales) et remonte les clics ; l'état de sélection et les appels API restent dans
  `game.component`. Types partagés dans `components/board/board.types.ts`.
- **`services/game-assets.service.ts` (`GameAssetsService`)** centralise tous les helpers
  d'affichage PURS (code → image/libellé FR : `weaponImg`, `actionImg`, `potionLabelFr`,
  `deckCount`, `modIconSrc`…). Injecté par `game.component` (qui garde des relais 1-ligne pour
  les modales) et directement par les composants de board.
- **Styles** : `src/app/game-board.scss` est un stylesheet **global** (chargé via `angular.json`)
  contenant tout le CSS du plateau, enveloppé sous `app-game { … }` pour rester scopé à la vue
  de jeu (pas de collision avec lobby/auth) tout en étant partagé par tous les composants de
  board sans duplication. Les composants de board portent juste `:host { display: contents }`
  (pour ne pas casser les grilles/flex du parent) et n'ont pas de `styleUrls`.
- Les **modales** sous `components/modals/` et `components/limit-modals/` associent souvent un
  `*.component.ts` à un `*.vm.ts` (view-model). Le zoom au survol (agrandir une carte) reste géré
  par `game.component` et est passé aux composants via un bundle `ZoomHandlers`.
- Services (`services/`) : `ApiService` (tous les appels REST + les types TypeScript partagés
  qui reflètent les DTO du backend), `LiveService` (STOMP), `GameAssetsService` (helpers
  d'affichage), `AssetPreloaderService` (utilise le manifeste généré), `NotifService`.

> **Build** : Angular exige Node ≥ 20.19 / 22.12. Le `node` par défaut peut être trop vieux —
> utiliser Node 22 (`npm start` / `npm run build`).

## Environnements & profils
- Profils Spring backend : `dev` (défaut, `application.yml`), `preprod`, `prod`, `container`
  (activé dans Docker). Les identifiants/URL de la base viennent des variables d'environnement
  `SPRING_DATASOURCE_*` dans Docker.
- Le `API_BASE_URL` du frontend est injecté au démarrage du conteneur dans `assets/env.js`.
- **Danger :** `POST /api/admin/wipe` et `/api/dev/**` ne sont pas authentifiés — dev/test
  uniquement.