# syntax=docker/dockerfile:1

# ---------- Build : Node ----------
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Vite fige ces valeurs dans le bundle au moment du build. Ce sont des URLs vues par le
# navigateur, donc "localhost" reste correct meme quand tout tourne dans Docker.
ARG VITE_API_BASE_URL=http://localhost:8080
ARG VITE_KEYCLOAK_AUTHORITY=http://localhost:8081/realms/eventhub
ARG VITE_KEYCLOAK_CLIENT_ID=eventhub-frontend
ARG VITE_MAILHOG_URL=http://localhost:8025
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_KEYCLOAK_AUTHORITY=$VITE_KEYCLOAK_AUTHORITY \
    VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID \
    VITE_MAILHOG_URL=$VITE_MAILHOG_URL
RUN npm run build

# ---------- Execution : fichiers statiques servis par nginx ----------
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
