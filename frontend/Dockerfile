# ---- STAGE 1: build Angular ----
FROM node:20-alpine AS build
WORKDIR /app

# deps
COPY package*.json ./
RUN npm ci

# sources
COPY . .

# NOTE: angular.json a "production" et "development".
# Pour préprod on utilise la build "production".
ARG NG_CONFIGURATION=production
RUN npm run build -- --configuration=${NG_CONFIGURATION}

# ---- STAGE 2: Nginx statique + injection env.js au runtime ----
FROM nginx:stable-alpine
WORKDIR /usr/share/nginx/html

# Copier le build
COPY --from=build /app/dist/app/browser/ ./

# Conf Nginx SPA
COPY default.conf /etc/nginx/conf.d/default.conf

COPY run.sh /run.sh
RUN chmod +x /run.sh
CMD ["/bin/sh","/run.sh"]


EXPOSE 80
