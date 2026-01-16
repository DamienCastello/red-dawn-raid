# Red Dawn Raid — Monorepo (Angular + Spring Boot)

Jeu de société "Chasseurs vs Vampire" — **frontend Angular** + **backend Spring Boot**.

## Sommaire
- [Prérequis](#prérequis)
- [Démarrage rapide (dev)](#démarrage-rapide-dev)
- [Déployer & Démarrer le serveur (prod)](#déployer-démarrer)
- [Identité / Auth légère](#identité--auth-légère)

---

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
cd backend
mvn -q -DskipTests clean package
mvn spring-boot:run
écoute sur http://localhost:8080

Frontend
cd frontend
npm install
ng serve --proxy-config proxy.conf.json
ouvre http://localhost:4200


Le proxy redirige /api/* vers http://localhost:8080/*

## Déployer & Démarrer le serveur (prod)
```text
scp -r /home/gamma/Documents/red-dawn-raid/* gamma@147.135.128.42:docker/red-dawn-raid-prod 
scp -r /home/gamma/Documents/red-dawn-raid/.env.prod gamma@147.135.128.42:docker/red-dawn-raid-prod

sudo docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

## Identité / Auth légère
Au join, le serveur génère un playerToken (UUID) et le renvoie avec le game.

Le front stocke playerToken (et playerId) en sessionStorage (dev) / localStorage (prod possible).

Un HttpInterceptor ajoute automatiquement Authorization: Bearer token à chaque requête.

Certains endpoints (ex: start) vérifient que le token correspond à un joueur de la bonne partie.