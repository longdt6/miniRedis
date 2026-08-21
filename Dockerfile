# syntax=docker/dockerfile:1.6

###############################################################################
# Stage 1 — build a native binary with Mandrel (GraalVM for Quarkus)
###############################################################################
# The Mandrel builder image ships with native-image + JDK pre-installed.
# Mandrel 23.1.12.0 ≥ 23.1.0, which Quarkus 3.15 requires.
FROM quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21 AS build

USER root
WORKDIR /build

# Cache dependencies first: copy only pom + wrapper, resolve, then copy source.
# mvnw downloads Maven automatically — no need to install it separately.
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline

COPY src ./src

# Point GRAALVM_HOME at the Mandrel installation so the quarkus-maven-plugin
# finds native-image. -Dnative activates the native profile.
ENV GRAALVM_HOME=/opt/mandrel
RUN ./mvnw -B -ntp package -Dnative -DskipTests

###############################################################################
# Stage 2 — copy the native binary onto a tiny runtime image
###############################################################################
FROM quay.io/quarkus/quarkus-micro-image:2.0

WORKDIR /work

# Quarkus produces a single static binary named <artifactId>-runner
COPY --from=build /build/target/*-runner /work/application

EXPOSE 10000

ENV MINIREDIS_DATA_FILE=/tmp/miniredis.json

# Bind on $PORT so Render can route. Render injects PORT (default 10000); the
# app reads it via quarkus.http.port=${PORT:8080} in application.properties, so
# no system property is needed here. Do NOT hardcode a port — it diverges from
# Render's routing port and produces a 502.
ENTRYPOINT ["./application"]
