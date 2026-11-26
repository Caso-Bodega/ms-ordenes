# syntax=docker/dockerfile:1
# Imagen de ms-ordenes (Ciclo de vida de las ordenes) - Caso BodegaNube (JVY0101)
#
# Build en 2 etapas: la primera compila con el JDK y Maven Wrapper; la segunda solo lleva el JRE y la
# aplicacion separada en capas, asi la imagen final es liviana y no incluye compiladores ni codigo fuente.
#   docker build -t ms-ordenes .
#   docker run -p 8082:8082 ms-ordenes

# ---------- Etapa 1: compilar ----------
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /build

# Primero solo lo necesario para bajar dependencias. Mientras pom.xml no cambie, Docker reutiliza esta
# capa y no vuelve a descargar nada en cada build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Despues el codigo. Las pruebas corren en el pipeline de CI (etapa "Build y test"), por eso aqui se omiten.
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests  && java -Djarmode=layertools -jar target/ms-ordenes.jar extract --destination target/capas

# ---------- Etapa 2: imagen final ----------
FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="ms-ordenes"       org.opencontainers.image.description="Ciclo de vida de las ordenes - Caso BodegaNube (JVY0101)"

# Usuario sin privilegios: si alguien compromete la aplicacion, no es root dentro del contenedor.
RUN addgroup -S bodega && adduser -S -G bodega bodega
WORKDIR /app

# Las capas van de la que menos cambia (dependencias) a la que mas cambia (codigo de la aplicacion).
COPY --from=build /build/target/capas/dependencies/ ./
COPY --from=build /build/target/capas/spring-boot-loader/ ./
COPY --from=build /build/target/capas/snapshot-dependencies/ ./
COPY --from=build /build/target/capas/application/ ./

USER bodega

# La JVM respeta el limite de memoria del contenedor (por ejemplo el "limits.memory" de Docker Swarm).
ENV PORT=8082     JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8082

# Docker y Swarm marcan el contenedor como "healthy" solo cuando la aplicacion responde.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5   CMD wget -qO- http://127.0.0.1:8082/actuator/health/liveness > /dev/null || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
