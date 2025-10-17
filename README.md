# Red Dawn Raid — Monorepo (Angular + Spring Boot)

Jeu de société "Chasseurs vs Vampire" — **frontend Angular** + **backend Spring Boot**.

## Sommaire
- [Structure](#structure)
- [Prérequis](#prérequis)
- [Démarrage rapide (dev)](#démarrage-rapide-dev)
- [API (aperçu)](#api-aperçu)
- [Identité / Auth légère](#identité--auth-légère)

---

```text
.
├─ backend/                      # Spring Boot (Java 21, Maven)
│  ├─ pom.xml
│  └─ src/main/java/org/castello/
│     ├─ RedDawnRaidApplication.java        # bootstrap Spring
│     ├─ web/
│     │  ├─ HealthController.java           # GET /api/health
│     │  ├─ GameController.java             # REST /api/games...
│     │  ├─ ApiError.java                   # format d’erreur JSON
│     │  ├─ GlobalExceptionHandler.java     # exceptions -> ApiError
│     │  └─ dto/
│     │     ├─ JoinRequest.java             # { nickname }
│     │     └─ JoinResponse.java            # { game, playerId, playerToken }
│     ├─ game/
│     │  ├─ GameStatus.java                 # CREATED / ACTIVE / ENDED
│     │  ├─ Game.java                       # état d'une partie
│     │  └─ GameService.java                # logique métier
│     └─ player/
│        ├─ Player.java                     # id, nickname, token, gameId
│        └─ PlayerService.java              # gestion tokens / validations
└─ frontend/                    # Angular 18 (Node 20+)
   ├─ angular.json
   ├─ package.json
   ├─ proxy.conf.json                       # /api -> localhost:8080 (dev)
   └─ src/app/
      ├─ app.ts                             # composant racine
      ├─ app.html                           # router-outlet
      ├─ app.routes.ts                      # '' -> Lobby, 'game/:id' -> Game
      ├─ api.service.ts                     # appels HTTP
      ├─ auth.interceptor.ts                # ajoute Authorization: Bearer token
      ├─ lobby.component.ts                 # lobby (lister/joindre/démarrer)
      └─ game.component.ts                  # affichage players
```

## Prérequis

- **Java 21** (OpenJDK)  
- **Maven 3.8+**
- **Node.js 20+** + **npm**  
- **Angular CLI** (installé globalement)  
  ```bash
    nvm install 20
    nvm use 20
    npm i -g @angular/cli
    sudo apt update
    sudo apt install -y openjdk-21-jdk
    sudo apt install -y maven
  ```

## Démarrage rapide (dev)
Backend
```text
cd backend
mvn -q -DskipTests clean package
mvn spring-boot:run
-> écoute sur http://localhost:8080
```

Frontend
```text
cd frontend
npm install
ng serve --proxy-config proxy.conf.json
-> ouvre http://localhost:4200

Le proxy redirige /api/* vers http://localhost:8080/*
```

## API (aperçu)
```text
GET /api/health → { "status":"ok" }
POST /api/games → crée une partie -> Game
GET /api/games → liste les parties -> Game[]
GET /api/games/{id} → état d’une partie -> Game
POST /api/games/{id}/join { nickname } → JoinResponse { game, playerId, playerToken }
POST /api/games/{id}/start (header Authorization: Bearer token) → Game
```

Erreurs renvoyées au format :
```text
{ "error":"400 BAD_REQUEST", "message":"...", "path":"/api/...", "timestamp":"..." }
```

## Identité / Auth légère
Au join, le serveur génère un playerToken (UUID) et le renvoie avec le game.

Le front stocke playerToken (et playerId) en sessionStorage (dev) / localStorage (prod possible).

Un HttpInterceptor ajoute automatiquement Authorization: Bearer token à chaque requête.

Certains endpoints (ex: start) vérifient que le token correspond à un joueur de la bonne partie.