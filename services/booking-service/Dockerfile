# syntax=docker/dockerfile:1

# ---------- Build : Maven + JDK ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# Les tests tournent dans la CI (Testcontainers a besoin d'un demon Docker) ; ici on ne
# fait que produire le jar. Le cache Maven est conserve entre deux builds.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# ---------- Execution : JRE seul, utilisateur non root ----------
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
