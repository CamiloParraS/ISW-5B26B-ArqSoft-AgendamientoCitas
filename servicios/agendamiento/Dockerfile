# Etapa 1: compilación + pruebas de arquitectura (ModularidadTest / PropiedadDeTablasTest rompen el build si se viola una frontera)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B package

# Etapa 2: imagen de ejecución (curl para el healthcheck de compose)
FROM eclipse-temurin:21-jre
RUN (command -v curl >/dev/null 2>&1) || (apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*)
RUN useradd --system --uid 10001 medicita
WORKDIR /app
COPY --from=build /build/target/agendamiento.jar /app/agendamiento.jar
USER medicita
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/agendamiento.jar"]
