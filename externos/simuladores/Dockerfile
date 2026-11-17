# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Corre pruebas unitarias; las *IT (Testcontainers) se ejecutan con "mvn verify"
RUN mvn -B package

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home medicita
WORKDIR /app
COPY --from=build /app/target/simuladores.jar app.jar
USER medicita
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
