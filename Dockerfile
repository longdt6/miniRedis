# syntax=docker/dockerfile:1.6

###############################################################################
# Stage 1 — build a GraalVM native binary with Mandrel
###############################################################################
FROM quay.io/quarkus/mandrel-builder-image:jdk-21 AS build

WORKDIR /build

# Cache dependencies first: copy only pom + wrapper, resolve, then copy source
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline

COPY src ./src

RUN ./mvnw -B -ntp package -Dnative -DskipTests

###############################################################################
# Stage 2 — copy the native binary onto a tiny runtime image
###############################################################################
FROM quay.io/quarkus/quarkus-micro-image:2.0

WORKDIR /work

# Quarkus produces a single static binary named <artifactId>-runner
COPY --from=build /build/target/*-runner /work/application

EXPOSE 8080

ENV MINIREDIS_DATA_FILE=/tmp/miniredis.json
ENV PORT=8080

# Micro-image uses /work as the working dir; bind on $PORT so Render can route
ENTRYPOINT ["./application", "-Dquarkus.http.port=${PORT}"]
