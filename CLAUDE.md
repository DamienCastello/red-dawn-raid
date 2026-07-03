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

### Backend : un unique blob d'état de partie sérialisé
Tout l'état d'exécution d'une partie tient dans **un seul POJO `Game`** (`game/Game.java`,
~1400 lignes) qui est **sérialisé en/depuis une chaîne JSON** et stocké dans la colonne
`state` (`jsonb`) de `GameEntity` (`persistence/`). Il n'y a **aucun modèle relationnel de
l'état de jeu** — la base contient `games(id, state jsonb, version)` plus les lignes
auth/joueur. C'est le point le plus important à comprendre :

- **`GameService.java` (~16k lignes) est le moteur de jeu tout entier.** Chaque mutation suit
  le schéma : charger `Game` via `findOr404ForUpdate` (verrou pessimiste) → désérialiser depuis
  le JSON → muter le POJO en mémoire → re-sérialiser → sauvegarder. Les helpers `toJson`/`fromJson`
  encapsulent Jackson. La quasi-totalité des règles du jeu, transitions de phase, calculs de
  combat, logique de boutique et effets de lieu/action vit ici.
- **`GameController.java` (~800 lignes)** est une fine couche REST sous `/api/games` — ~70
  endpoints, chacun déléguant à une méthode de `GameService` (ex. `advance`, `roll`,
  `select-location`, `shop/buy-*`, `actions/<nom>/resolve`, `effect-*`). Pour ajouter une
  mécanique de jeu on touche presque toujours : endpoint du contrôleur → méthode `GameService`
  → diffusion d'un événement via `LiveEvents`.
- **`Game.java`** imbrique aussi des records/enums métier ; les enums autonomes incluent `Phase`
  (PHASE0→PHASE4 avec PREPHASE3), `GameStatus` (CREATED/STARTING/ACTIVE/ENDED) et `Action`
  (les cartes action chasseur/vampire).
- **`web/dto/GameSnapshot.java`** est la projection destinée au client de `Game`, renvoyée par
  `GET /api/games/{id}` — pas le `Game` brut. Le garder synchronisé avec le type `GameSnapshot`
  côté frontend dans `api.service.ts`.
- Les phases de raid temporisées utilisent un `TaskScheduler`
  (`@Qualifier("raidTaskScheduler")`, configuré dans `config/SchedulingConfig`) — les tours de
  jeu peuvent avancer sur un timer côté serveur, pas uniquement sur action du joueur.

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

### Authentification (deux notions de token distinctes)
- **`authToken`** — session de compte. `POST /api/auth/signup|login` le renvoie ; stocké côté
  client dans `sessionStorage`/`localStorage`. `authInterceptor` ajoute
  `Authorization: Bearer <authToken>` à chaque requête. `TokenAuthFilter` le résout en un
  utilisateur et attribue `ROLE_USER`.
- **`playerToken`** — une identité par partie (UUID) émise au join, utilisée pour vérifier
  qu'un appelant appartient bien à la partie concernée pour les actions liées à une partie.
- `SecurityConfig` : sans état, CSRF désactivé, CORS restreint à localhost:4200 + les deux
  hôtes `castello.ovh`. `GET /api/games/**` et `/api/auth/**` sont publics ; les autres
  `/api/games/**` nécessitent `ROLE_USER` ; le handshake `/ws` n'est pas filtré par
  `TokenAuthFilter`.

### Structure du frontend
- Composants Angular standalone, routes dans `app.routes.ts` : `auth` → `lobby` → `game/:id`
  (les deux derniers derrière `authGuard`).
- **`game.component.ts` (~8,5k lignes)** est l'orchestrateur monolithique en partie ; l'UI est
  découpée en de nombreuses boîtes de dialogue sous `components/modals/` et
  `components/limit-modals/`. Les composants de modale associent fréquemment un `*.component.ts`
  à un `*.vm.ts` (view-model) qui met en forme les données du snapshot pour le template.
- Services (`services/`) : `ApiService` (tous les appels REST + les types TypeScript partagés
  qui reflètent les DTO du backend), `LiveService` (STOMP), `AssetPreloaderService` (utilise le
  manifeste généré), `NotifService`.

## Environnements & profils
- Profils Spring backend : `dev` (défaut, `application.yml`), `preprod`, `prod`, `container`
  (activé dans Docker). Les identifiants/URL de la base viennent des variables d'environnement
  `SPRING_DATASOURCE_*` dans Docker.
- Le `API_BASE_URL` du frontend est injecté au démarrage du conteneur dans `assets/env.js`.
- **Danger :** `POST /api/admin/wipe` et `/api/dev/**` ne sont pas authentifiés — dev/test
  uniquement.