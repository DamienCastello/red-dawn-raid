# --------- STAGE 1 : Build (Maven + JDK 21) ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copie le POM seul d'abord pour tirer le cache des deps Maven
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

# Copie le code et build le JAR (sans tests pour aller vite)
COPY src ./src
RUN mvn -q -DskipTests package
# Renomme le jar pour la copie ensuite
RUN sh -c 'set -e; JAR=$(ls target/*.jar | grep -v original- | head -n1); cp "$JAR" /app/app.jar'

# --------- STAGE 2 : Runtime (JRE 21 léger) ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copie le jar construit depuis le stage build
COPY --from=build /app/app.jar /app/app.jar

# (Optionnel) utilisateur non-root
RUN useradd -r -u 1001 appuser && chown -R appuser:appuser /app
USER appuser

# Expose le port Spring Boot
EXPOSE 8080

# Démarrage de l'application
ENTRYPOINT ["java","-XX:+UseG1GC","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
